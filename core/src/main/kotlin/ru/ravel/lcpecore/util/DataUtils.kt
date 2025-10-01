package ru.ravel.lcpecore.util

object DataUtils {

	private fun isEmpty(data: Any?): Boolean = when (data) {
		null -> true
		is Map<*, *> -> data.isEmpty()
		is Collection<*> -> data.isEmpty()
		is String -> data.isEmpty()
		else -> false
	}

	fun hasNonEmptyOutput(block: ru.ravel.lcpecore.model.CoreBlock): Boolean {
		return block.outputsData.any { !isEmpty(it) }
	}

	fun outputsChanged(old: List<MutableMap<String, Any?>>, new: List<MutableMap<String, Any?>>): Boolean {
		if (old.size != new.size) return true
		return old.zip(new).any { (o, n) -> o != n }
	}

}