package ru.ravel.lcpedesktop.android

import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilerConfiguration
import ru.ravel.lcpecore.model.CoreBlock
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

object GroovyJarCompiler {

	/**
	 * Компилирует groovy-скрипт в JAR.
	 */
	fun compileToJar(script: String, block: CoreBlock, outputDir: File): File {
		val fqcn = block.groovyClassName?.takeIf { it.isNotBlank() }
			?: "ru.ravel.scripts.GroovyBlock_${block.id.toString().replace("-", "")}"
				.also { block.groovyClassName = it }
		val className = "GroovyBlock_${block.id.toString().replace("-", "")}"
		val groovySource = wrapGroovySource(className, script, block.inputNames, block.outputNames)
		val simpleName = fqcn.substringAfterLast('.')
		val jarFile = File(outputDir, "$simpleName.jar")

		val config = CompilerConfiguration().apply {
			targetDirectory = File("build/tmp/groovy-classes")
			targetBytecode = "8"
			optimizationOptions["indy"] = false
		}
		val gcl = GroovyClassLoader(this::class.java.classLoader, config)
		val clazz = gcl.parseClass(groovySource, "$className.groovy")

		val classFile = File(config.targetDirectory, "${clazz.name.replace('.', '/')}.class")
		if (!classFile.exists()) {
			throw IllegalStateException("Не найден .class: ${classFile.absolutePath}")
		}
		jarFile.parentFile?.mkdirs()

		// собираем JAR только с нашим классом
		JarOutputStream(FileOutputStream(jarFile)).use { jar ->
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
		println("JAR создан (только скрипт): ${jarFile.absolutePath}")
		return jarFile
	}


	private fun wrapGroovySource(
		className: String,
		script: String,
		inputs: List<String>,
		outputs: List<String>
	): String {
		val lines = script.lines()
		val imports = lines.filter { it.trim().startsWith("import ") }
			.joinToString("\n")
		val body = lines.filterNot { it.trim().startsWith("import ") }
			.joinToString("\n")
		val inputDecls = inputs.joinToString("\n        ") { nm -> "def $nm = inputs[\"$nm\"]" }
		val outputDecls = outputs.joinToString("\n        ") { nm -> "def $nm = [:]" }
		val outputReturn = outputs.joinToString(", ") { nm -> "$nm: $nm" }
		val fixedBody = fixForAndroid(body, inputs, outputs)
		return """
	        |package ru.ravel.scripts
	        |
	        |@GrabConfig(initContextClass=false)
	        |import groovy.transform.CompileDynamic
	        |$imports
			|
	        |class $className {
	        |    @CompileDynamic
	        |    static Map<String,Object> run(Map<String,Object> inputs) {
	        |        $inputDecls
	        |        $outputDecls
	        |
	        |        $fixedBody
	        |
	        |        return [$outputReturn]
	        |    }
	        |}
	        """.trimMargin()
	}


	private fun fixForAndroid(body: String, inputs: List<String>, outputs: List<String>): String {
		var fixed = body
		val ignoreVars = (inputs + outputs).toSet()
		fixed = Regex("""(\w+)\.(\w+)\s*=\s*([^\n]+)""")
			.replace(fixed) { m ->
				val obj = m.groupValues[1]
				val prop = m.groupValues[2]
				val value = m.groupValues[3].trim()
				if (obj in ignoreVars) {
					return@replace m.value // игнорируем inputs/outputs
				}
				val setter = "set" + prop.replaceFirstChar { it.uppercaseChar() }
				"$obj.$setter($value)"
			}
		val getterProps = listOf("outputStream", "inputStream", "errorStream", "responseCode", "headerFields")
		getterProps.forEach { prop ->
			fixed = Regex("""(\w+)\.$prop\b""").replace(fixed) { m ->
				val obj = m.groupValues[1]
				if (obj in ignoreVars) {
					m.value // для input/output ничего не меняем
				} else {
					"$obj.get${prop.replaceFirstChar { it.uppercaseChar() }}()"
				}
			}
		}
		fixed = Regex("""(\w+)\.(key|value)\b""")
			.replace(fixed) { m ->
				val obj = m.groupValues[1]
				val prop = m.groupValues[2]
				if (obj in ignoreVars) {
					m.value // оставляем как есть
				} else {
					"$obj.get${prop.replaceFirstChar { it.uppercaseChar() }}()"
				}
			}
		return fixed
	}

}