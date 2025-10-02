package ru.ravel.lcpeandroid

import ru.ravel.lcpecore.io.OutputsRepository
import ru.ravel.lcpecore.model.CoreProject
import java.io.File

class AndroidOutputsRepository : OutputsRepository {
	override fun loadLast(file: File, project: CoreProject) {}
	override fun saveOutputs(projectFile: File, project: CoreProject) {}
	override fun loadLastOutputs(projectFile: File, into: CoreProject) {}
	override fun save(file: File, project: CoreProject) {}
}