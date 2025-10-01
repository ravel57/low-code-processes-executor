package ru.ravel.lcpecore.runtime

import kotlinx.coroutines.*
import ru.ravel.lcpecore.model.ExecutionListener
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.lcpecore.util.DataUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.associateBy
import kotlin.collections.filter
import kotlin.collections.getOrNull
import kotlin.collections.getValue
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.collections.orEmpty
import kotlin.collections.set
import kotlin.collections.sortedBy
import kotlin.collections.toMutableMap

class EngineRunner(
	private val groovy: GroovyExecutor?,          // можно передать null, если язык не используется
	private val python: PythonExecutor?,          // см. комментарии в MAPPING_PYTHON
	private val js: JsExecutor?,                  // см. комментарии в MAPPING_JAVA_SCRIPT
	private val subProjectRunner: SubProjectRunner,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
	private val listeners: List<ExecutionListener> = emptyList(),
) {

	/** Запуск всего проекта (DAG + возможные циклы — «мягкая» схема с фазами) */
	fun run(project: CoreProject) {
		// 1) Выполнить всё, что можно без учёта цикла
		runPhase(project, exclude = emptySet())

		// 2) Если есть цикл — итеративно прокручиваем, пока есть прогресс
		val cycle = CycleDetector.findFirstCycle(project)
		if (!cycle.isNullOrEmpty()) {
			val cycleSet = cycle.toSet()
			while (true) {
				var progressed = false
				for (b in cycle) {
					listeners.forEach { it.onStart(b) }
					try {
						runBlock(b, project)
						progressed = true
						listeners.forEach { it.onOutput(b, mapOf("outputs" to b.outputsData)) }
					} catch (t: Throwable) {
						listeners.forEach { it.onError(b, t) }
						throw t
					} finally {
						listeners.forEach { it.onFinish(b) }
					}
				}
				if (!progressed) break
				// Упрощённый критерий стабилизации: если все входы EXIt-потомков пустые/одинаковые — выходим.
				// При необходимости замените на ваш строгий критерий.
				if (!CycleDetector.hasProgress(project, cycleSet)) break
			}
			// После цикла ещё раз догоняем «детей» цикла
			runPhase(project, exclude = emptySet())
		}
	}

	/** Одна «фаза»: выполняем любые блоки, у которых все родители уже дали выходы */
	private fun runPhase(project: CoreProject, exclude: Set<CoreBlock>) {
		val byId = project.blocks.associateBy { it.id }
		val incoming: Map<CoreBlock, List<CoreBlock>> = project.incomingIndex()
		val outgoing = mutableMapOf<CoreBlock, MutableList<CoreBlock>>()
		project.connections.forEach { c ->
			val from = byId[c.fromId]
			val to = byId[c.toId]
			if (from != null && to != null) {
				outgoing.computeIfAbsent(from) { mutableListOf() }.add(to)
			}
		}

		// Кандидаты текущей фазы (не исключены, напр. узлы цикла)
		val candidates = project.blocks.filter { it !in exclude }.toSet()
		if (candidates.isEmpty()) {
			return
		}

		// Сколько родителей ещё НЕ дало данных для каждого кандидата
		val deps = ConcurrentHashMap<CoreBlock, AtomicInteger>()
		candidates.forEach { b ->
			val need = incoming[b].orEmpty().count { parent ->
				parent in candidates && !DataUtils.hasNonEmptyOutput(parent)
			}
			deps[b] = AtomicInteger(need)
		}

		// Очередь готовых к запуску
		val ready = ConcurrentLinkedQueue<CoreBlock>()
		candidates.forEach { if (deps[it]!!.get() == 0) ready.add(it) }
		if (ready.isEmpty()) return

		val inFlight = AtomicInteger(0)
		val done = CompletableDeferred<Unit>()

		fun submit(block: CoreBlock) {
			inFlight.incrementAndGet()
			scope.launch {
				listeners.forEach { it.onStart(block) }
				try {
					runBlock(block, project)
					listeners.forEach { it.onOutput(block, mapOf("outputs" to block.outputsData)) }

					// только теперь освобождаем потомков
					outgoing[block].orEmpty().forEach { child ->
						if (child in candidates) {
							val left = deps[child]!!.decrementAndGet()
							if (left == 0) ready.add(child)
						}
					}
				} catch (t: Throwable) {
					listeners.forEach { it.onError(block, t) }
				} finally {
					listeners.forEach { it.onFinish(block) }
					if (inFlight.decrementAndGet() == 0 && ready.isEmpty()) {
						done.complete(Unit)
					}
				}
			}
		}

		// стартовые задачи
		while (true) ready.poll()?.let(::submit) ?: break

		// координатор
		scope.launch {
			while (isActive && (inFlight.get() > 0 || ready.isNotEmpty())) {
				var scheduled = false
				while (true) {
					submit(ready.poll() ?: break)
					scheduled = true
				}
				if (!scheduled && inFlight.get() > 0) delay(5)
			}
		}

		// ждём завершения всей фазы
		runBlocking { done.await() }
	}


	/** Исполнение одного блока */
	private fun runBlock(block: CoreBlock, project: CoreProject) {
		if (!block.type.isService()) {
			block.outputsData = MutableList(block.outputCount) { mutableMapOf() }
		}
		try {
			listeners.forEach { it.onStart(block) }
			val newOutputs: List<MutableMap<String, Any?>> = when (block.type) {
				BlockType.MAPPING_GROOVY -> {
					val inputs = collectInputs(block, project)
					val outputs = prepareOutputs(block)
					val code = readCode(block)
					val inout: MutableMap<String, Any?> = inputs.toMutableMap().apply { putAll(outputs) }
					requireNotNull(groovy) { "GroovyExecutor is not provided" }.exec(code, inout)
					block.outputNames.map { name ->
						@Suppress("UNCHECKED_CAST")
						(inout[name] as? MutableMap<String, Any?>)?.toMutableMap()
							?: outputs[name]?.toMutableMap()
							?: mutableMapOf()
					}
				}

				BlockType.MAPPING_PYTHON -> {
					val inputs = collectInputs(block, project)
					val outputs = prepareOutputs(block)
					val result = requireNotNull(python) { "PythonExecutor is not provided" }
						.exec(block, inputs, outputs, block.packagesNames)
					block.outputNames.map { name ->
						val value = result[name]
						when (value) {
							is MutableMap<*, *> -> (value as MutableMap<String, Any?>).toMutableMap()
							is Map<*, *> -> (value as Map<String, Any?>).toMutableMap()
							null -> outputs[name]?.toMutableMap() ?: mutableMapOf()
							else -> mutableMapOf("value" to value)
						}
					}
				}

				BlockType.MAPPING_JAVA_SCRIPT -> {
					val inputs = collectInputs(block, project)
					val code = readCode(block)
					val result = requireNotNull(js) { "JsExecutor is not provided" }
						.exec(code, inputs, block.outputNames)
					block.outputNames.map { name ->
						result[name]?.toMutableMap() ?: mutableMapOf()
					}
				}

				BlockType.INPUT_DATA -> {
					val text = readCode(block)
					val parsed: MutableMap<String, Any?> = InputParsers.parse(block.inputFormat, text)
						.mapValues { it.value }
						.toMutableMap()
					if (parsed.isEmpty() && text.isNotBlank()) parsed["data"] = text
					listOf(parsed)
				}

				BlockType.START -> {
					val text = readCode(block).trim()
					val current = block.outputsData.map { it.toMutableMap() }.toMutableList()
					if (current.isEmpty()) {
						val first = mutableMapOf<String, Any?>()
						if (text.isNotBlank()) first["trigger"] = text
						listOf(first)
					} else {
						if (text.isNotBlank()) {
							current[0]["trigger"] = text
						}
						current
					}
				}

				BlockType.SUB_PROJECT -> {
					subProjectRunner.run(block, project).toMutableList()
				}

				BlockType.PROPERTIES -> {
					if (block.outputsData.isEmpty()) {
						block.outputNames.map { mutableMapOf(it to "") }
					} else {
						block.outputsData
					}
				}

				else -> block.outputsData
			}

			block.outputsData = newOutputs.toMutableList()

			listeners.forEach { it.onOutput(block, mapOf("outputs" to block.outputsData)) }
		} catch (t: Throwable) {
			listeners.forEach { it.onError(block, t) }
			throw t
		} finally {
			listeners.forEach { it.onFinish(block) }
		}
		val outgoingBlocks = project.connections
			.filter { it.fromId == block.id }
			.mapNotNull { conn -> project.blocks.find { it.id == conn.toId } }
		for (child in outgoingBlocks) {
			val allInputsReady = project.connections
				.filter { it.toId == child.id }
				.mapNotNull { c -> project.blocks.find { it.id == c.fromId } }
				.all { parent ->
					parent.outputsData.any { it.isNotEmpty() } ||
							parent.type in listOf(BlockType.START, BlockType.INPUT_DATA, BlockType.PROPERTIES)
				}
			if (allInputsReady) {
				runBlock(child, project)
			}
		}
	}


	private fun collectInputs(block: CoreBlock, project: CoreProject): MutableMap<String, Any?> {
		val inputs = mutableMapOf<String, Any?>()
		project.connections
			.filter { it.toId == block.id }
			.sortedBy { it.toInputIndex }
			.forEach { conn ->
				val fromBlock = project.blocks.firstOrNull { it.id == conn.fromId }
				val value = fromBlock?.outputsData?.getOrNull(conn.fromOutputIndex) ?: mutableMapOf<String, Any?>()
				val portName = block.inputNames.getOrNull(conn.toInputIndex) ?: "in${conn.toInputIndex}"
				inputs[portName] = value
			}
		return inputs
	}

	private fun prepareOutputs(block: CoreBlock): MutableMap<String, MutableMap<String, Any?>> =
		block.outputNames.associateWith { mutableMapOf<String, Any?>() }.toMutableMap()

	private fun readCode(block: CoreBlock): String {
		return block.codePath?.let { p -> File(p).takeIf { it.exists() && it.isFile }?.readText() } ?: ""
	}

	/** Индекс входящих рёбер: для каждого блока — список «родителей» */
	private fun CoreProject.incomingIndex(): Map<CoreBlock, List<CoreBlock>> {
		val byId = blocks.associateBy { it.id }
		val map = mutableMapOf<CoreBlock, MutableList<CoreBlock>>()
		blocks.forEach { map[it] = mutableListOf() }
		connections.forEach { c ->
			val to = byId[c.toId]
			val from = byId[c.fromId]
			if (to != null && from != null) map.getValue(to).add(from)
		}
		return map
	}


	class BlockExecutionException(blockName: String, cause: Throwable) :
		RuntimeException("Exception in $blockName: ${cause.message}", cause)
}