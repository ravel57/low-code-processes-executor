package ru.ravel.lcpecore.runtime

import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import java.io.File

class CoreSubProjectRunner(
	private val projectRepo: ProjectRepository,
	private val groovy: GroovyExecutor?,
	private val python: PythonExecutor?,
	private val js: JsExecutor?,
) : SubProjectRunner {

	override fun run(parentBlock: CoreBlock, outerProject: CoreProject): List<MutableMap<String, Any?>> {
		val path = parentBlock.subProjectPath
		if (path.isBlank()) return List(parentBlock.outputNames.size) { mutableMapOf() }
		val file = File(path)
		if (!file.exists()) return List(parentBlock.outputNames.size) { mutableMapOf() }

		val baseDir = file.parentFile
		val sub = projectRepo.loadProject(file)

		// 1) Абсолютизируем пути
		sub.blocks.forEach { b ->
			b.codePath = b.codePath?.takeIf { it.isNotBlank() }?.let { absolutize(baseDir, it) }
			b.subProjectPath = b.subProjectPath.takeIf { it.isNotBlank() }?.let { absolutize(baseDir, it) } ?: ""
		}

		// 2) Свойства в PROPERTIES
		if (parentBlock.subProjectProps.isNotEmpty()) {
			val props = parentBlock.subProjectProps.toMap()
			sub.blocks.filter { it.type == BlockType.PROPERTIES }.forEach { pb ->
				while (pb.outputsData.size < pb.outputNames.size) pb.outputsData.add(mutableMapOf())
				pb.outputNames.forEachIndexed { idx, name ->
					if (name in props) pb.outputsData[idx] = mutableMapOf(name to props[name])
				}
			}
		}

		// 3) Входы внешнего SUB_PROJECT -> START/INPUT_DATA подпроекта (исправлено)
		val incoming = outerProject.connections
			.filter { it.toId == parentBlock.id }
			.sortedBy { it.toInputIndex }

		val startBlocks = sub.blocks.filter { it.type == BlockType.START }
		val inputDataBlank = sub.blocks.filter { it.type == BlockType.INPUT_DATA && (it.codePath.isNullOrBlank()) }

		val receivers = sub.blocks.filter { it.type == BlockType.START || it.type == BlockType.INPUT_DATA }
		val byName = receivers.associateBy { it.name }

		fun asMutableMap(v: Any?): MutableMap<String, Any?> = when (v) {
			is MutableMap<*, *> -> (v as MutableMap<String, Any?>)
			is Map<*, *> -> (v as Map<String, Any?>).toMutableMap()
			null -> mutableMapOf()
			else -> mutableMapOf("value" to v)
		}

		fun putOut(target: CoreBlock, outIndex: Int, value: MutableMap<String, Any?>) {
			val idx = outIndex.coerceAtLeast(0)
			while (target.outputsData.size <= idx) target.outputsData.add(mutableMapOf())
			// не затираем другие выходы — пишем по индексу
			target.outputsData[idx] = value
		}

		incoming.forEach { c ->
			val src = outerProject.blocks.firstOrNull { it.id == c.fromId }
			val value = asMutableMap(src?.outputsData?.getOrNull(c.fromOutputIndex))
			val portName = parentBlock.inputNames.getOrNull(c.toInputIndex)
			val target = when {
				portName != null && byName.containsKey(portName) -> byName[portName]!!
				else -> receivers.getOrNull(c.toInputIndex)
			} ?: return@forEach
			val outIndex = if (portName != null) target.outputNames.indexOf(portName).takeIf { it >= 0 } ?: 0 else c.toInputIndex
			putOut(target, outIndex, value)
		}

		// 4) Запуск подпроекта тем же ядром
		val runner = EngineRunner(
			groovy = groovy,
			python = python,
			js = js,
			subProjectRunner = this
		)
		runner.run(sub)

		// 5) Сбор EXIT-ов
		val innerExits = sub.blocks.filter { it.type == BlockType.EXIT }
		val exitsByName = innerExits.associateBy { it.name }
		val out = parentBlock.outputNames.mapIndexed { idx, name ->
			val ex = exitsByName[name] ?: innerExits.getOrNull(idx)
			?: return@mapIndexed mutableMapOf<String, Any?>()
			val merged = mutableMapOf<String, Any?>()
			sub.connections
				.filter { it.toId == ex.id }
				.sortedBy { it.toInputIndex }
				.forEach { ic ->
					val src = sub.blocks.firstOrNull { it.id == ic.fromId }
					val mm = when (val v = src?.outputsData?.getOrNull(ic.fromOutputIndex)) {
						is MutableMap<*, *> -> (v as MutableMap<String, Any?>).toMutableMap()
						is Map<*, *>       -> (v as Map<String, Any?>).toMutableMap()
						null               -> mutableMapOf()
						else               -> mutableMapOf("value" to v)
					}
					merged.putAll(mm)
				}
			merged
		}.toMutableList()
		while (out.size < parentBlock.outputNames.size) out += mutableMapOf()
		return out.take(parentBlock.outputNames.size)
	}

	private fun absolutize(base: File, p: String): String =
		File(p).let { if (it.isAbsolute) it else File(base, p) }.absolutePath
}
