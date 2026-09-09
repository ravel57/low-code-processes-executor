package ru.ravel.lcpedesktop.android

import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DexCompiler {
	fun jarToDexJar(
		inputJar: File,
		outputDexJar: File,
		groovyLibsDir: File = File("libs/groovy"),
		minApi: Int = 26,
	) {
		val androidHome = System.getenv("ANDROID_HOME")
			?: throw IllegalStateException("ANDROID_HOME не задан в окружении")

		// 1) Собираем combined JAR и одновременно собираем провайдеры из META-INF/services/**
		val combinedJar = kotlin.io.path.createTempFile(prefix = "combined-", suffix = ".jar").toFile()
		val services = LinkedHashMap<String, MutableSet<String>>() // name -> providers

		JarOutputStream(FileOutputStream(combinedJar)).use { jos ->
			val addedEntries = HashSet<String>()

			fun mergeService(name: String, payload: ByteArray) {
				val lines = payload.toString(Charsets.UTF_8)
					.lineSequence()
					.map { it.trim() }
					.filter { it.isNotEmpty() && !it.startsWith("#") }
					.toList()
				if (lines.isEmpty()) return
				services.getOrPut(name) { linkedSetOf() }.addAll(lines)
			}

			fun addJar(jarFile: File) {
				JarFile(jarFile).use { jf ->
					val it = jf.entries()
					while (it.hasMoreElements()) {
						val e = it.nextElement()
						val n = e.name
						// пропускаем служебные файлы и подписи
						if (n.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
						if (n.matches(Regex("""META-INF/.*\.(SF|RSA|DSA)"""))) continue
						if (n.startsWith("META-INF/maven/")) continue
						if (n == "classes.dex") continue

						val SVC = "META-INF/services/"
						val GROOVY_SVC = "META-INF/groovy/services/"
						if (n.startsWith(SVC) || n.startsWith(GROOVY_SVC)) {
							val canonical = if (n.startsWith(GROOVY_SVC))
								SVC + n.removePrefix(GROOVY_SVC)
							else n
							jf.getInputStream(e).use { mergeService(canonical, it.readBytes()) }
							continue
						}

						// обычные файлы кладём один раз
						if (!addedEntries.add(n)) continue
						jos.putNextEntry(JarEntry(n))
						jf.getInputStream(e).use { it.copyTo(jos) }
						jos.closeEntry()
					}
				}
			}

			require(groovyLibsDir.exists()) { "Нет папки с groovy runtime: $groovyLibsDir" }
			groovyLibsDir.listFiles { f -> f.extension == "jar" }
				?.sortedBy { it.name }
				?.forEach { jar -> addJar(jar) }

			addJar(inputJar)
		}

		// 2) D8 → classes.dex
		val buildToolsDir = File(androidHome, "build-tools")
		val d8 = buildToolsDir.listFiles()
			?.sortedByDescending { it.name }
			?.map { listOf(File(it, "d8.bat"), File(it, "d8")) }
			?.flatten()
			?.firstOrNull { it.exists() }
			?: throw IllegalStateException("Не найден d8 в $buildToolsDir")
		// --- android.jar (для --lib) ---
		val androidJar = listOf(36, 35, 34, 33, 32, 31, 30, 29, 28, 27, 26)
			.asSequence()
			.map { File(androidHome, "platforms/android-$it/android.jar") }
			.firstOrNull { it.exists() }
			?: throw IllegalStateException("Не найден android.jar в $androidHome/platforms")

		val outDexDir = kotlin.io.path.createTempDirectory(prefix = "d8-out-").toFile()
		val args = mutableListOf(
			d8.absolutePath,
			"--min-api", minApi.toString(),
			"--release",
			"--output", outDexDir.absolutePath,
			"--lib", androidJar.absolutePath,
			combinedJar.absolutePath
		)
		val proc = ProcessBuilder(args).redirectErrorStream(true).start()
		val log = proc.inputStream.bufferedReader().readText()
		val exit = proc.waitFor()
		if (exit != 0) {
			error("d8=$exit\n$log")
		}

		val classesDex = File(outDexDir, "classes.dex")
		require(classesDex.exists() && classesDex.length() > 5_000) {
			"classes.dex подозрительно маленький (${classesDex.length()} байт)\n$log"
		}

		outputDexJar.parentFile?.mkdirs()
		ZipOutputStream(FileOutputStream(outputDexJar)).use { zos ->
			fun put(name: String, bytes: ByteArray) {
				zos.putNextEntry(ZipEntry(name))
				zos.write(bytes)
				zos.closeEntry()
			}

			// classes.dex
			put("classes.dex", classesDex.readBytes())

			// полезные groovy-метаданные (dgm, groovy/*, ExtensionModule и т.п.)
			copySelectedMeta(combinedJar, zos)

			// services: пишем слитые провайдеры
			writeServicesMerged(zos, services)
		}

		println("DEX JAR создан: ${outputDexJar.absolutePath} (size=${outputDexJar.length()} байт)")

		// очистка
		combinedJar.delete()
		outDexDir.deleteRecursively()
	}

	/** Копируем только мета, полезную для Groovy-рантайма. */
	private fun copySelectedMeta(fromJar: File, zos: ZipOutputStream) {
		JarFile(fromJar).use { jf ->
			val keepPrefixes = listOf("META-INF/dgm/", "META-INF/groovy/")
			val keepFiles = setOf(
				"META-INF/dgminfo",
				"META-INF/services/org.codehaus.groovy.runtime.ExtensionModule",
				"META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory",
				"META-INF/services/org.codehaus.groovy.vmplugin.VMPlugin",
				"META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule",
				"META-INF/groovy/org.codehaus.groovy.source.Extensions",
				"META-INF/groovy-release-info.properties"
			)
			val it = jf.entries()
			while (it.hasMoreElements()) {
				val e = it.nextElement()
				val n = e.name
				val sig = n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA")
				val keep = keepPrefixes.any { p -> n.startsWith(p) } || n in keepFiles
				if (e.isDirectory || n == "classes.dex" || sig || !keep) continue
				jf.getInputStream(e).use {
					zos.putNextEntry(ZipEntry(n))
					it.copyTo(zos)
					zos.closeEntry()
				}
			}
		}
		// гарантируем VMPluginFactory (если вдруг в зависимостях его нет)
		zos.putNextEntry(ZipEntry("META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory"))
		zos.write("org.codehaus.groovy.vmplugin.v8.Java8\n".toByteArray(Charsets.UTF_8))
		zos.closeEntry()
	}

	/**
	 * Пишем services-файлы (объединённые провайдеры).
	 * Дополнительно — если есть только один из FastStringService (codehaus/apache), дублируем для совместимости.
	 */
	private fun writeServicesMerged(zos: ZipOutputStream, services: Map<String, Set<String>>) {
		val written = HashSet<String>()

		fun writeOne(name: String, providers: Set<String>) {
			if (providers.isEmpty() || !written.add(name)) return
			zos.putNextEntry(ZipEntry(name))
			val payload = providers.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
			zos.write(payload)
			zos.closeEntry()
		}

		// 1) основная запись — всё, что собрали
		for ((name, prov) in services) writeOne(name, prov)

		// 2) зеркалируем FastStringService при необходимости
		val ch = services["META-INF/services/org.codehaus.groovy.util.FastStringService"]
		val ap = services["META-INF/services/org.apache.groovy.util.FastStringService"]
		fun putService(name: String, prov: Set<String>) {
			zos.putNextEntry(ZipEntry(name))
			zos.write(prov.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
			zos.closeEntry()
		}
		when {
			ch != null && ap == null -> putService("META-INF/services/org.apache.groovy.util.FastStringService", ch)
			ap != null && ch == null -> putService("META-INF/services/org.codehaus.groovy.util.FastStringService", ap)
		}
	}

	/**
	 * Обновлённый мердж скриптовых JAR’ов: не выкидываем META-INF/services,
	 * а аккуратно объединяем провайдеров.
	 */
	fun mergeAllBlockJars(outputJar: File, blocksDir: File) {
		if (outputJar.exists()) outputJar.delete()
		JarOutputStream(FileOutputStream(outputJar)).use { jos ->
			val added = HashSet<String>()
			val services = LinkedHashMap<String, MutableSet<String>>()

			fun mergeService(name: String, payload: ByteArray) {
				val lines = payload.toString(Charsets.UTF_8)
					.lineSequence()
					.map { it.trim() }
					.filter { it.isNotEmpty() && !it.startsWith("#") }
					.toList()
				if (lines.isEmpty()) return
				services.getOrPut(name) { linkedSetOf() }.addAll(lines)
			}

			blocksDir.listFiles { f -> f.extension == "jar" }?.forEach { jar ->
				if (jar.name == outputJar.name) return@forEach
				JarFile(jar).use { jf ->
					val it = jf.entries()
					while (it.hasMoreElements()) {
						val e = it.nextElement()
						if (e.isDirectory) continue
						val n = e.name
						if (n.equals("META-INF/MANIFEST.MF", true)) continue
						if (n.matches(Regex("""META-INF/.*\.(SF|RSA|DSA)"""))) continue
						if (n.startsWith("META-INF/maven/")) continue

						val SVC = "META-INF/services/"
						val GROOVY_SVC = "META-INF/groovy/services/"
						if (n.startsWith(SVC) || n.startsWith(GROOVY_SVC)) {
							val canonical = if (n.startsWith(GROOVY_SVC)) {
								SVC + n.removePrefix(GROOVY_SVC)
							} else {
								n
							}
							jf.getInputStream(e).use { mergeService(canonical, it.readBytes()) }
							continue
						}

						if (!added.add(n)) continue
						jos.putNextEntry(JarEntry(n))
						jf.getInputStream(e).use { it.copyTo(jos) }
						jos.closeEntry()
					}
				}
			}

			// Записываем объединённые service-файлы в конец
			for ((name, prov) in services) {
				jos.putNextEntry(JarEntry(name))
				jos.write(prov.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
				jos.closeEntry()
			}
		}
	}
}
