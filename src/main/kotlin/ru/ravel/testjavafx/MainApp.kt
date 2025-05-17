package ru.ravel.testjavafx

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.readValue
import groovy.lang.GroovyShell
import javafx.application.Application
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Point2D
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
import ru.ravel.testjavafx.model.BlockSerialized
import ru.ravel.testjavafx.model.BlockType
import ru.ravel.testjavafx.model.BlocksData
import ru.ravel.testjavafx.model.InputFormatType
import java.io.File


class MainApp : Application() {
	val blocks = mutableListOf<BlockNode>()
	val connections = mutableListOf<Connection>()
	var draggingLine: Line? = null
	var draggingFromBlock: BlockNode? = null
	var draggingFromOutputIndex: Int? = null
	private var selectedBlock: BlockNode? = null
	private var selectedConnection: Connection? = null
	private var activeContextMenu: ContextMenu? = null
	private val windowW = 2000.0
	private val windowH = 1200.0
	private val gridCanvas = Canvas(2000.0, 1200.0)
	val contentPane = Pane().apply {
		children.add(gridCanvas)
		prefWidth = windowW * 3
		prefHeight = windowH * 3
		padding = Insets(10.0)
		isFocusTraversable = true
	}
	private val scrollPane = ScrollPane(contentPane).apply {
		isPannable = false
		hbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
	}


	override fun start(primaryStage: Stage) {
		drawGrid(gridCanvas, 10.0)
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

		val saveJsonButton = Button("Сохранить в JSON").apply {
			setOnAction {
				val fileChooser = FileChooser()
				fileChooser.title = "Сохранить как JSON"
				fileChooser.extensionFilters.add(FileChooser.ExtensionFilter("JSON Files", "*.json"))
				val file = fileChooser.showSaveDialog(primaryStage)
				if (file != null) exportBlocksToFile(file, asXml = false)
			}
		}
		val loadButton = Button("Открыть").apply {
			setOnAction {
				val fileChooser = FileChooser()
				fileChooser.title = "Открыть блок-схему"
				fileChooser.extensionFilters.addAll(
					FileChooser.ExtensionFilter("JSON и XML", "*.json", "*.xml"),
				)
				val file = fileChooser.showOpenDialog(primaryStage)
				if (file != null) importBlocksFromFile(file)
			}
		}

		val runButton = Button("Бег").apply {
			setOnAction {
				blocks.sortedBy { block ->
					block.x
				}.forEach { block ->
					when (block.blockType) {
						BlockType.MAPPING_GROOVY -> {
							block.outputs = mutableListOf()
							val inputDataMap = HashMap<String, Any>()
							block.connectedLines.filter {
								it.to == block
							}.forEachIndexed { index: Int, connection: Connection ->
								inputDataMap["in${index}"] = connection.from.outputs[connection.fromPort]
							}
							val outputs = (0 until block.outputCount)
								.associate { index -> "out$index" to mutableMapOf<String, Any>() }
								.toMutableMap()
							inputDataMap.putAll(outputs)
							try {
								block.code.runGroovyScript(inputDataMap)
							} catch (e: Exception) {
								System.err.println(e.localizedMessage)
							}
							outputs.forEach { (outNo, value) ->
								block.outputs.add(outNo.replace("out", "").toInt(), value)
							}
						}

						BlockType.MAPPING_PYTHON -> {
							TODO()
						}

						BlockType.MAPPING_JAVA_SCRIPT -> {
							TODO()
						}

						BlockType.CONNECTOR -> {
							TODO()
						}

						BlockType.INPUT_DATA -> {
							block.outputs.add(
								try {
									when (block.inputFormat) {
										InputFormatType.JSON -> ObjectMapper().readValue<MutableMap<String, Any>>(block.code)
										InputFormatType.XML -> XmlMapper().readValue<MutableMap<String, Any>>(block.code)
										InputFormatType.YAML -> TODO()
										InputFormatType.PROTOBUF -> TODO()
									}
								} catch (e: Exception) {
									mutableMapOf()
								}
							)
						}

						BlockType.START -> {
							block.outputs.add(
								try {
									when (block.inputFormat) {
										InputFormatType.JSON -> ObjectMapper().readValue<MutableMap<String, Any>>(block.code)
										InputFormatType.XML -> XmlMapper().readValue<MutableMap<String, Any>>(block.code)
										InputFormatType.YAML -> TODO()
										InputFormatType.PROTOBUF -> TODO()
									}
								} catch (e: Exception) {
									mutableMapOf()
								}
							)
						}

						BlockType.EXIT -> {
						}
					}
				}
			}
		}

		val savesButtonBox = HBox(10.0, loadButton, saveJsonButton).apply {
			padding = Insets(8.0)
		}
		val runButtonBox = HBox(10.0, runButton).apply {
			padding = Insets(8.0)
		}
		val root = VBox(savesButtonBox, runButtonBox, scrollPane)

		// Горячая клавиша DEL для удаления
		val scene = Scene(root, windowW, windowH).apply {
			setOnKeyPressed { event ->
				if (event.code == KeyCode.DELETE || event.code == KeyCode.BACK_SPACE) {
					selectedBlock?.let { block ->
						deleteBlockRequest(block)
					}
					selectedConnection?.let { conn ->
						connections.remove(conn)
						conn.from.connectedLines.remove(conn)
						conn.to.connectedLines.remove(conn)
						(conn.line.parent as? Pane)?.children?.remove(conn.line)
						selectedConnection = null
					}
				}
			}
		}

		primaryStage.scene = scene.apply {
			addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
				activeContextMenu?.let { menu ->
					if (menu.isShowing) {
						menu.hide()
						activeContextMenu = null
					}
				}
			}
		}
		primaryStage.title = "Блочное визуальное программирование (JavaFX Kotlin)"
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

	fun selectConnection(conn: Connection?) {
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

	fun addBlock(parent: Pane, x: Double, y: Double, name: String, blockType: BlockType) {
		val block = BlockNode(x, y, name, blockType)
		blocks.add(block)
		parent.children.add(block)
		setupHandlersForBlock(block)
	}


	// --- Сериализация и загрузка ---

	fun exportBlocksToFile(file: File, asXml: Boolean) {
		val mapper = if (asXml) XmlMapper() else ObjectMapper()
		val blocksData = BlocksData(blocks.map { it.toSerialized() }, connections.map { it.toSerialized() })
		mapper.writeValue(file, blocksData)
	}

	private fun importBlocksFromFile(file: File) {
		updateBlocks()
		val isXml = file.extension.equals("xml", ignoreCase = true)
		val mapper = if (isXml) XmlMapper() else ObjectMapper()
		val data: BlocksData = mapper.readValue(file, BlocksData::class.java)

		// Очистка
		blocks.clear()
		connections.clear()
		(scrollPane.content as? Pane)?.children?.removeIf { it is BlockNode || it is Line }

		val idToBlock = mutableMapOf<Int, BlockNode>()
		BlockNode.nextBlockId = data.blocks.maxBy(BlockSerialized::id).id + 1
		data.blocks.forEach { b ->
			val blockType = try {
				BlockType.valueOf(b.blockType)
			} catch (_: Exception) {
				BlockType.MAPPING_GROOVY
			}
			val defaultInput = if (blockType == BlockType.START || blockType == BlockType.INPUT_DATA) {
				0
			} else {
				b.inputCount.coerceAtLeast(1)
			}
			val defaultOutput = if (blockType == BlockType.EXIT) 0 else b.outputCount.coerceAtLeast(1)
			val block = BlockNode(
				x = b.x,
				y = b.y,
				name = b.name,
				blockType = blockType,
				inputCount = defaultInput,
				outputCount = defaultOutput,
				serializedId = b.id,
				inputFormat = b.inputFormat ?: InputFormatType.JSON,
				code = b.code ?: "",
				dataDocs = b.dataDocs ?: "",
				otherInfo = b.otherInfo ?: "",
			)
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
						val toBlockPair = blocks.asSequence().flatMap { other ->
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

							conn.line.onMouseClicked = EventHandler { event ->
								if (event.button == MouseButton.PRIMARY) {
									selectConnection(conn)
									(conn.line.parent as? Pane)?.requestFocus()
									event.consume()
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

	fun finishConnectionDrag(event: MouseEvent) {
		// Найти подходящий input и создать соединение, если на input-е
		// Аналогично твоей логике, только теперь мы знаем outputIndex
		draggingLine?.let { line ->
			// ... твоя логика по поиску блока-назначения
			// (см. ниже, если надо полный код)
		}
		draggingLine = null
		draggingFromBlock = null
		draggingFromOutputIndex = null
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

				// Получить координаты для добавления блока
				val (paneX, paneY) = when (node) {
					is Canvas -> Pair(event.x, event.y)
					is Pane -> Pair(event.x, event.y)
					else -> {
						// координаты в системе contentPane
						val scenePoint = Point2D(event.sceneX, event.sceneY)
						val panePoint = contentPane.sceneToLocal(scenePoint)
						Pair(panePoint?.x, panePoint?.y)
					}
				}

//				showBlockCreationMenu(event.screenX, event.screenY, paneX!!, paneY!!)
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

						conn.line.onMouseClicked = EventHandler { event ->
							if (event.button == MouseButton.PRIMARY) {
								selectConnection(conn)
								(conn.line.parent as? Pane)?.requestFocus()
								event.consume()
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


	private fun String.runGroovyScript(bindings: Map<String, Any?> = emptyMap()): Any? {
		val shell = GroovyShell()
		val binding = shell.context
		for ((k, v) in bindings) {
			binding.setProperty(k, v)
		}
		return shell.evaluate(this)
	}


	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			launch(MainApp::class.java)
		}
	}
}