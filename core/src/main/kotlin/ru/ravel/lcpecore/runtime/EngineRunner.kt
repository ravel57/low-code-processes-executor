package ru.ravel.lcpecore.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import ru.ravel.lcpecore.graph.CycleDetector
import ru.ravel.lcpecore.model.*
import ru.ravel.lcpecore.util.InputParsers
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.*

class EngineRunner(
	private val groovy: GroovyExecutor?,
	private val python: PythonExecutor?,
	private val js: JsExecutor?,
	private val subProjectRunner: SubProjectRunner,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
	private val listeners: List<ExecutionListener> = emptyList(),
	/** Слушатель форм: сообщаем на Android, что нужна форма, и ждём submit */
	private val formListener: FormListener? = null,
	private val maxParallelism: Int = Runtime.getRuntime().availableProcessors(),
) {

	/** Внутренний реестр «ожиданий» по FORM: blockId -> обещание с данными submit */
	private val formWaiters = ConcurrentHashMap<UUID, CompletableDeferred<Map<String, Any?>>>()
	private val activeForms = AtomicInteger(0)
	private val breakPhaseAfterForm = AtomicBoolean(false)
	private val formSlot = AtomicBoolean(false)

	private val formQueues = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Map<String, Any?>>>()
	private val outputVersion = ConcurrentHashMap<UUID, MutableList<Long>>()
	private val globalTick = AtomicLong(0)
	private val consumedVer = ConcurrentHashMap<UUID, MutableMap<UUID, Long>>()
	private val failedSnapshotByBlock = ConcurrentHashMap<UUID, Map<UUID, Long>>()
	private val lastInputVerByBlock: MutableMap<UUID, MutableMap<UUID, Long>> = ConcurrentHashMap()
	private val consumedPayloadByBlock: MutableMap<UUID, MutableMap<UUID, Map<String, Any?>>> = ConcurrentHashMap()
	private val versionPhase = ConcurrentHashMap<UUID, MutableList<Long>>() // версия -> номер фазы
	private val currentPhase = AtomicLong(0)
	private val blockLocks = ConcurrentHashMap<UUID, Any>()
	private val versionReady = ConcurrentHashMap<UUID, MutableList<CompletableDeferred<Unit>>>()
	private val blockReadySignal = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()


	private fun edgeKey(c: CoreConnection): UUID {
		return UUID.nameUUIDFromBytes("${c.fromId}:${c.fromOutputId}:${c.toId}:${c.toInputId}".toByteArray())
	}


	private fun formPauseOn() {
		activeForms.incrementAndGet()
	}


	private fun formPauseOff() {
		activeForms.decrementAndGet()
	}


	private fun formSlotOff() {
		formSlot.set(false)
	}


	private fun formPaused(): Boolean {
		return activeForms.get() > 0
	}


	/** Внешняя точка возобновления: Android вызывает на submit */
	fun submitForm(blockId: UUID, values: Map<String, Any?>) {
		val q = formQueues.computeIfAbsent(blockId) { ConcurrentLinkedQueue() }
		q.add(values)
		formWaiters.remove(blockId)?.let { waiter ->
			q.poll()?.let { next ->
				if (!waiter.isCompleted) waiter.complete(next)
			}
			if (q.isEmpty()) formQueues.remove(blockId)
		}
	}

	private fun isEffectivelyEmptyMap(m: Map<String, Any?>?): Boolean {
		if (m.isNullOrEmpty()) return true
		fun emptyAny(v: Any?): Boolean = when (v) {
			null -> true
			is Map<*, *> -> v.isEmpty() || v.values.all { emptyAny(it) }
			is Collection<*> -> v.isEmpty() || v.all { emptyAny(it) }
			is String -> v.isEmpty()
			else -> false
		}
		return m.values.all { emptyAny(it) }
	}


	@Suppress("UNCHECKED_CAST")
	private fun deepCopyAny(v: Any?): Any? {
		return when (v) {
			is Map<*, *> -> v.entries
				.associate { (k, vv) -> (k?.toString() ?: "") to deepCopyAny(vv) }
				.toMutableMap()

			is Collection<*> -> v.map { deepCopyAny(it) }.toMutableList()
			else -> v
		}
	}

	private fun deepCopyMap(m: Map<String, Any?>?): MutableMap<String, Any?> {
		return if (m == null) mutableMapOf() else deepCopyAny(m) as MutableMap<String, Any?>
	}


	/** Событие для UI формы */
	interface FormListener {
		/**
		 * Вызывается когда движок дошёл до FORM и собирается ждать submit.
		 * @param block сам блок
		 * @param specJson содержимое block.codePath (JSON-спека формы)
		 * @param initial начальные значения (мердж входов и дефолтов)
		 */
		fun onFormRequested(block: CoreBlock, specJson: String, initial: Map<String, Any?>)
	}

	/** Запуск всего проекта */
	fun run(project: CoreProject) {
		// Сброс старых выходов
		project.blocks.forEach { b ->
			if (!b.type.isService() && b.type != BlockType.PROPERTIES) {
				b.outputsData = MutableList(b.outputCount) { mutableMapOf() }
			}
		}

		val raw = CycleDetector.findFirstCycle(project)

		runPhase(project, exclude = emptySet(), eagerForms = true)
		var endedByForm = breakPhaseAfterForm.getAndSet(false)
		if (raw == null) {
			while (endedByForm) {
				runPhase(project, exclude = emptySet(), eagerForms = false)
				endedByForm = breakPhaseAfterForm.getAndSet(false)
			}
			return
		}
		val cycleBlocks = raw.toSet()  //CycleDetector.filterCycleBlocks(raw).toSet()
		val excludeOutside = project.blocks.filter { it !in cycleBlocks }.toSet()
		while (true) {
			runPhase(project, exclude = excludeOutside, eagerForms = false)
			val ended = breakPhaseAfterForm.getAndSet(false)
			if (cycleHasOutputsToOutside(project, cycleBlocks)) break
			if (!ended && cycleInternalsDrained(project, cycleBlocks)) break
			if (formPaused()) Thread.sleep(10)
		}
		runPhase(project, exclude = cycleBlocks, eagerForms = false)
	}


	/** Есть ли непустые выходы из цикловых блоков наружу (на блоки вне цикла) */
	private fun cycleHasOutputsToOutside(project: CoreProject, cycle: Set<CoreBlock>): Boolean {
		val byId = project.blocks.associateBy { it.id }
		val cycleIds = cycle.map { it.id }.toSet()
		return project.connections.any { c ->
			c.fromId in cycleIds && c.toId !in cycleIds && run {
				val src = byId.getValue(c.fromId)
				val outIdx = src.outputIds.indexOf(c.fromOutputId).let { if (it >= 0) it else 0 }
				val m = src.outputsData.getOrNull(outIdx)
				!isEffectivelyEmptyMap(m)
			}
		}
	}


	/**
	 * Фаза исполнения.
	 * Блок считается «готов», когда по всем его НЕОПЦИОНАЛЬНЫМ входящим рёбрам пришли непустые карты.
	 * Опциональные рёбра передают данные, но не блокируют запуск.
	 */
	private fun runPhase(project: CoreProject, exclude: Set<CoreBlock>, eagerForms: Boolean) {
		val byId = project.blocks.associateBy { it.id }
		val candidates = project.blocks.filter { it !in exclude }.toSet()
		val ranThisPhase = ConcurrentHashMap.newKeySet<UUID>()
		val queuedThisPhase = ConcurrentHashMap.newKeySet<UUID>()
		if (candidates.isEmpty()) {
			return
		}

		data class FormQEntry(val block: CoreBlock, val rank: Int, val ticket: Long)

		candidates.associateWith { b ->
			project.connections
				.filter { it.toId == b.id }
				.mapNotNull { c ->
					val p = byId[c.fromId] ?: return@mapNotNull null
					if (p !in candidates) return@mapNotNull null
					val outIdx = p.outputIds.indexOf(c.fromOutputId).let { if (it >= 0) it else 0 }
					InEdge(p, outIdx, c)
				}
		}

		// --- входящие рёбра БЕЗ фильтра по кандидатам (для корректного подсчёта обязательных входов/готовности)
		val allIncoming: Map<CoreBlock, List<InEdge>> = candidates.associateWith { b ->
			project.connections
				.filter { it.toId == b.id }
				.mapNotNull { c ->
					val p = byId[c.fromId] ?: return@mapNotNull null
					val outIdx = p.outputIds.indexOf(c.fromOutputId).let { if (it >= 0) it else 0 }
					InEdge(p, outIdx, c)
				}
		}

		val outgoingEdges = mutableMapOf<CoreBlock, MutableList<OutEdge>>()
		project.connections.forEach { c ->
			val from = byId[c.fromId]
			val to = byId[c.toId]
			if (from != null && to != null && from in candidates && to in candidates) {
				val outIdx = from.outputIds.indexOf(c.fromOutputId).let { if (it >= 0) it else 0 }
				outgoingEdges.computeIfAbsent(from) { mutableListOf() }.add(OutEdge(to, outIdx, c))
			}
		}

		// кто получает входы ИЗВНЕ множества кандидатов (только по НЕопциональным рёбрам) — для якорей/ранга
		val candidateIds = candidates.map { it.id }.toSet()
		val hasIncomingFromOutside: Map<CoreBlock, Boolean> = candidates.associateWith { b ->
			project.connections.any { c -> c.toId == b.id && c.fromId !in candidateIds && c.incomeDataType != IncomeDataType.OPTIONAL }
		}

		// --- очереди готовых
		val readyForms = PriorityQueue(compareBy<FormQEntry> { it.rank }.thenBy { it.ticket })
		val readyOthers = ConcurrentLinkedQueue<CoreBlock>()
		val formTicket = AtomicLong(0)

		// --- степень ожидания по НЕопциональным входам (считаем по ВСЕМ входам)
		val pending = ConcurrentHashMap<CoreBlock, AtomicInteger>()
		val requiredIncomingCount = candidates.associateWith { b ->
			allIncoming[b].orEmpty().count { it.conn.incomeDataType != IncomeDataType.OPTIONAL }
		}

		// якоря для ранжирования форм: нет обязательных входов, есть внешний вход, START/INPUT_DATA/PROPERTIES
		val anchors: Set<CoreBlock> = candidates.filter { b ->
			requiredIncomingCount[b] == 0 ||
					hasIncomingFromOutside[b] == true ||
					b.type == BlockType.START || b.type == BlockType.INPUT_DATA || b.type == BlockType.PROPERTIES
		}.toSet()

		// расстояние от якорей — для стабильного порядка показа форм
		val dist = mutableMapOf<UUID, Int>()
		val q = ArrayDeque<CoreBlock>()
		anchors.forEach { a -> dist[a.id] = 0; q.add(a) }
		while (q.isNotEmpty()) {
			val u = q.removeFirst()
			val du = dist[u.id]!!
			outgoingEdges[u].orEmpty().forEach { e ->
				if (e.child in candidates && dist.putIfAbsent(e.child.id, du + 1) == null) {
					q.add(e.child)
				}
			}
		}

		fun formRank(b: CoreBlock): Int {
			val d = dist[b.id] ?: if (hasIncomingFromOutside[b] == true) 0 else Int.MAX_VALUE / 2
			return d * 100_000 + project.blocks.indexOf(b) // стабильный тай-брейкер
		}

		val scheduledMap = ConcurrentHashMap<UUID, Boolean>()
		val runningNow = ConcurrentHashMap<UUID, Boolean>()
		fun enqueueReady(b: CoreBlock) {
			synchronizedBlock(b.id) {
				if (runningNow.containsKey(b.id)) return@synchronizedBlock
				val alreadyScheduled = scheduledMap.putIfAbsent(b.id, true) != null
				val alreadyQueued = !queuedThisPhase.add(b.id)
				if (alreadyScheduled || alreadyQueued) {
					return@synchronizedBlock
				}
				if (b.type == BlockType.FORM) {
					readyForms.add(FormQEntry(b, formRank(b), formTicket.incrementAndGet()))
				} else {
					readyOthers.add(b)
				}
			}
		}

		fun hasData(parent: CoreBlock, outIdx: Int): Boolean {
			return !isEffectivelyEmptyMap(parent.outputsData.getOrNull(outIdx))
		}

		fun readyNow(child: CoreBlock): Boolean {
			val inAll = allIncoming[child].orEmpty()
			return inAll
				.filter { it.conn.incomeDataType != IncomeDataType.OPTIONAL }
				.all { inEdge ->
					when (inEdge.conn.incomeDataType) {
						IncomeDataType.REQUIRED_FRESH_DATA -> edgeFreshFor(child, inEdge)
						IncomeDataType.REQUIRED_DATA -> hasData(inEdge.parent, inEdge.outIdx)
						else -> true
					}
				}
		}


		// Первичная инициализация ожиданий
		candidates.forEach { b ->
			val inAll = allIncoming[b].orEmpty()
			val requiredIn = inAll.filter { it.conn.incomeDataType != IncomeDataType.OPTIONAL }

			val lackRequired = requiredIn.count { inEdge ->
				when (inEdge.conn.incomeDataType) {
					IncomeDataType.REQUIRED_FRESH_DATA -> !edgeFreshFor(b, inEdge)
					else -> !hasData(inEdge.parent, inEdge.outIdx)
				}
			}
			// НИКАКИХ «частичных» запусков: ждём все обязательные входы
			val need = if (b.type == BlockType.FORM && eagerForms && requiredIncomingCount[b] == 0) {
				0
			} else {
				lackRequired
			}
			pending[b] = AtomicInteger(need)
			if (need == 0) {
				val hasOptional = inAll.any { it.conn.incomeDataType == IncomeDataType.OPTIONAL }
				val hasFreshOptional =
					inAll.any { it.conn.incomeDataType == IncomeDataType.OPTIONAL && edgeFreshFor(b, it) }
				val noRequired = requiredIncomingCount[b] == 0
				if (noRequired && hasOptional && !hasFreshOptional) {
					// ждём первого свежего опционального входа
				} else {
					if (ranThisPhase.contains(b.id)) {
						return
					}
					enqueueReady(b)
				}
			}
		}
		if (readyForms.isEmpty() && readyOthers.isEmpty()) return

		val inFlight = AtomicInteger(0)
		val done = CompletableDeferred<Unit>()

		fun submit(block: CoreBlock) {
			inFlight.incrementAndGet()
			if (runningNow.putIfAbsent(block.id, true) != null) {
				return
			}
			scope.launch {
				if (ranThisPhase.contains(block.id)) {
					return@launch
				}
				listeners.forEach { it.onStart(block) }
				var consumedSnapshot: Map<UUID, Long> = emptyMap()
				var success = false
				try {
					consumedSnapshot = run {
						val last = consumedVer[block.id].orEmpty()

						data class Pick(val edge: UUID, val ver: Long)

						val best = mutableMapOf<String, Pick>() // port -> winner
						allIncoming[block].orEmpty().forEach { inEdge ->
							val parent = inEdge.parent
							val ver = outputVersion[parent.id]?.getOrNull(inEdge.outIdx) ?: -1L
							val edge = edgeKey(inEdge.conn)
							val needFresh = inEdge.conn.incomeDataType == IncomeDataType.OPTIONAL ||
									inEdge.conn.incomeDataType == IncomeDataType.REQUIRED_FRESH_DATA
							val was = last[edge] ?: -1L
							if (needFresh && ver <= was) return@forEach
							val toInIdx = block.inputIds.indexOf(inEdge.conn.toInputId)
							val port = if (toInIdx >= 0) block.inputNames.getOrNull(toInIdx) ?: "in$toInIdx" else "in?"
							val prev = best[port]
							if (prev == null || ver >= prev.ver) best[port] = Pick(edge, ver)
						}
						best.values.associate { it.edge to it.ver }
					}
					if (failedSnapshotByBlock[block.id] == consumedSnapshot) {
						ranThisPhase.add(block.id)
						listeners.forEach { it.onFinish(block) }
						if (inFlight.decrementAndGet() == 0 && readyOthers.isEmpty() &&
							(breakPhaseAfterForm.get() || readyForms.isEmpty())
						) {
							done.complete(Unit)
						}
						return@launch
					}
					runBlock(block, project)
					success = true

					failedSnapshotByBlock.remove(block.id)
					lastInputVerByBlock.compute(block.id) { _, prev ->
						val m = (prev ?: mutableMapOf()).toMutableMap()
						consumedSnapshot.forEach { (edge, ver) -> m[edge] = ver }
						m
					}
					lastInputVerByBlock.compute(block.id) { _, prev ->
						val m = (prev ?: mutableMapOf()).toMutableMap()
						consumedSnapshot.forEach { (edge, ver) -> m[edge] = ver }
						m
					}
					consumedPayloadByBlock.compute(block.id) { _, prev ->
						val m = (prev ?: mutableMapOf()).toMutableMap()
						allIncoming[block].orEmpty().forEach { inEdge ->
							val parent = inEdge.parent
							val outIdx = inEdge.outIdx
							val edge = edgeKey(inEdge.conn)
							val payload = deepCopyMap(parent.outputsData.getOrNull(outIdx))
							m[edge] = normalizeMap(payload, ignoreKeys = emptySet())
						}
						m
					}
					listeners.forEach { it.onOutput(block, mapOf("outputs" to block.outputsData)) }

					outgoingEdges[block].orEmpty().forEach { e ->
						val payload = block.outputsData.getOrNull(e.outIdx)
						if (!passesGate(payload, e.conn.gate)) return@forEach
						if (isEffectivelyEmptyMap(payload)) return@forEach

						val child = e.child
						val fresh = edgeFreshFor(child, InEdge(block, e.outIdx, e.conn))

						when (e.conn.incomeDataType) {
							IncomeDataType.OPTIONAL -> {
								if (fresh && !ranThisPhase.contains(child.id) && !scheduledMap.containsKey(child.id) && readyNow(
										child
									)
								) {
									enqueueReady(child)
								}
							}

							IncomeDataType.REQUIRED_DATA,
							IncomeDataType.REQUIRED_FRESH_DATA,
								-> {
								if (readyNow(child) && !scheduledMap.containsKey(child.id) && !ranThisPhase.contains(
										child.id
									)
								) {
									enqueueReady(child)
								}
							}
						}
					}
				} catch (t: Throwable) {
					listeners.forEach { it.onError(block, t) }
					failedSnapshotByBlock[block.id] = consumedSnapshot
				} finally {
					ranThisPhase.add(block.id)
					listeners.forEach { it.onFinish(block) }
					if (success) {
						val dst = consumedVer.computeIfAbsent(block.id) { ConcurrentHashMap() }
						consumedSnapshot.forEach { (edgeUuid, ver) -> dst[edgeUuid] = ver }
					}
					consumedVer[block.id]?.forEach { (edge, ver) ->
						if (!outputVersion[block.id].orEmpty().any { it > ver }) {
							consumedVer[block.id]?.set(edge, ver)
						}
					}
					val drainedOthers = readyOthers.isEmpty()
					val noFormsAllowedOrPending = breakPhaseAfterForm.get() || readyForms.isEmpty()
					if (inFlight.decrementAndGet() == 0 && drainedOthers && noFormsAllowedOrPending) {
						done.complete(Unit)
					}
					runningNow.remove(block.id)
				}
			}
		}

		fun pumpReady() {
			while (true) {
				if (inFlight.get() >= maxParallelism) {
					break
				}
				if (formPaused() || formSlot.get()) {
					break
				}
				if (breakPhaseAfterForm.get()) {
					val b = readyOthers.poll()
						?: break
					submit(b)
					continue
				}
				val f = readyForms.poll()
				if (f != null) {
					formSlot.set(true)
					submit(f.block)
					break
				}
				val b = readyOthers.poll()
					?: break
				submit(b)
			}
		}

		pumpReady()

		scope.launch {
			while (isActive && (inFlight.get() > 0 || !readyForms.isEmpty() || !readyOthers.isEmpty())) {
				val before = inFlight.get()
				pumpReady()
				if (inFlight.get() == before && inFlight.get() > 0) delay(5)
			}
		}
		runBlocking { done.await() }
		queuedThisPhase.clear()
		ranThisPhase.clear()
		currentPhase.incrementAndGet()
	}


	/** Исполнение одного блока */
	private suspend fun runBlock(
		block: CoreBlock,
		project: CoreProject,
	) {
		val prev = synchronizedBlock(block.id) {
			block.outputsData.map { it.toMutableMap() }
		}
		blockReadySignal[block.id] = CompletableDeferred()
		try {
			val newOutputs: List<MutableMap<String, Any?>> = when (block.type) {
				BlockType.MAPPING_GROOVY -> {
					val inputs = collectInputs(block, project)
					val outputs = prepareOutputs(block)
					val cls = block.groovyClassName?.trim().orEmpty()
					val code = readCode(block, project)

					val allInputs = block.inputNames.associateWith { inputs[it] ?: mutableMapOf() }
					val inout = allInputs.toMutableMap().apply { putAll(outputs) }
					val retAny = requireNotNull(groovy) { "GroovyExecutor is not provided" }
						.exec(code, inout, cls)

					if (retAny is Map<*, *>) {
						retAny.forEach { (k, v) ->
							if (k is String) {
								inout[k] = when (v) {
									is MutableMap<*, *> -> (v as MutableMap<String, Any?>).toMutableMap()
									is Map<*, *> -> (v as Map<String, Any?>).toMutableMap()
									else -> mutableMapOf("value" to v)
								}
							}
						}
					}

					block.outputNames.map { name ->
						when (val v = inout[name]) {
							is MutableMap<*, *> -> v.toMutableMap()
							is Map<*, *> -> (v as Map<String, Any?>).toMutableMap()
							null -> outputs[name]?.toMutableMap() ?: mutableMapOf()
							else -> mutableMapOf("value" to v)
						}
					}
				}

				BlockType.MAPPING_PYTHON -> {
					val inputs = collectInputs(block, project)
					val outputs = prepareOutputs(block)
					val result = requireNotNull(python) { "PythonExecutor is not provided" }
						.exec(block, inputs, outputs, block.packagesNames)
					block.outputNames.map { name ->
						when (val value = result[name]) {
							is MutableMap<*, *> -> (value as MutableMap<String, Any?>).toMutableMap()
							is Map<*, *> -> (value as Map<String, Any?>).toMutableMap()
							null -> outputs[name]?.toMutableMap() ?: mutableMapOf()
							else -> mutableMapOf("value" to value)
						}
					}
				}

				BlockType.MAPPING_JAVA_SCRIPT -> {
					val inputs = collectInputs(block, project)
					val code = readCode(block, project)
					val result = requireNotNull(js) { "JsExecutor is not provided" }
						.exec(code, inputs, block.outputNames)
					block.outputNames.map { name ->
						result[name]?.toMutableMap() ?: mutableMapOf()
					}
				}

				BlockType.INPUT_DATA -> {
					val inputs = collectInputs(block, project)
					val text = readCode(block, project)
					val parsed: MutableMap<String, Any?> = InputParsers.parse(block.inputFormat, text)
						.mapValues { it.value }
						.toMutableMap()
					if (parsed.isEmpty() && text.isNotBlank()) {
						parsed["data"] = text
					}
					inputs.forEach { (_, v) ->
						val map = when (v) {
							is MutableMap<*, *> -> v
							is Map<*, *> -> (v as Map<String, Any?>).toMutableMap()
							else -> mutableMapOf("value" to v)
						}
						parsed.putAll(map)
					}
					listOf(parsed)
				}

				BlockType.START -> {
					val inputs = collectInputs(block, project)
					val text = readCode(block, project).trim()
					val current = block.outputsData.map { it.toMutableMap() }.toMutableList()
					val base = if (current.isEmpty()) {
						mutableMapOf()
					} else {
						current[0]
					}
					if (text.isNotBlank()) {
						base["trigger"] = text
					}
					inputs.forEach { (_, v) ->
						val map = when (v) {
							is MutableMap<*, *> -> v
							is Map<*, *> -> (v as Map<String, Any?>).toMutableMap()
							else -> mutableMapOf("value" to v)
						}
						base.putAll(map)
					}
					listOf(base)
				}

				BlockType.SUB_PROCESS -> {
					subProjectRunner.run(block, project).toMutableList()
				}

				BlockType.PROPERTIES -> {
					val text = readCode(block, project).trim()
					val defaults: Map<String, Any?> = if (text.isNotBlank()) {
						jacksonObjectMapper().readValue(text, Map::class.java) as Map<String, Any?>
					} else {
						emptyMap()
					}
					block.outputNames.mapIndexed { i, name ->
						val userSet = prev.getOrNull(i)?.get(name)
						val v = userSet ?: defaults[name] ?: ""
						mutableMapOf(name to v)
					}
				}

				BlockType.FORM -> {
					val inputs = collectInputs(block, project)
					val specJson = readCode(block, project)
					val initial = inputs.toMutableMap()
					formPauseOn()
					val submittedRaw = try {
						awaitForm(block, specJson, initial)
					} finally {
						formPauseOff()
						formSlotOff()
						breakPhaseAfterForm.set(true)
					}

					// --- NEW: постобработка через postProcessingNodes ---
					@Suppress("UNCHECKED_CAST")
					val submitted = submittedRaw.toMutableMap()

					// 1) Определяем action
					val actionFromPayload = sequenceOf("action", "_action", "submitAction")
						.mapNotNull { key -> (submitted[key] as? String)?.takeIf { it.isNotBlank() } }
						.firstOrNull()

					val action = actionFromPayload
						?: block.postProcessingNodes.firstOrNull()?.action

					if (action != null && block.postProcessingNodes.isNotEmpty()) {
						val node = block.postProcessingNodes.firstOrNull { it.action == action }
							?: block.postProcessingNodes.first()

						fun readPostProcessingCode(path: String?): String {
							if (path.isNullOrBlank()) return ""
							val base = project.baseDir ?: File(".")
							val normalized = path.replace('\\', '/')
							val file = File(normalized).let { f ->
								if (f.isAbsolute) f else File(base, normalized)
							}.normalize()
							if (!file.exists() || !file.isFile) return ""
							return file.readText()
						}

						fun runHandler(codePath: String?, className: String?) {
							val code = readPostProcessingCode(codePath)
							val cls = className?.trim().orEmpty()
							if (code.isBlank() || cls.isBlank()) return
							val exec = requireNotNull(groovy) { "GroovyExecutor is not provided" }
							// Контекст для обработчика
							val inout = inputs.mapValues { (_, v) -> deepCopyMap(v) }
							val ret = exec.exec(code, inout, cls)
//							// Приоритетно берём изменённый inout["form"], если нет — Map из ret
//							val modified: Map<String, Any?>? = when {
//								inout["form"] is Map<*, *> ->
//									inout["form"] as Map<String, Any?>
//
//								ret is Map<*, *> ->
//									ret as Map<String, Any?>
//
//								else -> null
//							}
//
//							if (modified != null) {
//								submitted.clear()
//								submitted.putAll(modified)
//							}
						}

						// сначала постобработка, потом подготовка данных к submit (если нужна)
						runHandler(node.mainProcessingCodePath, node.mainProcessingClassName)
						runHandler(node.submitDataCodePath, node.submitDataClassName)
					}
					when {
						block.outputNames.isEmpty() -> {
							listOf(submitted.toMutableMap())
						}

						submitted.keys.any { it in block.outputNames.toSet() } -> {
							block.outputNames.map { name ->
								when (val v = submitted[name]) {
									is MutableMap<*, *> -> (v as MutableMap<String, Any?>).toMutableMap()
									is Map<*, *> -> (v as Map<String, Any?>).toMutableMap()
									null -> mutableMapOf() // если не пришло — пустой
									else -> mutableMapOf("value" to v)
								}
							}
						}

						else -> {
							val first = submitted.toMutableMap()
							val rest =
								List((block.outputNames.size - 1).coerceAtLeast(0)) { mutableMapOf<String, Any?>() }
							listOf(first) + rest
						}
					}
				}

				else -> block.outputsData
			}
			if (!block.type.isService() && block.type != BlockType.PROPERTIES) {
				block.outputsData = MutableList(block.outputCount) { mutableMapOf() }
			}
			block.outputsData = newOutputs.toMutableList()
		} catch (t: Exception) {
			listeners.forEach { it.onError(block, t) }
			throw t
		} finally {
			// 1) Зафиксировали версии и фазы под локом
			synchronizedBlock(block.id) {
				val vers = outputVersion.computeIfAbsent(block.id) { MutableList(block.outputCount) { 0L } }
				block.outputsData.forEachIndexed { i, out ->
					if (!isEffectivelyEmptyMap(out)) {
						val newVer = globalTick.incrementAndGet()
						vers[i] = newVer
						val phaseList = versionPhase.computeIfAbsent(block.id) { MutableList(block.outputCount) { 0L } }
						phaseList[i] = currentPhase.get()
					}
				}
			}
			// 2) ВЫСЫЛАЕМ СИГНАЛ ВСЕГДА и ОДИН РАЗ, уже вне лока
			blockReadySignal.remove(block.id)?.complete(Unit)

			// 3) Будим всех, кто ждал конкретной версии
			versionReady.compute(block.id) { _, p ->
				p?.forEach { it.complete(Unit) }
				mutableListOf()
			}
		}

	}


	private suspend fun awaitForm(block: CoreBlock, specJson: String, initial: Map<String, Any?>): Map<String, Any?> {
		formListener?.onFormRequested(block, specJson, initial)
		formQueues[block.id]?.poll()?.let { ready ->
			if (formQueues[block.id]?.isEmpty() == true) formQueues.remove(block.id)
			return ready
		}
		val promise = CompletableDeferred<Map<String, Any?>>()
		formWaiters.put(block.id, promise)?.cancel()
		return promise.await().also {
			if (formQueues[block.id]?.isEmpty() == true) {
				formQueues.remove(block.id)
			}
		}
	}


	/** Собрать входные карты для блока из подключений проекта */
	private fun collectInputs(block: CoreBlock, project: CoreProject): Map<String, MutableMap<String, Any?>> {
		val byId = project.blocks.associateBy { it.id }
		val inputs = mutableMapOf<String, MutableMap<String, Any?>>()
		val inputsVer = mutableMapOf<String, Long>()
		val lastUsed: Map<UUID, Long> = consumedVer[block.id].orEmpty()
		project.connections
			.filter { it.toId == block.id }
			.forEach { conn ->
				val fromBlock = byId[conn.fromId]
				val fromOutIdx = fromBlock?.outputIds?.indexOf(conn.fromOutputId) ?: -1
				val toInIdx = block.inputIds.indexOf(conn.toInputId)
				val portName = if (toInIdx >= 0) block.inputNames.getOrNull(toInIdx) ?: "in$toInIdx" else "in?"
				val value = if (fromOutIdx >= 0 && fromBlock != null) {
					// Ждём, пока родитель реально закончит runBlock и зафиксирует выходы
					runBlocking {
						blockReadySignal[fromBlock.id]?.await()
					}
					synchronizedBlock(fromBlock.id) {
						deepCopyMap(fromBlock.outputsData.getOrNull(fromOutIdx))
					}
				} else {
					mutableMapOf()
				}
				val ver = outputVersion[fromBlock?.id]?.getOrNull(fromOutIdx) ?: -1L
				val needFresh = conn.incomeDataType == IncomeDataType.OPTIONAL ||
						conn.incomeDataType == IncomeDataType.REQUIRED_FRESH_DATA
				val edge = edgeKey(conn)
				val was = lastUsed[edge] ?: -1L
				if (needFresh && ver <= was) return@forEach
				val prevVer = inputsVer[portName] ?: -1L
				if (ver >= prevVer) {
					inputs[portName] = value
					inputsVer[portName] = ver
				}
			}
		block.inputNames.forEach { name -> inputs.putIfAbsent(name, mutableMapOf()) }
		return inputs
	}


	private fun prepareOutputs(block: CoreBlock): MutableMap<String, MutableMap<String, Any?>> {
		return block.outputNames.associateWith { mutableMapOf<String, Any?>() }.toMutableMap()
	}


	private fun readCode(block: CoreBlock, project: CoreProject): String {
		val base = project.baseDir ?: File(".")
		val raw = block.codePath
			?: return ""
		val normalized = raw.replace('\\', '/')
		val candidate = File(normalized).let { f ->
			if (f.isAbsolute) {
				f
			} else {
				File(base, normalized)
			}
		}.normalize()
		if (!(candidate.exists() && candidate.isFile)) {
			return ""
		}
		return candidate.readText()
	}


	private fun normalizeMap(m: Map<String, Any?>, ignoreKeys: Set<String>): Map<String, Any?> {
		return m.filterKeys { it !in ignoreKeys }
			.mapValues { (_, v) -> normalizeValue(v, ignoreKeys) }
			.toSortedMap()
	}


	@Suppress("UNCHECKED_CAST")
	private fun normalizeValue(v: Any?, ignoreKeys: Set<String>): Any? {
		return when (v) {
			is Map<*, *> -> normalizeMap(v as Map<String, Any?>, ignoreKeys)
			is List<*> -> v.map { normalizeValue(it, ignoreKeys) }
			is Int, is Short, is Byte, is Long -> (v as Number).toLong()
			is Float, is Double -> (v as Number).toDouble()
			is BigInteger -> v.toLong()
			is BigDecimal -> v.toDouble()
			else -> v
		}
	}


	private fun cycleInternalsDrained(project: CoreProject, cycle: Set<CoreBlock>): Boolean {
		val byId = project.blocks.associateBy { it.id }
		val ids = cycle.map { it.id }.toSet()
		val internal = project.connections.filter { it.fromId in ids && it.toId in ids }
		return internal.all { c ->
			val src = byId.getValue(c.fromId)
			val idx = src.outputIds.indexOf(c.fromOutputId).let { if (it >= 0) it else 0 }
			val m = src.outputsData.getOrNull(idx)
			isEffectivelyEmptyMap(m)
		}
	}


	private fun edgeFreshFor(child: CoreBlock, e: InEdge): Boolean {
		val parent = e.parent
		val payload = parent.outputsData.getOrNull(e.outIdx)
		val hasValue = !isEffectivelyEmptyMap(payload)
		val ver = outputVersion[parent.id]?.getOrNull(e.outIdx) ?: -1L
		val last = consumedVer[child.id]?.get(edgeKey(e.conn)) ?: -1L
		val phaseOfVer = versionPhase[parent.id]?.getOrNull(e.outIdx) ?: 0L
		if (!hasValue || ver <= last) return false
		val isNewPhase = phaseOfVer < currentPhase.get()
		if (!isNewPhase) return false
		val payloadChanged = run {
			val lastPayload = consumedPayloadByBlock[child.id]?.get(edgeKey(e.conn))
			val normalized = normalizeMap(payload ?: emptyMap(), ignoreKeys = emptySet())
			lastPayload != normalized
		}
		return payloadChanged || isNewPhase
	}


	private fun passesGate(payload: Any?, gate: EdgeGate?): Boolean {
		if (gate == null) return true
		return when (gate.mode) {
			GateMode.ALWAYS -> true
			GateMode.NON_EMPTY -> (payload as? Map<*, *>)?.isNotEmpty() == true
			GateMode.WHEN_KEY_PRESENT -> (payload as? Map<*, *>)?.containsKey(gate.key) == true
			GateMode.WHEN_EQUALS -> {
				val m = payload as? Map<*, *> ?: return false
				m[gate.key]?.toString() == gate.equals
			}
		}
	}


	private fun <T> synchronizedBlock(blockId: UUID, action: () -> T): T {
		return synchronized(blockLocks.computeIfAbsent(blockId) { Any() }) {
			action()
		}
	}


	private data class InEdge(
		val parent: CoreBlock,
		val outIdx: Int,
		val conn: CoreConnection,
	)


	private data class OutEdge(
		val child: CoreBlock,
		val outIdx: Int,
		val conn: CoreConnection,
	)


	class BlockExecutionException(blockName: String, cause: Throwable) :
		RuntimeException("Exception in $blockName: ${cause.message}", cause)
}