package ru.ravel.testjavafx

import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.event.EventHandler
import javafx.scene.shape.Line
import ru.ravel.testjavafx.model.BlockSerialized
import ru.ravel.testjavafx.model.BlockType
import kotlin.math.roundToInt

class BlockNode(
	var x: Double,
	var y: Double,
	var name: String = "",
	var blockType: BlockType = BlockType.MAPPING_GROOVY,
	var code: String = "",
	var inputCount: Int = 1,
	var outputCount: Int = 1,
	var serializedId: Int? = nextBlockId++
) : Pane() {

	private val width = 100.0
	private val height = 40.0
	private val rect = Rectangle(width, height)
	private val label = Text(name)

	companion object {
		private var nextBlockId = 1
	}

	// Списки для кружочков
	val inputCircles = mutableListOf<Circle>()
	val outputCircles = mutableListOf<Circle>()

	var selected: Boolean = false
		set(value) {
			field = value
			rect.fill = if (value) Color.LIGHTGREEN else blockType.color
		}

	var inputFormat: String = "JSON"

	var dataDocs: String = ""
	var otherInfo: String = ""

	val connectedLines = mutableListOf<Connection>()

	private var dragOffsetX = 0.0
	private var dragOffsetY = 0.0


	init {
		layoutX = x
		layoutY = y
		rect.stroke = Color.BLACK
		rect.fill = blockType.color

		rect.arcWidth = 14.0
		rect.arcHeight = 14.0
		rect.stroke = Color.DARKGRAY
		rect.strokeWidth = 2.0

		label.font = Font(14.0)
		label.x = 15.0
		label.y = 30.0
		label.text = name

		// --- добавление только один раз! ---
		children.add(rect)
		children.add(label)

		createIOCircles()

		// Drag&Drop обработчики

		rect.onMouseReleased = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				snapToGrid()
				updateConnectedLines()
				event.consume()
			}
		}
		label.onMouseReleased = rect.onMouseReleased

		rect.onMousePressed = EventHandler { event -> onBlockPressed(event) }
		rect.onMouseDragged = EventHandler { event -> onBlockDragged(event) }
		label.onMousePressed = rect.onMousePressed
		label.onMouseDragged = rect.onMouseDragged

		this.onMouseClicked = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY && event.clickCount == 2) {
				showCodeEditor()
				event.consume()
			}
		}

		// Контекстное меню по ПКМ на блоке
		this.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.SECONDARY) {
				val contextMenu = ContextMenu()

				val deleteItem = MenuItem("Удалить")
				deleteItem.setOnAction {
					(scene?.window?.userData as? MainApp)?.deleteBlockRequest(this)
				}

				val prevBlocksItem = MenuItem("Предыдущие связанные блоки")
				prevBlocksItem.setOnAction {
					val prevBlocks = connectedLines
						.filter { it.to == this }
						.map { it.from.name }
						.joinToString("\n")
					showAlert("Предыдущие блоки", if (prevBlocks.isNotBlank()) prevBlocks else "Нет связанных")
				}

				val nextBlocksItem = MenuItem("Следующие связанные блоки")
				nextBlocksItem.setOnAction {
					val nextBlocks = connectedLines
						.filter { it.from == this }
						.map { it.to.name }
						.joinToString("\n")
					showAlert("Следующие блоки", if (nextBlocks.isNotBlank()) nextBlocks else "Нет связанных")
				}

				contextMenu.items.addAll(deleteItem, prevBlocksItem, nextBlocksItem)
				contextMenu.show(this, event.screenX, event.screenY)
				event.consume()
			}
		}
	}


	private fun onBlockPressed(event: javafx.scene.input.MouseEvent) {
		if (event.button == MouseButton.PRIMARY) {
			val parentPane = parent as Pane
			val mouseInPane = parentPane.sceneToLocal(event.sceneX, event.sceneY)
			dragOffsetX = mouseInPane.x - layoutX
			dragOffsetY = mouseInPane.y - layoutY
			event.consume()
		}
	}

	private fun onBlockDragged(event: javafx.scene.input.MouseEvent) {
		if (event.button == MouseButton.PRIMARY) {
			val parentPane = parent as Pane
			val mouseInPane = parentPane.sceneToLocal(event.sceneX, event.sceneY)
			layoutX = mouseInPane.x - dragOffsetX
			layoutY = mouseInPane.y - dragOffsetY
			updateConnectedLines()
			event.consume()
		}
	}


	private fun showAlert(title: String, message: String) {
		val alert = Alert(Alert.AlertType.INFORMATION)
		alert.title = title
		alert.headerText = null
		alert.contentText = message
		alert.showAndWait()
	}

	fun showCodeEditor() {
		val dialog = Stage()
		dialog.title = "Редактор блока \"$name\""

		val titleTextArea = TextArea().apply {
			prefWidth = 400.0
			prefHeight = 40.0
			text = name
		}

		if (blockType == BlockType.INPUT_DATA) {
			// Форматы
			val formats = listOf("JSON", "XML", "YAML", "ProtoBuf")
			val toggleGroup = ToggleGroup()
			val radioButtons = formats.map { format ->
				RadioButton(format).apply {
					this.toggleGroup = toggleGroup
					isSelected = (format == inputFormat)
				}
			}
			val radiosBox = VBox(10.0, *radioButtons.toTypedArray()).apply {
				padding = Insets(5.0)
			}
			val radioTab = Tab("Формат", radiosBox).apply { isClosable = false }

			// Код
			val codeTextArea = TextArea().apply {
				prefWidth = 400.0
				prefHeight = 250.0
				text = code
			}
			val codeTab = Tab("Код", codeTextArea).apply { isClosable = false }

			// Конфиг входов/выходов
			val inputSpinner = Spinner<Int>(1, 10, inputCount)
			val outputSpinner = Spinner<Int>(1, 10, outputCount)
			val configBox = VBox(
				10.0,
				HBox(10.0, Label("Входы:"), inputSpinner),
				HBox(10.0, Label("Выходы:"), outputSpinner)
			).apply {
				padding = Insets(5.0)
			}
			val configTab = Tab("Конфигурация входов и выходов", configBox).apply { isClosable = false }

			val tabPane = TabPane(radioTab, codeTab, configTab)

			val saveButton = Button("Сохранить").apply {
				setOnAction {
					name = titleTextArea.text
					label.text = name
					inputFormat = formats[radioButtons.indexOfFirst { it.isSelected }]
					code = codeTextArea.text
					inputCount = inputSpinner.value
					outputCount = outputSpinner.value
					recreateIOCircles()
					dialog.close()
				}
			}

			val vbox = VBox(10.0, titleTextArea, tabPane, saveButton).apply {
				padding = Insets(15.0)
			}
			dialog.scene = Scene(vbox)
			dialog.initModality(Modality.APPLICATION_MODAL)
			dialog.showAndWait()
			return
		}

		// --- Для других типов (оставить старый редактор) ---
		val codeTextArea = TextArea().apply {
			prefWidth = 400.0
			prefHeight = 250.0
			text = code
		}
		val dataDocsTextArea = TextArea().apply {
			prefWidth = 400.0
			prefHeight = 250.0
			text = dataDocs
		}
		// Новая вкладка
		val inputSpinner = Spinner<Int>(1, 10, inputCount)
		val outputSpinner = Spinner<Int>(1, 10, outputCount)
		val configBox = VBox(
			10.0,
			HBox(10.0, Label("Входы:"), inputSpinner),
			HBox(10.0, Label("Выходы:"), outputSpinner)
		).apply {
			padding = Insets(5.0)
		}
		val configTab = Tab("Конфигурация входов и выходов", configBox).apply { isClosable = false }

		val codeTab = Tab("Код", codeTextArea).apply { isClosable = false }
		val docsTab = Tab("DataDocs", dataDocsTextArea).apply { isClosable = false }
		val tabPane = TabPane(codeTab, docsTab, configTab)

		val saveButton = Button("Сохранить").apply {
			setOnAction {
				code = codeTextArea.text
				dataDocs = dataDocsTextArea.text
				name = titleTextArea.text
				label.text = name
				inputCount = inputSpinner.value
				outputCount = outputSpinner.value
				recreateIOCircles()
				dialog.close()
			}
		}

		val vbox = VBox(10.0, titleTextArea, tabPane, saveButton).apply {
			padding = Insets(15.0)
		}
		dialog.scene = Scene(vbox)
		dialog.initModality(Modality.APPLICATION_MODAL)
		dialog.showAndWait()
	}

	private fun recreateIOCircles() {
		// Удалить старые кружки
		children.removeAll(inputCircles)
		children.removeAll(outputCircles)
		inputCircles.clear()
		outputCircles.clear()
		createIOCircles()

		// 1. Удалить соединения с недопустимыми индексами (например, если выходов стало меньше)
		val invalidConnections = connectedLines.filter {
			it.from == this && it.fromPort >= outputCircles.size ||
			it.to == this && it.toPort >= inputCircles.size
		}
		invalidConnections.forEach { conn ->
			(scene?.window?.userData as? MainApp)?.let { app ->
				app.connections.remove(conn)
				conn.from.connectedLines.remove(conn)
				conn.to.connectedLines.remove(conn)
				(conn.line.parent as? Pane)?.children?.remove(conn.line)
			}
		}
		connectedLines.removeAll(invalidConnections)

		// 2. Обновить линии (перепривязать к новым кружкам)
		updateConnectedLines()

		// 3. Переназначить обработчики для новых кружков
		(scene?.window?.userData as? MainApp)?.let { app ->
			rebuildCirclesHandlers { outIndex, outCircle ->
				outCircle.onMousePressed = javafx.event.EventHandler { event ->
					if (event.button == MouseButton.PRIMARY) {
						app.startConnectionFromBlock(this, outIndex)
						event.consume()
					}
				}
				outCircle.onMouseDragged = javafx.event.EventHandler { event ->
					if (event.button == MouseButton.PRIMARY) {
						app.continueConnectionDrag(event)
						event.consume()
					}
				}
				outCircle.onMouseReleased = javafx.event.EventHandler { event ->
					if (event.button == MouseButton.PRIMARY) {
						app.finishConnectionDrag(event)
						event.consume()
					}
				}
			}
		}
		(scene?.window?.userData as? MainApp)?.setupHandlersForBlock(this)
		println("Назначаю обработчики outputCircles.size = ${outputCircles.size}")
	}

	private fun createIOCircles() {
		// Очищаем старые кружки
		children.removeAll(inputCircles)
		children.removeAll(outputCircles)
		inputCircles.clear()
		outputCircles.clear()

		// --- обновляем высоту блока ---
		val newHeight = computeBlockHeight()
		rect.height = newHeight
		this.prefHeight = newHeight

		// Входы
		if (blockType != BlockType.START && blockType != BlockType.INPUT_DATA) {
			val step = newHeight / (inputCount + 1)
			repeat(inputCount) { i ->
				val y = step * (i + 1)
				val circle = Circle(0.0, y, 7.0, Color.LIGHTSKYBLUE).apply {
					stroke = Color.DARKBLUE
					strokeWidth = 1.6
				}
				inputCircles.add(circle)
				children.add(circle)
			}
		}
		// Выходы
		if (blockType != BlockType.EXIT) {
			val step = newHeight / (outputCount + 1)
			repeat(outputCount) { i ->
				val y = step * (i + 1)
				val circle = Circle(rect.width, y, 7.0, Color.ORANGE).apply {
					stroke = Color.DARKRED
					strokeWidth = 1.6
				}
				outputCircles.add(circle)
				children.add(circle)
			}
		}
	}

	fun inputPoint(index: Int = 0): Pair<Double, Double> {
		val circle = inputCircles.getOrNull(index) ?: inputCircles.firstOrNull()
		return if (circle != null)
			Pair(layoutX + circle.centerX, layoutY + circle.centerY)
		else
		// Для специальных блоков возвращаем левый край
			Pair(layoutX, layoutY + height / 2)
	}

	fun outputPoint(index: Int = 0): Pair<Double, Double> {
		val circle = outputCircles.getOrNull(index) ?: outputCircles.firstOrNull()
		return if (circle != null)
			Pair(layoutX + circle.centerX, layoutY + circle.centerY)
		else
		// Для специальных блоков возвращаем правый край
			Pair(layoutX + width, layoutY + height / 2)
	}

	fun updateConnectedLines() {
		connectedLines.forEach { it.updateLine() }
	}

	fun rebuildCirclesHandlers(handler: (outIndex: Int, outCircle: Circle) -> Unit) {
		outputCircles.forEachIndexed { outIndex, outCircle ->
			handler(outIndex, outCircle)
		}
	}

	fun toSerialized(): BlockSerialized = BlockSerialized(
		id = this.serializedId!!,
		x = this.layoutX,
		y = this.layoutY,
		name = this.name,
		blockType = this.blockType.name,
		inputFormat = this.inputFormat,
		code = this.code,
		dataDocs = this.dataDocs,
		otherInfo = this.otherInfo,
		inputCount = this.inputCount,
		outputCount = this.outputCount
	)

	fun updateOutputs() {
		// Удаляем старые выходы из children
		children.removeAll(outputCircles)
		outputCircles.clear()
		for (i in 0 until outputCount) {
			val circle = Circle(width - 10.0, 15.0 + i * 20, 9.0, Color.ORANGE).apply {
				stroke = Color.DARKRED
				strokeWidth = 2.0
			}
			outputCircles.add(circle)
			children.add(circle)

			// Обработчики:
			circle.onMousePressed = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					(scene?.window?.userData as? MainApp)?.let { app ->
						app.startConnectionFromBlock(this@BlockNode, i)
					}
					event.consume()
				}
			}
			circle.onMouseDragged = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					(scene?.window?.userData as? MainApp)?.continueConnectionDrag(event)
					event.consume()
				}
			}
			circle.onMouseReleased = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					(scene?.window?.userData as? MainApp)?.finishConnectionDrag(event)
					event.consume()
				}
			}
		}
	}


	private fun computeBlockHeight(): Double {
		val count = maxOf(inputCount, outputCount)
		val minHeight = 50.0
		val step = 30.0
		return maxOf(minHeight, step * (count + 1))
	}


	private fun snapToGrid(gridSize: Double = 10.0) {
		layoutX = (layoutX / gridSize).roundToInt() * gridSize
		layoutY = (layoutY / gridSize).roundToInt() * gridSize
	}

	fun rebuildOutputsHandlers(mainApp: MainApp) {
		// Очистить старые выходы (если они есть)
		this.outputCircles.forEach { out ->
			(out.parent as? Pane)?.children?.remove(out)
		}
		this.outputCircles.clear()

		// Пересоздать выходы
		for (i in 0 until this.outputCount) {
			val (x, y) = this.outputPoint(i)
			val circle = Circle(x, y, 8.0, Color.ORANGE)
			circle.stroke = Color.DARKRED
			circle.strokeWidth = 2.0

			// Назначить обработчики
			circle.onMousePressed = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					mainApp.selectBlock(this)
					mainApp.contentPane?.requestFocus()
					val (startX, startY) = this.outputPoint(i)
					val line = Line(startX, startY, startX, startY).apply {
						stroke = Color.BLUE
						strokeWidth = 2.0
					}
					mainApp.contentPane?.children?.add(line)
					mainApp.draggingLine = line
					mainApp.draggingFromBlock = this
					mainApp.draggingFromOutputIndex = i
					event.consume()
				}
			}
			circle.onMouseDragged = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY && mainApp.draggingLine != null) {
					val paneCoords = mainApp.contentPane?.sceneToLocal(event.sceneX, event.sceneY)
					if (paneCoords == null) {
						return@EventHandler
					}
					mainApp.draggingLine!!.endX = paneCoords.x
					mainApp.draggingLine!!.endY = paneCoords.y
					event.consume()
				}
			}
			circle.onMouseReleased = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY && mainApp.draggingLine != null) {
					val paneCoords =
						mainApp.contentPane?.sceneToLocal(event.sceneX, event.sceneY) ?: return@EventHandler
					mainApp.draggingLine!!.endX = paneCoords.x
					mainApp.draggingLine!!.endY = paneCoords.y
					val toBlockPair = mainApp.blocks.asSequence().flatMap { other ->
						other.inputCircles.mapIndexed { inputIdx, inputCircle -> Triple(other, inputCircle, inputIdx) }
					}.find { (other, inputCircle, _) ->
						other != mainApp.draggingFromBlock &&
								inputCircle.localToScene(inputCircle.centerX, inputCircle.centerY).let { p ->
									val panePoint = mainApp.contentPane?.sceneToLocal(p.x, p.y)
									val dx = panePoint?.x?.minus(paneCoords.x!!)
									val dy = panePoint?.y?.minus(paneCoords.y!!)
									Math.hypot(dx!!, dy!!) <= inputCircle.radius + 4
								}
					}
					if (toBlockPair != null) {
						val (toBlock, _, inputIdx) = toBlockPair
						val (startX, startY) = mainApp.draggingFromBlock!!.outputPoint(mainApp.draggingFromOutputIndex!!)
						val (endX, endY) = toBlock.inputPoint(inputIdx)
						mainApp.draggingLine!!.startX = startX
						mainApp.draggingLine!!.startY = startY
						mainApp.draggingLine!!.endX = endX
						mainApp.draggingLine!!.endY = endY
						val conn = Connection(
							mainApp.draggingFromBlock!!,
							toBlock,
							mainApp.draggingLine!!,
							mainApp.draggingFromOutputIndex!!,
							inputIdx
						)
						mainApp.connections.add(conn)
						mainApp.draggingFromBlock!!.connectedLines.add(conn)
						toBlock.connectedLines.add(conn)

						conn.line.onMouseClicked = EventHandler { event ->
							if (event.button == MouseButton.PRIMARY) {
								mainApp.selectConnection(conn)
								(conn.line.parent as? Pane)?.requestFocus()
								event.consume()
							}
						}

						mainApp.draggingLine = null
						mainApp.draggingFromOutputIndex = null
					} else {
						mainApp.contentPane?.children?.remove(mainApp.draggingLine)
						mainApp.draggingLine = null
						mainApp.draggingFromOutputIndex = null
					}
					event.consume()
				}
			}
			this.outputCircles.add(circle)
			(this.parent as? Pane)?.children?.add(circle)
		}
	}

}
