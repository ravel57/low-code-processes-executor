package ru.ravel.lcpecore.model

interface ExecutionListener {
	fun onStart(block: CoreBlock) {}
	fun onOutput(block: CoreBlock, data: Map<String, Any?>) {}
	fun onError(block: CoreBlock, error: Throwable) {}
	fun onFinish(block: CoreBlock) {}
}