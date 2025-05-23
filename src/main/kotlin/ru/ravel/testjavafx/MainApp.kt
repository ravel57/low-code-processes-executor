package ru.ravel.testjavafx

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
import org.yaml.snakeyaml.Yaml
import ru.ravel.testjavafx.model.BlockType
import ru.ravel.testjavafx.model.BlocksData
import ru.ravel.testjavafx.model.InputFormatType
import java.io.File
import java.util.*
import javax.script.ScriptEngineManager


class MainApp : Application() {
	private var currentProjectFile: File? = null
	private val blocks = mutableListOf<BlockNode>()
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
					.coerceIn(0.0, contentPane.width - scrollPane.viewportBounds.width) / (contentPane.width - scrollPane.viewportBounds.width)
				scrollPane.vvalue = (scrollPane.vvalue * (contentPane.height - scrollPane.viewportBounds.height) + dy)
					.coerceIn(0.0, contentPane.height - scrollPane.viewportBounds.height) / (contentPane.height - scrollPane.viewportBounds.height)
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
						addBlock(contentPane, event.x, event.y, type.displayName, type)
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
		val dataDocksButton = Button("DataDocs").apply {
			setOnAction { }
		}
		val runButtonBox = HBox(10.0, runButton, dataDocksButton).apply {
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

	private fun addBlock(parent: Pane, x: Double, y: Double, name: String, blockType: BlockType) {
		val block = BlockNode(x, y, name, blockType)
		blocks.add(block)
		block.onMove = { ensureBlockVisible(block) }
		parent.children.add(block)
		setupHandlersForBlock(block)
	}


	// --- Сериализация и загрузка ---

	private fun exportBlocksToFile(file: File) {
		currentProjectFile = file
		val blocksData = BlocksData(blocks.map { it.toSerialized() }, connections.map { it.toSerialized() })
		file.writeText(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(blocksData))
	}

	private fun importBlocksFromFile(file: File) {
		currentProjectFile = file
		updateBlocks()
		val data: BlocksData = ObjectMapper().readValue(file, BlocksData::class.java)

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
			val defaultInputCount = if (blockType in arrayOf(BlockType.START, BlockType.INPUT_DATA)) {
				0
			} else {
				b.inputCount.coerceAtLeast(1)
			}
			val defaultOutputCount = if (blockType == BlockType.EXIT) {
				0
			} else {
				b.outputCount.coerceAtLeast(1)
			}
			val block = BlockNode(
				x = b.x,
				y = b.y,
				name = b.name,
				blockType = blockType,
				inputCount = defaultInputCount,
				outputCount = defaultOutputCount,
				serializedId = b.id,
				inputFormat = b.inputFormat ?: InputFormatType.JSON,
				code = b.code ?: "",
				dataDocs = b.dataDocs ?: "",
				otherInfo = b.otherInfo ?: "",
				inputNames = b.inputNames?.toMutableList() ?: mutableListOf(),
				outputNames = b.outputNames?.toMutableList() ?: mutableListOf(),
				outputsData = b.outputsData ?: mutableListOf(),
				packagesNames = b.packagesNames ?: mutableListOf(),
			)
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
		}

		// Восстановление соединений
		data.connections.forEach { c ->
			val fromBlock = idToBlock[c.fromId] ?: return@forEach
			val toBlock = idToBlock[c.toId] ?: return@forEach
			val outIdx = c.fromOutputIndex
			val inIdx = c.toInputIndex
			val (startX, startY) = fromBlock.outputPoint(outIdx)
			val (endX, endY) = toBlock.inputPoint(inIdx)
			val line = Line(startX, startY, endX, endY).apply {
				stroke = Color.BLUE
				strokeWidth = 2.0
			}
			val conn = Connection(fromBlock, toBlock, line, outIdx, inIdx)
			connections.add(conn)
			fromBlock.connectedLines.add(conn)
			toBlock.connectedLines.add(conn)
			line.onMouseClicked = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					selectConnection(conn)
					(line.parent as? Pane)?.requestFocus()
					event.consume()
				}
			}
			(scrollPane.content as? Pane)?.children?.add(line)
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
						other.inputCircles.mapIndexed { inputIdx, inputCircle -> Triple(other, inputCircle, inputIdx) }
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
//		val repeatCount = 99 // Можно заменить на переменную, как тебе нужно
		// Найти цикл (если есть)
		val cycle = findFirstCycle()
		val cycleSet = cycle?.toSet() ?: emptySet()

		// Функция для стандартного обхода (без циклов)
		fun runBlockRecursively(block: BlockNode) {
			if (incoming[block]?.all { it in finished } != true) {
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

		// Запускать только те, которые не входят в цикл
		blocks.filter {
			it !in cycleSet && incoming[it]?.all { p -> p !in cycleSet } == true
		}.forEach {
			runBlockRecursively(it)
		}
		// Если есть цикл — обходить его N раз
		if (!cycle.isNullOrEmpty()) {
			while (true) {
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
				for (block in cycle) {
					Platform.runLater { block.executing = true }
					runBlock(block)
					Platform.runLater { block.executing = false }
				}
			}
		}
	}


	private fun runBlock(block: BlockNode) {
		block.outputsData = mutableListOf()
		when (block.blockType) {
			BlockType.MAPPING_GROOVY -> {
				val inputDataMap = HashMap<String, Any>()
				block.connectedLines.filter {
					it.to == block
				}.forEachIndexed { index: Int, connection: Connection ->
					inputDataMap[block.inputNames[index]] = connection.from.outputsData[connection.fromPort]
				}
				val outputs = (0 until block.outputCount)
					.associate { index -> block.outputNames[index] to mutableMapOf<String, Any>() }
					.toMutableMap()
				inputDataMap.putAll(outputs)
				try {
					block.code.runGroovyScript(inputDataMap)
				} catch (e: Exception) {
					System.err.println(e.localizedMessage)
					Platform.runLater {
						Alert(Alert.AlertType.ERROR).apply {
							title = block.name
							contentText = e.localizedMessage
							showAndWait()
						}
					}
				}
				outputs.forEach { (_, value) ->
					block.outputsData.add(value)
				}
			}

			BlockType.MAPPING_PYTHON -> {
				val inputDataMap = HashMap<String, Any>()
				block.connectedLines.filter {
					it.to == block
				}.forEachIndexed { index: Int, connection: Connection ->
					inputDataMap[block.inputNames[index]] = connection.from.outputsData[connection.fromPort]
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
				} catch (e: Exception) {
					System.err.println(e.localizedMessage)
					Platform.runLater {
						Alert(Alert.AlertType.ERROR).apply {
							title = "Ошибка"
							contentText = e.localizedMessage
							showAndWait()
						}
					}
				}
				outputs.forEach { (_, value) ->
					block.outputsData.add(value)
				}
			}

			BlockType.MAPPING_JAVA_SCRIPT -> {
				val inputDataMap = HashMap<String, Any>()
				block.connectedLines.filter {
					it.to == block
				}.forEachIndexed { index: Int, connection: Connection ->
					inputDataMap[block.inputNames[index]] = connection.from.outputsData[connection.fromPort]
				}
				val outputs = (0 until block.outputCount)
					.associate { index -> block.outputNames[index] to mutableMapOf<String, Any>() }
					.toMutableMap()
				inputDataMap.putAll(outputs)
				try {
					block.code.runJavaScript(inputDataMap)
				} catch (e: Exception) {
					System.err.println(e.localizedMessage)
					Platform.runLater {
						Alert(Alert.AlertType.ERROR).apply {
							title = "Ошибка"
							contentText = e.localizedMessage
							showAndWait()
						}
					}
				}
				outputs.forEach { (_, value) ->
					block.outputsData.add(value)
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

			BlockType.INPUT_DATA, BlockType.START -> {
				block.outputsData.add(
					try {
						when (block.inputFormat) {
							InputFormatType.JSON -> ObjectMapper().readValue<MutableMap<String, Any>>(block.code)
							InputFormatType.XML -> XmlMapper().readValue<MutableMap<String, Any>>(block.code)
							InputFormatType.YAML -> Yaml().load(block.code)
							InputFormatType.PROTOBUF -> TODO()
						}
					} catch (e: Exception) {
						mutableMapOf()
					}
				)
			}

			BlockType.EXIT -> {}
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


	private fun String.runJavaScript(bindings: Map<String, Any?> = emptyMap()): Any? {
		val engine = ScriptEngineManager().getEngineByName("JavaScript")
		val scriptBindings = engine.createBindings()
		for ((k, v) in bindings) {
			scriptBindings[k] = v
		}
		return engine.eval(this, scriptBindings)
	}


	fun String.runPythonScript(
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
				|params = json.loads(os.environ.get("PARAMS_JSON", "{}"))
				|locals().update(params)
				|
				|${this}
				|
				|print(json.dumps({${outputs.map { "\"${it.key}\": ${it.key}" }.joinToString(", ")}}))
				""".trimMargin()
		val pythonProc = ProcessBuilder(pythonPath, "-c", fullScript)
			.redirectErrorStream(true)
			.apply { environment()["PARAMS_JSON"] = paramsJson }
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


	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			launch(MainApp::class.java)
		}
	}
}