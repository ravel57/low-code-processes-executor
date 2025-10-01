package ru.ravel.lcpeandrotd

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object DexCompiler {
	fun jarToApk(inputJar: File, outputApk: File) {
		val androidHome = "C:\\Users\\petr\\AppData\\Local\\Android\\Sdk"

		val buildToolsDir = File(androidHome, "build-tools/34.0.0")
		val d8 = listOf("d8.bat", "d8").map { File(buildToolsDir, it) }.firstOrNull { it.exists() }
			?: throw IllegalStateException("Не найден d8 в $buildToolsDir")

		if (!d8.exists()) {
			throw IllegalStateException("Не найден d8 по пути: ${d8.absolutePath}")
		}

		val outDexDir = File("build/tmp/dex").apply { mkdirs() }

		// Шаг 1: прогоняем jar через d8
		val process = ProcessBuilder(
			d8.absolutePath,
			"--release",
			"--output", outDexDir.absolutePath,
			inputJar.absolutePath
		)
			.redirectErrorStream(true)
			.start()

		val log = process.inputStream.bufferedReader().readText()
		val code = process.waitFor()
		if (code != 0) {
			throw RuntimeException("d8 завершился с ошибкой $code\n$log")
		}

		// Шаг 2: упаковываем classes.dex в apk (zip)
		val classesDex = File(outDexDir, "classes.dex")
		if (!classesDex.exists()) {
			throw IllegalStateException("Не найден classes.dex")
		}

		zipFiles(listOf(classesDex), outputApk)

		println("APK создан: ${outputApk.absolutePath}")
	}

	fun jarToDex(jarFile: File, dexOut: File) {
		val androidHome = "C:\\Users\\petr\\AppData\\Local\\Android\\Sdk"
		if (!jarFile.exists()) error("Jar file not found: ${jarFile.absolutePath}")
		dexOut.parentFile.mkdirs()

		val buildToolsDir = File(androidHome, "build-tools/34.0.0")
		val d8 = listOf("d8.bat", "d8").map { File(buildToolsDir, it) }.firstOrNull { it.exists() }?.absolutePath
			?: throw IllegalStateException("Не найден d8 в $buildToolsDir")

		val process = ProcessBuilder(
			d8,
			"--release",
			"--output", dexOut.parentFile.absolutePath,  // указываем директорию
			jarFile.absolutePath
		)
			.redirectErrorStream(true)
			.start()

		val result = process.inputStream.bufferedReader().readText()
		val exitCode = process.waitFor()
		if (exitCode != 0) {
			error("d8 failed ($exitCode): $result")
		}

		println("DEX создан: ${File(dexOut.parentFile, "classes.dex").absolutePath}")
	}

	fun jarToDexJar(inputJar: File,outputDexJar: File,runtimeJar: File = File("libs/groovy/groovy-4.0.28.jar"), minApi: Int = 26) {
		require(inputJar.exists()) { "Нет ${inputJar.absolutePath}" }
		require(runtimeJar.exists()) { "Нет ${runtimeJar.absolutePath}" }

		val androidHome = System.getenv("ANDROID_HOME")
			?: "C:\\Users\\petr\\AppData\\Local\\Android\\Sdk"

		// --- d8 ---
		val buildToolsDir = File(androidHome, "build-tools")
		val d8 = buildToolsDir.listFiles()
			?.sortedByDescending { it.name }
			?.map { listOf(File(it, "d8.bat"), File(it, "d8")) }
			?.flatten()
			?.firstOrNull { it.exists() }
			?: throw IllegalStateException("Не найден d8 в $buildToolsDir")

		// --- android.jar (для --lib) ---
		val androidJar = listOf(34, 33, 32, 31, 30, 29, 28, 27, 26)
			.map { File(androidHome, "platforms/android-$it/android.jar") }
			.firstOrNull { it.exists() }
			?: throw IllegalStateException("Не найден android.jar в $androidHome\\platforms")

		// --- собираем fat-jar ---
		val combinedJar = createTempFile(prefix = "combined-", suffix = ".jar")
		JarOutputStream(FileOutputStream(combinedJar)).use { jos ->
			val services = mutableMapOf<String, MutableList<String>>()

			fun addJar(jar: File) {
				JarFile(jar).use { jf ->
					for (entry in jf.entries()) {
						if (entry.isDirectory) continue
						if (entry.name.startsWith("META-INF/services/")) continue // сервисы перезапишем сами
						val bytes = jf.getInputStream(entry).readBytes()
						jos.putNextEntry(JarEntry(entry.name))
						jos.write(bytes)
						jos.closeEntry()
					}
				}
			}

			addJar(runtimeJar)
			addJar(inputJar)

			// записываем объединённые services
			for ((name, lines) in services) {
				jos.putNextEntry(JarEntry(name))
				jos.write(lines.joinToString("\n").toByteArray())
				jos.closeEntry()
			}

			// Хардкодим META-INF/services/VMPluginFactory
			jos.putNextEntry(JarEntry("META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory"))
			// для Android Java 8/9 плагин
			val plugin = "org.codehaus.groovy.vmplugin.v9.Java9\n"
			jos.write(plugin.toByteArray(Charsets.UTF_8))
			jos.closeEntry()
		}

		// --- d8 ---
		val outDexDir = createTempDir(prefix = "d8-out-")
		val classesDex = File(outDexDir, "classes.dex")
		if (classesDex.exists()) classesDex.delete()

		// --- команда d8: опции -> затем Program JAR ---
		val args = mutableListOf(
			d8.absolutePath,
			"--min-api", minApi.toString(),
			"--release",
			"--output", outDexDir.absolutePath,
			"--lib", androidJar.absolutePath,
			// runtimeJar — только как classpath для d8, чтобы были ссылки на Groovy классы
			runtimeJar.absolutePath,
			inputJar.absolutePath
		)

		val pb = ProcessBuilder(args).redirectErrorStream(true)
		val proc = pb.start()
		val log = proc.inputStream.bufferedReader().readText()
		val exit = proc.waitFor()
		if (exit != 0) error("d8=$exit\n$log")
		require(classesDex.exists() && classesDex.length() > 5_000) {
			"classes.dex подозрительно маленький (${classesDex.length()} байт)\n$log"
		}

		// --- Собираем выходной JAR: classes.dex + нужные META-INF из runtimeJar ---
		val requiredEntries = setOf(
			"META-INF/dgminfo",
			"META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule",
			"META-INF/groovy/org.codehaus.groovy.source.Extensions",
			"META-INF/services/org.codehaus.groovy.vmplugin.VMPlugin",
			"META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory",
			"META-INF/services/org.apache.groovy.json.FastStringServiceFactory"
		)

		outputDexJar.parentFile?.mkdirs()
		java.util.zip.ZipOutputStream(outputDexJar.outputStream()).use { zos ->
			// 1) classes.dex
			zos.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
			classesDex.inputStream().use { it.copyTo(zos) }
			zos.closeEntry()

			// ресурсы из groovy-runtime-res.jar
			val resourcesJar = File("scripts/groovy-runtime-res.jar")
			if (resourcesJar.exists()) {
				ZipFile(resourcesJar).use { zf ->
					for (entry in zf.entries()) {
						if (entry.isDirectory || entry.name == "classes.dex") continue
						zos.putNextEntry(ZipEntry(entry.name))
						zf.getInputStream(entry).use { it.copyTo(zos) }
						zos.closeEntry()
					}
				}
			}

			// хардкод VMPluginFactory, если нет
			val servicePath = "META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory"
			zos.putNextEntry(ZipEntry(servicePath))
			zos.write("org.codehaus.groovy.vmplugin.v8.Java8\n".toByteArray())
			zos.closeEntry()
		}

		println("DEX JAR создан: ${outputDexJar.absolutePath} (size=${outputDexJar.length()} байт)")
	}

	/**
	 * Собирает единый groovy-runtime.jar из groovy-зависимостей
	 */
	private fun buildGroovyRuntimeJar(groovyJars: List<File>, runtimeJar: File) {
		val tmpDir = File("build/tmp/groovy-merge").apply {
			deleteRecursively()
			mkdirs()
		}

		// Распаковываем groovy-*.jar
		for (jar in groovyJars) {
			ProcessBuilder("jar", "xf", jar.absolutePath)
				.directory(tmpDir)
				.inheritIO()
				.start()
				.waitFor()
		}

		// Собираем единый runtime.jar
		ProcessBuilder("jar", "cf", runtimeJar.absolutePath, ".")
			.directory(tmpDir)
			.inheritIO()
			.start()
			.waitFor()

		println("Groovy runtime jar создан: ${runtimeJar.absolutePath}")
	}

	fun buildGroovyRuntimeDexJar(groovyJars: List<File>, outputDexJar: File) {
		val tmpDir = File("build/tmp/groovy-merge").apply {
			deleteRecursively()
			mkdirs()
		}

		// 1) Распаковываем каждый groovy-*.jar в tmpDir
		for (jar in groovyJars) {
			ProcessBuilder("jar", "xf", jar.absolutePath)
				.directory(tmpDir)
				.inheritIO()
				.start()
				.waitFor()
		}

		// 2) Собираем единый groovy-runtime.jar
		val runtimeJar = File("build/libs/groovy-runtime.jar").apply { parentFile.mkdirs() }
		ProcessBuilder("jar", "cf", runtimeJar.absolutePath, ".")
			.directory(tmpDir)
			.inheritIO()
			.start()
			.waitFor()

		// 3) Конвертим в DEX-JAR
		jarToDexJar(runtimeJar, outputDexJar)

		println("Groovy runtime dex jar создан: ${outputDexJar.absolutePath}")
	}

	private fun zipFiles(inputFiles: List<File>, outputZip: File) {
		ZipOutputStream(FileOutputStream(outputZip)).use { zipOut ->
			inputFiles.forEach { file ->
				val entry = ZipEntry(file.name)
				zipOut.putNextEntry(entry)
				file.inputStream().use { it.copyTo(zipOut) }
				zipOut.closeEntry()
			}
		}
	}

	fun packDexToJar(dexFile: File, jarFile: File) {
		ZipOutputStream(FileOutputStream(jarFile)).use { zos ->
			FileInputStream(dexFile).use { fis ->
				val entry = ZipEntry("classes.dex")
				zos.putNextEntry(entry)
				fis.copyTo(zos)
				zos.closeEntry()
			}
		}
	}
}