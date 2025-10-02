package ru.ravel.lcpeandrotd

import android.content.Context
import ru.ravel.lcpecore.io.OutputsRepository
import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.lcpecore.model.ExecutionListener
import ru.ravel.lcpecore.runtime.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Headless-обёртка над EngineRunner для Android */
class AndroidRunController(
	private val context: Context,
	private val projectRepo: ProjectRepository,
	private val outputsRepo: OutputsRepository,
	private val groovy: GroovyExecutor?,
	private val python: PythonExecutor?,
	private val js: JsExecutor?,
	private val executor: ExecutorService = Executors.newCachedThreadPool()
) {
	/** Запускает проект в фоне, возвращает Future */
	fun runAsync(project: CoreProject, projectFile: File?, events: RunEvents): Future<*> {
		return executor.submit {
			val runner = EngineRunner(
				groovy = groovy,
				python = python,
				js = js,
				// ключевое отличие: подпроекты гоняет тот же EngineRunner
				subProjectRunner = CoreSubProjectRunner(
					projectRepo = projectRepo,
					groovy = groovy,
					python = python,
					js = js
				),
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
