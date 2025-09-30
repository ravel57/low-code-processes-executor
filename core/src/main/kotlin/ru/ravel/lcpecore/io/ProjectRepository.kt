package ru.ravel.lcpecore.io

import ru.ravel.lcpecore.model.*
import java.io.File

interface ProjectRepository {

	fun loadProject(
		absFile: File
	): CoreProject

	fun saveProject(
		absFile: File,
		project: CoreProject,
	)

}