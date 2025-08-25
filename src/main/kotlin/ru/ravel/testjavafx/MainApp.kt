package ru.ravel.testjavafx

//import javafx.scene.web.WebView
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*


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
	private val contentPane = Pane().apply {
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


	override fun start(primaryStage: Stage) {
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
								val node = addBlock(contentPane, event.x, event.y, file.nameWithoutExtension, type)
								node.subProjectPath = file.absolutePath
								adjustProjectNodeIO(node, file)
							}
						} else {
							addBlock(contentPane, event.x, event.y, type.displayName, type)
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
				// Очищаем всё
				blocks.clear()
				connections.clear()
				contentPane.children.removeIf { it is BlockNode || it is Line }
				currentProjectFile = null
				primaryStage.title = "Low code processes executor"
			}
		}

		val openProjectButton = Button("Открыть проект").apply {
			setOnAction {
				val fileChooser = FileChooser()
				fileChooser.title = "Открыть проект"
				fileChooser.extensionFilters.addAll(
					FileChooser.ExtensionFilter("JSON", "*.json"),
				)
				val file = fileChooser.showOpenDialog(primaryStage)
				if (file != null) {
					importBlocksFromFile(file)
					importOutputsData(file)
					currentProjectFile = file
					primaryStage.title = currentProjectFile?.name ?: "Low code processes executor"
				}
			}
		}

		val saveProjectButton = Button("Сохранить проект").apply {
			setOnAction {
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
						primaryStage.title = currentProjectFile?.name ?: "Low code processes executor"
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
					selectedBlock?.let { block ->
						deleteBlockRequest(block)    //FIXME
					}
					selectedConnection?.let { conn ->
						connections.remove(conn)
						conn.from.connectedLines.remove(conn)
						conn.to.connectedLines.remove(conn)
						(conn.line.parent as? Pane)?.children?.remove(conn.line)
						selectedConnection = null
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

	private fun addBlock(parent: Pane, x: Double, y: Double, name: String, blockType: BlockType): BlockNode {
		val block = BlockNode(x, y, name, blockType)
		blocks.add(block)
		block.onMove = { ensureBlockVisible(block) }
		parent.children.add(block)
		setupHandlersForBlock(block)
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
	}

	fun importBlocksFromFile(file: File) {
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
			block.onMove = { ensureBlockVisible(block) }
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
					selectBlock(block)
					contentPane.requestFocus()
					val (startX, startY) = block.outputPoint(outputIdx)
					val line = Line(startX, startY, startX, startY).apply {
						stroke = Color.BLUE
						strokeWidth = 2.0
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
		val incoming = mutableMapOf<BlockNode, MutableSet<BlockNode>>()
		val outgoing = mutableMapOf<BlockNode, MutableList<BlockNode>>()
		blocks.forEach {
			incoming[it] = mutableSetOf()
		}
		connections.forEach { conn ->
			incoming[conn.to]?.add(conn.from)
			outgoing.computeIfAbsent(conn.from) { mutableListOf() }.add(conn.to)
		}
		val finished = mutableSetOf<BlockNode>()
		// Найти цикл (если есть)
		val cycle = findFirstCycle()
		val cycleSet = cycle?.toSet() ?: emptySet()

		fun runBlockRecursively(block: BlockNode) {
			val inputConnections = connections.filter { it.to == block }
			val allInputsFilled = inputConnections.all { conn ->
				val src = conn.from
				val port = conn.fromPort
				val out = src.outputsData
				if (out.size <= port) return@all false
				if (src.blockType in arrayOf(BlockType.START, BlockType.INPUT_DATA))
					return@all true
				!isDataEmpty(out[port])
			}
			if (!allInputsFilled) {
				return
			}
			Platform.runLater { block.selected = true }
			runBlock(block)
			Platform.runLater { block.selected = false }
			finished.add(block)
			outgoing[block]?.forEach { child ->
				if (child !in cycleSet) {
					runBlockRecursively(child)
				}
			}
		}

		blocks.filter {
			it !in cycleSet && incoming[it]?.all { p -> p !in cycleSet } == true
		}.forEach {
			runBlockRecursively(it)
		}
		if (!cycle.isNullOrEmpty()) {
			while (true) {
				// 1. Проходим все блоки цикла (ваша логика)
				for (block in cycle) {
					Platform.runLater { block.executing = true }
					runBlock(block)
					Platform.runLater { block.executing = false }
				}

				// 2. Триггерим все следующие блоки вне цикла после каждого прохода
				val exitBlocks = mutableSetOf<BlockNode>()
				outgoing.forEach { (from, outs) ->
					if (from in cycleSet) {
						outs.filter { it !in cycleSet }.forEach { exitBlocks.add(it) }
					}
				}
				exitBlocks.forEach { nextBlock ->
					// Проверяем что все входящие из цикла "готовы"
					val fromCycle = incoming[nextBlock]?.filter { it in cycleSet } ?: emptyList()
					if (fromCycle.all { it in finished || cycleSet.contains(it) }) {
						runBlockRecursively(nextBlock)
					}
				}

				// 3. Условие выхода (ваше)
				if (cycle.all { block ->
						val pairs = block.connectedLines
							.filter { it.to != block }
							.filter { it.to in cycle }
							.map { Pair(it.to, it.from) }
						pairs.all { p ->
							val to = p.first
							val from = p.second
							val list = List(to.connectedLines.filter { it.to == from }.size) { index -> index }
							to.outputsData.filterIndexed { index, _ -> index in list }.all { it.isEmpty() }
						}
					}) {
					break
				}
			}
		}
		currentProjectFile?.let { projectFile ->
			saveOutputsData(projectFile)
		}
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
					.forEach { connection ->
						val name = block.inputNames[connection.toPort]
						inputDataMap[name] = connection.from.outputsData[connection.fromPort]
					}
				val outputs = (0 until block.outputCount)
					.associate { index -> block.outputNames[index] to mutableMapOf<String, Any>() }
					.toMutableMap()
				inputDataMap.putAll(outputs)
				try {
					block.code.runGroovyScript(inputDataMap)
					outputs.forEach { (_, value) ->
						block.outputsData.add(value)
					}
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
						inputDataMap[block.inputNames[c.toPort]] = c.from.outputsData[c.fromPort]
					}
				val outputs = (0 until block.outputCount)
					.associate { index -> block.outputNames[index] to mutableMapOf<String, Any>() }
					.toMutableMap()
				inputDataMap.putAll(outputs)
				try {
					val pyOutputs: Map<String, Any?> = block.code.runPythonScript(block, inputDataMap, outputs)
					block.outputNames.forEach { name ->
						block.outputsData.add(pyOutputs[name] as? MutableMap<String, Any> ?: mutableMapOf())
					}
					outputs.forEach { (_, value) ->
						block.outputsData.add(value)
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
					val prev = block.outputsData
					block.outputsData = block.subProjectProps
						.map { (k, v) -> mutableMapOf(k to v) }.toMutableList()
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


		}
	}


	private fun ensureBlockVisible(block: BlockNode, margin: Double = 80.0, extendStep: Double = 200.0) {
		val now = System.currentTimeMillis()
		if (now - lastEnsureVisible < 180) return
		lastEnsureVisible = now

		val right = block.layoutX + block.width
		val bottom = block.layoutY + block.height
		var changed = false

		if (right + margin > contentPane.width) {
			contentPane.prefWidth = contentPane.width + extendStep
			changed = true
		}
		if (bottom + margin > contentPane.height) {
			contentPane.prefHeight = contentPane.height + extendStep
			changed = true
		}
		if (block.layoutX - margin < 0) {
			val shift = extendStep
			blocks.forEach { it.layoutX += shift }
			connections.forEach { conn ->
				conn.line.startX += shift
				conn.line.endX += shift
			}
			contentPane.prefWidth = contentPane.width + shift
			changed = true
		}
		if (block.layoutY - margin < 0) {
			val shift = extendStep
			blocks.forEach { it.layoutY += shift }
			connections.forEach { conn ->
				conn.line.startY += shift
				conn.line.endY += shift
			}
			contentPane.prefHeight = contentPane.height + shift
			changed = true
		}
		if (changed) {
			gridCanvas.widthProperty().unbind()
			gridCanvas.heightProperty().unbind()
			gridCanvas.width = contentPane.prefWidth
			gridCanvas.height = contentPane.prefHeight
			gridCanvas.widthProperty().bind(contentPane.widthProperty())
			gridCanvas.heightProperty().bind(contentPane.heightProperty())
			drawGrid(gridCanvas)
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
		val shell = GroovyShell()
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
		val venvDir = "./run/python/${block.serializedId}_${block.hashCode()}"
		ProcessBuilder("python3", "-m", "venv", venvDir)
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
		val pythonProc = ProcessBuilder(pythonPath, "-c", fullScript)
			.redirectErrorStream(true)
			.apply { environment()[PYTHON_PARAMS_VARIABLE] = paramsJson }
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

		/* --- 1. Строим внутренние BlockNode без UI --- */
		val idToBlock = mutableMapOf<UUID, BlockNode>()
		val blocks = data.blocks.map { b ->
			val codeText = b.codeFile
				?.let { File(subDir, it).readText() }
				?: b.codeFile.orEmpty()
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

		/* --- 1.1. ЗАГРУЖАЕМ PROPERTIES из ресурсов подпроекта --- */
		data.blocks.forEach { b ->
			if (BlockType.valueOf(b.blockType) == BlockType.PROPERTIES && b.codeFile != null) {
				val bn = idToBlock[b.id] ?: return@forEach
				val propsFile = File(subDir, b.codeFile)
				if (propsFile.exists() && propsFile.length() > 0) {
					try {
						val props: Map<String, Any> =
							ObjectMapper().readValue(propsFile, Map::class.java) as Map<String, Any>
						val byName = props
						bn.outputsData = bn.outputNames.map { name ->
							mutableMapOf<String, Any>(name to (byName[name] ?: ""))
						}.toMutableList()
					} catch (_: Exception) {
						bn.outputsData = bn.outputNames.map { name ->
							mutableMapOf<String, Any>(name to "")
						}.toMutableList()
					}
				} else {
					bn.outputsData = bn.outputNames.map { name ->
						mutableMapOf<String, Any>(name to "")
					}.toMutableList()
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
				} /*else if (propBlock.outputsData[idx].isEmpty()) {
					propBlock.outputsData[idx] = mutableMapOf(propName to "")
				}*/
				// ВАЖНО: если v == null — НЕ трогаем значение из ресурсов!
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
				BlockType.INPUT_DATA -> it.code.isBlank()
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

		val inputStubsNeeded = parentBlock.inputCount - entryBlocks.size
		repeat(inputStubsNeeded) {
			val stub = BlockNode(
				x = 0.0, y = 0.0,
				name = "EXT_IN$it",
				blockType = BlockType.INPUT_DATA,
				inputCount = 0,
				outputCount = 1,
				inputFormat = InputFormatType.JSON,      // или YAML
				outputsData = mutableListOf()
			)
			blocks.add(stub)
			entryBlocks.add(stub)
		}

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

				BlockType.EXIT -> {}    // EXIT не исполняем явно

				BlockType.SUB_PROJECT -> {
					val f = File(b.subProjectPath)
					if (f.exists()) {
						val sub = MainApp()
						sub.importBlocksFromFile(f)
						val outs = runSubProject(f, b/*, sub*/)
						b.outputsData = outs.toMutableList()
					} else {
						b.outputsData = MutableList(b.outputCount) { mutableMapOf() }
					}
				}

				BlockType.PROPERTIES -> {}

			}
		}

		// топологическая сортировка «в лоб»
		val incoming = mutableMapOf<BlockNode, MutableSet<BlockNode>>()
		val outgoing = mutableMapOf<BlockNode, MutableList<BlockNode>>()
		blocks.forEach { incoming[it] = mutableSetOf() }
		connections.forEach { c ->
			incoming[c.to]?.add(c.from)
			outgoing.computeIfAbsent(c.from) { mutableListOf() }.add(c.to)
		}
		val queue = ArrayDeque(blocks.filter { incoming[it]?.isEmpty() == true })
		while (queue.isNotEmpty()) {
			val b = queue.removeFirst()
			// ждём, пока все входы заполнятся
			val ready = connections.filter { it.to == b }
				.all { c ->
					c.from.outputsData.size > c.fromPort && (c.from.blockType == BlockType.START || !isDataEmpty(c.from.outputsData[c.fromPort]))
				}
			if (!ready) {
				continue
			}
			execute(b)
			outgoing[b]?.forEach { child ->
				incoming[child]?.remove(b)
				if (incoming[child]?.isEmpty() == true) queue.add(child)
			}
		}

		/* --- 5. Ищем EXIT и возвращаем его входные данные --- */
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
		val newInputs = data.blocks.count { it.blockType == "START" }
		val newOutputs = data.blocks.count { it.blockType == "EXIT" }
		node.updatePorts(newInputs/*.coerceAtLeast(1)*/, newOutputs/*.coerceAtLeast(1)*/)

		val inputBlocks = data.blocks.filter { it.blockType == "START" }
		val outputBlocks = data.blocks.filter { it.blockType == "EXIT" }
		node.inputNames = inputBlocks.map { it.name }.toMutableList()
		node.outputNames = outputBlocks.map { it.name }.toMutableList()
		node.inputCount = node.inputNames.size
		node.outputCount = node.outputNames.size

		node.recreateIOCircles()
	}


	companion object {
		private const val PYTHON_PARAMS_VARIABLE = "PARAMS_JSON"

		@JvmStatic
		fun main(args: Array<String>) {
			launch(MainApp::class.java)
		}
	}
}