package ru.ravel.lcpedesktop.android

import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object DexCompiler {

	fun jarToDexJar(
		inputJar: File,
		outputDexJar: File,
		groovyLibsDir: File = File("libs/groovy"),
		minApi: Int = 26,
	) {
		val androidHome = System.getenv("ANDROID_HOME")!!
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
			val addedEntries = mutableSetOf<String>()

			fun addJar(inputJar: File, jos: JarOutputStream) {
				JarFile(inputJar).use { jar ->
					val entries = jar.entries()
					while (entries.hasMoreElements()) {
						val entry = entries.nextElement()
						val name = entry.name
						if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
							continue
						}
						val skip = name.matches(Regex("""META-INF/.*\.(SF|RSA|DSA)""")) ||
								name.startsWith("META-INF/maven/")
						if (skip) {
							continue
						}
						if (addedEntries.contains(name)) {
							continue
						}
						addedEntries.add(name)
						val newEntry = JarEntry(name)
						jos.putNextEntry(newEntry)
						jar.getInputStream(entry).use { it.copyTo(jos) }
						jos.closeEntry()
					}
				}
			}
			require(groovyLibsDir.exists()) { "Нет папки с groovy runtime: $groovyLibsDir" }
			groovyLibsDir.listFiles { f -> f.extension == "jar" }?.forEach { jar ->
				println("Добавляю в fat-jar: ${jar.name}")
				addJar(jar, jos)
			}
			addJar(inputJar, jos)
			for ((name, lines) in services) {
				jos.putNextEntry(JarEntry(name))
				jos.write(lines.joinToString("\n").toByteArray())
				jos.closeEntry()
			}
			jos.putNextEntry(JarEntry("META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory"))
			val plugin = "org.codehaus.groovy.vmplugin.v8.Java8\n"
			jos.write(plugin.toByteArray(Charsets.UTF_8))
			jos.closeEntry()
		}

		// --- d8 ---
		val outDexDir = createTempDir(prefix = "d8-out-")
		val classesDex = File(outDexDir, "classes.dex")
		if (classesDex.exists()) {
			classesDex.delete()
		}
		// --- команда d8: опции -> затем Program JAR ---
		val args = mutableListOf(
			d8.absolutePath,
			"--min-api", minApi.toString(),
			"--release",
			"--output", outDexDir.absolutePath,
			"--lib", androidJar.absolutePath,
			combinedJar.absolutePath,
//			inputJar.absolutePath
		)

		val pb = ProcessBuilder(args).redirectErrorStream(true)
		val proc = pb.start()
		val log = proc.inputStream.bufferedReader().readText()
		val exit = proc.waitFor()
		if (exit != 0) {
			error("d8=$exit\n$log")
		}
		require(classesDex.exists() && classesDex.length() > 5_000) {
			"classes.dex подозрительно маленький (${classesDex.length()} байт)\n$log"
		}

		outputDexJar.parentFile?.mkdirs()
		ZipOutputStream(outputDexJar.outputStream()).use { zos ->
			val written = mutableSetOf<String>()
			fun put(name: String, bytes: ByteArray) {
				if (!written.add(name)) return
				zos.putNextEntry(ZipEntry(name))
				zos.write(bytes); zos.closeEntry()
			}
			put("classes.dex", classesDex.readBytes())
			fun copyMeta(fromJar: File) {
				if (!fromJar.exists()) return
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
					val e = jf.entries()
					while (e.hasMoreElements()) {
						val en = e.nextElement(); val n = en.name
						val sig = n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA")
						val keep = keepPrefixes.any { n.startsWith(it) } || n in keepFiles
						if (en.isDirectory || n == "classes.dex" || sig || !keep) continue
						jf.getInputStream(en).use { put(n, it.readBytes()) }
					}
				}
			}
			copyMeta(combinedJar)
			put(
				"META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory",
				"org.codehaus.groovy.vmplugin.v8.Java8\n".toByteArray()
			)
		}


		println("DEX JAR создан: ${outputDexJar.absolutePath} (size=${outputDexJar.length()} байт)")
	}


	fun mergeAllBlockJars(outputJar: File, blocksDir: File) {
		if (outputJar.exists()) {
			outputJar.delete()
		}
		JarOutputStream(FileOutputStream(outputJar)).use { jos ->
			blocksDir.listFiles { f -> f.extension == "jar" }?.forEach { jar ->
				if (jar.name == outputJar.name) {
					return@forEach
				}
				JarFile(jar).use { jf ->
					for (entry in jf.entries()) {
						if (entry.isDirectory) {
							continue
						}
						if (entry.name.startsWith("META-INF/")) {
							continue
						}
						val bytes = jf.getInputStream(entry).readBytes()
						jos.putNextEntry(JarEntry(entry.name))
						jos.write(bytes)
						jos.closeEntry()
					}
				}
			}
		}
	}

}