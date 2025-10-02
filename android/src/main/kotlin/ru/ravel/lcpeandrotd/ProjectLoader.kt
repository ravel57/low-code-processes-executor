package ru.ravel.lcpeandrotd

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import ru.ravel.lcpecore.model.CoreProject

object ProjectLoader {
	private val mapper = ObjectMapper()

	fun loadProjectFromAssets(context: Context, fileName: String): CoreProject {
		val inputStream = context.assets.open(fileName)
		return mapper.readValue(inputStream, CoreProject::class.java)
	}
}
