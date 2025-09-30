package ru.ravel.lcpecore.runtime

import ru.ravel.lcpecore.model.*

object CycleDetector {

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
			val next = project.connections.filter { it.fromId == cur.id }.map { c ->
				project.blocks.first { it.id == c.toId }
			}
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

	/** Грубая эвристика «есть ли прогресс» для цикла */
	fun hasProgress(project: CoreProject, cycle: Set<CoreBlock>): Boolean {
		return cycle.any { b -> b.outputsData.any { it.isNotEmpty() } }
	}

}