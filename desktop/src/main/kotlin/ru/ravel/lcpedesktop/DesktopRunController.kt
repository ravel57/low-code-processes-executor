package ru.ravel.lcpedesktop

import ru.ravel.lcpecore.io.OutputsRepository
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.lcpecore.model.ExecutionListener
import ru.ravel.lcpecore.runtime.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** События для UI — ни одной зависимости от JavaFX. */
interface RunEvents {
	fun onStart(block: CoreBlock) {}
	fun onOutput(block: CoreBlock, payload: Map<String, Any?>) {}
	fun onFinish(block: CoreBlock) {}
	fun onError(block: CoreBlock, error: Throwable) {}
	/** Вызывается 1 раз по завершении всего графа. */
	fun onCompleted(project: CoreProject) {}
}

/** Headless-обёртка над EngineRunner: фон, репозитории, события. */
class DesktopRunController(
	private val groovy: GroovyExecutor?,
	private val python: PythonExecutor?,
	private val js: JsExecutor?,
	private val subProjectRunner: SubProjectRunner,
	private val outputsRepo: OutputsRepository,
	private val executor: ExecutorService,
) {
	/** Запускает вычисление в фоне. Возвращает Future, чтобы UI мог по желанию ждать/отменять. */
	fun runAsync(project: CoreProject, projectFile: File?, events: RunEvents): Future<*> {
		return executor.submit {
			val runner = EngineRunner(
				groovy = groovy,
				python = python,
				js = js,
				subProjectRunner = subProjectRunner,
				listeners = listOf(object : ExecutionListener {
					override fun onStart(block: CoreBlock) = events.onStart(block)
					override fun onFinish(block: CoreBlock) = events.onFinish(block)
					override fun onError(block: CoreBlock, error: Throwable) = events.onError(block, error)
					override fun onOutput(block: CoreBlock, payload: Map<String, Any?>) =
						events.onOutput(block, payload)
				})
			)
			runner.run(project)
			if (projectFile != null) {
				outputsRepo.saveOutputs(projectFile, project)
			}
			events.onCompleted(project)
		}
	}
}
