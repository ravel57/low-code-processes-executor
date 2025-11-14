package ru.ravel.lcpedesktop.android

import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilerConfiguration
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.PostProcessingNode
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

object GroovyJarCompiler {

	/**
	 * Компилирует groovy-скрипт в JAR.
	 */
	fun compileToJar(
		script: String,
		block: CoreBlock,
		outputDir: File,
		compileFormCode: String? = null,
		blockNode: PostProcessingNode? = null,
		isNeedReturn: Boolean = false,
	): File {
		// 1. FQCN и имя класса
		val fqcn = if (compileFormCode == null) {
			block.groovyClassName?.takeIf { it.isNotBlank() }
				?: "ru.ravel.scripts.GroovyBlock_${block.id.toString().replace("-", "")}"
					.also { block.groovyClassName = it }
		} else {
			when (compileFormCode) {
				"mainProcessing" -> blockNode?.mainProcessingClassName
				"submitData" -> blockNode?.submitDataClassName
				else -> null
			} ?: throw IllegalArgumentException("compileFormCode must be specified correctly")
		}

		val className = if (compileFormCode == null) {
			"GroovyBlock_${block.id.toString().replace("-", "")}"
		} else {
			when (compileFormCode) {
				"mainProcessing" -> "GroovyBlock_${blockNode?.mainProcessingUuid?.replace("-", "")}"
				"submitData" -> "GroovyBlock_${blockNode?.submitDataUuid?.replace("-", "")}"
				else -> throw IllegalArgumentException("compileFormCode must be specified correctly")
			}
		}
		val groovySource = if (compileFormCode == null) {
			wrapGroovySource(
				className,
				script,
				block.inputNames,
				block.outputNames
			)
		} else {
			wrapGroovySourceForFormSubmitProcessing(
				className,
				script,
				block.inputNames,
				block.outputNames,
				isNeedReturn
			)
		}
		val simpleName = fqcn.substringAfterLast('.')
		// 2. Абсолютный каталог вывода + гарантированное создание
		val outDirAbs = outputDir.absoluteFile
		if (!outDirAbs.exists()) {
			val ok = outDirAbs.mkdirs()
			if (!ok && !outDirAbs.exists()) {
				throw IllegalStateException("Не удалось создать каталог сборки: ${outDirAbs.absolutePath}")
			}
		}
		val jarFile = File(outDirAbs, "$simpleName.jar").absoluteFile
		val jarParent = jarFile.parentFile
			?: throw IllegalStateException("У файла JAR нет родительского каталога: ${jarFile.path}")
		if (!jarParent.exists()) {
			val ok = jarParent.mkdirs()
			if (!ok && !jarParent.exists()) {
				throw IllegalStateException("Не удалось создать каталог для JAR: ${jarParent.absolutePath}")
			}
		}
		println(
			"== GroovyJarCompiler ==\n" +
					"  outDirAbs    = ${outDirAbs.absolutePath}\n" +
					"  jarFile      = ${jarFile.absolutePath}\n" +
					"  jarParent    = ${jarParent.absolutePath}\n" +
					"  existsDir    = ${jarParent.exists()}\n" +
					"  canWriteDir  = ${jarParent.canWrite()}\n" +
					"  isDir        = ${jarParent.isDirectory}"
		)
		// 3. Компиляция groovy в .class
		val config = CompilerConfiguration().apply {
			// делаем targetDirectory тоже абсолютным, чтобы исключить сюрпризы с CWD
			targetDirectory = File(outDirAbs, "groovy-classes").absoluteFile
			if (!targetDirectory.exists()) {
				targetDirectory.mkdirs()
			}
			targetBytecode = "8"
			optimizationOptions["indy"] = false
		}
		val gcl = GroovyClassLoader(this::class.java.classLoader, config)
		val clazz = gcl.parseClass(groovySource, "$className.groovy")

		val classFile = File(config.targetDirectory, "${clazz.name.replace('.', '/')}.class")
		if (!classFile.exists()) {
			throw IllegalStateException("Не найден .class: ${classFile.absolutePath}")
		}
		// 4. Сборка JAR — через NIO, с дополнительной диагностикой
		try {
			// на всякий случай: если вдруг уже что-то есть и это директория
			if (jarFile.exists() && jarFile.isDirectory) {
				throw IllegalStateException("По пути JAR находится каталог, а не файл: ${jarFile.absolutePath}")
			}
			// создаём/очищаем файл через NIO
			val path = jarFile.toPath()
			val fos = java.nio.file.Files.newOutputStream(
				path,
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
				java.nio.file.StandardOpenOption.WRITE
			)
			JarOutputStream(fos).use { jar ->
				val added = mutableSetOf<String>()
				config.targetDirectory
					.walkTopDown()
					.filter { it.isFile && it.extension == "class" }
					.forEach { file ->
						val relPath = file
							.relativeTo(config.targetDirectory)
							.invariantSeparatorsPath
						if (relPath.startsWith("ru/ravel/scripts/$className")) {
							if (added.add(relPath)) {
								val entry = JarEntry(relPath)
								jar.putNextEntry(entry)
								jar.write(file.readBytes())
								jar.closeEntry()
							}
						}
					}
			}
		} catch (e: Exception) {
			System.err.println(
				"Ошибка при записи JAR:\n" +
						"  jarFile      = ${jarFile.absolutePath}\n" +
						"  jarExists    = ${jarFile.exists()}\n" +
						"  jarIsDir     = ${jarFile.isDirectory}\n" +
						"  parentExists = ${jarParent.exists()}\n" +
						"  parentIsDir  = ${jarParent.isDirectory}\n" +
						"  parentCanWrite = ${jarParent.canWrite()}"
			)
			throw e
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


	private fun wrapGroovySourceForFormSubmitProcessing(
		className: String,
		script: String,
		inputs: List<String>,
		outputs: List<String>,
		isNeedReturn: Boolean,
	): String {
		val lines = script.lines()
		val imports = lines.filter { it.trim().startsWith("import ") }
			.joinToString("\n")
		val inputDecls = inputs.joinToString("\n        ") { nm -> "def $nm = inputs[\"$nm\"]" }
		val outputDecls = outputs.joinToString("\n        ") { nm -> "def $nm = [:]" }
		val body = lines.filterNot { it.trim().startsWith("import ") }
			.joinToString("\n")
			.ifBlank { "[:]" }
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
	        |        ${if (isNeedReturn) "return" else ""} $fixedBody
			|        ${if (!isNeedReturn) "return [:]" else ""}
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