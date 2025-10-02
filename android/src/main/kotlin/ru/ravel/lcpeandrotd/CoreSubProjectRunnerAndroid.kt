package ru.ravel.lcpeandrotd

import android.content.Context
import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.lcpecore.runtime.EngineRunner
import ru.ravel.lcpecore.runtime.GroovyExecutor
import ru.ravel.lcpecore.runtime.JsExecutor
import ru.ravel.lcpecore.runtime.PythonExecutor
import ru.ravel.lcpecore.runtime.SubProjectRunner
import java.io.File
import java.util.*


class CoreSubProjectRunnerAndroid(
	private val context: Context,
	private val projectRepo: ProjectRepository,
	private val groovy: GroovyExecutor?,
	private val python: PythonExecutor?,
	private val js: JsExecutor?,
) : SubProjectRunner {

	override fun run(parentBlock: CoreBlock, outerProject: CoreProject): List<MutableMap<String, Any?>> {
		val path = parentBlock.subProjectPath
		if (path.isBlank()) return List(parentBlock.outputNames.size) { mutableMapOf() }
		val file = resolveFile(path)
		if (!file.exists()) return List(parentBlock.outputNames.size) { mutableMapOf() }

		val baseDir = file.parentFile ?: context.filesDir
		val sub = projectRepo.loadProject(file)
		sub.blocks.forEach { b ->
			b.codePath = b.codePath?.takeIf { it.isNotBlank() }?.let { absolutize(baseDir, it) }
			b.subProjectPath = b.subProjectPath.takeIf { it.isNotBlank() }?.let { absolutize(baseDir, it) } ?: ""
		}
		if (parentBlock.subProjectProps.isNotEmpty()) {
			val props = parentBlock.subProjectProps.toMap()
			sub.blocks.filter { it.type == BlockType.PROPERTIES }.forEach { pb ->
				while (pb.outputsData.size < pb.outputNames.size) pb.outputsData.add(mutableMapOf())
				pb.outputNames.forEachIndexed { idx, name ->
					if (name in props) pb.outputsData[idx] = mutableMapOf(name to props[name])
				}
			}
		}
		val incoming = outerProject.connections
			.filter { it.toId == parentBlock.id }
			.sortedBy { parentBlock.indexOfInput(it.toInputId) }

		val receivers = sub.blocks.filter { it.type == BlockType.START || it.type == BlockType.INPUT_DATA }
		val byName = receivers.associateBy { it.name }

		fun asMutableMap(v: Any?): MutableMap<String, Any?> = when (v) {
			is MutableMap<*, *> -> v as MutableMap<String, Any?>
			is Map<*, *> -> v as MutableMap<String, Any?>
			null -> mutableMapOf()
			else -> mutableMapOf("value" to v)
		}

		fun putOut(target: CoreBlock, outIndex: Int, value: MutableMap<String, Any?>) {
			val idx = outIndex.coerceAtLeast(0)
			while (target.outputsData.size <= idx) target.outputsData.add(mutableMapOf())
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
		val runner = EngineRunner(
			groovy = groovy,
			python = python,
			js = js,
			subProjectRunner = this
		)
		runner.run(sub)
		val innerExits = sub.blocks.filter { it.type == BlockType.EXIT }
		val exitsByName = innerExits.associateBy { it.name }
		val out = parentBlock.outputNames
			.mapIndexed { idx, name ->
				val ex = exitsByName[name] ?: innerExits.getOrNull(idx)
				?: return@mapIndexed mutableMapOf<String, Any?>()
				val merged = mutableMapOf<String, Any?>()
				sub.connections
					.filter { it.toId == ex.id }
					.sortedBy { ex.indexOfInput(it.toInputId) }
					.forEach { ic ->
						val src = sub.blocks.firstOrNull { it.id == ic.fromId }
						val mm = when (val v = src?.outputsData?.getOrNull(src.indexOfOutput(ic.fromOutputId))) {
							is MutableMap<*, *> -> v.toMutableMap()
							is Map<*, *> -> (v as Map<String, Any?>).toMutableMap()
							null -> mutableMapOf()
							else -> mutableMapOf("value" to v)
						}
						merged.putAll(mm)
					}
				merged
			}
			.toMutableList()

		while (out.size < parentBlock.outputNames.size) {
			out += mutableMapOf()
		}
		return out.take(parentBlock.outputNames.size)
	}


	private fun resolveFile(path: String): File {
		val f = File(path)
		return if (f.isAbsolute) f else File(context.filesDir, path)
	}


	private fun absolutize(base: File, p: String): String =
		File(p).let { if (it.isAbsolute) it else File(base, p) }.absolutePath


	private fun CoreBlock.indexOfInput(id: UUID): Int =
		inputIds.indexOf(id).takeIf { it >= 0 } ?: 0


	private fun CoreBlock.indexOfOutput(id: UUID): Int =
		outputIds.indexOf(id).takeIf { it >= 0 } ?: 0
}