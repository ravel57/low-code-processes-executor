package ru.ravel.lcpecore.graph

import ru.ravel.lcpecore.model.*
import java.math.BigDecimal
import java.math.BigInteger


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

}