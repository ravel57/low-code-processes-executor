package ru.ravel.lcpedesktop

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector
import com.fasterxml.jackson.module.kotlin.KotlinModule
import ru.ravel.lcpecore.io.OutputsRepository
import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpecore.model.CoreProject
import java.io.File

class JsonProjectRepository : ProjectRepository {
	private val mapper: ObjectMapper = ObjectMapper()
		.registerModule(KotlinModule.Builder().build())
		.enable(SerializationFeature.INDENT_OUTPUT)
		.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
		.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.ANY)
		.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.ANY)
		.setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.ANY)
		.setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
		.setAnnotationIntrospector(
			AnnotationIntrospectorPair(
				JacksonAnnotationIntrospector(),
				NopAnnotationIntrospector.instance
			)
		)

	override fun loadProject(absFile: File): CoreProject {
		val coreProject = mapper.readValue(absFile, CoreProject::class.java)
		coreProject.blocks
			.filter { it.type == BlockType.SUB_PROJECT }
			.onEach { block ->
				val codeFile = File("${absFile.parentFile}/${block.codePath}")
				if (codeFile.exists() && codeFile.isFile) {
					block.subProjectProps = mapper.readValue(codeFile, MutableMap::class.java) as MutableMap<String, Any?>
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
