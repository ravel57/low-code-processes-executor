package ru.ravel.lcpecore.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import ru.ravel.lcpecore.graph.CycleDetector
import ru.ravel.lcpecore.model.*
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class EngineRunner(
	private val groovy: GroovyExecutor?,
	private val python: PythonExecutor?,
	private val js: JsExecutor?,
	private val subProjectRunner: SubProjectRunner,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
	private val listeners: List<ExecutionListener> = emptyList(),
	/** Слушатель форм: сообщаем на Android, что нужна форма, и ждём submit */
	private val formListener: FormListener? = null,
	private val maxParallelism: Int = 1,
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
			is Map<*, *> -> (v as Map<String, Any?>).entries
				.associate { (k, vv) -> k to deepCopyAny(vv) }
				.toMutableMap()

			is Collection<*> -> v.map { deepCopyAny(it) }.toMutableList()
			else -> v
		}
	}

	private fun deepCopyMap(m: Map<String, Any?>?): MutableMap<String, Any?> =
		if (m == null) mutableMapOf() else deepCopyAny(m) as MutableMap<String, Any?>


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
		if (candidates.isEmpty()) return

		data class FormQEntry(val block: CoreBlock, val rank: Int, val ticket: Long)

		// --- входящие рёбра ТОЛЬКО внутри множества кандидатов (для пробуждения детей и BFS)
		val incomingRestricted: Map<CoreBlock, List<InEdge>> = candidates.associateWith { b ->
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
			project.connections.any { c -> c.toId == b.id && c.fromId !in candidateIds && !c.isOptional }
		}

		// --- очереди готовых
		val readyForms = PriorityQueue(compareBy<FormQEntry> { it.rank }.thenBy { it.ticket })
		val readyOthers = ConcurrentLinkedQueue<CoreBlock>()
		val formTicket = AtomicLong(0)
		val scheduled = ConcurrentHashMap.newKeySet<UUID>()  // чтобы не ставить блок в очередь дважды

		// --- степень ожидания по НЕопциональным входам (считаем по ВСЕМ входам)
		val pending = ConcurrentHashMap<CoreBlock, AtomicInteger>()
		val requiredIncomingCount = candidates.associateWith { b ->
			allIncoming[b].orEmpty().count { !it.conn.isOptional }
		}

		// якоря для ранжирования форм: нет обязательных входов, есть внешний вход, START/INPUT_DATA/PROPERTIES
		val anchors: Set<CoreBlock> = candidates.filter { b ->
			requiredIncomingCount[b] == 0 ||
					hasIncomingFromOutside[b] == true ||
					b.type == BlockType.START || b.type == BlockType.INPUT_DATA || b.type == BlockType.PROPERTIES
		}.toSet()

		// расстояние от якорей — для стабильного порядка показа форм
		val dist = mutableMapOf<UUID, Int>()
		val q: ArrayDeque<CoreBlock> = ArrayDeque()
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

		fun enqueueReady(b: CoreBlock) {
			if (!scheduled.add(b.id)) {
				return
			}
			if (b.type == BlockType.FORM) {
				readyForms.add(FormQEntry(b, formRank(b), formTicket.incrementAndGet()))
			}
			else {
				readyOthers.add(b)
			}
		}

		// первичная инициализация ожиданий — по ВСЕМ входам; MAPPING_* по-прежнему можно запускать частично
		candidates.forEach { b ->
			val inAll = allIncoming[b].orEmpty()
			val requiredIn = inAll.filter { !it.conn.isOptional }

			fun outEmpty(b: CoreBlock, idx: Int): Boolean {
				return isEffectivelyEmptyMap(b.outputsData.getOrNull(idx))
			}

			val hasAnyNonEmpty = inAll.any { (p, idx) -> !outEmpty(p, idx) }

			val allowPartial = b.type in setOf(
				BlockType.MAPPING_GROOVY,
				BlockType.MAPPING_PYTHON,
				BlockType.MAPPING_JAVA_SCRIPT
			)

			val lackRequired = requiredIn.count { inEdge -> !edgeFreshFor(b, inEdge) }

			val need = when {
				allowPartial && hasAnyNonEmpty -> 0
				b.type == BlockType.FORM && eagerForms && requiredIncomingCount[b] == 0 -> 0
				else -> lackRequired
			}
			pending[b] = AtomicInteger(need)
			if (need == 0) {
				val hasOptional = allIncoming[b].orEmpty().any { it.conn.isOptional }
				val hasFreshOptional = allIncoming[b].orEmpty().any { it.conn.isOptional && edgeFreshFor(b, it) }
				val noRequired = requiredIncomingCount[b] == 0
				if (noRequired && hasOptional && !hasFreshOptional) {
					// ждём первого свежего опционального входа
				} else {
					enqueueReady(b)
				}
			}
		}
		if (readyForms.isEmpty() && readyOthers.isEmpty()) return

		val inFlight = AtomicInteger(0)
		val done = CompletableDeferred<Unit>()

		fun submit(block: CoreBlock) {
			inFlight.incrementAndGet()
			scope.launch {
				listeners.forEach { it.onStart(block) }
				try {
					runBlock(block, project)
					listeners.forEach { it.onOutput(block, mapOf("outputs" to block.outputsData)) }

					// триггерим детей только внутри множества кандидатов
					outgoingEdges[block].orEmpty().forEach { e ->
						val payload = block.outputsData.getOrNull(e.outIdx)
						val nonEmpty = !isEffectivelyEmptyMap(payload)
						if (!nonEmpty) {
							return@forEach
						}
						if (e.conn.isNeedDataToRun && isEffectivelyEmptyMap(payload)) {
							return@forEach
						}
						if (!e.conn.isOptional) {
							val fresh = edgeFreshFor(e.child, InEdge(block, e.outIdx, e.conn))
							if (fresh) {
								val left = pending[e.child]!!.decrementAndGet()
								if (left == 0) {
									enqueueReady(e.child)
								}
							}
						} else {
							val fresh = edgeFreshFor(e.child, InEdge(block, e.outIdx, e.conn))
							if (pending[e.child]!!.get() == 0 && fresh) {
								enqueueReady(e.child)
							}
						}
					}
				} catch (t: Throwable) {
					listeners.forEach { it.onError(block, t) }
				} finally {
					listeners.forEach { it.onFinish(block) }
					allIncoming[block].orEmpty().forEach { inEdge ->
						val verNow = outputVersion[inEdge.parent.id]?.getOrNull(inEdge.outIdx) ?: -1L
						consumedVer.computeIfAbsent(block.id) { ConcurrentHashMap() }[edgeKey(inEdge.conn)] = verNow
					}
					val drainedOthers = readyOthers.isEmpty()
					val noFormsAllowedOrPending = breakPhaseAfterForm.get() || readyForms.isEmpty()
					if (inFlight.decrementAndGet() == 0 && drainedOthers && noFormsAllowedOrPending) {
						done.complete(Unit)
					}
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
	}


	/** Исполнение одного блока */
	private suspend fun runBlock(
		block: CoreBlock,
		project: CoreProject,
		preserveOutputs: Boolean = false,
	) {
		val prev = block.outputsData.map { it.toMutableMap() }
		if (!preserveOutputs && !block.type.isService() && block.type != BlockType.PROPERTIES) {
			block.outputsData = MutableList(block.outputCount) { mutableMapOf() }
		}
		try {
			val newOutputs: List<MutableMap<String, Any?>> = when (block.type) {
				BlockType.MAPPING_GROOVY -> {
					val inputs = collectInputs(block, project)
					val outputs = prepareOutputs(block)
					val cls = block.groovyClassName?.trim().orEmpty()
					val code = readCode(block, project)

					val inout = inputs.toMutableMap().apply { putAll(outputs) }
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
							is MutableMap<*, *> -> (v as MutableMap<String, Any?>).toMutableMap()
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
							is MutableMap<*, *> -> v as MutableMap<String, Any?>
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
							is MutableMap<*, *> -> v as MutableMap<String, Any?>
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
					val submitted = try {
						awaitForm(block, specJson, initial)
					} finally {
						formPauseOff()
						formSlotOff()
						breakPhaseAfterForm.set(true)
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
							val rest = List((block.outputNames.size - 1).coerceAtLeast(0)) { mutableMapOf<String, Any?>() }
							listOf(first) + rest
						}
					}
				}

				else -> block.outputsData
			}
			block.outputsData = newOutputs.toMutableList()
			val vers = outputVersion.computeIfAbsent(block.id) { MutableList(block.outputCount) { 0L } }
			block.outputsData.forEachIndexed { i, out ->
				val nonEmpty = !isEffectivelyEmptyMap(out)
				if (nonEmpty) {
					vers[i] = globalTick.incrementAndGet()
				}
			}
		} catch (t: Exception) {
			listeners.forEach { it.onError(block, t) }
			throw t
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
		project.connections
			.filter { it.toId == block.id }
			.forEach { conn ->
				val fromBlock = byId[conn.fromId]
				val fromOutIdx = fromBlock?.outputIds?.indexOf(conn.fromOutputId) ?: -1
				val value = if (fromOutIdx >= 0) deepCopyMap(fromBlock?.outputsData?.getOrNull(fromOutIdx)) else mutableMapOf()
				val toInIdx = block.inputIds.indexOf(conn.toInputId)
				val portName = if (toInIdx >= 0) {
					block.inputNames.getOrNull(toInIdx) ?: "in$toInIdx"
				} else {
					"in?"
				}
				val ver = outputVersion[fromBlock?.id]?.getOrNull(fromOutIdx) ?: -1L
				val prevVer = inputsVer[portName] ?: -1L
				if (ver >= prevVer) {
					inputs[portName] = value
					inputsVer[portName] = ver
				}
			}
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
		val hasValue = !isEffectivelyEmptyMap(parent.outputsData.getOrNull(e.outIdx))
		val ver = outputVersion[parent.id]?.getOrNull(e.outIdx) ?: -1L
		val last = consumedVer[child.id]?.get(edgeKey(e.conn)) ?: -1L
		return if (e.conn.isNeedDataToRun) (hasValue && ver > last) else hasValue
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