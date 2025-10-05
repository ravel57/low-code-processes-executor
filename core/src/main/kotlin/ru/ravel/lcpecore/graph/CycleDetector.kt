package ru.ravel.lcpecore.graph

import ru.ravel.lcpecore.model.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID


object CycleDetector {

	/** Находит первый цикл простым DFS. */
	fun findFirstCycle(project: CoreProject): List<CoreBlock>? {
		val visited = mutableSetOf<CoreBlock>()
		val stack = mutableListOf<CoreBlock>()

		fun dfs(cur: CoreBlock): List<CoreBlock>? {
			if (cur in stack) {
				val idx = stack.indexOf(cur)
				return stack.subList(idx, stack.size).toList()
			}
			if (!visited.add(cur)) return null
			stack.add(cur)

			val next = project.connections
				.filter { it.fromId == cur.id }
				.map { c -> project.blocks.first { it.id == c.toId } }

			for (n in next) {
				val r = dfs(n)
				if (r != null) return r
			}
			stack.removeAt(stack.size - 1)
			return null
		}

		for (b in project.blocks) {
			stack.clear()
			val c = dfs(b)
			if (!c.isNullOrEmpty()) return c
		}
		return null
	}


	/** Утилита: отфильтровать блоки цикла (например, исключить FORM). */
	fun filterCycleBlocks(cycle: List<CoreBlock>, excludeTypes: Set<BlockType> = setOf(BlockType.FORM)): List<CoreBlock> {
		return cycle.filter { it.type !in excludeTypes }
	}


	/**
	 * «Снимок» стабильного состояния выходов для набора блоков.
	 * Возвращает хэши уже нормализованных структур, чтобы сравнение было дешёвым и детерминированным.
	 */
	fun snapshot(
		cycle: Collection<CoreBlock>,
		ignoreKeys: Set<String> = setOf("_ts", "ts", "time", "_trace", "log"),
	): Map<UUID, Int> {
		return cycle.associate { b ->
			b.id to stableHashOutputs(b.outputsData, ignoreKeys)
		}
	}


	/** Равен ли снимок A снимку B (полная стабилизация цикла). */
	fun snapshotsEqual(a: Map<UUID, Int>, b: Map<UUID, Int>): Boolean = a == b


	/**
	 * Хэшим «список карт» выходов блока после глубокой нормализации:
	 * - удаляем шумовые ключи,
	 * - сортируем ключи map'ов,
	 * - приводим числа к стабильным типам (Long/Double),
	 * - рекурсивно нормализуем списки/карты.
	 */
	private fun stableHashOutputs(
		out: List<MutableMap<String, Any?>>,
		ignoreKeys: Set<String>,
	): Int = normalizeListOfMaps(out, ignoreKeys).hashCode()


	private fun normalizeListOfMaps(
		out: List<MutableMap<String, Any?>>,
		ignoreKeys: Set<String>,
	): List<Map<String, Any?>> = out.map { normalizeMap(it, ignoreKeys) }


	private fun normalizeMap(m: Map<String, Any?>, ignoreKeys: Set<String>): Map<String, Any?> {
		return m.filterKeys { it !in ignoreKeys }
			.mapValues { (_, v) -> normalizeValue(v, ignoreKeys) }
			.toSortedMap()
	}


	@Suppress("UNCHECKED_CAST")
	private fun normalizeValue(v: Any?, ignoreKeys: Set<String>): Any? = when (v) {
		is Map<*, *> -> normalizeMap(v as Map<String, Any?>, ignoreKeys)
		is List<*> -> v.map { normalizeValue(it, ignoreKeys) }
		is Int, is Short, is Byte, is Long -> (v as Number).toLong()
		is Float, is Double -> (v as Number).toDouble()
		is BigInteger -> v.toLong()
		is BigDecimal -> v.toDouble()
		else -> v
	}


	/**
	 * БЫЛО: «хоть что-то непустое в выходах цикла?»
	 * Такое условие не годится для остановки — оно остаётся true навсегда.
	 * Держим только для обратной совместимости, не используйте.
	 */
	@Deprecated("Используйте snapshot/snapshotsEqual для проверки стабилизации")
	fun hasProgress(project: CoreProject, cycle: Set<CoreBlock>): Boolean {
		return cycle.any { b -> b.outputsData.any { it.isNotEmpty() } }
	}
}