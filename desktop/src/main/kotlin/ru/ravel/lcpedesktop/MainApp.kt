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
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.input.KeyCode
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
import ru.ravel.lcpedesktop.model.Command
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.Executors
import kotlin.system.exitProcess

class MainApp : Application() {

	//	private lateinit var projectRepo: ProjectRepository
//	private lateinit var outputsRepo: OutputsRepository
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
	private var currentProjectFile: File? = null
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
	private var draggingFromOutputIndex: Int? = null

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

		onOpenSubProject = { file ->
			val stage = Stage()
			val project = projectRepo.loadProject(file)
			normalizePathsAfterLoad(
				project,
				file
			)
			val subApp = MainApp()
			subApp.start(stage)
			Platform.runLater {
				subApp.restoreUiFromCore(project)
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
			override fun exec(code: String, bindings: Map<String, Any?>): Any? {
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
							BlockType.SUB_PROJECT -> {
								val fc = FileChooser().apply {
									title = "Выберите проект"
									extensionFilters += FileChooser.ExtensionFilter("JSON", "*.json")
								}
								fc.showOpenDialog(primaryStage)?.let { file ->
									val node = addBlock(contentPane, event.x, event.y, file.nameWithoutExtension, type)
									node.core.subProjectPath = file.absolutePath
									adjustSubProjectNodeIO(node, file)
								}
							}

							else -> addBlock(contentPane, event.x, event.y, type.displayName, type)
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

		val newBtn = Button("Новый проект").apply {
			setOnAction {
				if (!confirmSaveIfDirty()) return@setOnAction
				blocks.clear()
				connections.clear()
				contentPane.children.removeIf { it is BlockNode || it is Line }
				currentProjectFile = null
				clearDirty()
				updateTitle()
			}
		}
		val openBtn = Button("Открыть проект").apply {
			setOnAction {
				if (!confirmSaveIfDirty()) return@setOnAction
				val fc = FileChooser().apply {
					title = "Открыть проект"
					extensionFilters += FileChooser.ExtensionFilter("JSON", "*.json")
				}
				fc.showOpenDialog(stage)?.let { f ->
					val project = projectRepo.loadProject(f)
					normalizePathsAfterLoad(project, f)
					restoreUiFromCore(project)
					outputsRepo.loadLast(f, project)
					applyOutputsToUi(project)
					currentProjectFile = f
					clearDirty()
					updateTitle()
				}
			}
		}
		val saveBtn = Button("Сохранить проект").apply {
			setOnAction {
				val project = collectCoreProject()
				if (currentProjectFile != null) {
					projectRepo.saveProject(currentProjectFile!!, project)
				} else {
					val fc = FileChooser().apply {
						title = "Сохранить проект"
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
			val btn = this
			setOnAction {
//				if (isRunning) return@setOnAction
//				isRunning = true
//				btn.isDisable = true

				val project = collectCoreProject()

				runController.runAsync(project, currentProjectFile, object : RunEvents {
					override fun onStart(block: CoreBlock) {
						val ui = blocks.find { it.core.id == block.id }
						Platform.runLater { ui?.executing = true }
					}

					override fun onFinish(block: CoreBlock) {
						val ui = blocks.find { it.core.id == block.id }
						Platform.runLater { ui?.executing = false }
					}

					override fun onError(block: CoreBlock, error: Throwable) {
						Platform.runLater {
							Alert(Alert.AlertType.ERROR).apply {
								title = block.name
								headerText = null
								contentText = error.localizedMessage
							}.showAndWait()
//							btn.isDisable = false
//							isRunning = false
						}
					}

					override fun onCompleted(project: CoreProject) {
						Platform.runLater {
							applyOutputsToUi(project)
//							btn.isDisable = false
//							isRunning = false
						}
					}
				})
			}
		}


		val saves = HBox(10.0, newBtn, openBtn, saveBtn).apply { padding = Insets(8.0) }
		val runBox = HBox(10.0, runBtn).apply { padding = Insets(8.0) }
		val root = VBox(saves, runBox, scrollPane)
		val scene = Scene(root, windowW, windowH)

		// Горячие клавиши
		scene.setOnKeyPressed { e ->
			if (e.code in arrayOf(KeyCode.DELETE, KeyCode.BACK_SPACE)) {
				selectedBlock?.let { deleteBlockRequest(it) }
				selectedConnection?.let { conn ->
					connections.remove(conn)
					conn.from.connectedLines.remove(conn)
					conn.to.connectedLines.remove(conn)
					(conn.line.parent as? Pane)?.children?.remove(conn.line)
					selectedConnection = null
					markDirty()
				}
			}
			if (e.isControlDown && e.code == KeyCode.S) {
				saveBtn.fire()
				e.consume()
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
						importOutputsData(file)
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
				val project = collectCoreProject()
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

	fun addBlock(parent: Pane, x: Double, y: Double, name: String, blockType: BlockType): BlockNode {
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
				}
			}
		}

		val block = BlockNode(
			core = core,
			x = x,
			y = y,
			loadCode = { b -> b.codePath?.let { File(it).takeIf(File::exists)?.readText() } ?: "" },
			saveCode = { b, text ->
				val file = b.codePath?.let { File(it) } ?: codeFileFor(b)
				if (file != null) {
					file.writeText(text)
					b.codePath = file.absolutePath
					markDirty()
				}
			},
			callbacks = callbacks
		)
		blocks.add(block)
		block.onMove = {
			ensureBlockVisible(block)
			markDirty()
		}
		parent.children.add(block)
		setupHandlersForBlock(block)
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
		val toRemove = connections.filter { it.from == block || it.to == block }
		toRemove.forEach { conn ->
			(conn.line.parent as? Pane)?.children?.remove(conn.line)
			conn.from.connectedLines.remove(conn)
			conn.to.connectedLines.remove(conn)
		}
		connections.removeAll(toRemove)
		(block.parent as? Pane)?.children?.remove(block)
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
		connections.forEach { it.selected = false }
		blocks.forEach { it.selected = false }
		selectedConnection = conn
		conn?.selected = true
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
						// запускаем протяжку только при реальном драгe
						val (sx, sy) = block.outputPoint(outputIdx)
						val line = Line(sx, sy, sx, sy).apply {
							stroke = Color.BLUE
							strokeWidth = 2.0
							viewOrder = 1.0
						}
						contentPane.children.add(line)
						draggingLine = line
						draggingFromBlock = block
						draggingFromOutputIndex = outputIdx
						draggingStarted = true
					}
					if (draggingStarted) {
						val p = contentPane.sceneToLocal(event.sceneX, event.sceneY)
						draggingLine?.apply {
							endX = p.x
							endY = p.y
						}
					}
					event.consume()
				}
			}
			outCircle.onMouseReleased = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					if (!draggingStarted) {
						// это клик — показываем результаты конкретного выхода
						showOutputFor(block, outputIdx)
					} else {
						// завершение протяжки (оставляем как в вашем коде)
						val paneCoords = contentPane.sceneToLocal(event.sceneX, event.sceneY)
						val toBlockPair = blocks.asSequence().flatMap { other ->
							other.inputCircles.mapIndexed { idx, circle -> Triple(other, circle, idx) }
						}.find { (other, circle, _) ->
							if (other == draggingFromBlock) return@find false
							val sp = circle.localToScene(circle.centerX, circle.centerY)
							val pp = contentPane.sceneToLocal(sp.x, sp.y) ?: return@find false
							val dx = pp.x - paneCoords.x

							val dy = pp.y - paneCoords.y
							Math.hypot(dx, dy) <= circle.radius + 4
						}
						if (toBlockPair != null) {
							val (toBlock, _, inputIdx) = toBlockPair
							val (sx, sy) = draggingFromBlock!!.outputPoint(draggingFromOutputIndex!!)
							val (ex, ey) = toBlock.inputPoint(inputIdx)
							val visible = draggingLine!!.apply {
								startX = sx
								startY = sy
								endX = ex
								endY = ey
							}
							val conn = Connection(draggingFromBlock!!, toBlock, visible, draggingFromOutputIndex!!, inputIdx)

							val pick = Line().apply {
								stroke = Color.TRANSPARENT
								strokeWidth = 12.0
								isPickOnBounds = false
								viewOrder = 0.9
								startXProperty().bind(visible.startXProperty())
								startYProperty().bind(visible.startYProperty())
								endXProperty().bind(visible.endXProperty())
								endYProperty().bind(visible.endYProperty())
								onMouseClicked = EventHandler { ev ->
									if (ev.button == MouseButton.PRIMARY) {
										selectConnection(conn)
										(parent as? Pane)?.requestFocus()
										ev.consume()
									}
								}
							}
							(scrollPane.content as? Pane)?.children?.add(pick)
							pick.toFront()
							visible.toFront()
							connections.add(conn)
							draggingFromBlock!!.connectedLines.add(conn)
							toBlock.connectedLines.add(conn)
							markDirty()
						} else {
							contentPane.children.remove(draggingLine)
						}
						draggingLine = null
						draggingFromOutputIndex = null
					}
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

	private fun drawGrid(canvas: Canvas, grid: Double = 10.0, boldStep: Int = 5) {
		val gc = canvas.graphicsContext2D
		gc.clearRect(0.0, 0.0, canvas.width, canvas.height)
		val w = canvas.width

		val h = canvas.height
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
			if ((x / grid) % boldStep == 0.0) gc.strokeLine(x, 0.0, x, h)
			x += grid
		}
		y = 0.0
		while (y <= h) {
			if ((y / grid) % boldStep == 0.0) gc.strokeLine(0.0, y, w, y)
			y += grid
		}
		gc.lineWidth = 1.0
	}

	private fun uiListener(): ExecutionListener = object : ExecutionListener {
		override fun onStart(block: CoreBlock) {
			val ui = blocks.find { it.core.id == block.id }
			Platform.runLater { ui?.executing = true }
		}

		override fun onFinish(block: CoreBlock) {
			val ui = blocks.find { it.core.id == block.id }
			Platform.runLater { ui?.executing = false }
		}

		override fun onError(block: CoreBlock, error: Throwable) {
			Platform.runLater {
				Alert(Alert.AlertType.ERROR).apply {
					title = block.name
					contentText = error.localizedMessage
					showAndWait()
				}
			}
		}
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
			blocks.clear()
			connections.clear()
			contentPane.children.removeIf { it is BlockNode || it is Line }

			val idToUi = mutableMapOf<UUID, BlockNode>()

			project.blocks.forEach { cb ->
				val b = BlockNode(
					core = cb,
					x = cb.uiX ?: 50.0,
					y = cb.uiY ?: 50.0,
					loadCode = { core ->
						core.codePath?.let { p -> File(p).takeIf { it.exists() && it.isFile }?.readText() } ?: ""
					},
					saveCode = { core, text ->
						val file = core.codePath?.let { File(it) } ?: codeFileFor(core)
						file.writeText(text)
						core.codePath = file.absolutePath
						markDirty()
					},
					callbacks = callbacks
				)
				if (b.core.type == BlockType.SUB_PROJECT && b.core.subProjectPath.isNotBlank()) {
					val f = File(b.core.subProjectPath)
					if (f.exists()) adjustSubProjectNodeIO(b, f)
				}
				blocks += b
				(scrollPane.content as? Pane)?.children?.add(b)
				setupHandlersForBlock(b)
				idToUi[cb.id] = b
			}
			project.connections.forEach { c ->
				val from = idToUi[c.fromId] ?: return@forEach
				val to = idToUi[c.toId] ?: return@forEach
				val (sx, sy) = from.outputPoint(c.fromOutputIndex)
				val (ex, ey) = to.inputPoint(c.toInputIndex)
				val visible = Line(sx, sy, ex, ey).apply {
					stroke = Color.BLUE
					strokeWidth = 2.0
				}
				val conn = Connection(from, to, visible, c.fromOutputIndex, c.toInputIndex)
				val pick = Line().apply {
					stroke = Color.TRANSPARENT
					strokeWidth = 12.0
					isPickOnBounds = false
					viewOrder = 0.9
					startXProperty().bind(visible.startXProperty())
					startYProperty().bind(visible.startYProperty())
					endXProperty().bind(visible.endXProperty())
					endYProperty().bind(visible.endYProperty())
					onMouseClicked = EventHandler { ev ->
						if (ev.button == MouseButton.PRIMARY) {
							selectConnection(conn)
							(parent as? Pane)?.requestFocus()
							ev.consume()
						}
					}
				}
				(scrollPane.content as? Pane)?.children?.addAll(pick, visible)
				connections += conn
				from.connectedLines.add(conn)
				to.connectedLines.add(conn)
			}
		} finally {
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
		if (!this::stage.isInitialized) return
		val base = currentProjectFile?.name?.removeSuffix(".json") ?: "Low code processes executor"
		stage.title = if (isDirty) "• $base" else base
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
			title = "Проект изменён"
			headerText = "Сохранить изменения?"
			contentText = currentProjectFile?.name ?: "Новый проект"
			buttonTypes.setAll(save, dont, cancel)
		}
		return when (alert.showAndWait().orElse(cancel)) {
			save -> {
				val project = collectCoreProject()
				if (currentProjectFile != null) projectRepo.saveProject(currentProjectFile!!, project)
				else {
					val fc = FileChooser().apply {
						title = "Сохранить проект"
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
		val dir =
			File(currentProjectFile?.parentFile, "${currentProjectFile!!.nameWithoutExtension}_resources").apply { mkdirs() }
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

	private fun loadCodeFromDisk(b: CoreBlock): String {
		val path = b.codePath ?: return ""
		val base = currentProjectFile?.parentFile
		val f = File(path).let { if (it.isAbsolute) it else File(base, path) }
		return runCatching { f.readText() }.getOrElse { "" }
	}

	private fun saveCodeToDisk(b: CoreBlock, text: String) {
		val baseDir = currentProjectFile?.parentFile ?: File(".")
		val file = b.codePath?.let { p ->
			val f = File(p)
			if (f.isAbsolute) f else File(baseDir, p)
		} ?: codeFileFor(b)
		file.writeText(text)
		val rel = baseDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
		b.codePath = rel
		markDirty()
	}


	private fun normalizePathsAfterLoad(project: CoreProject, projectFile: File) {
		val base = projectFile.parentFile
		project.blocks.forEach { b ->
			b.codePath = b.codePath
				?.takeIf { it.isNotBlank() }
				?.let { p -> File(p).let { if (it.isAbsolute) it else File(base, p) }.absolutePath }

			b.subProjectPath = b.subProjectPath
				.takeIf { it.isNotBlank() }
				?.let { p -> File(p).let { if (it.isAbsolute) it else File(base, p) }.absolutePath }
				?: ""
		}
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
				headerText = "Не удалось прочитать структуру подпроекта"
				contentText = e.localizedMessage
			}.showAndWait()
		}
	}


	/** Открыть проект в этом окне, как раньше (восстановление UI + outputs). */
	fun importBlocksFromFile(file: File) {
		val project = projectRepo.loadProject(file)
		normalizePathsAfterLoad(project, file)
		restoreUiFromCore(project)
		outputsRepo.loadLast(file, project)
		applyOutputsToUi(project)
		currentProjectFile = file
		clearDirty()
		updateTitle()
	}

	/** Совместимость со старым кодом (раньше вызывалось после importBlocksFromFile). */
	fun importOutputsData(file: File) {
		val project = collectCoreProject()
		outputsRepo.loadLast(file, project)
		applyOutputsToUi(project)
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