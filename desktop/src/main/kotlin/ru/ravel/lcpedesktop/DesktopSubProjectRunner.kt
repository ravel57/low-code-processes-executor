//package ru.ravel.lcpedesktop
//
//import ru.ravel.lcpecore.io.ProjectRepository
//import ru.ravel.lcpecore.model.BlockType
//import ru.ravel.lcpecore.model.CoreBlock
//import ru.ravel.lcpecore.model.CoreProject
//import ru.ravel.lcpecore.runtime.*
//import java.io.File
//
//
///** Исполнение SUB_PROJECT на десктопе с прокидыванием входов и нормализацией путей. */
//class DesktopSubProjectRunner(
//	private val projectRepo: ProjectRepository,
//	private val collectProject: () -> CoreProject,
//	private val groovy: GroovyExecutor,
//	private val python: PythonExecutor,
//	private val js: JsExecutor
//) : SubProjectRunner {
//
//	override fun run(parentBlock: CoreBlock): List<MutableMap<String, Any>> {
//		val file = File(parentBlock.subProjectPath)
//		if (parentBlock.subProjectPath.isBlank() || !file.exists()) {
//			return List(parentBlock.outputNames.size) { mutableMapOf() }
//		}
//
//		// 1) Снимок родительского проекта и сбор входов в порядке портов
//		val parent = collectProject()
//		val parentInputs: MutableList<MutableMap<String, Any>> =
//			parent.connections
//				.filter { it.toId == parentBlock.id }
//				.sortedBy { it.toInputIndex }
//				.map { conn ->
//					val src = parent.blocks.firstOrNull { it.id == conn.fromId }
//					@Suppress("UNCHECKED_CAST")
//					when (val v = src?.outputsData?.getOrNull(conn.fromOutputIndex)) {
//						is MutableMap<*, *> -> v as MutableMap<String, Any>
//						is Map<*, *> -> (v as Map<String, Any?>).toMutableMap() as MutableMap<String, Any>
//						else -> mutableMapOf()
//					}
//				}.toMutableList()
//		while (parentInputs.size < parentBlock.inputNames.size) parentInputs += mutableMapOf()
//
//		// 2) Загрузка подпроекта + нормализация путей (как в UI)
//		val sub = projectRepo.loadProject(file)
//		val baseDir = file.parentFile
//		sub.blocks.forEach { b ->
//			b.codePath = b.codePath
//				?.takeIf { it.isNotBlank() }
//				?.let { p -> File(p).let { if (it.isAbsolute) it.absolutePath else File(baseDir, p).absolutePath } }
//			b.subProjectPath = b.subProjectPath
//				.takeIf { it.isNotBlank() }
//				?.let { p -> File(p).let { if (it.isAbsolute) it.absolutePath else File(baseDir, p).absolutePath } }
//				?: ""
//		}
//
//		// 3) Прокидываем входы в стартовые блоки подпроекта:
//		//    - START всегда принимают внешнее значение
//		//    - INPUT_DATA только если пустой (нет собственного codePath)
//		val entryBlocks = sub.blocks.filter { b ->
//			when (b.type) {
//				BlockType.START -> true
//				BlockType.INPUT_DATA -> b.codePath.isNullOrBlank()
//				else -> false
//			}
//		}
//
//		// Сопоставление по имени порта (из родителя) -> имени стартового блока; иначе — по индексу
//		val entryByName = entryBlocks.associateBy { it.name }
//		parentInputs.forEachIndexed { idx, inMap ->
//			val portName = parentBlock.inputNames.getOrNull(idx)
//			val target = (portName?.let { entryByName[it] }) ?: entryBlocks.getOrNull(idx)
//			if (target != null) {
//				target.outputsData = mutableListOf(inMap)
//			}
//		}
//
//		// 4) Запуск подпроекта реальным EngineRunner-ом
//		val runner = EngineRunner(
//			groovy = groovy,
//			python = python,
//			js = js,
//			subProjectRunner = this
//		)
//		runner.run(sub)
//
//		// 5) Сбор результатов: сохраняем порядок EXIT как в JSON (без сортировки по имени)
//		val exitBlocks = sub.blocks.filter { it.type == BlockType.EXIT }
//		val out = exitBlocks.map { ex ->
//			val merged = mutableMapOf<String, Any>()
//			sub.connections
//				.filter { it.toId == ex.id }
//				.sortedBy { it.toInputIndex }
//				.forEach { conn ->
//					val src = sub.blocks.first { it.id == conn.fromId }
//					@Suppress("UNCHECKED_CAST")
//					val m = (src.outputsData.getOrNull(conn.fromOutputIndex) as? Map<String, Any>) ?: emptyMap()
//					merged.putAll(m)
//				}
//			merged
//		}.toMutableList()
//
//		while (out.size < parentBlock.outputNames.size) out += mutableMapOf()
//		return out.take(parentBlock.outputNames.size)
//	}
//}
