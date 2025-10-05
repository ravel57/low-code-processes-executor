package ru.ravel.lcpeandroid

import android.content.Context
import ru.ravel.lcpecore.io.OutputsRepository
import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.lcpecore.model.ExecutionListener
import ru.ravel.lcpecore.runtime.*
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Headless-обёртка над EngineRunner для Android */
@Suppress("unused")
class AndroidRunController(
	private val context: Context,
	private val projectRepo: ProjectRepository,
	private val outputsRepo: OutputsRepository,
	private val groovy: GroovyExecutor?,
	private val python: PythonExecutor?,
	private val js: JsExecutor?,
	private val executor: ExecutorService = Executors.newCachedThreadPool(),
) {

	@Volatile
	private var lastRunner: EngineRunner? = null

	interface RunEvents {
		fun onStart(block: CoreBlock) {}
		fun onFinish(block: CoreBlock) {}
		fun onError(block: CoreBlock, error: Throwable) {}
		fun onOutput(block: CoreBlock, payload: Map<String, Any?>) {}

		/** Важный новый колбэк: показать форму пользователю */
		fun onFormRequested(block: CoreBlock, specJson: String, initial: Map<String, Any?>) {}
		fun onCompleted(project: CoreProject) {}
	}

	/** Запускает проект, возвращает Future */
	fun runAsync(project: CoreProject, projectFile: File?, events: RunEvents): Future<*> {
		return executor.submit {
			if (project.baseDir == null && projectFile != null) {
				project.baseDir = projectFile.parentFile
			}

			val formListener = object : EngineRunner.FormListener {
				override fun onFormRequested(block: CoreBlock, specJson: String, initial: Map<String, Any?>) {
					events.onFormRequested(block, specJson, initial)
				}
			}

			val subProjectRunner = CoreSubProjectRunner(
				projectRepo = projectRepo, groovy = groovy, python = python, js = js, formListener = formListener
			)

			val runner = EngineRunner(
				groovy = groovy,
				python = python,
				js = js,
				subProjectRunner = subProjectRunner,
				listeners = listOf(object : ExecutionListener {
					override fun onStart(block: CoreBlock) {
						events.onStart(block)
					}

					override fun onFinish(block: CoreBlock) {
						events.onFinish(block)
					}

					override fun onError(block: CoreBlock, error: Throwable) {
						events.onError(block, error)
					}

					override fun onOutput(block: CoreBlock, payload: Map<String, Any?>) {
						events.onOutput(block, payload)
					}
				}),
				formListener = formListener,
			)

			lastRunner = runner
			runner.run(project)
			if (projectFile != null) outputsRepo.saveOutputs(projectFile, project)
			events.onCompleted(project)
		}
	}

	/** Вызывает Android-UI после submit формы */
	fun submitForm(blockId: UUID, values: Map<String, Any?>) {
		lastRunner?.submitForm(blockId, values)
	}
}
