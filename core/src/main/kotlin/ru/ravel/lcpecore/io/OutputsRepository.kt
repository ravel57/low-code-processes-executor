package ru.ravel.lcpecore.io

import java.io.File
import ru.ravel.lcpecore.model.CoreProject

interface OutputsRepository {

	fun saveOutputs(projectFile: File, project: CoreProject)

	fun loadLastOutputs(projectFile: File, into: CoreProject)

	fun save(file: File, project: CoreProject)

	fun loadLast(file: File, project: CoreProject)

}