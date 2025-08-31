package ru.ravel.testjavafx

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.stage.FileChooser
import javafx.stage.Stage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyObject
import org.yaml.snakeyaml.Yaml
import ru.ravel.testjavafx.model.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString


class MainApp : Application() {
	private var currentProjectFile: File? = null
	val blocks = mutableListOf<BlockNode>()
	val connections = mutableListOf<Connection>()
	private var draggingLine: Line? = null
	private var draggingFromBlock: BlockNode? = null
	private var draggingFromOutputIndex: Int? = null
	private var selectedBlock: BlockNode? = null
	private var selectedConnection: Connection? = null
	private var activeContextMenu: ContextMenu? = null
	private val windowW = 800.0
	private val windowH = 600.0
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
	private var lastEnsureVisible = 0L
	private val executor = Executors.newFixedThreadPool(
		Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
	)
	private lateinit var stage: Stage
	private var isDirty = false
	private var suppressDirty = false
	private var springProcess: Process? = null
	private val undoStack: Deque<Command> = ArrayDeque()
	private val redoStack: Deque<Command> = ArrayDeque()
	private var lastPressX: Double = 0.0
	private var lastPressY: Double = 0.0
	private var worldOffsetX = 0.0
	private var worldOffsetY = 0.0

	override fun start(primaryStage: Stage) {
		stage = primaryStage
		drawGrid(gridCanvas, 10.0)
		gridCanvas.widthProperty().bind(contentPane.widthProperty())
		gridCanvas.heightProperty().bind(contentPane.heightProperty())

		gridCanvas.widthProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }
		gridCanvas.heightProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }

		primaryStage.userData = this

		// ПАНОРАМИРОВАНИЕ мышью
		var panLastX = 0.0
		var panLastY = 0.0
		var panning = false
		contentPane.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.MIDDLE) {
				panLastX = event.screenX
				panLastY = event.screenY
				panning = true
				contentPane.cursor = javafx.scene.Cursor.CLOSED_HAND
				event.consume()
			}
		}
		contentPane.onMouseDragged = EventHandler { event ->
			if (panning && event.button == MouseButton.MIDDLE) {
				val dx = panLastX - event.screenX
				val dy = panLastY - event.screenY
				scrollPane.hvalue = (scrollPane.hvalue * (contentPane.width - scrollPane.viewportBounds.width) + dx)
					.coerceIn(
						0.0,
						contentPane.width - scrollPane.viewportBounds.width
					) / (contentPane.width - scrollPane.viewportBounds.width)
				scrollPane.vvalue = (scrollPane.vvalue * (contentPane.height - scrollPane.viewportBounds.height) + dy)
					.coerceIn(
						0.0,
						contentPane.height - scrollPane.viewportBounds.height
					) / (contentPane.height - scrollPane.viewportBounds.height)
				panLastX = event.screenX
				panLastY = event.screenY
				event.consume()
			}
		}
		contentPane.onMouseReleased = EventHandler { event ->
			if (panning && event.button == MouseButton.MIDDLE) {
				panning = false
				contentPane.cursor = javafx.scene.Cursor.DEFAULT
				event.consume()
			}
		}

		// Контекстное меню для создания блока
		contentPane.onMouseClicked = EventHandler { event ->
			if (event.button == MouseButton.SECONDARY && event.target === contentPane) {
				val n = event.pickResult.intersectedNode
				if (n is BlockNode || n is Circle) return@EventHandler // не показываем по блокам и кружкам
				event.consume()
				val contextMenu = ContextMenu()
				BlockType.entries.forEach { type ->
					val item = MenuItem(type.displayName)
					item.setOnAction {
						if (type == BlockType.SUB_PROJECT) {
							val fc = FileChooser().apply {
								title = "Выберите проект"
								extensionFilters += FileChooser.ExtensionFilter("JSON", "*.json")
							}
							val file = fc.showOpenDialog(primaryStage)
							if (file != null) {
								runCommand(AddBlockCommand(this, contentPane, event.x, event.y, file.nameWithoutExtension, type))
								val node = (undoStack.peek() as? AddBlockCommand)?.block
								node?.subProjectPath = file.absolutePath
								node?.let { adjustProjectNodeIO(it, file) }
							}
						} else {
							runCommand(AddBlockCommand(this, contentPane, event.x, event.y, type.displayName, type))
						}
					}
					contextMenu.items.add(item)
				}
				contextMenu.show(contentPane, event.screenX, event.screenY)
				activeContextMenu = contextMenu
				event.consume()
			}
			if (event.button == MouseButton.PRIMARY) {
				if (event.target === contentPane) {
					selectBlock(null)
					selectConnection(null)
				}
			}
			contentPane.requestFocus()
		}

		val newProjectButton = Button("Новый проект").apply {
			setOnAction {
				if (!confirmSaveIfDirty()) return@setOnAction
				// Очищаем всё
				blocks.clear()
				connections.clear()
				contentPane.children.removeIf { it is BlockNode || it is Line }
				currentProjectFile = null
				clearDirty()
				updateTitle()
			}
		}

		val openProjectButton = Button("Открыть проект").apply {
			setOnAction {
				if (!confirmSaveIfDirty()) return@setOnAction
				val fileChooser = FileChooser().apply {
					title = "Открыть проект"
					extensionFilters.add(FileChooser.ExtensionFilter("JSON", "*.json"))
				}
				val file = fileChooser.showOpenDialog(stage)
				if (file != null) {
					importBlocksFromFile(file)
					importOutputsData(file)
					currentProjectFile = file
					clearDirty()
					updateTitle()
				}
			}
		}

		val saveProjectButton = Button("Сохранить проект").apply {
			setOnAction {
				if (currentProjectFile != null) {
					exportBlocksToFile(currentProjectFile!!)
					updateTitle()
				} else {
					val fileChooser = FileChooser()
					fileChooser.title = "Сохранить проект"
					fileChooser.extensionFilters.add(FileChooser.ExtensionFilter("JSON Files", "*.json"))
					val file = fileChooser.showSaveDialog(primaryStage)
					if (file != null) {
						exportBlocksToFile(file)
						currentProjectFile = file
						updateTitle()
					}
				}
			}
		}

		val savesButtonBox = HBox(10.0, newProjectButton, openProjectButton, saveProjectButton).apply {
			padding = Insets(8.0)
		}

		val runButton = Button("Бег").apply {
			setOnAction { runButtonHandler() }
		}
		val runButtonBox = HBox(10.0, runButton).apply {
			padding = Insets(8.0)
		}
		val root = VBox(savesButtonBox, runButtonBox, scrollPane)

		// Горячие клавиши
		val scene = Scene(root, windowW, windowH).apply {
			setOnKeyPressed { event ->
				if (event.code in arrayOf(KeyCode.DELETE, KeyCode.BACK_SPACE)) {
					selectedBlock?.let { runCommand(DeleteBlockCommand(this@MainApp, it)) }
					selectedConnection?.let { conn ->
						connections.remove(conn)
						conn.from.connectedLines.remove(conn)
						conn.to.connectedLines.remove(conn)
						(conn.line.parent as? Pane)?.children?.remove(conn.line)
						selectedConnection = null
						markDirty()
					}
				}
				// Ctrl+S для сохранения
				if (event.isControlDown && event.code == KeyCode.S) {
					if (currentProjectFile != null) {
						exportBlocksToFile(currentProjectFile!!)
					} else {
						val fileChooser = FileChooser()
						fileChooser.title = "Сохранить проект"
						fileChooser.extensionFilters.add(FileChooser.ExtensionFilter("JSON Files", "*.json"))
						val file = fileChooser.showSaveDialog(primaryStage)
						if (file != null) {
							exportBlocksToFile(file)
							currentProjectFile = file
						}
					}
					event.consume()
				}
				// Ctrl+Z
				if (event.isControlDown && !event.isShiftDown && event.code == KeyCode.Z) {
					if (undoStack.isNotEmpty()) {
						val cmd = undoStack.pop()
						cmd.undo()
						redoStack.push(cmd)
					}
					event.consume()
				}
				// Ctrl+Shift+z
				if (event.isControlDown && event.isShiftDown && event.code == KeyCode.Z) {
					if (redoStack.isNotEmpty()) {
						val cmd = redoStack.pop()
						cmd.execute()
						undoStack.push(cmd)
					}
					event.consume()
				}
			}
			// фильтр на клик мышью — скрыть контекстное меню
			addEventFilter(MouseEvent.MOUSE_PRESSED) { _ ->
				activeContextMenu?.let { menu ->
					if (menu.isShowing) {
						menu.hide()
						activeContextMenu = null
					}
				}
			}
		}
		primaryStage.scene = scene.apply {
			addEventFilter(MouseEvent.MOUSE_PRESSED) { _ ->
				activeContextMenu?.let { menu ->
					if (menu.isShowing) {
						menu.hide()
						activeContextMenu = null
					}
				}
			}
		}

		primaryStage.setOnCloseRequest { ev ->
			if (!confirmSaveIfDirty()) ev.consume()
		}
		updateTitle()

//		val webView = WebView()
//		webView.engine.loadContent("<h1>Hello, World!</h1>")

		primaryStage.title = currentProjectFile?.name ?: "Low code processes executor"
		primaryStage.show()
		setupContextMenu()
		contentPane.requestFocus()
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
	}

	fun addBlock(parent: Pane, x: Double, y: Double, name: String, blockType: BlockType): BlockNode {
		val block = BlockNode(x, y, name, blockType)
		blocks.add(block)
		block.onMove = { ensureBlockVisible(block) }
		parent.children.add(block)
		setupHandlersForBlock(block)
		block.onMove = {
			ensureBlockVisible(block)
			markDirty()
		}
		markDirty()
		return block
	}


	// --- Сериализация и загрузка ---

	private fun exportBlocksToFile(file: File) {
		saveOutputsData(file)

		val projectDir = file.parentFile ?: File(".")
		// Например, создаём папку для всех фрагментов кода/данных
		val resourcesDir = File(projectDir, "${file.nameWithoutExtension}_resources")
		resourcesDir.mkdirs()

		// Для каждого блока: сохраняем code и/или dataDocs в отдельные файлы
		val serializedBlocks = blocks.map { block ->
			// уникальное имя файла на основе UUID блока
			val baseName = block.serializedId.toString()
			val codeFile: String?

			when (block.blockType) {
				BlockType.INPUT_DATA -> {
					// выбираем расширение по inputFormat
					val ext = when (block.inputFormat) {
						InputFormatType.JSON -> "json"
						InputFormatType.XML -> "xml"
						InputFormatType.YAML -> "yaml"
						else -> "txt"
					}
					val f = File(resourcesDir, "$baseName.$ext")
					f.writeText(block.code)
					codeFile = "${resourcesDir.name}/$baseName.$ext"
				}

				BlockType.PROPERTIES -> {
					// Сохраняем свойства как JSON в отдельный файл
					val f = File(resourcesDir, "$baseName.json")
					val propsMap = block.outputNames.mapIndexedNotNull { idx, name ->
						block.outputsData.getOrNull(idx)?.get(name)?.let { name to it }
					}.toMap()
					f.writeText(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(propsMap))
					codeFile = "${resourcesDir.name}/$baseName.json"
				}

				BlockType.SUB_PROJECT -> {
					val f = File(resourcesDir, "$baseName.json")
					f.writeText(
						ObjectMapper().writerWithDefaultPrettyPrinter()
							.writeValueAsString(block.subProjectProps)
					)
					codeFile = "${resourcesDir.name}/$baseName.json"
				}

				else -> {
					// для всех маппингов — по языку
					val ext = when (block.blockType) {
						BlockType.MAPPING_GROOVY -> "groovy"
						BlockType.MAPPING_PYTHON -> "py"
						BlockType.MAPPING_JAVA_SCRIPT -> "js"
						BlockType.FORM -> "html"
						else -> "txt"
					}
					val f = File(resourcesDir, "$baseName.$ext")
					f.writeText(block.code)
					codeFile = "${resourcesDir.name}/$baseName.$ext"

					if (block.dataDocs.isNotBlank()) {
						val docsF = File(resourcesDir, "$baseName.docs.md")
						docsF.writeText(block.dataDocs)
					}
				}
			}

			// создаём сериализуемый объект с путями
			BlockSerialized(
				id = block.serializedId,
				x = block.layoutX,
				y = block.layoutY,
				name = block.name,
				blockType = block.blockType.name,
				inputFormat = block.inputFormat,
				codeFile = codeFile,
				subProjectPath = block.subProjectPath,
				inputCount = block.inputCount,
				outputCount = block.outputCount,
				inputNames = block.inputNames.toList(),
				outputNames = block.outputNames.toList(),
				packagesNames = block.packagesNames,
			)
		}

		// пишем основной JSON
		val blocksData = BlocksData(serializedBlocks, connections.map { it.toSerialized() })
		file.writeText(
			ObjectMapper().writerWithDefaultPrettyPrinter()
				.writeValueAsString(blocksData)
		)
		clearDirty()
	}

	fun importBlocksFromFile(file: File) {
		suppressDirty = true
		try {
			currentProjectFile = file
			updateBlocks()
			val data: BlocksData = ObjectMapper().readValue(file, BlocksData::class.java)
			val projectDir = file.parentFile ?: File(".")
//		val resourcesDirName = "${file.nameWithoutExtension}_resources"
//		val resourcesDir = File(projectDir, resourcesDirName)

			// Очистка
			blocks.clear()
			connections.clear()
			(scrollPane.content as? Pane)?.children?.removeIf { it is BlockNode || it is Line }

			val idToBlock = mutableMapOf<UUID, BlockNode>()
			data.blocks.forEach { b ->
				val blockType = try {
					BlockType.valueOf(b.blockType)
				} catch (_: Exception) {
					BlockType.MAPPING_GROOVY
				}
				val defaultInputCount = when (blockType) {
					BlockType.START, BlockType.INPUT_DATA -> 0
					BlockType.SUB_PROJECT -> b.inputCount // берём как есть, потом adjustProjectNodeIO всё поправит
					else -> b.inputCount.coerceAtLeast(1)
				}
				val defaultOutputCount = when (blockType) {
					BlockType.EXIT -> 0
					BlockType.SUB_PROJECT -> b.outputCount
					else -> b.outputCount.coerceAtLeast(1)
				}
				// читаем код из файла, если указан
				val code = b.codeFile?.let { File(projectDir, it).readText() } ?: ""

				// создаём BlockNode как раньше, но передаём код и dataDocs
				val block = BlockNode(
					x = b.x,
					y = b.y,
					name = b.name,
					blockType = BlockType.valueOf(b.blockType),
					inputCount = defaultInputCount,
					outputCount = defaultOutputCount,
					serializedId = b.id,
					inputFormat = b.inputFormat ?: InputFormatType.JSON,
					code = code,        // загруженный из файла
					subProjectPath = b.subProjectPath ?: "",
					inputNames = b.inputNames?.toMutableList() ?: mutableListOf(),
					outputNames = b.outputNames?.toMutableList() ?: mutableListOf(),
					packagesNames = b.packagesNames ?: mutableListOf(),
				)
				if (block.blockType == BlockType.PROPERTIES && b.codeFile != null) {
					val propsFile = File(projectDir, b.codeFile)
					if (propsFile.exists() && propsFile.length() > 0) {
						try {
							val props: Map<String, Any> = ObjectMapper().readValue(propsFile, Map::class.java) as Map<String, Any>
							block.outputsData = props.entries.map { (k, v) ->
								mutableMapOf(k to v)
							}.toMutableList()
						} catch (_: Exception) {
							block.outputsData = mutableListOf()
						}
					} else {
						// файл пустой — инициализируем пустыми значениями
						block.outputsData = block.outputNames.map { name ->
							mutableMapOf<String, Any>(name to "")
						}.toMutableList()
					}
				}
				if (block.blockType == BlockType.SUB_PROJECT && b.codeFile != null) {
					val propsFile = File(projectDir, b.codeFile)
					if (propsFile.exists() && propsFile.length() > 0) {
						val props: Map<String, Any> = ObjectMapper().readValue(propsFile, Map::class.java) as Map<String, Any>
						block.subProjectProps = props.toMutableMap()
					}
				}
				block.onMove = {
					ensureBlockVisible(block)
					markDirty()
				}
				blocks.add(block)
				idToBlock[b.id] = block
				(scrollPane.content as? Pane)?.children?.add(block)
				block.rebuildCirclesHandlers { outIndex, outCircle ->
					outCircle.onMousePressed = EventHandler { event ->
						if (event.button == MouseButton.PRIMARY) {
							selectBlock(block)
							(scrollPane.content as? Pane)?.requestFocus()
							val (startX, startY) = block.outputPoint(outIndex)
							val line = Line(startX, startY, startX, startY).apply {
								stroke = Color.BLUE
								strokeWidth = 2.0
								viewOrder = 1.0
							}
							(scrollPane.content as? Pane)?.children?.add(line)
							draggingLine = line
							draggingFromBlock = block
							draggingFromOutputIndex = outIndex
							event.consume()
						}
					}
					outCircle.onMouseDragged = EventHandler { event ->
						if (event.button == MouseButton.PRIMARY && draggingLine != null) {
							val paneCoords = (scrollPane.content as? Pane)?.sceneToLocal(event.sceneX, event.sceneY)
							if (paneCoords != null) {
								draggingLine!!.endX = paneCoords.x
								draggingLine!!.endY = paneCoords.y
							}
							event.consume()
						}
					}
					outCircle.onMouseReleased = EventHandler { event ->
						if (event.button == MouseButton.PRIMARY && draggingLine != null) {
							val paneCoords = contentPane.sceneToLocal(event.sceneX, event.sceneY)
							val toBlockPair = blocks.asSequence()
								.flatMap { other ->
									other.inputCircles.mapIndexed { inputIdx, inputCircle ->
										Triple(
											other, inputCircle, inputIdx
										)
									}
								}.find { (other, inputCircle, _) ->
									if (other == draggingFromBlock) return@find false
									val p = inputCircle.localToScene(inputCircle.centerX, inputCircle.centerY)
									val panePoint = contentPane.sceneToLocal(p.x, p.y)
									if (panePoint == null || paneCoords == null) {
										return@find false
									}
									val dx = panePoint.x - paneCoords.x
									val dy = panePoint.y - paneCoords.y
									Math.hypot(dx, dy) <= inputCircle.radius + 4
								}
							if (toBlockPair != null && paneCoords != null) {
								val (toBlock, _, inputIdx) = toBlockPair
								val (startX, startY) = draggingFromBlock!!.outputPoint(draggingFromOutputIndex!!)
								val (endX, endY) = toBlock.inputPoint(inputIdx)
								draggingLine!!.startX = startX
								draggingLine!!.startY = startY
								draggingLine!!.endX = endX
								draggingLine!!.endY = endY
								val conn = Connection(
									draggingFromBlock!!, toBlock, draggingLine!!, draggingFromOutputIndex!!, inputIdx
								)
								connections.add(conn)
								draggingFromBlock!!.connectedLines.add(conn)
								toBlock.connectedLines.add(conn)
								markDirty()
								conn.line.onMouseClicked = EventHandler { onMouseEvent ->
									if (onMouseEvent.button == MouseButton.PRIMARY) {
										selectConnection(conn)
										(conn.line.parent as? Pane)?.requestFocus()
										onMouseEvent.consume()
									}
								}
								draggingLine = null
								draggingFromOutputIndex = null
							} else {
								contentPane.children?.remove(draggingLine)
								draggingLine = null
								draggingFromOutputIndex = null
							}
							event.consume()
						}
					}
				}
				if (block.blockType == BlockType.SUB_PROJECT && block.subProjectPath.isNotBlank()) {
					adjustProjectNodeIO(block, File(block.subProjectPath))
				}
			}

			// Восстановление соединений
			data.connections.forEach { c ->
				val fromBlock = idToBlock[c.fromId] ?: return@forEach
				val toBlock = idToBlock[c.toId] ?: return@forEach
				val outIdx = c.fromOutputIndex
				val inIdx = c.toInputIndex
				val (startX, startY) = fromBlock.outputPoint(outIdx)
				val (endX, endY) = toBlock.inputPoint(inIdx)
				val visibleLine = Line(startX, startY, endX, endY).apply {
					stroke = Color.BLUE
					strokeWidth = 2.0
				}
				val conn = Connection(fromBlock, toBlock, visibleLine, outIdx, inIdx)
				val pickLine = Line().apply {
					stroke = Color.TRANSPARENT
					strokeWidth = 12.0
					isPickOnBounds = false
					viewOrder = 0.9

					startXProperty().bind(visibleLine.startXProperty())
					startYProperty().bind(visibleLine.startYProperty())
					endXProperty().bind(visibleLine.endXProperty())
					endYProperty().bind(visibleLine.endYProperty())

					onMouseClicked = EventHandler { ev ->
						if (ev.button == MouseButton.PRIMARY) {
							selectConnection(conn)
							(parent as? Pane)?.requestFocus()
							ev.consume()
						}
					}
				}
				(scrollPane.content as? Pane)?.children?.addAll(pickLine, visibleLine)
				pickLine.toFront()
				visibleLine.toFront()
				connections.add(conn)
				fromBlock.connectedLines.add(conn)
				toBlock.connectedLines.add(conn)
				fromBlock.toFront()
				toBlock.toFront()
			}
		} finally {
			suppressDirty = false
			clearDirty()
			updateTitle()
		}
	}


	private fun saveOutputsData(projectFile: File) {
		val projectDir = projectFile.parentFile ?: File(".")
		val outputsDir = File(projectDir, "${projectFile.nameWithoutExtension}_outputs_data")
		outputsDir.mkdirs()
		val outputsMap = blocks.associate { it.serializedId.toString() to it.outputsData }
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
		if (!latestFile.exists()) {
			return
		}
		val typeRef = object : TypeReference<Map<String, List<Map<String, Any>>>>() {}
		val outputsMap = ObjectMapper().readValue(latestFile, typeRef)
		blocks.forEach { block ->
			if (block.blockType in arrayOf(BlockType.PROPERTIES, BlockType.SUB_PROJECT)) {
				return@forEach
			}
			outputsMap[block.serializedId.toString()]?.let { list ->
				block.outputsData = list.map { it.toMutableMap() }.toMutableList()
			}
		}
	}


	fun startConnectionFromBlock(block: BlockNode, outputIndex: Int) {
		draggingFromBlock = block
		draggingFromOutputIndex = outputIndex
		val (startX, startY) = block.outputPoint(outputIndex)
		val line = Line(startX, startY, startX, startY).apply {
			stroke = Color.BLUE
			strokeWidth = 2.0
			viewOrder = 1.0
		}
		(scrollPane.content as Pane).children.add(line)
		draggingLine = line
	}

	fun continueConnectionDrag(event: MouseEvent) {
		draggingLine?.let { line ->
			val paneCoords = (scrollPane.content as Pane).sceneToLocal(event.sceneX, event.sceneY)
			line.endX = paneCoords.x
			line.endY = paneCoords.y
		}
	}


	private fun updateBlocks() {
		// 1. Сохраняем положение камеры
		val hValue = scrollPane.hvalue
		val vValue = scrollPane.vvalue

		// 2. Меняем контент (или делаем что угодно с блоками)
		val grid = gridCanvas
		contentPane.children?.setAll(grid)
		blocks.forEach {
			contentPane.children?.add(it)
		}

		Platform.runLater {
			scrollPane.hvalue = hValue
			scrollPane.vvalue = vValue
		}
	}


	private fun drawGrid(canvas: Canvas, gridSize: Double = 10.0, boldStep: Int = 5) {
		val gc = canvas.graphicsContext2D
		gc.clearRect(0.0, 0.0, canvas.width, canvas.height)
		val w = canvas.width
		val h = canvas.height

		// Сетка 10×10 — полупрозрачная
		gc.stroke = Color.rgb(180, 180, 180, 0.25)
		gc.lineWidth = 1.0
		var x = 0.0
		while (x <= w) {
			gc.strokeLine(x, 0.0, x, h)
			x += gridSize
		}
		var y = 0.0
		while (y <= h) {
			gc.strokeLine(0.0, y, w, y)
			y += gridSize
		}

		// Сетка 50×50 — более видимая (каждая 5-я линия)
		gc.stroke = Color.rgb(120, 120, 120, 0.5)
		gc.lineWidth = 2.0
		x = 0.0
		while (x <= w) {
			if ((x / gridSize) % boldStep == 0.0) {
				gc.strokeLine(x, 0.0, x, h)
			}
			x += gridSize
		}
		y = 0.0
		while (y <= h) {
			if ((y / gridSize) % boldStep == 0.0) {
				gc.strokeLine(0.0, y, w, y)
			}
			y += gridSize
		}
		gc.lineWidth = 1.0 // возвращаем обратно
	}

	// для gridCanvas и contentPane
	private fun setupContextMenu() {
		val showMenuHandler = EventHandler<MouseEvent> { event ->
			if (event.button == MouseButton.SECONDARY) {
				// Не показывать меню, если клик по блокам (BlockNode или Circle)
				val node = event.pickResult.intersectedNode
				if (node is BlockNode || node is Circle) return@EventHandler
				event.consume()
			}
		}
		contentPane.onMousePressed = showMenuHandler
		gridCanvas.onMousePressed = showMenuHandler
		gridCanvas.isMouseTransparent = true
	}


	fun setupHandlersForBlock(block: BlockNode) {
		// Для каждого выходного кружка
		block.outputCircles.forEachIndexed { outputIdx, outCircle ->
			outCircle.onMousePressed = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					setPressCoords(block, block.layoutX, block.layoutY)
					selectBlock(block)
					contentPane.requestFocus()
					val (startX, startY) = block.outputPoint(outputIdx)
					val line = Line(startX, startY, startX, startY).apply {
						stroke = Color.BLUE
						strokeWidth = 2.0
						viewOrder = 1.0
					}
					contentPane.children?.add(line)
					draggingLine = line
					draggingFromBlock = block
					draggingFromOutputIndex = outputIdx
					event.consume()
				}
				block.toFront()
			}
			outCircle.onMouseDragged = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY && draggingLine != null) {
					val paneCoords = contentPane.sceneToLocal(event.sceneX, event.sceneY)
					if (paneCoords != null) {
						draggingLine!!.endX = paneCoords.x
						draggingLine!!.endY = paneCoords.y
					}
					event.consume()
				}
			}
			outCircle.onMouseReleased = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY && draggingLine != null) {
					val paneCoords = contentPane.sceneToLocal(event.sceneX, event.sceneY)
					// Найти input-кружок под курсором
					val toBlockPair = blocks.asSequence().flatMap { other ->
						other.inputCircles.mapIndexed { inputIdx, inputCircle ->
							Triple(other, inputCircle, inputIdx)
						}
					}.find { (other, inputCircle, _) ->
						if (other == draggingFromBlock) {
							return@find false
						}
						val scenePoint = inputCircle.localToScene(inputCircle.centerX, inputCircle.centerY)
						val panePoint = contentPane.sceneToLocal(scenePoint.x, scenePoint.y)
						if (paneCoords == null || panePoint == null) return@find false
						val dx = panePoint.x - paneCoords.x
						val dy = panePoint.y - paneCoords.y
						Math.hypot(dx, dy) <= inputCircle.radius + 4
					}
					if (toBlockPair != null && paneCoords != null) {
						val (toBlock, _, inputIdx) = toBlockPair
						val (startX, startY) = draggingFromBlock!!.outputPoint(draggingFromOutputIndex!!)
						val (endX, endY) = toBlock.inputPoint(inputIdx)
						val visibleLine = draggingLine!!.apply {
							this.startX = startX
							this.startY = startY
							this.endX = endX
							this.endY = endY
						}
						val conn = Connection(
							draggingFromBlock!!,
							toBlock,
							visibleLine,
							draggingFromOutputIndex!!,
							inputIdx
						)
						val pickLine = Line().apply {
							stroke = Color.TRANSPARENT
							strokeWidth = 12.0
							isPickOnBounds = false
							viewOrder = 0.9
							startXProperty().bind(visibleLine.startXProperty())
							startYProperty().bind(visibleLine.startYProperty())
							endXProperty().bind(visibleLine.endXProperty())
							endYProperty().bind(visibleLine.endYProperty())
							onMouseClicked = EventHandler { ev ->
								if (ev.button == MouseButton.PRIMARY) {
									selectConnection(conn)
									(parent as? Pane)?.requestFocus()
									ev.consume()
								}
							}
						}
						(scrollPane.content as? Pane)?.children?.add(pickLine)
						pickLine.toFront()
						visibleLine.toFront()
						connections.add(conn)
						draggingFromBlock!!.connectedLines.add(conn)
						toBlock.connectedLines.add(conn)
						draggingLine = null
						draggingFromOutputIndex = null
					} else {
						contentPane.children?.remove(draggingLine)
						draggingLine = null
						draggingFromOutputIndex = null
					}
					event.consume()
				}
			}
		}
	}


	private fun isDataEmpty(data: Any?): Boolean {
		return when (data) {
			null -> true
			is Map<*, *> -> data.isEmpty()
			is Collection<*> -> data.isEmpty()
			is String -> data.isEmpty()
			else -> false
		}
	}


	private fun runButtonHandler() {
		springProcess?.destroy()
		springProcess = null
		startSpringBoot("./spring-app/test-spring-0.0.1-SNAPSHOT.jar")
		// Запускаем весь оркестратор НЕ на FX-потоке, чтобы UI не подвисал,
		// а Platform.runLater обновлял рамку "executing" в реальном времени.
		Thread {
			// Готовим граф зависимостей (как у вас)
			val incoming = mutableMapOf<BlockNode, MutableSet<BlockNode>>()
			val outgoing = mutableMapOf<BlockNode, MutableList<BlockNode>>()
			blocks.forEach {
				incoming[it] = mutableSetOf()
			}
			connections.forEach { conn ->
				incoming[conn.to]!!.add(conn.from)
				outgoing.computeIfAbsent(conn.from) { mutableListOf() }.add(conn.to)
			}

			// Уже выполненные узлы
			val executed = ConcurrentHashMap.newKeySet<BlockNode>()

			// Вспомогательная фаза: параллельный запуск всех узлов,
			// которые можно выполнить при данном множестве `executed`.
			fun runPhase(exclude: Set<BlockNode>) {
				// Кандидаты этой фазы (не выполнены и не исключены — например, узлы цикла)
				val candidates = blocks.filter { it !in executed && it !in exclude }.toSet()
				if (candidates.isEmpty()) return

				// Счётчик "оставшихся невыполненных родителей" для каждого кандидата
				val deps = ConcurrentHashMap<BlockNode, AtomicInteger>()
				candidates.forEach { node ->
					val notDoneParents = incoming[node]?.count { it !in executed } ?: 0
					deps[node] = AtomicInteger(notDoneParents)
				}

				// Очередь готовых к старту
				val ready = ConcurrentLinkedQueue<BlockNode>()
				candidates.forEach { if (deps[it]!!.get() == 0) ready.add(it) }

				// Если ни одного узла запустить нельзя — выходим из фазы
				if (ready.isEmpty()) return

				// Счётчик активных задач, чтобы корректно дождаться завершения фазы
				val inFlight = AtomicInteger(0)
				val done = CountDownLatch(1)

				fun submitNode(node: BlockNode) {
					inFlight.incrementAndGet()
					executor.submit {
						// Показываем "красную" обводку
						Platform.runLater { node.executing = true }
						try {
							runBlock(node) // ваш существующий синхронный вызов
						} finally {
							// Снимаем "красную" обводку
							Platform.runLater { node.executing = false }
							executed.add(node)

							// Освобождаем потомков
							outgoing[node]?.forEach { child ->
								if (child in candidates) {
									val left = deps[child]!!.decrementAndGet()
									if (left == 0) {
										ready.add(child)
									}
								}
							}

							// Если мы были последними и очередь пуста — закрываем фазу
							if (inFlight.decrementAndGet() == 0 && ready.isEmpty()) {
								done.countDown()
							}
						}
					}
				}

				// Раздаём стартовые задачи
				while (true) {
					val n = ready.poll() ?: break
					submitNode(n)
				}

				// Координатор: пока в процессе появляются новые готовые узлы — запускаем их
				while (inFlight.get() > 0 || !ready.isEmpty()) {
					var scheduled = false
					while (true) {
						val n = ready.poll() ?: break
						submitNode(n)
						scheduled = true
					}
					if (!scheduled && inFlight.get() > 0) {
						// Немного уступим CPU, чтобы дети успели попасть в очередь
						Thread.sleep(5)
					}
				}

				// На случай гоночных условий — ждём явного сигнала окончания
				done.await()
			}

			// 1) Находим один цикл, как у вас
			val cycle = findFirstCycle()
			val cycleSet = cycle?.toSet() ?: emptySet()

			// 2) Фаза 1: параллельно выполняем всё вне цикла, что уже готово (корни DAG и т.д.)
			runPhase(exclude = cycleSet)

			// 3) Узлы цикла — по вашей логике (оставлено последовательно)
			if (!cycle.isNullOrEmpty()) {
				while (true) {
					for (b in cycle) {
						Platform.runLater { b.executing = true }
						runBlock(b)
						Platform.runLater { b.executing = false }
						executed.add(b) // помечаем, чтобы дети разблокировались в следующей фазе
					}

					// Ваше исходное условие завершения цикла
					val shouldBreak = cycle.all { block ->
						val pairs = block.connectedLines
							.filter { it.to != block }
							.filter { it.to in cycleSet }
							.map { Pair(it.to, it.from) }
						pairs.all { p ->
							val to = p.first
							val from = p.second
							val list = List(to.connectedLines.filter { it.to == from }.size) { idx -> idx }
							to.outputsData
								.filterIndexed { index, _ -> index in list }
								.all { it.isEmpty() }
						}
					}
					if (shouldBreak) break
				}
			}

			// 4) Фаза 2: после завершения цикла параллелим всё остальное (дети узлов цикла и т.д.)
			runPhase(exclude = emptySet())

			// 5) Сохраняем результаты
			currentProjectFile?.let { saveOutputsData(it) }
		}.start()
	}


	private fun runBlock(block: BlockNode) {
		if (block.blockType !in arrayOf(BlockType.SUB_PROJECT, BlockType.PROPERTIES)) {
			block.outputsData = mutableListOf()
		}
		when (block.blockType) {
			BlockType.MAPPING_GROOVY -> {
				val inputDataMap = HashMap<String, Any>()
				block.connectedLines
					.filter { it.to == block }
					.sortedBy { it.toPort }
					.forEach { c ->
						inputDataMap[block.inputNames[c.toPort]] = c.from.outputsData.getOrNull(c.fromPort)
							?: mutableMapOf<String, Any>()
					}

				// создаём пустые выходы под все outputNames
				val outputs = block.outputNames.associateWith { mutableMapOf<String, Any>() }.toMutableMap()
				inputDataMap.putAll(outputs)
				try {
					block.code.runGroovyScript(inputDataMap)
					// гарантируем наличие данных под каждый выход
					block.outputsData = block.outputNames.map { name ->
						outputs[name] ?: mutableMapOf()
					}.toMutableList()
				} catch (e: Exception) {
					System.err.println("${e.localizedMessage}\n${e.stackTraceToString()}")
					Platform.runLater {
						Alert(Alert.AlertType.ERROR).apply {
							title = block.name
							contentText = "Exception in ${block.name}:\n${e.localizedMessage}"
							showAndWait()
						}
					}
				}
			}

			BlockType.MAPPING_PYTHON -> {
				val inputDataMap = HashMap<String, Any>()
				block.connectedLines
					.filter { it.to == block }
					.sortedBy { it.toPort }
					.forEach { c ->
						inputDataMap[block.inputNames[c.toPort]] =
							c.from.outputsData.getOrNull(c.fromPort) ?: mutableMapOf<String, Any>()
					}
				val outputs = block.outputNames.associateWith { mutableMapOf<String, Any>() }.toMutableMap()
				inputDataMap.putAll(outputs)
				try {
					val pyOutputs = block.code.runPythonScript(block, inputDataMap, outputs)
					block.outputsData = block.outputNames.map { name ->
						(pyOutputs[name] as? MutableMap<String, Any>) ?: outputs[name] ?: mutableMapOf()
					}.toMutableList()
				} catch (e: Exception) {
					System.err.println("${e.localizedMessage}\n${e.stackTraceToString()}")
					Platform.runLater {
						Alert(Alert.AlertType.ERROR).apply {
							title = "Ошибка"
							contentText = e.localizedMessage
							showAndWait()
						}
					}
				}
			}

			BlockType.MAPPING_JAVA_SCRIPT -> {
				val inputs = HashMap<String, Any>()
				block.connectedLines
					.filter { it.to == block }
					.sortedBy { it.toPort }
					.forEach { c ->
						inputs[block.inputNames[c.toPort]] = c.from.outputsData[c.fromPort]
					}
				val outputNames = (0 until block.outputCount)
					.map { index -> block.outputNames[index] }
				try {
					val result = block.code.runJavaScript(inputs, outputNames)
					outputNames.forEach { name ->
						block.outputsData.add(result[name] as? MutableMap<String, Any> ?: mutableMapOf())
					}
				} catch (e: Exception) {
					System.err.println("${e.localizedMessage}\n${e.stackTraceToString()}")
					Platform.runLater {
						Alert(Alert.AlertType.ERROR).apply {
							title = "Ошибка"
							contentText = e.localizedMessage
							showAndWait()
						}
					}
				}
			}

			BlockType.CONNECTOR -> {
				Platform.runLater {
					Alert(Alert.AlertType.ERROR).apply {
						title = "CONNECTOR еще не поддерживается :("
						contentText = "CONNECTOR еще не поддерживается :("
						showAndWait()
					}
				}
			}

			BlockType.INPUT_DATA -> {
				val parsed: MutableMap<String, Any> = try {
					when (block.inputFormat) {
						InputFormatType.JSON -> ObjectMapper().readValue(
							block.code,
							MutableMap::class.java
						) as MutableMap<String, Any>

						InputFormatType.YAML -> Yaml().load(block.code) as? MutableMap<String, Any> ?: mutableMapOf()

						InputFormatType.XML -> XmlMapper().readValue(
							block.code,
							MutableMap::class.java
						) as MutableMap<String, Any>

						InputFormatType.PROTOBUF -> TODO("поддержите при необходимости")
					}
				} catch (ex: Exception) {
					mutableMapOf("_parseError" to ex.message.toString())
				}
				if (parsed.isEmpty() && block.code.isNotBlank()) parsed["data"] = block.code
				block.outputsData = mutableListOf(parsed)
			}

			BlockType.START -> {
				val map = mutableMapOf<String, Any>()
				if (block.code.isNotBlank()) {
					map["trigger"] = block.code.trim()
				}
				block.outputsData = mutableListOf(map)
			}

			BlockType.EXIT -> {}

			BlockType.SUB_PROJECT -> {
				val file = File(block.subProjectPath)
				if (file.exists()) {
					// собираем входные данные: либо subProjectProps, либо реальные inputs
					val inputs: MutableList<MutableMap<String, Any>> = mutableListOf()
					if (block.subProjectProps.isNotEmpty()) {
						inputs.addAll(block.subProjectProps.map { (k, v) -> mutableMapOf(k to v) })
					} else {
						// берем данные от соединённых блоков
						block.connectedLines
							.filter { it.to == block }
							.sortedBy { it.toPort }
							.forEach { c ->
								val value = c.from.outputsData.getOrNull(c.fromPort) ?: mutableMapOf()
								inputs.add(value)
							}
					}
					// если вообще ничего нет, но у блока есть входы — инициализируем пустыми картами
					if (inputs.isEmpty() && block.inputCount > 0) {
						repeat(block.inputCount) { inputs.add(mutableMapOf()) }
					}

					// пробрасываем на выходы подпроекта
					block.outputsData = inputs

					// запускаем сам подпроект
					val outs = runSubProject(file, block)
					block.outputsData = outs.toMutableList() // наружу — результаты
				} else {
					block.outputsData = MutableList(block.outputCount) { mutableMapOf() }
				}
			}

			BlockType.PROPERTIES -> {
				// Убедимся, что есть outputsData под все выходы
				if (block.outputsData.size < block.outputNames.size) {
					repeat(block.outputNames.size - block.outputsData.size) {
						block.outputsData.add(mutableMapOf())
					}
				}
				// Для каждого выхода: кладём значение по имени
				block.outputsData = block.outputNames.mapIndexed { idx, name ->
					val existing = block.outputsData.getOrNull(idx)?.get(name)
					val value = existing ?: ""  // если ничего не было, оставляем пустую строку
					mutableMapOf(name to value)
				}.toMutableList()
			}

			BlockType.FORM -> {
				// генерируем путь на основе имени блока
				val endpointPath = "/${block.name.lowercase().replace("\\s+".toRegex(), "_")}"

				// если код начинается с "<", считаем что это HTML-страница
				if (block.code.trim().startsWith("<")) {
					// HTML
					createDynamicThymeleafEndpoint(endpointPath, block)
				} else {
					// JSON или текст
					val response: Map<String, Any> = try {
						ObjectMapper().readValue(block.code, Map::class.java) as Map<String, Any>
					} catch (_: Exception) {
						mapOf("result" to block.code)
					}
					createDynamicRestEndpoint(endpointPath, response)
				}
			}

		}
	}


	private fun ensureBlockVisible(@Suppress("UNUSED_PARAMETER") trigger: BlockNode? = null) {
		val margin = 200.0
		if (blocks.isEmpty()) return

		// --- 1) Экстенты «мира» в логических координатах блоков
		val minX = blocks.minOf { it.layoutX }
		val minY = blocks.minOf { it.layoutY }
		val maxX = blocks.maxOf { it.layoutX + it.boundsInLocal.width }
		val maxY = blocks.maxOf { it.layoutY + it.boundsInLocal.height }

		// --- 2) Если есть отрицательные координаты — сдвигаем визуально весь «мир» внутрь положительной области
		val newOffsetX = if (minX < 0.0) -minX + margin else 0.0
		val newOffsetY = if (minY < 0.0) -minY + margin else 0.0

		if (newOffsetX != worldOffsetX || newOffsetY != worldOffsetY) {
			worldOffsetX = newOffsetX
			worldOffsetY = newOffsetY
			// Сдвигаем все узлы рабочего поля (кроме сетки) одним translate,
			// не трогая их layoutX/layoutY и биндинги линий.
			contentPane.children.forEach { node ->
				if (node !== gridCanvas) {
					node.translateX = worldOffsetX
					node.translateY = worldOffsetY
				}
			}
		}

		// --- 3) Реально расширяем скроллируемую область под ВЕСЬ диапазон (и слева/сверху, и справа/снизу)
		val widthNeeded  = (maxX - minX) + 2 * margin
		val heightNeeded = (maxY - minY) + 2 * margin

		// Правый край: если тащим вправо — maxX растёт, widthNeeded растёт → расширяем prefWidth
		if (widthNeeded > contentPane.prefWidth) {
			contentPane.prefWidth = widthNeeded
		}
		// Нижний край: аналогично для высоты
		if (heightNeeded > contentPane.prefHeight) {
			contentPane.prefHeight = heightNeeded
		}
	}


	private fun findFirstCycle(): List<BlockNode>? {
		val visited = mutableSetOf<BlockNode>()
		val stack = mutableListOf<BlockNode>()
		fun dfs(current: BlockNode): List<BlockNode>? {
			if (current in stack) {
				val idx = stack.indexOf(current)
				return stack.subList(idx, stack.size).toList()
			}
			if (current in visited) return null
			visited.add(current)
			stack.add(current)
			val nextBlocks = connections.filter { it.from == current }.map { it.to }
			for (next in nextBlocks) {
				val result = dfs(next)
				if (result != null) return result
			}
			stack.removeAt(stack.size - 1)
			return null
		}
		for (block in blocks) {
			stack.clear()
			val cycle = dfs(block)
			if (!cycle.isNullOrEmpty()) return cycle
		}
		return null
	}


	private fun String.runGroovyScript(bindings: Map<String, Any?> = emptyMap()): Any? {
		val cl = this::class.java.classLoader
		val shell = GroovyShell(cl)
		val binding = shell.context
		for ((k, v) in bindings) {
			binding.setProperty(k, v)
		}
		return shell.evaluate(this)
	}


	private fun String.runJavaScript(
		inputs: Map<String, Any?> = emptyMap(),
		outputNames: List<String> = emptyList(),
	): Map<String, Map<String, Any?>> {
		val context = Context.newBuilder("js").allowAllAccess(true).build()

		fun Any?.toJsFriendly(ctx: Context): Any? {
			return when (this) {
				null -> null
				is Map<*, *> -> this.mapValues { (_, v) -> v.toJsFriendly(ctx) }
				is List<*> -> ctx.asValue(this.map { it.toJsFriendly(ctx) }.toTypedArray())
				else -> this
			}
		}

		fun Value.toKotlin(): Any? {
			fun Any?.deepUnwrap(): Any? = when (this) {
				is Value -> this.toKotlin()            // раскрутить Value
				is Map<*, *> -> this.mapValues { (_, v) -> v.deepUnwrap() }
					.toMutableMap()

				is List<*> -> this.map { it.deepUnwrap() }
				else -> this                       // примитивы
			}

			return when {
				isNull -> null
				isBoolean -> asBoolean()
				isNumber -> when {
					fitsInInt() -> asInt()
					fitsInLong() -> asLong()
					fitsInDouble() -> asDouble()
					else -> asDouble()
				}

				isString -> asString()

				hasArrayElements() ->
					(0 until arraySize.toInt())
						.map { getArrayElement(it.toLong()).deepUnwrap() }

				isHostObject -> {
					when (val host: Any? = asHostObject<Any?>()) {
						is Map<*, *> -> host.mapValues { (_, v) -> v.deepUnwrap() }.toMutableMap()

						is List<*> -> host.map { it.deepUnwrap() }
						else -> host
					}
				}

				hasMembers() -> memberKeys.associateWith { getMember(it).deepUnwrap() }.toMutableMap()

				else -> throw RuntimeException("Unsupported Value type")
			}
		}

		inputs.forEach { (k, v) ->
			context.getBindings("js").putMember(k, v.toJsFriendly(context))
		}
		val outputs = mutableMapOf<String, MutableMap<String, Any?>>()
		for (name in outputNames) {
			if (inputs.containsKey(name)) {
				error("Имя «$name» уже занято входным параметром")
			}
			val m = mutableMapOf<String, Any?>()
			outputs[name] = m
			context.getBindings("js").putMember(name, ProxyObject.fromMap(m))
		}
		context.eval("js", this)
		val cleaned = outputs.mapValues { (_, inner) ->
			inner.mapValues { (_, v) ->
				if (v is Value) v.toKotlin() else v
			}.toMutableMap()
		}
		return cleaned
	}


	private fun String.runPythonScript(
		block: BlockNode,
		bindings: Map<String, Any?> = emptyMap(),
		outputs: MutableMap<String, MutableMap<String, Any>>
	): Map<String, Any?> {
		val tmp = "run/python/${block.serializedId}_${block.hashCode()}"
		val venvDirPath = Paths.get("").toAbsolutePath().resolve(tmp).apply { Files.createDirectories(this) }
		val venvDir = venvDirPath.absolutePathString()
		ProcessBuilder(PYTHON, "-m", "venv", venvDir)
			.redirectErrorStream(true)
			.start()
			.waitFor()
		val isWindows = System.getProperty("os.name").startsWith("Windows")
		val pipPath = if (isWindows) {
			"${venvDir}/Scripts/pip.exe"
		} else {
			"${venvDir}/bin/pip"
		}
		if (block.packagesNames.isNotEmpty()) {
			val pipProc = ProcessBuilder(pipPath, "install", *block.packagesNames.toTypedArray())
				.redirectErrorStream(true)
				.start()
			pipProc.waitFor()
		}
		val pythonPath = if (isWindows) {
			"$venvDir/Scripts/python.exe"
		} else {
			"$venvDir/bin/python"
		}
		val paramsJson = ObjectMapper().writeValueAsString(bindings)
		val fullScript = """
				|import os, json
				|params = json.loads(os.environ.get("$PYTHON_PARAMS_VARIABLE", "{}"))
				|locals().update(params)
				|
				|${this}
				|
				|print(json.dumps({${outputs.map { "\"${it.key}\": ${it.key}" }.joinToString(", ")}}))
				""".trimMargin()
		val scriptPath = File(venvDir, "script.py").apply { writeText(fullScript, StandardCharsets.UTF_8) }
		val pythonProc = ProcessBuilder(pythonPath, scriptPath.toString())
			.redirectErrorStream(true)
			.apply {
				environment()[PYTHON_PARAMS_VARIABLE] = paramsJson
				environment()["PYTHONIOENCODING"] = "utf-8"
			}
			.start()
		val readText = pythonProc.inputStream.bufferedReader().readText()
		File(venvDir).deleteRecursively()
		val lastLine = readText.lines().lastOrNull { it.trim().startsWith("{") && it.trim().endsWith("}") }
		val result: Map<String, Any?> = if (lastLine != null) {
			ObjectMapper().readValue(lastLine)
		} else {
			if (readText.startsWith("Traceback")) {
				throw RuntimeException(readText)
			}
			emptyMap()
		}
		return result
	}


	/**
	 * Выполняет подпроект так же, как runButtonHandler(),
	 * но полностью «в памяти» и без GUI.
	 * @return список карт для вывода из EXIT-блока в том же порядке,
	 *         в каком они сконфигурированы у SUB_PROJECT-ноды.
	 */
	private fun runSubProject(file: File, parentBlock: BlockNode): List<MutableMap<String, Any>> {
		val data: BlocksData = ObjectMapper().readValue(file, BlocksData::class.java)
		val subDir = file.parentFile
		val om = ObjectMapper()

		/* --- 1. Строим внутренние BlockNode без UI --- */
		val idToBlock = mutableMapOf<UUID, BlockNode>()
		val blocks = data.blocks.map { b ->
			val codeText = b.codeFile?.let { File(subDir, it).readText() } ?: b.codeFile.orEmpty()
			val bn = BlockNode(
				x = 0.0, y = 0.0,
				name = b.name,
				blockType = BlockType.valueOf(b.blockType),
				code = codeText,
				inputCount = b.inputCount,
				outputCount = b.outputCount,
				serializedId = b.id,
				inputFormat = b.inputFormat ?: InputFormatType.JSON,
				subProjectPath = b.subProjectPath ?: "",
				inputNames = b.inputNames?.toMutableList() ?: MutableList(b.inputCount) { "in$it" },
				outputNames = b.outputNames?.toMutableList() ?: MutableList(b.outputCount) { "out$it" },
				outputsData = mutableListOf()
			)
			idToBlock[b.id] = bn
			bn
		}.toMutableList()

		/* --- 1.1. Загружаем PROPERTIES из ресурсов подпроекта --- */
		data.blocks.forEach { b ->
			if (BlockType.valueOf(b.blockType) == BlockType.PROPERTIES && b.codeFile != null) {
				val bn = idToBlock[b.id] ?: return@forEach
				val propsFile = File(subDir, b.codeFile)
				bn.outputsData = if (propsFile.exists() && propsFile.length() > 0) {
					try {
						val props: Map<String, Any> = ObjectMapper().readValue(propsFile, Map::class.java) as Map<String, Any>
						bn.outputNames.map { name -> mutableMapOf<String, Any>(name to (props[name] ?: "")) }.toMutableList()
					} catch (_: Exception) {
						bn.outputNames.map { name -> mutableMapOf<String, Any>(name to "") }.toMutableList()
					}
				} else {
					bn.outputNames.map { name -> mutableMapOf<String, Any>(name to "") }.toMutableList()
				}
			}
		}

		/* --- 1.5. Пробрасываем свойства из parentBlock ТОЛЬКО по ИМЕНАМ, без обнуления --- */
		val propsByName: Map<String, Any> = parentBlock.outputsData
			.flatMap { it.entries }
			.associate { it.key to it.value }

		blocks.filter { it.blockType == BlockType.PROPERTIES }.forEach { propBlock ->
			// гарантируем размер outputsData = числу выходов
			if (propBlock.outputsData.size < propBlock.outputNames.size) {
				repeat(propBlock.outputNames.size - propBlock.outputsData.size) {
					propBlock.outputsData.add(mutableMapOf())
				}
			}
			propBlock.outputNames.forEachIndexed { idx, propName ->
				val v = propsByName[propName]
				if (v != null) {
					propBlock.outputsData[idx] = mutableMapOf(propName to v)
				}
				// если v == null — оставляем значение из ресурсов
			}
		}

		/* --- 2. Конвертируем соединения --- */
		val connections = data.connections.map { c ->
			Connection(
				from = idToBlock[c.fromId]!!,
				to = idToBlock[c.toId]!!,
				line = Line(),   // GUI не нужен
				fromPort = c.fromOutputIndex,
				toPort = c.toInputIndex
			)
		}.toMutableList()

		connections.forEach { c ->
			c.from.connectedLines.add(c)
			c.to.connectedLines.add(c)
		}

		/* --- 3. Передаём входы из внешнего блока внутрь подпроекта --- */
		// Передагаем по совпадению имён: in0 -> first INPUT_DATA / START, и т.д.
		val entryBlocks = blocks.filter {
			when (it.blockType) {
				BlockType.START -> true
				BlockType.INPUT_DATA -> it.code.isBlank() // пустой INPUT_DATA используем как внешний вход
				else -> false
			}
		}.toMutableList()

		// сопоставляем каждый inputName из SUB_PROJECT с entryBlock
		val entryByPort = entryBlocks.withIndex().associate { (i, b) ->
			parentBlock.inputNames.getOrNull(i) to b
		}

		parentBlock.connectedLines
			.filter { it.to == parentBlock }
			.sortedBy { it.toPort }
			.forEach { conn ->
				val portName = parentBlock.inputNames.getOrNull(conn.toPort)
				val target = entryByPort[portName] ?: entryBlocks.getOrNull(conn.toPort)
				val src = conn.from.outputsData.getOrNull(conn.fromPort) ?: return@forEach

				if (target != null) {
					// если это START — записываем карту напрямую
					target.outputsData = mutableListOf(src.toMutableMap())
				}
			}

		// если входов в подпроекте меньше, чем у блока-обёртки — создаём «заглушки»
		val inputStubsNeeded = (parentBlock.inputCount - entryBlocks.size).coerceAtLeast(0)
		repeat(inputStubsNeeded) {
			val stub = BlockNode(
				x = 0.0, y = 0.0,
				name = "EXT_IN$it",
				blockType = BlockType.INPUT_DATA,
				inputCount = 0,
				outputCount = 1,
				inputFormat = InputFormatType.JSON,
				outputsData = mutableListOf()
			)
			blocks.add(stub)
			entryBlocks.add(stub)
		}

		/* --- 4. Вспомогалки --- */
		fun isDataEmpty(data: Any?): Boolean = when (data) {
			null -> true
			is Map<*, *> -> data.isEmpty()
			is Collection<*> -> data.isEmpty()
			is String -> data.isEmpty()
			else -> false
		}

		fun execute(b: BlockNode) {
			// если это INPUT/START и данные уже проставлены вручную – оставляем как есть
			if (b.blockType in arrayOf(BlockType.INPUT_DATA, BlockType.START) && b.outputsData.isNotEmpty()) {
				return
			}
			// Для PROPERTIES не сбрасываем данные!
			if (b.blockType != BlockType.PROPERTIES) {
				b.outputsData = mutableListOf()
			}
			when (b.blockType) {
				BlockType.MAPPING_GROOVY,
				BlockType.MAPPING_JAVA_SCRIPT,
				BlockType.MAPPING_PYTHON,
				BlockType.CONNECTOR,
				BlockType.INPUT_DATA,
				BlockType.START -> runBlock(b)

				BlockType.SUB_PROJECT -> {
					val f = File(b.subProjectPath)
					if (f.exists()) {
						// Рекурсия разрешена: просто заходим внутрь ещё раз
						val outs = runSubProject(f, b)
						b.outputsData = outs.toMutableList()
					} else {
						b.outputsData = MutableList(b.outputCount) { mutableMapOf() }
					}
				}

				BlockType.PROPERTIES -> { /* уже загружены/проброшены */
				}

				BlockType.EXIT -> { /* не исполняем явно */
				}

				BlockType.FORM -> {}
			}
		}

		// Быстрый доступ к ребрам
		val incoming = mutableMapOf<BlockNode, MutableList<Connection>>().apply {
			blocks.forEach { this[it] = mutableListOf() }
			connections.forEach { c -> this[c.to]!!.add(c) }
		}

		/* --- 4.1 Подпись входов блока: JSON от упорядоченных по toPort карт родителя --- */
		val lastSig = mutableMapOf<BlockNode, String?>()
		fun inputSignature(b: BlockNode): String {
			val ins = incoming[b]!!.sortedBy { it.toPort }.map { c ->
				@Suppress("UNCHECKED_CAST")
				(c.from.outputsData.getOrNull(c.fromPort) as? Map<String, Any>) ?: emptyMap()
			}
			return om.writeValueAsString(ins)
		}

		/* --- 4.2 Итеративный планировщик (фикс-пойнт) вместо топологической сортировки --- */
		while (true) {
			var progressed = false

			for (b in blocks) {
				if (b.blockType == BlockType.EXIT) continue

				val ins = incoming[b]!!
				val anyReady =
					ins.isEmpty() || ins.any { c ->
						val src = c.from
						val portOk = src.outputsData.size > c.fromPort
						val hasData = portOk && !isDataEmpty(src.outputsData[c.fromPort])
						// узлы START/INPUT_DATA считаем готовыми источниками
						hasData || src.blockType in arrayOf(BlockType.START, BlockType.INPUT_DATA)
					}

				if (!anyReady) continue

				val sig = inputSignature(b)
				if (lastSig[b] != sig) {
					// входы изменились — исполняем
					execute(b)
					lastSig[b] = sig
					progressed = true
				}
			}

			if (!progressed) break // стабилизация: больше ничего не меняется
		}

		/* --- 5. Собираем выходы из EXIT-блоков (в порядке по координатам, как у тебя) --- */
		val exitBlocks = blocks
			.filter { it.blockType == BlockType.EXIT }
			.sortedWith(compareBy<BlockNode> { it.layoutY }.thenBy { it.layoutX })

		val exitOutputs = exitBlocks.map { ex ->
			val merged = mutableMapOf<String, Any>()
			connections.filter { it.to == ex }
				.sortedBy { it.toPort }
				.forEach { conn ->
					val src = conn.from.outputsData.getOrNull(conn.fromPort) as? Map<*, *>
					if (src != null) merged.putAll(src as Map<String, Any>)
				}
			merged
		}.toMutableList()

		while (exitOutputs.size < parentBlock.outputCount) {
			exitOutputs.add(mutableMapOf())
		}
		return exitOutputs.take(parentBlock.outputCount)
	}


	private fun adjustProjectNodeIO(node: BlockNode, file: File) {
		val data: BlocksData = ObjectMapper().readValue(file, BlocksData::class.java)

		// входами считаем START + INPUT_DATA без кода
		val inputBlocks = data.blocks.filter {
			it.blockType == "START" || (it.blockType == "INPUT_DATA" && it.codeFile.isNullOrBlank())
		}
		val outputBlocks = data.blocks.filter { it.blockType == "EXIT" }

		val newInputs = inputBlocks.size.coerceAtLeast(1)
		val newOutputs = outputBlocks.size.coerceAtLeast(1)

		node.updatePorts(newInputs, newOutputs)

		node.inputNames = inputBlocks.map { it.name.ifBlank { "in" } }.toMutableList()
		node.outputNames = outputBlocks.map { it.name.ifBlank { "out" } }.toMutableList()

		node.inputCount = node.inputNames.size
		node.outputCount = node.outputNames.size

		node.recreateIOCircles()
		markDirty()
	}


	private fun updateTitle() {
		if (!this::stage.isInitialized) {
			return
		}
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

	/** Показывает диалог «Сохранить изменения?» если есть несохранённые правки.
	 *  Возвращает true — продолжать операцию; false — отменить. */
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
		when (alert.showAndWait().orElse(cancel)) {
			save -> {
				if (currentProjectFile != null) {
					exportBlocksToFile(currentProjectFile!!)
				} else {
					val fc = FileChooser().apply {
						title = "Сохранить проект"
						extensionFilters.add(FileChooser.ExtensionFilter("JSON Files", "*.json"))
					}
					val f = fc.showSaveDialog(stage) ?: return false
					exportBlocksToFile(f)
					currentProjectFile = f
				}
				clearDirty()
				return true
			}

			dont -> return true
			else -> return false
		}
	}


	private fun startSpringBoot(jarPath: String, port: Int = 8080) {
		if (springProcess != null && springProcess!!.isAlive) {
			println("Spring Boot уже запущен на порту $port")
			return
		}

		val process = ProcessBuilder(
			"java",
			"-jar", jarPath,
			"--server.port=$port"
		)
			.redirectErrorStream(true)
			.start()

		springProcess = process

		// читаем логи в отдельном потоке
		Thread {
			process.inputStream.bufferedReader().forEachLine { line ->
				springLogs.appendText("${line}\n")
			}
		}.start()
	}


	private fun createDynamicRestEndpoint(path: String, response: Map<String, Any>) {
		val endpointsDir = File("./spring-app/endpoints")
		if (!endpointsDir.exists()) endpointsDir.mkdirs()

		val file = File(endpointsDir, "${path.trimStart('/')}.json")
		val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
			mapOf(
				"path" to path,
				"response" to response
			)
		)
		file.writeText(json)
		println("Создан REST эндпоинт $path -> ${file.absolutePath}")
	}


	private fun createDynamicThymeleafEndpoint(path: String, block: BlockNode) {
		val endpointsDir = File("./spring-app/endpoints")
		if (!endpointsDir.exists()) {
			endpointsDir.mkdirs()
		}
		val inputs = HashMap<String, Any>()
		block.connectedLines
			.filter { it.to == block }
			.sortedBy { it.toPort }
			.forEach { c ->
				inputs[block.inputNames[c.toPort]] = c.from.outputsData[c.fromPort]
			}
		val merged = inputs.values
			.filterIsInstance<Map<String, Any>>()
			.flatMap { it.entries }
			.associate { it.key to it.value }
		val file = File(endpointsDir, "${path.trimStart('/')}.json")
		val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
			mapOf(
				"path" to path,
				"type" to "inline-view",
				"template" to block.code,
				"model" to merged,
			)
		)
		file.writeText(json)
		println("Создан thymeleaf-эндпоинт $path")
	}


	fun runCommand(cmd: Command) {
		cmd.execute()
		undoStack.push(cmd)
		redoStack.clear() // сбрасываем redo после нового действия
	}


	fun onBlockReleased(block: BlockNode) {
		if (lastPressX != block.layoutX || lastPressY != block.layoutY) {
			runCommand(MoveBlockCommand(block, lastPressX, lastPressY, block.layoutX, block.layoutY))
		}
	}


	fun setPressCoords(block: BlockNode, x: Double, y: Double) {
		lastPressX = x
		lastPressY = y
	}


	override fun stop() {
		super.stop()
		stopSpringBoot()
	}

	private fun stopSpringBoot() {
		springProcess?.destroy()
		springProcess = null
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


		@JvmStatic
		fun main(args: Array<String>) {
			launch(MainApp::class.java)
		}
	}
}