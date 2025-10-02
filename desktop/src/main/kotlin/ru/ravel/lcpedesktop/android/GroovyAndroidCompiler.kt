package ru.ravel.lcpedesktop.android

import java.io.File

object GroovyAndroidCompiler {
	@JvmStatic
	fun main(args: Array<String>) {
		val jarFile = File("scripts/DateAdderScript.jar")
		val dexJarFile = File("scripts/DateAdderScript-dex.jar")

		GroovyJarCompiler.compileToJar(
			script = """
				import java.time.LocalDate
	            def days = input['days'] as int
	            return [result: LocalDate.now().plusDays(days)]
	        """.trimIndent(),
			className = "DateAdderScript",
			outputJarFile = jarFile
		)
		DexCompiler.jarToDexJar(jarFile, dexJarFile)
	}
}