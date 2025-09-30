package ru.ravel.lcpecore.util

object DataUtils {

	fun isEmpty(data: Any?): Boolean = when (data) {
		null -> true
		is Map<*, *> -> data.isEmpty()
		is Collection<*> -> data.isEmpty()
		is String -> data.isEmpty()
		else -> false
	}

	fun hasNonEmptyOutput(block: ru.ravel.lcpecore.model.CoreBlock): Boolean {
		return block.outputsData.any { !isEmpty(it) }
	}

}