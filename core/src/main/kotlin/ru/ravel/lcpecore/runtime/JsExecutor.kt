package ru.ravel.lcpecore.runtime

interface JsExecutor {
	fun exec(
		code: String,
		inputs: Map<String, Any?>,
		outputNames: List<String>
	): Map<String, MutableMap<String, Any?>>
}