package ru.ravel.lcpedesktop

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import groovy.lang.Binding
import groovy.lang.GroovyShell
import javafx.application.Application
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyObject
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import ru.ravel.lcpecore.io.OutputsRepository
import ru.ravel.lcpecore.io.ProjectRepository
import ru.ravel.lcpecore.model.*
import ru.ravel.lcpecore.runtime.CoreSubProjectRunner
import ru.ravel.lcpecore.runtime.GroovyExecutor
import ru.ravel.lcpecore.runtime.JsExecutor
import ru.ravel.lcpecore.runtime.PythonExecutor
import ru.ravel.lcpedesktop.android.DexCompiler
import ru.ravel.lcpedesktop.android.GroovyJarCompiler
import ru.ravel.lcpedesktop.model.BlockLoc
import ru.ravel.lcpedesktop.model.Command
import ru.ravel.lcpedesktop.model.DeleteBlockCommand
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors
import kotlin.system.exitProcess

class MainApp : Application() {

	private lateinit var groovy: GroovyExecutor
	private lateinit var python: PythonExecutor
	private lateinit var js: JsExecutor

	private val undoStack: Deque<Command> = ArrayDeque()
	private val redoStack: Deque<Command> = ArrayDeque()


	private lateinit var stage: Stage
	private val windowW = 800.0
	private val windowH = 600.0

	// Рабочая область
	private val gridCanvas = Canvas(windowW, windowH)
	private val workspaceGroup = Group()
	val contentPane = Pane().apply {
		children.setAll(gridCanvas, workspaceGroup)
		prefWidth = windowW * 2
		prefHeight = windowH * 2
		padding = Insets(10.0)
		isFocusTraversable = true
	}
	private val scrollPane = ScrollPane(contentPane).apply {
		isPannable = false
		hbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
	}

	// Состояние проекта в UI
	var currentProjectFile: File? = null
	val blocks = mutableListOf<BlockNode>()
	private val connections = mutableListOf<Connection>()
	private var selectedBlock: BlockNode? = null
	private var selectedConnection: Connection? = null
	private var activeContextMenu: ContextMenu? = null
	private var isDirty = false
	private var suppressDirty = false

	// Временные поля для протягивания соединений
	private var draggingLine: Line? = null
	private var draggingFromBlock: BlockNode? = null
	private var draggingFromOutputId: UUID? = null

//	@Volatile
//	private var isRunning = false

	private val projectRepo: ProjectRepository by lazy {
		JsonProjectRepository()
	}

	private val outputsRepo: OutputsRepository by lazy {
		JsonOutputsRepository()
	}

	private val runController by lazy {
		val subProjectRunner = CoreSubProjectRunner(projectRepo, groovy, python, js)
		DesktopRunController(
			groovy = groovy,
			python = python,
			js = js,
			subProjectRunner = subProjectRunner,
			outputsRepo = outputsRepo,
			executor = executor
		)
	}

	// Пул потоков
	private val executor = Executors.newFixedThreadPool(
		Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
	)


	private fun runCommand(cmd: Command) {
		cmd.execute()
		undoStack.push(cmd)
		redoStack.clear()
		markDirty()
	}

	private val callbacks = BlockNodeCallbacks(
		onSelect = { bn -> selectBlock(bn) },
		onReleased = { bn ->
			bn.core.uiX = bn.layoutX
			bn.core.uiY = bn.layoutY
			markDirty()
		},
		onDeleteRequested = { bn -> deleteBlockRequest(bn) },
		onInvalidConnections = { invalid ->
			invalid.forEach { c ->
				(c.line.parent as? Pane)?.children?.remove(c.line)
				connections.remove(c)
				c.from.connectedLines.remove(c)
				c.to.connectedLines.remove(c)
			}
			markDirty()
		},
		onShowOutput = { bn, outIdx -> showOutputFor(bn, outIdx) },

		onOpenSubProject = { rawFile ->
			val file = resolveProjectFile(rawFile.path)
			val stage = Stage()
			val project = projectRepo.loadProject(file)
			project.baseDir = file.parentFile
			normalizePathsAfterLoad(project)
			val subApp = MainApp()
			subApp.start(stage)
			Platform.runLater {
				subApp.restoreUiFromCore(project)
				scrollToRightmostBlock()
				subApp.outputsRepo.loadLast(file, project)
				subApp.applyOutputsToUi(project)
				subApp.currentProjectFile = file
				subApp.clearDirty()
				subApp.updateTitle()
			}
		},
	)


	override fun init() {
		groovy = object : GroovyExecutor {
			override fun exec(code: String, bindings: Map<String, Any?>, groovyClassName: String?): Any? {
				val binding = Binding(bindings.toMutableMap())
				val shell = GroovyShell(binding)
				val result = shell.evaluate(code)
				if (bindings is MutableMap<String, Any?>) {
					bindings.putAll(binding.variables as Map<out String, Any?>)
				}
				return result
			}
		}
		python = object : PythonExecutor {
			override fun exec(
				block: CoreBlock,
				bindings: Map<String, Any?>,
				outputs: MutableMap<String, MutableMap<String, Any?>>,
				packagesNames: MutableList<String>,
			): Map<String, Any?> {
				val tmp = "run/python/${block.id}_${block.hashCode()}"
				val venvDirPath = Paths.get("").toAbsolutePath().resolve(tmp).apply { Files.createDirectories(this) }
				val venvDir = venvDirPath.toAbsolutePath().toString()
				ProcessBuilder(PYTHON, "-m", "venv", venvDir)
					.redirectErrorStream(true)
					.start()
					.waitFor()
				val isWindows = System.getProperty("os.name").startsWith("Windows")
				val pipPath = if (isWindows) {
					"$venvDir/Scripts/pip.exe"
				} else {
					"$venvDir/bin/pip"
				}
				if (block.packagesNames.isNotEmpty()) {
					ProcessBuilder(pipPath, "install", *block.packagesNames.toTypedArray())
						.inheritIO()
						.start()
						.waitFor()
				}
				val pythonPath = if (isWindows) {
					"$venvDir/Scripts/python.exe"
				} else {
					"$venvDir/bin/python"
				}
				val paramsJson = ObjectMapper().writeValueAsString(bindings)
				val codeText = block.codePath?.let { File(it).readText() } ?: ""
				val initOutputs = buildString {
					block.outputNames.forEach { name ->
						append("$name = {}\n")
					}
				}
				val pyOutputs = block.outputNames.joinToString(", ") { "\"$it\": $it" }

				val fullScript = """
		            |import os, json
		            |params = json.loads(os.environ.get("$PYTHON_PARAMS_VARIABLE", "{}"))
		            |locals().update(params)
		            |
		            |$initOutputs
		            |
		            |$codeText
		            |
		            |print(json.dumps({$pyOutputs}))
		        """.trimMargin()
				val scriptPath = File(venvDir, "script.py").apply {
					writeText(fullScript, StandardCharsets.UTF_8)
				}
				val proc = ProcessBuilder(pythonPath, scriptPath.toString())
					.redirectErrorStream(true)
					.apply {
						environment()[PYTHON_PARAMS_VARIABLE] = paramsJson
						environment()["PYTHONIOENCODING"] = "utf-8"
					}
					.start()
				val output = proc.inputStream.bufferedReader().readText()
				proc.waitFor()
				File(venvDir).deleteRecursively()
				val lastLine = output.lines().lastOrNull {
					it.trim().startsWith("{") && it.trim().endsWith("}")
				}
				return if (lastLine != null) {
					val typeRef = object : TypeReference<Map<String, Any?>>() {}
					val parsed = ObjectMapper().readValue(lastLine, typeRef)
					parsed
				} else {
					if (output.contains("Traceback")) throw RuntimeException(output)
					emptyMap()
				}
			}
		}
		js = object : JsExecutor {
			override fun exec(
				code: String,
				inputs: Map<String, Any?>,
				outputNames: List<String>,
			): Map<String, MutableMap<String, Any?>> {
				val context = Context.newBuilder("js").allowAllAccess(true).build()

				fun Any?.toJsFriendly(): Any? = when (this) {
					null -> null
					is Map<*, *> -> this.mapValues { (_, v) -> v.toJsFriendly() }
					is List<*> -> context.asValue(this.map { it.toJsFriendly() }.toTypedArray())
					else -> this
				}

				fun Value.toKotlin(): Any? = when {
					isNull -> null
					isBoolean -> asBoolean()
					isNumber -> if (fitsInInt()) asInt() else if (fitsInLong()) asLong() else asDouble()
					isString -> asString()
					hasArrayElements() -> (0 until arraySize.toInt()).map { getArrayElement(it.toLong()).toKotlin() }
					hasMembers() -> memberKeys.associateWith { getMember(it).toKotlin() }.toMutableMap()
					else -> this
				}

				inputs.forEach { (k, v) -> context.getBindings("js").putMember(k, v.toJsFriendly()) }

				val outputs = mutableMapOf<String, MutableMap<String, Any?>>()
				for (name in outputNames) {
					require(!inputs.containsKey(name)) { "Имя «$name» уже занято входным параметром" }
					val m = mutableMapOf<String, Any?>()
					outputs[name] = m
					context.getBindings("js").putMember(name, ProxyObject.fromMap(m))
				}

				context.eval("js", code)

				return outputs.mapValues { (_, inner) ->
					inner.mapValues { (_, v) -> if (v is Value) v.toKotlin() else v }.toMutableMap()
				}
			}
		}
	}

	override fun start(primaryStage: Stage) {
		stage = primaryStage
		primaryStage.userData = this
		primaryStage.show()
		drawGrid(gridCanvas, 10.0)
		gridCanvas.widthProperty().bind(contentPane.widthProperty())
		gridCanvas.heightProperty().bind(contentPane.heightProperty())
		gridCanvas.widthProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }
		gridCanvas.heightProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }
		scrollPane.hvalueProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }
		scrollPane.vvalueProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }
		contentPane.widthProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }
		contentPane.heightProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }

		// Контекстное меню для создания блока
		contentPane.onMouseClicked = EventHandler { event ->
			if (event.button == MouseButton.SECONDARY && event.target === contentPane) {
				val n = event.pickResult.intersectedNode
				if (n is BlockNode || n is Circle) return@EventHandler
				event.consume()
				val ctx = ContextMenu()
				BlockType.entries.forEach { type ->
					val item = MenuItem(type.displayName)
					item.setOnAction {
						when (type) {
							BlockType.SUB_PROCESS -> {
								val fc = FileChooser().apply {
									title = "Выберите процесс"
									extensionFilters += FileChooser.ExtensionFilter("JSON", "*.json")
								}
								fc.showOpenDialog(primaryStage)?.let { file ->
									val node = addBlock(event.x, event.y, file.nameWithoutExtension, type)
									scrollToRightmostBlock()
									val base = currentProjectFile?.parentFile ?: File(".")
									node.core.subProjectPath = storeRel(base, file)
									adjustSubProjectNodeIO(node, file)
								}
							}

							else -> {
								addBlock(event.x, event.y, type.displayName, type)
							}
						}
					}
					ctx.items.add(item)
				}
				ctx.show(contentPane, event.screenX, event.screenY)
				activeContextMenu = ctx
			}
			if (event.button == MouseButton.PRIMARY && event.target === contentPane) {
				selectBlock(null)
				selectConnection(null)
			}
			contentPane.requestFocus()
		}

		val newBtn = Button("Новый процесс").apply {
			setOnAction {
				if (!confirmSaveIfDirty()) {
					return@setOnAction
				}
				blocks.clear()
				connections.clear()
				workspaceGroup.children.removeIf { it is BlockNode || it is Line }
				currentProjectFile = null
				clearDirty()
				updateTitle()
			}
		}
		val openBtn = Button("Открыть процесс").apply {
			setOnAction {
				if (!confirmSaveIfDirty()) return@setOnAction
				val fc = FileChooser().apply {
					title = "Открыть процесс"
					extensionFilters += FileChooser.ExtensionFilter("JSON", "*.json")
				}
				fc.showOpenDialog(stage)?.let { f ->
					val project = projectRepo.loadProject(f)
					project.baseDir = f.parentFile
					normalizePathsAfterLoad(project)
					restoreUiFromCore(project)
					outputsRepo.loadLast(f, project)
					applyOutputsToUi(project)
					currentProjectFile = f
					clearDirty()
					updateTitle()
				}
			}
		}
		val saveBtn = Button("Сохранить процесс").apply {
			setOnAction {
				val project = collectCoreProject()
				if (currentProjectFile != null) {
					projectRepo.saveProject(currentProjectFile!!, project)
				} else {
					val fc = FileChooser().apply {
						title = "Сохранить процесс"
						extensionFilters += FileChooser.ExtensionFilter("JSON Files", "*.json")
					}
					fc.showSaveDialog(primaryStage)?.let { f ->
						projectRepo.saveProject(f, project)
						currentProjectFile = f
					}
				}
				updateTitle()
				clearDirty()
			}
		}

		val runBtn = Button("Бег").apply {
			setOnAction {
				val project = collectCoreProject().apply {
					baseDir = currentProjectFile?.parentFile ?: File(".")
				}
				runController.runAsync(project, currentProjectFile, object : RunEvents {
					override fun onStart(block: CoreBlock) {
						val ui = blocks.find { it.core.id == block.id }
						Platform.runLater { ui?.executing = true }
					}

					override fun onFinish(block: CoreBlock) {
						val ui = blocks.find { it.core.id == block.id }
						Platform.runLater { ui?.executing = false }
						currentProjectFile?.let { saveOutputsData(it) }
					}

					override fun onError(block: CoreBlock, error: Throwable) {
						Platform.runLater {
							Alert(Alert.AlertType.ERROR).apply {
								title = block.name
								headerText = null
								contentText = error.localizedMessage
							}.showAndWait()
							currentProjectFile?.let { saveOutputsData(it) }
						}
					}

					override fun onCompleted(project: CoreProject) {
						Platform.runLater {
							applyOutputsToUi(project)
							currentProjectFile?.let { saveOutputsData(it) }
						}
					}
				})
			}
		}

		val buildAndroidBtn = Button("Собрать под Android").apply {
			setOnAction {
				val outputDir = File("android_build").apply {
					deleteRecursively()
					mkdirs()
				}
				val all = collectAllBlocksWithFile(currentProjectFile ?: return@setOnAction)
				val groovyBlocks = all.filter { it.block.type == BlockType.MAPPING_GROOVY }
				groovyBlocks.forEach { (_, b) ->
					if (b.groovyClassName.isNullOrBlank()) {
						b.groovyClassName = "ru.ravel.scripts.GroovyBlock_${b.id.toString().replace("-", "")}"
					}
					b.codeAbsolutePath
						?.takeIf { it.exists() }
						?.readText()
						?.let { code -> GroovyJarCompiler.compileToJar(script = code, block = b, outputDir = outputDir) }
				}
				all.groupBy { it.file }.forEach { (file, list) ->
					val proj = projectRepo.loadProject(file).apply { baseDir = file.parentFile }
					val byId = proj.blocks.associateBy { it.id }

					list.asSequence()
						.filter { it.block.type == BlockType.MAPPING_GROOVY }
						.mapNotNull { loc ->
							loc.block.groovyClassName?.takeIf { it.isNotBlank() }?.let { name -> loc.block.id to name }
						}
						.forEach { (id, name) ->
							byId[id]?.groovyClassName = name
						}

					projectRepo.saveProject(file, proj)
				}
				val mergedJar = File(outputDir, "all-blocks.jar")
				val dexFile = File(outputDir, "groovy-blocks-dex.jar")
				DexCompiler.mergeAllBlockJars(mergedJar, outputDir)
				DexCompiler.jarToDexJar(mergedJar, dexFile)
				Alert(Alert.AlertType.INFORMATION).apply {
					title = "Сборка завершена"
					headerText = "Файл dex создан"
					contentText = dexFile.absolutePath
				}.showAndWait()
			}
		}


		val saves = HBox(10.0, newBtn, openBtn, saveBtn).apply { padding = Insets(8.0) }
		val runBox = HBox(10.0, runBtn, buildAndroidBtn).apply { padding = Insets(8.0) }
		val root = VBox(saves, runBox, scrollPane)
		val scene = Scene(root, windowW, windowH)

		// Горячие клавиши
		scene.addEventHandler(KeyEvent.KEY_PRESSED) { e ->
			when {
				e.code == KeyCode.DELETE || e.code == KeyCode.BACK_SPACE -> {
					val selectedConnections = connections.filter { it.selected }.toList()
					if (selectedConnections.isNotEmpty()) {
						selectedConnections.forEach { removeConnection(it) }
						markDirty()
						e.consume()
						return@addEventHandler
					}
					val selectedBlocks = blocks.filter { it.selected }.toList()
					if (selectedBlocks.isNotEmpty()) {
						selectedBlocks.forEach { runCommand(DeleteBlockCommand(this, it)) }
						markDirty()
						e.consume()
						return@addEventHandler
					}
				}

				e.isControlDown && e.code == KeyCode.S -> {
					saveBtn.fire()
					e.consume()
				}

				e.isControlDown && e.code == KeyCode.Z && !e.isShiftDown -> {
					undoStack.poll()?.let {
						it.undo()
						redoStack.push(it)
						markDirty()
					}
					e.consume()
				}

				e.isControlDown && e.isShiftDown && e.code == KeyCode.Z -> {
					redoStack.poll()?.let {
						it.execute()
						undoStack.push(it)
						markDirty()
					}
					e.consume()
				}
			}
		}

		// Спрятать меню по клику
		val hideMenu: (MouseEvent) -> Unit = {
			activeContextMenu?.takeIf { it.isShowing }?.hide()
			activeContextMenu = null
		}
		scene.addEventFilter(MouseEvent.MOUSE_PRESSED, hideMenu)
		contentPane.addEventFilter(MouseEvent.MOUSE_PRESSED, hideMenu)

		primaryStage.scene = scene
		primaryStage.title = "Low code processes executor"
		primaryStage.setOnCloseRequest { ev -> if (!confirmSaveIfDirty()) ev.consume() }
		primaryStage.show()

		scene.setOnKeyPressed { e ->
			if (e.isControlDown && e.code == KeyCode.Z && !e.isShiftDown) {
				undoStack.poll()?.let {
					it.undo()
					redoStack.push(it)
					markDirty()
				}
				e.consume()
			}
			if (e.isControlDown && e.isShiftDown && e.code == KeyCode.Z) {
				redoStack.poll()?.let {
					it.execute()
					undoStack.push(it)
					markDirty()
				}
				e.consume()
			}
		}

		setupContextMenu()
		contentPane.requestFocus()
		updateTitle()

		if (inputData.isNotEmpty()) {
			var isNeedToRun = false
			var isNeedToQuit = false
			for ((index, s) in inputData.withIndex()) {
				when (s) {
					"-f" -> {
						val file = File(inputData[index + 1])
						importBlocksFromFile(file)
//						importOutputsData(file)
						currentProjectFile = file
						clearDirty()
						updateTitle()
					}

					"-d" -> {
						val file = File(inputData[index + 1])
						val objectMapper = ObjectMapper()
						val data = objectMapper.readValue(file, Map::class.java) as Map<String, Any>
						blocks.filter { it.core.type == BlockType.START }.forEach { startBlock ->
							if (data.containsKey(startBlock.core.name)) {
								val values = data[startBlock.core.name] as? Map<String, Any> ?: emptyMap()
								startBlock.core.outputsData = mutableListOf(values.toMutableMap())
							}
						}
					}

					"-r" -> {
						isNeedToRun = true
					}

					"-q" -> {
						isNeedToQuit = true
					}
				}
			}
			if (isNeedToRun) {
				val project = collectCoreProject().apply {
					baseDir = currentProjectFile?.parentFile ?: File(".")
				}
				val file = currentProjectFile
				runController.runAsync(project, file, object : RunEvents {
					override fun onStart(block: CoreBlock) {
						val ui = blocks.find { it.core.id == block.id }
						Platform.runLater { ui?.executing = true }
					}

					override fun onFinish(block: CoreBlock) {
						val ui = blocks.find { it.core.id == block.id }
						Platform.runLater {
							ui?.executing = false
							if (isNeedToQuit) {
								exitProcess(0)
							}
						}
					}

					override fun onError(block: CoreBlock, error: Throwable) {
						Platform.runLater {
							Alert(Alert.AlertType.ERROR).apply {
								title = block.name
								headerText = null
								contentText = error.localizedMessage
							}.showAndWait()
						}
					}

					override fun onCompleted(project: CoreProject) {
						Platform.runLater {
							applyOutputsToUi(project)
							if (isNeedToQuit) {
								exitProcess(0)
							}
						}
					}
				})
			}
		}
	}

	fun addBlock(x: Double, y: Double, name: String, blockType: BlockType): BlockNode {
		val core = CoreBlock(name = name, type = blockType).apply {
			when (blockType) {
				BlockType.START -> {
					inputCount = 0
					outputCount = 1
					inputNames.clear()
					outputNames = mutableListOf("out0")
				}

				BlockType.EXIT -> {
					inputCount = 1
					outputCount = 0
					inputNames = mutableListOf("in0")
					outputNames.clear()
				}

				BlockType.INPUT_DATA -> {
					inputCount = 0
					outputCount = 1
					inputNames.clear()
					outputNames = mutableListOf("out0")
				}

				BlockType.PROPERTIES -> {
					inputCount = 0
					outputNames = mutableListOf("prop0")
					outputCount = 1
					outputsData = mutableListOf(mutableMapOf("prop0" to ""))
				}

				else -> {
					inputCount = 1
					outputCount = 1
					inputNames = mutableListOf("in0")
					outputNames = mutableListOf("out0")
				}
			}
		}

		val block = BlockNode(
			core = core,
			x = x,
			y = y,
			loadCode = { b ->
				val base = currentProjectFile?.parentFile ?: File(".")
				b.codePath?.let { p -> resolveStored(base, p).takeIf(File::exists)?.readText() } ?: ""
			},
			saveCode = { b, text ->
				val base = currentProjectFile?.parentFile ?: File(".")
				val file = if (b.type == BlockType.INPUT_DATA) {
					val desiredExt = when (b.inputFormat) {
						InputFormatType.JSON -> "json"
						InputFormatType.XML -> "xml"
						InputFormatType.YAML -> "yaml"
						else -> "txt"
					}
					val current = b.codePath?.let { resolveStored(base, it) }
					val needNew = current == null || !current.name.endsWith(".$desiredExt", ignoreCase = true)
					if (needNew) codeFileFor(b) else current!!
				} else {
					b.codePath?.let { resolveStored(base, it) } ?: codeFileFor(b)
				}
				file.writeText(text)
				b.codePath = storeRel(base, file)
				markDirty()
			},
			callbacks = callbacks,
		)
		blocks.add(block)
		block.onMove = {
			ensureWorkspaceFits(block)
			ensureBlockVisible(block)
			markDirty()
		}
		workspaceGroup.children.add(block)
		setupHandlersForBlock(block)
		Platform.runLater { scrollToRightmostBlock() }
		markDirty()
		return block
	}


	private fun ensureBlockVisible(b: BlockNode) {
		val sp = scrollPane
		val content = contentPane

		// размеры
		val vw = sp.viewportBounds.width
		val vh = sp.viewportBounds.height
		val cw = content.boundsInLocal.width.coerceAtLeast(1.0)
		val ch = content.boundsInLocal.height.coerceAtLeast(1.0)

		// центрируем вид на блок (без прыжков за границы)
		val targetHX = ((b.layoutX + b.width / 2.0) - vw / 2.0).coerceIn(0.0, cw - vw)
		val targetVY = ((b.layoutY + b.height / 2.0) - vh / 2.0).coerceIn(0.0, ch - vh)

		sp.hvalue = if (cw <= vw) 0.0 else (targetHX / (cw - vw))
		sp.vvalue = if (ch <= vh) 0.0 else (targetVY / (ch - vh))
	}


	fun deleteBlockRequest(block: BlockNode) {
		val toRemove = connections.filter { it.from == block || it.to == block }.toList()
		toRemove.forEach {
			removeConnection(it)
		}
		detach(block)
		blocks.remove(block)
		selectedBlock = null
		markDirty()
	}


	fun selectBlock(block: BlockNode?) {
		blocks.forEach { it.selected = false }
		connections.forEach { it.selected = false }
		selectedBlock = block
		block?.selected = true
		selectedConnection = null
	}


	private fun selectConnection(conn: Connection?) {
		connections.forEach {
			it.selected = false
			it.line.stroke = Color.BLUE
		}
		blocks.forEach { it.selected = false }
		selectedConnection = conn
		conn?.let {
			it.selected = true
			it.line.stroke = Color.RED
		}
		selectedBlock = null
	}


	private fun setupContextMenu() {
		val showMenu = EventHandler<MouseEvent> { ev ->
			if (ev.button == MouseButton.SECONDARY) {
				val node = ev.pickResult.intersectedNode
				if (node is BlockNode || node is Circle) return@EventHandler
				ev.consume()
			}
		}
		contentPane.onMousePressed = showMenu
		gridCanvas.onMousePressed = showMenu
		gridCanvas.isMouseTransparent = true
	}


	fun setupHandlersForBlock(block: BlockNode) {
		block.outputCircles.forEachIndexed { outputIdx, outCircle ->
			var pressedSceneX = 0.0
			var pressedSceneY = 0.0
			var draggingStarted = false
			val dragThreshold = 6.0
			outCircle.onMousePressed = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					pressedSceneX = event.sceneX
					pressedSceneY = event.sceneY
					draggingStarted = false
					event.consume()
				}
			}
			outCircle.onMouseDragged = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					val dx = event.sceneX - pressedSceneX
					val dy = event.sceneY - pressedSceneY
					if (!draggingStarted && (dx * dx + dy * dy) > dragThreshold * dragThreshold) {
						val outId = block.core.outputIds[outputIdx]
						val sc = outCircle.localToScene(outCircle.centerX, outCircle.centerY)
						val pc = contentPane.sceneToLocal(sc.x, sc.y)
						val tmp = Line(pc.x, pc.y, pc.x, pc.y).apply {
							stroke = Color.BLUE
							strokeWidth = 2.0
							viewOrder = 1.0
						}
						if (tmp.parent != workspaceGroup) {
							workspaceGroup.children.add(tmp)
						}
						draggingLine = tmp
						draggingFromBlock = block
						draggingFromOutputId = outId
						draggingStarted = true
					}
					if (draggingStarted) {
						val p = contentPane.sceneToLocal(event.sceneX, event.sceneY)
						draggingLine?.endX = p.x
						draggingLine?.endY = p.y
					}
					event.consume()
				}
			}
			outCircle.onMouseReleased = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					if (!draggingStarted) return@EventHandler

					val panePt = contentPane.sceneToLocal(event.sceneX, event.sceneY)
					val hit = blocks.asSequence().flatMap { other ->
						other.inputCircles.mapIndexed { i, c -> Triple(other, i, c) }
					}.firstOrNull { (other, _, c) ->
						if (other == draggingFromBlock) return@firstOrNull false
						val sc = c.localToScene(c.centerX, c.centerY)
						val pc = contentPane.sceneToLocal(sc.x, sc.y)
						val dx = pc.x - panePt.x
						val dy = pc.y - panePt.y
						Math.hypot(dx, dy) <= c.radius + 4
					}

					if (hit != null) {
						val (toBlock, inputIdx, _) = hit
						val fromCenter = outCircle.localToScene(outCircle.centerX, outCircle.centerY)
						val fromPoint = contentPane.sceneToLocal(fromCenter.x, fromCenter.y)
						val toCircle = toBlock.inputCircles[inputIdx]
						val toCenter = toCircle.localToScene(toCircle.centerX, toCircle.centerY)
						val toPoint = contentPane.sceneToLocal(toCenter.x, toCenter.y)
						val visible = Line(fromPoint.x, fromPoint.y, toPoint.x, toPoint.y).apply {
							stroke = Color.BLUE
							strokeWidth = 2.0
						}
						val pick = Line(fromPoint.x, fromPoint.y, toPoint.x, toPoint.y).apply {
							stroke = Color.TRANSPARENT
							strokeWidth = 12.0
							isPickOnBounds = false
							viewOrder = 0.9
						}
						val conn =
							Connection(
								block,
								toBlock,
								visible,
								draggingFromOutputId!!,
								toBlock.core.inputIds[inputIdx],
								pick,
								IncomeDataType.REQUIRED_DATA,
								null,
							)
						conn.updateLine()
						connections.add(conn)
						if (!workspaceGroup.children.contains(visible)) {
							workspaceGroup.children.add(visible)
						}
						if (!workspaceGroup.children.contains(pick)) {
							workspaceGroup.children.add(pick)
						}
						pick.onMouseClicked = EventHandler { ev ->
							if (ev.button == MouseButton.PRIMARY) {
								selectConnection(conn)
								(pick.parent as? Pane)?.requestFocus()
								ev.consume()
							}
						}

						block.connectedLines.add(conn)
						toBlock.connectedLines.add(conn)
					}

					// убираем временную линию
					workspaceGroup.children.remove(draggingLine)
					draggingLine = null
					draggingStarted = false
					event.consume()
				}
			}
		}
	}


	/** Модалка с pretty JSON данными выхода */
	fun showOutputFor(block: BlockNode, outIndex: Int) {
		val output = block.core.outputsData.getOrNull(outIndex)
		if (output == null) {
			Alert(Alert.AlertType.INFORMATION, "Нет данных").showAndWait()
			return
		}

		val dialog = Stage()
		dialog.title = "Output $outIndex"

		val codeArea = CodeArea().apply {
			paragraphGraphicFactory = LineNumberFactory.get(this)
			isWrapText = true
			isEditable = false
			contextMenu = ContextMenu(
				MenuItem("Копировать").apply { setOnAction { copy() } },
				MenuItem("Вырезать").apply { setOnAction { cut() } },
				MenuItem("Вставить").apply { setOnAction { paste() } },
				MenuItem("Выделить всё").apply { setOnAction { selectAll() } },
			)
			style = "-fx-font-size: 16px; -fx-font-family: 'Consolas', 'monospace';"
		}
		val scroll = VirtualizedScrollPane(codeArea).also { VBox.setVgrow(it, Priority.ALWAYS) }

		val copyBtn = Button("Скопировать в буфер").apply {
			setOnAction {
				val cb = javafx.scene.input.Clipboard.getSystemClipboard()
				val content = javafx.scene.input.ClipboardContent()
				content.putString(codeArea.text)
				cb.setContent(content)
			}
		}

		// Переключатели форматов, как раньше (используем те же InputFormatType.entries)
		val formats = InputFormatType.entries
		val tg = ToggleGroup()
		val radioButtons = formats.map { fmt ->
			RadioButton(fmt.name).apply { toggleGroup = tg }
		}
		radioButtons.firstOrNull()?.isSelected = true

		fun render() {
			val selectedIndex = radioButtons.indexOfFirst { it.isSelected }
			val selected = if (selectedIndex >= 0) formats[selectedIndex] else InputFormatType.JSON
			val text = when (selected) {
				InputFormatType.JSON -> {
					ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output)
				}

				InputFormatType.XML -> {
					XmlMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output)
				}

				InputFormatType.YAML -> {
					val opts = DumperOptions().apply {
						defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
						isPrettyFlow = true
						indent = 2
						defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
					}
					Yaml(opts).dump(output)
				}

				InputFormatType.PROTOBUF -> {
					// Старое поведение оставляло TODO — сохраняем это же поведение
					ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output)
				}
			}
			codeArea.replaceText(text)
		}
		radioButtons.forEach { it.setOnAction { render() } }
		render()

		val formatBar = HBox(12.0, *radioButtons.toTypedArray()).apply { padding = Insets(6.0) }
		val root = VBox(10.0, formatBar, scroll, copyBtn).apply {
			padding = Insets(12.0)
			VBox.setVgrow(scroll, Priority.ALWAYS)
		}

		dialog.scene = Scene(root, 720.0, 600.0)
		Platform.runLater { codeArea.requestFocus() }
		dialog.initModality(Modality.APPLICATION_MODAL)
		dialog.show()
	}


	/** Отрисовать сетку, которая покрывает всю рабочую область (всю зону контента) */
	private fun drawGrid(canvas: Canvas, grid: Double = 10.0, boldStep: Int = 5) {
		val gc = canvas.graphicsContext2D
		val w = contentPane.width
		val h = contentPane.height
		gc.clearRect(0.0, 0.0, w, h)
		gc.stroke = Color.rgb(180, 180, 180, 0.25)
		gc.lineWidth = 1.0
		var x = 0.0
		while (x <= w) {
			gc.strokeLine(x, 0.0, x, h)
			x += grid
		}
		var y = 0.0
		while (y <= h) {
			gc.strokeLine(0.0, y, w, y)
			y += grid
		}
		gc.stroke = Color.rgb(120, 120, 120, 0.5)
		gc.lineWidth = 2.0
		x = 0.0
		while (x <= w) {
			gc.strokeLine(x, 0.0, x, h)
			x += grid * boldStep
		}
		y = 0.0
		while (y <= h) {
			gc.strokeLine(0.0, y, w, y)
			y += grid * boldStep
		}
		gc.lineWidth = 1.0
	}

	/** Собираем CoreProject из текущего UI */
	private fun collectCoreProject(): CoreProject {
		val coreBlocks = blocks.map { it.core }.toMutableList()
		val coreConns = connections.map { it.toCoreConnection() }.toMutableList()
		return CoreProject(coreBlocks, coreConns)
	}


	/** Восстановление UI из core-проекта (после load) */
	private fun restoreUiFromCore(project: CoreProject) {
		suppressDirty = true
		try {
			contentPane.children.setAll(gridCanvas, workspaceGroup)
			blocks.clear()
			connections.clear()
			workspaceGroup.children.removeIf { it is BlockNode || it is Line }
			val idToUi = mutableMapOf<UUID, BlockNode>()
			project.blocks.forEach { cb ->
				val b = BlockNode(
					core = cb,
					x = cb.uiX ?: 50.0,
					y = cb.uiY ?: 50.0,
					loadCode = { core ->
						val base = currentProjectFile?.parentFile ?: File(".")
						core.codePath?.let { p ->
							val file = File(p).let { if (File(p).isAbsolute) File(p) else File(base, p) }.normalize()
							file.takeIf { it.exists() && it.isFile }?.readText()
						} ?: ""
					},
					saveCode = { core, text ->
						val base = currentProjectFile?.parentFile ?: File(".")
						val file = codeFileFor(core)
						file.writeText(text)
						core.codePath = base.toPath().relativize(file.toPath()).toString().replace('\\', '/')
						markDirty()
					},
					callbacks = callbacks,
				)
				if (b.core.type == BlockType.SUB_PROCESS && b.core.subProjectPath.isNotBlank()) {
					val f = resolveProjectFile(b.core.subProjectPath)
					if (f.exists()) adjustSubProjectNodeIO(b, f)
				}
				b.onMove = {
					ensureWorkspaceFits(b)
					ensureBlockVisible(b)
					markDirty()
				}
				blocks.add(b)
				workspaceGroup.children.add(b)
				setupHandlersForBlock(b)
				idToUi[cb.id] = b
			}
			project.connections.forEach { c ->
				val from = idToUi[c.fromId] ?: return@forEach
				val to = idToUi[c.toId] ?: return@forEach
				val fromIdx = from.core.outputIds.indexOf(c.fromOutputId)
				val toIdx = to.core.inputIds.indexOf(c.toInputId)
				val fromCircle = from.outputCircles.getOrNull(fromIdx)
				val toCircle = to.inputCircles.getOrNull(toIdx)
				if (fromCircle == null || toCircle == null) {
					println("⚠️ Пропущена связь: не найден кружок для соединения ${c.fromOutputId} -> ${c.toInputId}")
					return@forEach
				}
				val fromCenter = fromCircle.localToScene(fromCircle.centerX, fromCircle.centerY)
				val toCenter = toCircle.localToScene(toCircle.centerX, toCircle.centerY)
				val p1 = contentPane.sceneToLocal(fromCenter.x, fromCenter.y)
				val p2 = contentPane.sceneToLocal(toCenter.x, toCenter.y)

				val visible = Line(p1.x, p1.y, p2.x, p2.y).apply {
					stroke = Color.BLUE
					strokeWidth = 2.0
				}
				val pick = Line(p1.x, p1.y, p2.x, p2.y).apply {
					stroke = Color.TRANSPARENT
					strokeWidth = 12.0
					isPickOnBounds = false
					viewOrder = 0.9
				}
				val conn = Connection(from, to, visible, c.fromOutputId, c.toInputId, pick, c.incomeDataType, c.gate)
				conn.updateLine()
				connections += conn
				(visible.parent as? Pane)?.children?.remove(visible)
				(pick.parent as? Pane)?.children?.remove(pick)
				(visible.parent as? Group)?.children?.remove(visible)
				(pick.parent as? Group)?.children?.remove(pick)
				if (visible.parent != workspaceGroup) {
					workspaceGroup.children.add(visible)
				}
				if (pick.parent != workspaceGroup) {
					workspaceGroup.children.add(pick)
				}
				pick.onMouseClicked = EventHandler { ev ->
					if (ev.button == MouseButton.PRIMARY) {
						selectConnection(conn)
						(pick.parent as? Pane)?.requestFocus()
						ev.consume()
					}
				}
				from.layoutXProperty().addListener { _, _, _ -> conn.updateLine() }
				from.layoutYProperty().addListener { _, _, _ -> conn.updateLine() }
				to.layoutXProperty().addListener { _, _, _ -> conn.updateLine() }
				to.layoutYProperty().addListener { _, _, _ -> conn.updateLine() }
				from.connectedLines.add(conn)
				to.connectedLines.add(conn)
			}
		} finally {
			Platform.runLater { scrollToRightmostBlock() }
			suppressDirty = false
			clearDirty()
			updateTitle()
		}
	}


	/** Переложить outputsData из core-проекта на UI-ноды. */
	private fun applyOutputsToUi(project: CoreProject) {
		val byId = project.blocks.associateBy { it.id }
		blocks.forEach { ui ->
			byId[ui.core.id]?.let { core ->
				ui.core.outputsData = core.outputsData
			}
		}
	}


	private fun updateTitle() {
		if (!this::stage.isInitialized) {
			return
		}
		val base = currentProjectFile?.name?.removeSuffix(".json")
			?: "Low code processes executor"
		stage.title = if (isDirty) {
			"• $base"
		} else {
			base
		}
	}


	private fun markDirty() {
		if (!suppressDirty && !isDirty) {
			isDirty = true
			updateTitle()
		}
	}


	private fun clearDirty() {
		if (isDirty) {
			isDirty = false
			updateTitle()
		}
	}


	private fun confirmSaveIfDirty(): Boolean {
		if (!isDirty) return true
		val save = ButtonType("Сохранить", ButtonBar.ButtonData.YES)
		val dont = ButtonType("Не сохранять", ButtonBar.ButtonData.NO)
		val cancel = ButtonType.CANCEL
		val alert = Alert(Alert.AlertType.CONFIRMATION).apply {
			title = "Процесс изменён"
			headerText = "Сохранить изменения?"
			contentText = currentProjectFile?.name ?: "Новый процесс"
			buttonTypes.setAll(save, dont, cancel)
		}
		return when (alert.showAndWait().orElse(cancel)) {
			save -> {
				val project = collectCoreProject()
				if (currentProjectFile != null) {
					projectRepo.saveProject(currentProjectFile!!, project)
				} else {
					val fc = FileChooser().apply {
						title = "Сохранить процесс"
						extensionFilters += FileChooser.ExtensionFilter("JSON Files", "*.json")
					}
					val f = fc.showSaveDialog(stage) ?: return false
					projectRepo.saveProject(f, project)
					currentProjectFile = f
				}
				clearDirty()
				true
			}

			dont -> true
			else -> false
		}
	}


	private fun ensureResourcesDir(): File? {
		val proj = currentProjectFile ?: return null
		val dir = File(proj.parentFile ?: File("."), "${proj.nameWithoutExtension}_resources")
		dir.mkdirs()
		return dir
	}


	private fun codeFileFor(b: CoreBlock): File {
		if (currentProjectFile == null) {
			val fc = FileChooser().apply {
				title = "Сохранить процесс"
				extensionFilters += FileChooser.ExtensionFilter("JSON Files", "*.json")
			}
			val project = collectCoreProject()
			fc.showSaveDialog(stage)?.let { f ->
				projectRepo.saveProject(f, project)
				currentProjectFile = f
			}
		}
		val parentFile = currentProjectFile?.parentFile
		val nameWithoutExtension = currentProjectFile?.nameWithoutExtension
		val dir = File(parentFile, "${nameWithoutExtension}_resources").apply { mkdirs() }
		val base = b.id.also { b.id = it }
		val ext = when (b.type) {
			BlockType.MAPPING_GROOVY -> "groovy"
			BlockType.MAPPING_PYTHON -> "py"
			BlockType.MAPPING_JAVA_SCRIPT -> "js"
			BlockType.FORM -> "html"
			BlockType.INPUT_DATA -> when (b.inputFormat) {
				InputFormatType.JSON -> "json"
				InputFormatType.XML -> "xml"
				InputFormatType.YAML -> "yaml"
				else -> "txt"
			}

			else -> "txt"
		}
		return File(dir, "$base.$ext")
	}


	private fun normalizePathsAfterLoad(project: CoreProject) {
		project.blocks.forEach { it.ensureIoIds() }
	}

	/** Прочитать подпроект и выставить I/O у SUB_PROJECT-ноды.
	 * ВХОДЫ: столько, сколько в подпроекте START-блоков (имена берём из name START-блоков или генерируем inN).
	 * ВЫХОДЫ: как у EXIT-блока (если есть), иначе оставляем текущее. */
	private fun adjustSubProjectNodeIO(node: BlockNode, file: File) {
		try {
			val mapper = ObjectMapper().findAndRegisterModules()
			val root = mapper.readTree(file)
			val blocksNode = root.get("blocks") ?: return

			val startNames = mutableListOf<String>()
			var si = 0
			blocksNode.forEach { b ->
				val t = b.get("type")?.asText() ?: b.get("blockType")?.asText()
				if (t == "START") {
					val nm = b.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: "in$si"
					startNames += nm; si++
				}
			}
			node.core.inputNames = startNames.toMutableList()
			node.core.inputCount = startNames.size

			val exitNames = mutableListOf<String>()
			var ei = 0
			blocksNode.forEach { b ->
				val t = b.get("type")?.asText() ?: b.get("blockType")?.asText()
				if (t == "EXIT") {
					val nm = b.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: "out$ei"
					exitNames += nm; ei++
				}
			}
			node.core.outputNames = exitNames.toMutableList()
			node.core.outputCount = exitNames.size

			node.recreateIOCircles()
			setupHandlersForBlock(node)
			markDirty()
		} catch (e: Exception) {
			Alert(Alert.AlertType.WARNING).apply {
				title = "SUB_PROJECT"
				headerText = "Не удалось прочитать структуру подпроцесса"
				contentText = e.localizedMessage
			}.showAndWait()
		}
	}


	/** Открыть проект в этом окне, как раньше (восстановление UI + outputs). */
	private fun importBlocksFromFile(file: File) {
		val project = projectRepo.loadProject(file)
		project.baseDir = file.parentFile
		normalizePathsAfterLoad(project)
		restoreUiFromCore(project)
		outputsRepo.loadLast(file, project)
		applyOutputsToUi(project)
		currentProjectFile = file
		clearDirty()
		updateTitle()
	}


	/** Совместимость со старым кодом (раньше вызывалось после importBlocksFromFile). */
	private fun saveOutputsData(projectFile: File) {
		val projectDir = projectFile.parentFile ?: File(".")
		val outputsDir = File(projectDir, "${projectFile.nameWithoutExtension}_outputs_data")
		outputsDir.mkdirs()
		val outputsMap = blocks.associate { block ->
			block.core.id.toString() to block.core.outputsData
		}
		val timestamp = DateTimeFormatter
			.ofPattern("yyyyMMddHHmmss")
			.withZone(ZoneId.systemDefault())
			.format(Instant.now())
		File(outputsDir, "$timestamp.json").writeText(
			ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(outputsMap)
		)
		File(outputsDir, "last_run.json").writeText(
			ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(outputsMap)
		)
	}


	fun importOutputsData(projectFile: File) {
		val projectDir = projectFile.parentFile ?: File(".")
		val outputsDir = File(projectDir, "${projectFile.nameWithoutExtension}_outputs_data")
		val latestFile = File(outputsDir, "last_run.json")
		if (!latestFile.exists()) return
		val typeRef = object : TypeReference<Map<String, List<Map<String, Any>>>>() {}
		val outputsMap = ObjectMapper().readValue(latestFile, typeRef)
		blocks.forEach { block ->
			if (block.core.type in arrayOf(BlockType.PROPERTIES, BlockType.SUB_PROCESS)) return@forEach
			outputsMap[block.core.id.toString()]?.let { list ->
				block.core.outputsData = list.map { it.toMutableMap() }.toMutableList() as MutableList<MutableMap<String, Any?>>
			}
		}
	}


	private fun detach(node: Node?) {
		if (node == null) {
			return
		}
		when (val p = node.parent) {
			is Pane -> p.children.remove(node)
			is Group -> p.children.remove(node)
		}
	}


	private fun removeConnection(conn: Connection) {
		connections.remove(conn)
		conn.from.connectedLines.remove(conn)
		conn.to.connectedLines.remove(conn)
		detach(conn.line)
		detach(conn.pick)
	}


	private fun ensureWorkspaceFits(b: BlockNode) {
		// координаты блока в системе contentPane
		val bScene = b.localToScene(b.boundsInLocal)
		val pMin = contentPane.sceneToLocal(bScene.minX, bScene.minY)
		val pMax = contentPane.sceneToLocal(bScene.maxX, bScene.maxY)

		var changed = false
		if (pMax.x + 50 > contentPane.prefWidth) {
			contentPane.prefWidth = pMax.x + 50; changed = true
		}
		if (pMax.y + 50 > contentPane.prefHeight) {
			contentPane.prefHeight = pMax.y + 50; changed = true
		}
		if (pMin.x < 0) {
			val s = -pMin.x; contentPane.prefWidth += s; contentPane.translateX += s; changed = true
		}
		if (pMin.y < 0) {
			val s = -pMin.y; contentPane.prefHeight += s; contentPane.translateY += s; changed = true
		}

		if (changed) {
			contentPane.requestLayout()
		}
	}


	private fun collectAllBlocksWithFile(
		projectFile: File,
		visited: MutableSet<String> = mutableSetOf(),
	): List<BlockLoc> {
		if (!projectFile.exists()) return emptyList()
		if (!visited.add(projectFile.absolutePath)) return emptyList()

		val project = projectRepo.loadProject(projectFile).apply { baseDir = projectFile.parentFile }
		val thisFile = projectFile
		val acc = mutableListOf<BlockLoc>()

		project.blocks.forEach { b ->
			b.codeAbsolutePath = b.codePath?.let { p ->
				resolveStored(projectFile.parentFile, p)
			}
			acc.add(BlockLoc(thisFile, b))
		}
		project.blocks.forEach { b ->
			if (b.subProjectPath.isNotBlank()) {
				val subFile = resolveStored(projectFile.parentFile, b.subProjectPath)
				if (subFile.exists()) {
					acc += collectAllBlocksWithFile(subFile, visited)
				}
			}
		}
		return acc
	}


	fun resolveProjectFile(path: String): File {
		val base = currentProjectFile?.parentFile ?: File(".")
		return resolveStored(base, path)
	}


	/** Сериализация: относительный путь с ведущим '/' и слешами '/' */
	private fun storeRel(base: File, file: File): String {
		val baseAbs = base.toPath().toAbsolutePath().normalize()
		val fileAbs = file.toPath().toAbsolutePath().normalize()
		val stored = try {
			if (baseAbs.root != fileAbs.root) {
				fileAbs.toString().replace('\\', '/')
			} else {
				val rel = baseAbs.relativize(fileAbs).toString()
					.replace('\\', '/')
					.removePrefix("./")
				if (rel.startsWith("/")) rel else "/$rel"
			}
		} catch (_: IllegalArgumentException) {
			fileAbs.toString().replace('\\', '/')
		}
		return stored
	}

	/** Десериализация: трактуем ведущий '/' как наш «относительный» префикс */
	private fun resolveStored(base: File, stored: String): File {
		val s = if (stored.startsWith("/")) stored.substring(1) else stored
		val f = File(s)
		return if (f.isAbsolute) {
			f.normalize()
		} else {
			File(base, s).normalize()
		}
	}


	/** Прокрутить максимально вправо — до самого дальнего блока (учёт layout, translate, padding). */
	private fun scrollToRightmostBlock(padding: Double = 32.0) {
		Platform.runLater {
			// гарантируем актуальные bounds
			contentPane.applyCss()
			contentPane.layout()

			if (blocks.isEmpty()) return@runLater

			// X-координата правого края самого дальнего блока в координатах contentPane
			val rightmostX = blocks.maxOf { b ->
				val bndsScene = b.localToScene(b.boundsInLocal)
				val pt = contentPane.sceneToLocal(bndsScene.maxX, bndsScene.minY)
				pt.x
			} + padding

			// при необходимости расширяем контент
			if (rightmostX > contentPane.prefWidth) {
				contentPane.prefWidth = rightmostX + padding
				contentPane.requestLayout()
			}

			val viewportW = scrollPane.viewportBounds.width
			val contentW = contentPane.layoutBounds.width

			val totalScrollable = (contentW - viewportW)
			if (totalScrollable > 0) {
				val target = (rightmostX - viewportW).coerceAtLeast(0.0)
				val norm = (target / totalScrollable).coerceIn(0.0, 1.0)
				scrollPane.hvalue = if (norm > 0.98) 1.0 else norm // добивка до самого конца
			} else {
				scrollPane.hvalue = 0.0
			}
		}
	}


	companion object {
		private const val PYTHON_PARAMS_VARIABLE = "PARAMS_JSON"
		private val PYTHON = getPython()
		private val springLogs: File = File("./spring-app/output.log")


		private fun getPython(): String {
			val commands = listOf("python", "py", "python3")
			for (cmd in commands) {
				try {
					val process = ProcessBuilder(cmd, "--version")
						.redirectErrorStream(true)
						.start()
					val output = process.inputStream.bufferedReader().readText().trim()
					if (output.isNotEmpty() && !output.startsWith("Python was not found;")) {
						return cmd
					}
				} catch (_: Exception) {
				}
			}
			throw RuntimeException("Python не найден")
		}

		private val inputData: MutableList<String> = mutableListOf()


		@JvmStatic
		fun main(args: Array<String>) {
			if (args.isNotEmpty()) {
				inputData.addAll(args)
			}
			launch(MainApp::class.java)
		}
	}
}