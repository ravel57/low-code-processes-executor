package ru.ravel.lcpedesktop

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ru.ravel.lcpecore.io.OutputsRepository
import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpecore.model.CoreProject
import java.io.File

class JsonProjectRepository : ProjectRepository {
	private val mapper = ObjectMapper()
		.registerKotlinModule()
		.findAndRegisterModules()

	override fun loadProject(absFile: File): CoreProject {
		val coreProject = mapper.readValue(absFile, CoreProject::class.java)
		coreProject.blocks
			.filter { it.type == BlockType.SUB_PROJECT }
			.onEach { block ->
			val codeFile = File("${absFile.parentFile}/${block.codePath}")
			if (codeFile.exists() && codeFile.isFile) {
				block.subProjectProps = ObjectMapper().readValue(codeFile, MutableMap::class.java) as MutableMap<String, Any?>
			}
		}
		return coreProject
	}

	override fun saveProject(absFile: File, project: CoreProject) {
		mapper.writerWithDefaultPrettyPrinter().writeValue(absFile, project)
	}
}

class JsonOutputsRepository : OutputsRepository {
	override fun loadLast(file: File, project: CoreProject) {
	}

	override fun saveOutputs(projectFile: File, project: CoreProject) {
	}

	override fun loadLastOutputs(projectFile: File, into: CoreProject) {
	}

	override fun save(file: File, project: CoreProject) {
	}
}
