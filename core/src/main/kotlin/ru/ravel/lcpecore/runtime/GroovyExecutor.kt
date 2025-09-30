package ru.ravel.lcpecore.runtime

interface GroovyExecutor {
	fun exec(
		code: String,
		bindings: Map<String, Any?>
	): Any?
}