package ru.ravel.lcpeandrotd

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import ru.ravel.lcpecore.model.CoreProject
import java.io.File

object ProjectLoader {
	private val mapper = ObjectMapper()

	fun loadProjectFromAssets(context: Context, fileName: String): CoreProject {
		val inputStream = context.assets.open(fileName)
		return mapper.readValue(inputStream, CoreProject::class.java)
	}

	fun loadProjectFromFile(file: File): CoreProject {
		return mapper.readValue(file, CoreProject::class.java)
	}
}
