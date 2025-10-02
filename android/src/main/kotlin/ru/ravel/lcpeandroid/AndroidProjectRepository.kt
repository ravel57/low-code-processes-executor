package ru.ravel.lcpeandroid

import android.content.Context
import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.CoreProject
import java.io.File


class AndroidProjectRepository(
	private val context: Context
) : ProjectRepository {

	override fun loadProject(absFile: File): CoreProject {
		val project = ProjectLoader.loadProjectFromFile(absFile)
		project.baseDir = absFile.parentFile
		return project
	}

	override fun saveProject(absFile: File, project: CoreProject) {
	}
}
