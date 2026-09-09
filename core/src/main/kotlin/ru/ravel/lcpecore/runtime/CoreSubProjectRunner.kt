package ru.ravel.lcpecore.runtime

import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import java.io.File
import java.util.*

class CoreSubProjectRunner(
	private val projectRepo: ProjectRepository,
	private val groovy: GroovyExecutor?,
	private val python: PythonExecutor?,
	private val js: JsExecutor?,
	/** Пробрасываем FormListener внутрь саб-раннера, чтобы FORM работал и в подпроектах */
	private val formListener: EngineRunner.FormListener? = null,
) : SubProjectRunner {

	override fun run(parentBlock: CoreBlock, outerProject: CoreProject): List<MutableMap<String, Any?>> {
		val path = parentBlock.subProjectPath
		if (path.isBlank()) {
			return List(parentBlock.outputNames.size) { mutableMapOf() }
		}

		// резолвим путь относительно baseDir внешнего проекта
		val subFile = if (File(path).isAbsolute) {
			File(path)
		} else {
			File(outerProject.baseDir ?: File("."), path)
		}
		if (!subFile.exists()) {
			return List(parentBlock.outputNames.size) { mutableMapOf() }
		}

		val baseDir = subFile.parentFile
		val sub = projectRepo.loadProject(subFile).apply {
			this.baseDir = baseDir
		}

		// 1) Абсолютизируем пути внутри подпроекта
		sub.blocks.forEach { b ->
			b.codePath = b.codePath?.takeIf { it.isNotBlank() }?.let { rel ->
				File(baseDir, rel.removePrefix("/")).normalize().absolutePath
			}
			b.subProjectPath = b.subProjectPath.takeIf { it.isNotBlank() }?.let { absolutize(baseDir, it) } ?: ""
		}

		// 2) Подмешиваем subProjectProps в PROPERTIES
		if (parentBlock.subProjectProps.isNotEmpty()) {
			val props = parentBlock.subProjectProps.toMap()
			sub.blocks.filter { it.type == BlockType.PROPERTIES }.forEach { pb ->
				while (pb.outputsData.size < pb.outputNames.size) {
					pb.outputsData.add(mutableMapOf())
				}
				pb.outputNames.forEachIndexed { idx, name ->
					if (name in props) {
						pb.outputsData[idx] = mutableMapOf(name to props[name])
					}
				}
			}
		}

		// 3) Перенос входов из внешнего SUB_PROJECT -> START/INPUT_DATA подпроекта
		val incoming = outerProject.connections
			.filter { it.toId == parentBlock.id }
			.sortedBy { parentBlock.indexOfInput(it.toInputId) }

		val receivers = sub.blocks.filter { it.type == BlockType.START || it.type == BlockType.INPUT_DATA }
		val byName = receivers.associateBy { it.name }

		@Suppress("UNCHECKED_CAST")
		fun asMutableMap(v: Any?): MutableMap<String, Any?> = when (v) {
			is MutableMap<*, *> -> (v as MutableMap<String, Any?>)
			is Map<*, *> -> (v as Map<String, Any?>).toMutableMap()
			null -> mutableMapOf()
			else -> mutableMapOf("value" to v)
		}

		fun putOut(target: CoreBlock, outIndex: Int, value: MutableMap<String, Any?>) {
			val idx = outIndex.coerceAtLeast(0)
			while (target.outputsData.size <= idx) {
				target.outputsData.add(mutableMapOf())
			}
			target.outputsData[idx] = value
		}

		incoming.forEach { c ->
			val src = outerProject.blocks.firstOrNull { it.id == c.fromId } ?: return@forEach
			val srcOutIdx = src.indexOfOutput(c.fromOutputId)
			val value: MutableMap<String, Any?> = asMutableMap(src.outputsData.getOrNull(srcOutIdx))
			val inIdx = parentBlock.indexOfInput(c.toInputId)
			val portName = parentBlock.inputNames.getOrNull(inIdx)
			val target = when {
				portName != null && byName.containsKey(portName) -> byName[portName]!!
				else -> receivers.getOrNull(inIdx)
			} ?: return@forEach
			val outIndex = portName?.let { nm -> target.outputNames.indexOf(nm).takeIf { it >= 0 } } ?: 0
			putOut(target, outIndex, value)
		}

		// 4) Запускаем подпроект через EngineRunner (с тем же formListener!)
		val runner = EngineRunner(
			groovy = groovy,
			python = python,
			js = js,
			subProjectRunner = this,
			formListener = formListener
		)
		runner.run(sub)

		// 5) Сбор EXIT блоков
		val innerExits = sub.blocks.filter { it.type == BlockType.EXIT }
		val exitsByName = innerExits.associateBy { it.name }

		@Suppress("KotlinConstantConditions")
		val out = parentBlock.outputNames.mapIndexed { idx, name ->
			val ex = exitsByName[name]
				?: innerExits.getOrNull(idx)
				?: return@mapIndexed mutableMapOf<String, Any?>()

			val merged = mutableMapOf<String, Any?>()
			sub.connections
				.filter { it.toId == ex.id }
				.sortedBy { ex.indexOfInput(it.toInputId) }
				.forEach { ic ->
					val src = sub.blocks.firstOrNull { it.id == ic.fromId }
					val map = src?.outputsData?.getOrNull(src.indexOfOutput(ic.fromOutputId))
					val mm = when (map) {
						is MutableMap<*, *> -> map.toMutableMap()
						is Map<*, *> -> (map as Map<String, Any?>).toMutableMap()
						null -> mutableMapOf()
						else -> mutableMapOf("value" to map)
					}
					merged.putAll(mm)
				}
			merged
		}.toMutableList()

		while (out.size < parentBlock.outputNames.size) {
			out += mutableMapOf()
		}
		return out.take(parentBlock.outputNames.size)
	}

	private fun absolutize(base: File, p: String): String =
		File(if (p.startsWith("/")) p.substring(1) else p).let { rel ->
			if (rel.isAbsolute) {
				rel
			} else {
				File(base, rel.path)
			}
		}.absolutePath

	private fun CoreBlock.indexOfInput(id: UUID): Int =
		inputIds.indexOf(id).takeIf { it >= 0 } ?: 0

	private fun CoreBlock.indexOfOutput(id: UUID): Int =
		outputIds.indexOf(id).takeIf { it >= 0 } ?: 0
}
