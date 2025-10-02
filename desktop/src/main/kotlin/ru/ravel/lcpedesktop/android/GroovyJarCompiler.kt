package ru.ravel.lcpedesktop.android

import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilerConfiguration
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

object GroovyJarCompiler {

	/**
	 * Компилирует groovy-скрипт в JAR.
	 *
	 * @param script полный текст скрипта (с импортами и любым кодом)
	 * @param className имя генерируемого класса
	 * @param outputJarFile jar-файл для записи
	 */
	fun compileToJar(script: String, className: String, outputJarFile: File) {
		val lines = script.lines()

		val imports = lines.filter { it.trim().startsWith("import ") }
			.joinToString("\n")

		val body = lines.filterNot { it.trim().startsWith("import ") }
			.joinToString("\n")

		val groovySource = """
	        package ru.ravel.scripts
	        
	        @GrabConfig(initContextClass=false)
	        import groovy.transform.CompileStatic
	        $imports

	        @CompileStatic
	        class $className {
	            Map<String, Object> run(Map<String,Object> input) {
	                $body
	            }
	        }
	    """.trimIndent()

		val config = CompilerConfiguration().apply {
			targetDirectory = File("build/tmp/groovy-classes")
			targetBytecode = "11"
			optimizationOptions["indy"] = false
		}

		val gcl = GroovyClassLoader(this::class.java.classLoader, config)
		val clazz = gcl.parseClass(groovySource, "$className.groovy")

		val classFile = File(config.targetDirectory, "${clazz.name.replace('.', '/')}.class")
		if (!classFile.exists()) {
			throw IllegalStateException("Не найден .class: ${classFile.absolutePath}")
		}
		outputJarFile.parentFile?.mkdirs()

		// собираем JAR только с нашим классом
		JarOutputStream(FileOutputStream(outputJarFile)).use { jar ->
			val added = mutableSetOf<String>()
			config.targetDirectory
				.walkTopDown()
				.filter { it.isFile && it.extension == "class" }
				.forEach { file ->
					val relPath = file.relativeTo(config.targetDirectory).invariantSeparatorsPath
					if (relPath.startsWith("ru/ravel/scripts/${className}")) {
						if (added.add(relPath)) {
							val entry = JarEntry(relPath)
							jar.putNextEntry(entry)
							jar.write(file.readBytes())
							jar.closeEntry()
						}
					}
				}
		}



		println("JAR создан (только скрипт): ${outputJarFile.absolutePath}")
	}
}