package ru.ravel.lcpecore.runtime

import ru.ravel.lcpecore.model.CoreBlock

interface PythonExecutor {
	fun exec(
		block: CoreBlock,
		bindings: Map<String, Any?>,
		outputs: MutableMap<String, MutableMap<String, Any?>>,
		packagesNames: MutableList<String>,
	): Map<String, Any?>
}