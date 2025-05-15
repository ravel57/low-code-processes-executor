package ru.ravel.testjavafx

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import javafx.application.Application
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.stage.FileChooser
import javafx.stage.Stage
import ru.ravel.testjavafx.model.BlockSerialized
import ru.ravel.testjavafx.model.BlocksData
import ru.ravel.testjavafx.model.ConnectionSerialized
import java.io.File
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper


class MainApp : Application() {
	private val blocks = mutableListOf<BlockNode>()
	private val connections = mutableListOf<Connection>()
	private var draggingLine: Line? = null
	private var draggingFromBlock: BlockNode? = null
	private var selectedBlock: BlockNode? = null
	private var selectedConnection: Connection? = null
	lateinit var scrollPane: ScrollPane
	lateinit var contentPane: Pane

	override fun start(primaryStage: Stage) {
		val windowW = 600.0
		val windowH = 400.0

		contentPane = Pane().apply {
			prefWidth = windowW * 3
			prefHeight = windowH * 3
			padding = Insets(10.0)
			isFocusTraversable = true
		}

		scrollPane = ScrollPane(contentPane).apply {
			isPannable = false
			hbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
			viewportBoundsProperty().addListener(
				object : javafx.beans.value.ChangeListener<javafx.geometry.Bounds> {
					override fun changed(
						observable: javafx.beans.value.ObservableValue<out javafx.geometry.Bounds>?,
						oldValue: javafx.geometry.Bounds?,
						newValue: javafx.geometry.Bounds?
					) {
						hvalue = 0.5
						vvalue = 0.5
					}
				}
			)
		}

		primaryStage.userData = this

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

		// Пример начальных блоков
		addBlock(contentPane, windowW * 1.5 - 100, windowH * 1.5 - 100, "Старт", BlockType.START)
		addBlock(contentPane, windowW * 1.5 + 150, windowH * 1.5, "Выход", BlockType.EXIT)

		// Контекстное меню по ПКМ для добавления блоков разных типов
		contentPane.onMouseClicked = EventHandler { event ->
			if (event.button == MouseButton.SECONDARY && event.target === contentPane) {
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
		val saveXmlButton = Button("Сохранить в XML").apply {
			setOnAction {
				val fileChooser = FileChooser()
				fileChooser.title = "Сохранить как XML"
				fileChooser.extensionFilters.add(FileChooser.ExtensionFilter("XML Files", "*.xml"))
				val file = fileChooser.showSaveDialog(primaryStage)
				if (file != null) exportBlocksToFile(file, asXml = true)
			}
		}
		val openButton = Button("Открыть из файла").apply {
			setOnAction {
				val fileChooser = FileChooser()
				fileChooser.title = "Открыть файл блоков"
				fileChooser.extensionFilters.addAll(
					FileChooser.ExtensionFilter("JSON и XML", "*.json", "*.xml"),
					FileChooser.ExtensionFilter("JSON", "*.json"),
					FileChooser.ExtensionFilter("XML", "*.xml"),
					FileChooser.ExtensionFilter("Все файлы", "*.*")
				)
				val file = fileChooser.showOpenDialog(primaryStage)
				if (file != null) importBlocksFromFile(file)
			}
		}


		val vbox = VBox(10.0, openButton, saveJsonButton, saveXmlButton, scrollPane)
		vbox.padding = Insets(10.0)
		val scene = Scene(vbox, windowW, windowH)
		scene.setOnKeyPressed { event ->
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

		primaryStage.scene = scene
		primaryStage.title = "Блочное визуальное программирование (JavaFX Kotlin)"
		primaryStage.show()
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
		(block.parent as? Pane)?.children?.removeAll(toRemove.map { it.line })
		blocks.remove(block)
		(block.parent as? Pane)?.children?.remove(block)
		if (selectedBlock == block) selectedBlock = null
	}

	private fun addBlock(
		parent: Pane,
		x: Double,
		y: Double,
		text: String,
		blockType: BlockType = BlockType.MAPPING_GROOVY
	) {
		val block = BlockNode(x, y, text, blockType)
		blocks.add(block)
		parent.children.add(block)

		block.outputCircle.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				(block.scene?.window?.userData as? MainApp)?.selectBlock(block)
				(block.parent as? Pane)?.requestFocus()
				val (startX, startY) = block.outputPoint()
				val line = Line(startX, startY, startX, startY).apply {
					stroke = Color.BLUE
					strokeWidth = 2.0
				}
				parent.children.add(line)
				draggingLine = line
				draggingFromBlock = block
				event.consume()
			}
		}
		block.outputCircle.onMouseDragged = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY && draggingLine != null) {
				val paneCoords = parent.sceneToLocal(event.sceneX, event.sceneY)
				draggingLine!!.endX = paneCoords.x
				draggingLine!!.endY = paneCoords.y
				event.consume()
			}
		}
		block.outputCircle.onMouseReleased = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY && draggingLine != null) {
				val paneCoords = parent.sceneToLocal(event.sceneX, event.sceneY)
				val toBlock = blocks.find { other ->
					other != draggingFromBlock &&
							other.inputCircle.localToScene(other.inputCircle.centerX, other.inputCircle.centerY)
								.let { p ->
									val panePoint = parent.sceneToLocal(p.x, p.y)
									val dx = panePoint.x - paneCoords.x
									val dy = panePoint.y - paneCoords.y
									Math.hypot(dx, dy) <= other.inputCircle.radius + 4
								}
				}
				if (toBlock != null) {
					val (startX, startY) = draggingFromBlock!!.outputPoint()
					val (endX, endY) = toBlock.inputPoint()
					draggingLine!!.startX = startX
					draggingLine!!.startY = startY
					draggingLine!!.endX = endX
					draggingLine!!.endY = endY
					val conn = Connection(draggingFromBlock!!, toBlock, draggingLine!!)
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
				} else {
					parent.children.remove(draggingLine)
					draggingLine = null
				}
				draggingFromBlock = null
				event.consume()
			}
		}
	}


	fun exportBlocksToFile(file: File, asXml: Boolean = false) {
		// Генерируем уникальные id для каждого блока
		val blockIds = blocks.withIndex().associate { it.value to it.index }
		val blockList = blocks.map { block ->
			BlockSerialized(
				id = blockIds[block]!!,
				x = block.layoutX,
				y = block.layoutY,
				name = block.name,
				blockType = block.blockType.name,
				inputFormat = block.inputFormat,
				code = block.code,
				dataDocs = block.dataDocs,
				otherInfo = block.otherInfo
			)
		}
		val connList = connections.map {
			ConnectionSerialized(
				fromId = blockIds[it.from]!!,
				toId = blockIds[it.to]!!
			)
		}
		val data = BlocksData(blockList, connList)
		if (asXml) {
			val xmlMapper = XmlMapper()
			xmlMapper.writeValue(file, data)
		} else {
			val mapper = ObjectMapper()
			mapper.writerWithDefaultPrettyPrinter().writeValue(file, data)
		}
	}

	fun importBlocksFromFile(file: File) {
		// Определяем формат по расширению
		val isXml = file.extension.equals("xml", ignoreCase = true)
		val data: BlocksData = if (isXml) {
			XmlMapper().readValue(file, BlocksData::class.java)
		} else {
			jacksonObjectMapper().readValue(file, BlocksData::class.java)
		}

		// Очищаем текущие блоки и соединения
		blocks.clear()
		connections.clear()
		(scrollPane.content as? Pane)?.children?.removeIf { it is BlockNode || it is Line }

		// Восстанавливаем блоки
		val idToBlock = mutableMapOf<Int, BlockNode>()
		data.blocks.forEach { b ->
			val blockType = try {
				BlockType.valueOf(b.blockType)
			} catch (e: Exception) {
				BlockType.MAPPING_GROOVY
			}
			val block = BlockNode(b.x, b.y, b.name, blockType).apply {
				inputFormat = b.inputFormat ?: "JSON"
				code = b.code ?: ""
				dataDocs = b.dataDocs ?: ""
				otherInfo = b.otherInfo ?: ""
			}
			blocks.add(block)
			idToBlock[b.id] = block
			(scrollPane.content as? Pane)?.children?.add(block)
		}
		// Восстанавливаем соединения
		data.connections.forEach { c ->
			val from = idToBlock[c.fromId]
			val to = idToBlock[c.toId]
			if (from != null && to != null) {
				val (startX, startY) = from.outputPoint()
				val (endX, endY) = to.inputPoint()
				val line = Line(startX, startY, endX, endY).apply {
					stroke = Color.BLUE
					strokeWidth = 2.0
				}
				(scrollPane.content as? Pane)?.children?.add(line)
				val conn = Connection(from, to, line)
				connections.add(conn)
				from.connectedLines.add(conn)
				to.connectedLines.add(conn)
				line.onMouseClicked = EventHandler { event ->
					if (event.button == MouseButton.PRIMARY) {
						selectConnection(conn)
						(line.parent as? Pane)?.requestFocus()
						event.consume()
					}
				}
			}
		}
	}


	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			launch(MainApp::class.java)
		}
	}
}
