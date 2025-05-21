package ru.ravel.testjavafx

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
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
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import ru.ravel.testjavafx.model.BlockSerialized
import ru.ravel.testjavafx.model.BlockType
import ru.ravel.testjavafx.model.InputFormatType
import java.util.UUID
import kotlin.math.roundToInt


class BlockNode(
	var x: Double,
	var y: Double,
	var name: String = "",
	var blockType: BlockType = BlockType.MAPPING_GROOVY,
	var code: String = "",
	var inputCount: Int = 1,
	var outputCount: Int = 1,
	var serializedId: UUID = UUID.randomUUID(),
	var inputFormat: InputFormatType = InputFormatType.JSON,
	var dataDocs: String = "",
	var otherInfo: String = "",
	var inputNames: MutableList<String> = MutableList(inputCount) { "in${it}" },
	var outputNames: MutableList<String> = MutableList(outputCount) { "out${it}" },
	var outputsData: MutableList<MutableMap<String, Any>> = mutableListOf(),
	var packagesNames: MutableList<String> = mutableListOf(),
) : Pane() {

	private val width = 150.0
	private val height = 40.0
	private val rect = Rectangle(width, height)
	private val label = Text(name)
	val inputCircles = mutableListOf<Circle>()
	val outputCircles = mutableListOf<Circle>()
	val connectedLines = mutableListOf<Connection>()
	private var dragOffsetX = 0.0
	private var dragOffsetY = 0.0
	var onMove: (() -> Unit)? = null
	var selected: Boolean = false
		set(value) {
			field = value
			rect.stroke = if (value) Color.LIGHTGREEN else Color.DARKGRAY
			rect.strokeWidth = if (value) 4.0 else 2.0
		}
	var executing: Boolean = false
		set(value) {
			field = value
			rect.stroke = if (value) Color.RED else Color.DARKGRAY
			rect.strokeWidth = if (value) 4.0 else 2.0
		}

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
			if (event.button == MouseButton.PRIMARY) {
				val app = scene?.window?.userData as? MainApp
				app?.selectBlock(this)
				if (event.clickCount == 2) {
					showCodeEditor()
					event.consume()
				}
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
			onMove?.invoke()
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

	private fun showCodeEditor() {
		val dialog = Stage()
		dialog.title = "Редактор блока \"$name\""

		val titleTextArea = TextArea().apply {
			prefWidth = 400.0
			prefHeight = 40.0
			text = name
		}

		if (blockType == BlockType.INPUT_DATA) {
			// Форматы
			val formats = InputFormatType.entries
			val toggleGroup = ToggleGroup()
			val radioButtons = formats.map { format ->
				RadioButton(format.name).apply {
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
			val tabPane = TabPane(codeTab, radioTab)
			val saveButton = Button("Сохранить").apply {
				setOnAction {
					name = titleTextArea.text
					label.text = name
					inputFormat = formats[radioButtons.indexOfFirst { it.isSelected }]
					code = codeTextArea.text
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
		} else {
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
			val editableInputsBox = buildEditableInputsBox()
			val editableOutputsBox = buildEditableOutputsBox()
			val configBox = VBox(10.0, editableInputsBox, editableOutputsBox).apply {
				padding = Insets(5.0)
			}
			val configTab = Tab("Конфигурация входов и выходов", configBox).apply { isClosable = false }

			val codeTab = Tab("Код", codeTextArea).apply { isClosable = false }
			val docsTab = Tab("DataDocs", dataDocsTextArea).apply { isClosable = false }
			val tabPane = TabPane(codeTab, docsTab, configTab)
			var pipPackagesBox: VBox? = null
			if (blockType == BlockType.MAPPING_PYTHON) {
				pipPackagesBox = buildEditablePipBox()
				val pipTab = Tab("pip", pipPackagesBox).apply { isClosable = false }
				tabPane.tabs.add(pipTab)
			}
			val saveButton = Button("Сохранить").apply {
				setOnAction {
					if (blockType == BlockType.MAPPING_PYTHON && pipPackagesBox != null) {
						val scrollPane = pipPackagesBox.children[1] as ScrollPane
						val rowsBox = scrollPane.content as VBox
						val newPackages = mutableListOf<String>()
						for (row in rowsBox.children) {
							val box = row as HBox
							val tf = box.children[0] as TextField
							val value = tf.text.trim()
							if (value.isNotBlank()) newPackages.add(value)
						}
						packagesNames = newPackages
					}
					val newInputNames = mutableListOf<String>()
					val scrollPane = editableInputsBox.children[1] as ScrollPane
					val rowsBox = scrollPane.content as VBox
					for (row in rowsBox.children) {
						val box = row as HBox
						val tf = box.children[0] as TextField
						newInputNames.add(tf.text)
					}
					inputNames = newInputNames
					inputCount = newInputNames.size
					val newOutputNames = mutableListOf<String>()
					val outputsScrollPane = editableOutputsBox.children[1] as ScrollPane
					val outputsRowsBox = outputsScrollPane.content as VBox
					for (row in outputsRowsBox.children) {
						val box = row as HBox
						val tf = box.children[0] as TextField
						newOutputNames.add(tf.text)
					}
					outputNames = newOutputNames
					outputCount = newOutputNames.size
					code = codeTextArea.text
					dataDocs = dataDocsTextArea.text
					name = titleTextArea.text
					label.text = name
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
	}

	private fun buildEditableInputsBox(): VBox {
		val inputsBox = VBox(4.0)
		val scrollContent = VBox(4.0)
		val scrollPane = ScrollPane(scrollContent).apply {
			prefHeight = 180.0
			isFitToWidth = true
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		}

		val addBtn = Button("+").apply {
			setOnAction {
				addInputRow(scrollContent)
			}
		}
		val header = HBox(6.0, Label("Входы:"), addBtn)
		inputsBox.children.add(header)
		inputsBox.children.add(scrollPane)
		inputNames.forEach { name ->
			addInputRow(scrollContent, name)
		}
		if (scrollContent.children.isEmpty()) {
			addInputRow(scrollContent)
		}
		return inputsBox
	}

	private fun addInputRow(container: VBox, initialText: String = "") {
		val defaultName = if (initialText.isEmpty()) "in${container.children.size}" else initialText
		val tf = TextField(defaultName)
		lateinit var box: HBox
		val delBtn = Button("–").apply {
			setOnAction {
				if (container.children.size > 1) {
					container.children.remove(box)
				}
			}
		}
		box = HBox(6.0, tf, delBtn)
		box.alignment = Pos.CENTER_LEFT
		container.children.add(box)
	}

	private fun buildEditableOutputsBox(): VBox {
		val outputsBox = VBox(4.0)
		val scrollContent = VBox(4.0)
		val scrollPane = ScrollPane(scrollContent).apply {
			prefHeight = 180.0
			isFitToWidth = true
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		}
		val addBtn = Button("+").apply {
			setOnAction {
				addOutputRow(scrollContent)
			}
		}
		val header = HBox(6.0, Label("Выходы:"), addBtn)
		outputsBox.children.add(header)
		outputsBox.children.add(scrollPane)
		outputNames.forEach { name ->
			addOutputRow(scrollContent, name)
		}
		if (scrollContent.children.isEmpty()) {
			addOutputRow(scrollContent)
		}
		return outputsBox
	}

	private fun addOutputRow(container: VBox, initialText: String = "") {
		val defaultName = if (initialText.isEmpty()) "out${container.children.size}" else initialText
		val tf = TextField(defaultName)
		lateinit var box: HBox
		val delBtn = Button("–").apply {
			setOnAction {
				if (container.children.size > 1) {
					container.children.remove(box)
				}
			}
		}
		box = HBox(6.0, tf, delBtn)
		box.alignment = Pos.CENTER_LEFT
		container.children.add(box)
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
						event.consume()
					}
				}
			}
		}
		(scene?.window?.userData as? MainApp)?.setupHandlersForBlock(this)
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
					Tooltip.install(this, Tooltip(inputNames[i]))
				}
				inputCircles.add(circle)
				children.add(circle)
			}
		}
		// Выходы
		if (blockType != BlockType.EXIT) {
			val step = newHeight / (outputCount + 1)
			for (i in 0 until outputCount) {
				val y = step * (i + 1)
				val circle = Circle(rect.width, y, 7.0, Color.ORANGE).apply {
					stroke = Color.DARKRED
					strokeWidth = 1.6
					Tooltip.install(this, Tooltip(outputNames.getOrNull(i)))
				}
				circle.onMouseClicked = EventHandler { event ->
					if (event.button == MouseButton.PRIMARY && event.clickCount == 1) {
						showOutputData(i)
						event.consume()
					}
				}
				outputCircles.add(circle)
				children.add(circle)
			}
		}
	}


	private fun showOutputData(index: Int) {
		val output = outputsData.getOrNull(index)
		if (output == null) {
			val alert = Alert(Alert.AlertType.INFORMATION, "Нет данных")
			alert.showAndWait()
			return
		}
		val dialog = Stage()
		dialog.title = "Output $index"
		val textArea = TextArea().apply {
			isEditable = false
			prefWidth = 480.0
			prefHeight = 340.0
			font = Font.font("monospace", 14.0)
		}
		val copyBtn = Button("Скопировать в буфер").apply {
			setOnAction {
				val clipboard = Clipboard.getSystemClipboard()
				val content = ClipboardContent()
				content.putString(textArea.text)
				clipboard.setContent(content)
			}
		}
		val formats = InputFormatType.entries
		val toggleGroup = ToggleGroup()
		val radioButtons = formats.map { format ->
			RadioButton(format.name).apply {
				this.toggleGroup = toggleGroup
			}
		}
		radioButtons[0].isSelected = true
		val hBox = HBox(12.0, *radioButtons.toTypedArray()).apply {
			padding = Insets(6.0)
		}

		fun updateTextArea() {
			val selected = formats[radioButtons.indexOfFirst { it.isSelected }]
			val formatted = when (selected) {
				InputFormatType.JSON -> ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output)
				InputFormatType.XML -> XmlMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output)
				InputFormatType.YAML -> {
					val options = DumperOptions().apply {
						defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
						isPrettyFlow = true
						indent = 2
						defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
					}
					val yaml = Yaml(options)
					yaml.dump(output)
				}

				InputFormatType.PROTOBUF -> TODO()
			}
			textArea.text = formatted
		}
		radioButtons.forEach { btn ->
			btn.setOnAction { updateTextArea() }
		}
		updateTextArea()
		val vbox = VBox(10.0, hBox, textArea, copyBtn).apply {
			padding = Insets(12.0)
		}
		dialog.scene = Scene(vbox)
		dialog.show()
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
		id = this.serializedId,
		x = this.layoutX,
		y = this.layoutY,
		name = this.name,
		blockType = this.blockType.name,
		inputFormat = this.inputFormat,
		code = this.code,
		dataDocs = this.dataDocs,
		otherInfo = this.otherInfo,
		inputCount = this.inputCount,
		outputCount = this.outputCount,
		inputNames = this.inputNames.toList(),
		outputNames = this.outputNames.toList(),
		outputsData = outputsData,
		packagesNames = packagesNames,
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
					event.consume()
				}
			}
		}
	}


	private fun buildEditablePipBox(): VBox {
		val pipBox = VBox(4.0)
		val scrollContent = VBox(4.0)
		val scrollPane = ScrollPane(scrollContent).apply {
			prefHeight = 140.0
			isFitToWidth = true
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		}
		val addBtn = Button("+").apply {
			setOnAction { addPipRow(scrollContent) }
		}
		val header = HBox(6.0, Label("pip пакеты:"), addBtn)
		pipBox.children.add(header)
		pipBox.children.add(scrollPane)
		if (packagesNames.isEmpty()) {
			addPipRow(scrollContent)
		} else {
			packagesNames.forEach { addPipRow(scrollContent, it) }
		}
		return pipBox
	}

	private fun addPipRow(container: VBox, initialText: String = "") {
		val tf = TextField(initialText)
		lateinit var box: HBox
		val delBtn = Button("–").apply {
			setOnAction {
				container.children.remove(box)
			}
		}
		box = HBox(6.0, tf, delBtn)
		box.alignment = Pos.CENTER_LEFT
		container.children.add(box)
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

}
