package ru.ravel.lcpeandroid

import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject

/** События выполнения, без зависимостей от Android UI */
interface RunEvents {
	fun onStart(block: CoreBlock) {}
	fun onOutput(block: CoreBlock, payload: Map<String, Any?>) {}
	fun onFinish(block: CoreBlock) {}
	fun onError(block: CoreBlock, error: Throwable) {}
	fun onCompleted(project: CoreProject) {}
}