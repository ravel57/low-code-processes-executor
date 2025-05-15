package ru.ravel.testjavafx

import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.MouseButton
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

class BlockNode(
	var x: Double,
	var y: Double,
	var name: String = "",
	var blockType: BlockType = BlockType.MAPPING_GROOVY,
	var code: String = ""
) : Pane() {
	private val width = 100.0
	private val height = 40.0
	private val rect = Rectangle(width, height)
	private val label = Text(name)

	val inputCircle = Circle(10.0, height / 2, 10.0, Color.LIGHTSKYBLUE).apply {
		stroke = Color.DARKBLUE
		strokeWidth = 2.0
	}
	val outputCircle = Circle(width - 10.0, height / 2, 9.0, Color.ORANGE).apply {
		stroke = Color.DARKRED
		strokeWidth = 2.0
	}

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
		children.add(rect)
		label.font = Font(14.0)
		label.x = 10.0
		label.y = height / 2 + 5.0
		label.text = name
		children.add(label)
		if (blockType != BlockType.START && blockType != BlockType.INPUT_DATA) {
			children.add(inputCircle)
		}
		if (blockType != BlockType.EXIT) {
			children.add(outputCircle)
		}

		rect.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				(this.scene?.window?.userData as? MainApp)?.selectBlock(this)
				(this.parent as? Pane)?.requestFocus()
				val parentPane = parent as Pane
				val mouseInPane = parentPane.sceneToLocal(event.sceneX, event.sceneY)
				dragOffsetX = mouseInPane.x - layoutX
				dragOffsetY = mouseInPane.y - layoutY
				event.consume()
			}
		}
		rect.onMouseDragged = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				val parentPane = parent as Pane
				val mouseInPane = parentPane.sceneToLocal(event.sceneX, event.sceneY)
				layoutX = mouseInPane.x - dragOffsetX
				layoutY = mouseInPane.y - dragOffsetY
				updateConnectedLines()
				event.consume()
			}
		}
		label.onMousePressed = rect.onMousePressed
		label.onMouseDragged = rect.onMouseDragged

		inputCircle.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				(this.scene?.window?.userData as? MainApp)?.selectBlock(this)
				(this.parent as? Pane)?.requestFocus()
				event.consume()
			}
		}
		inputCircle.isPickOnBounds = true
		inputCircle.onMouseDragged = EventHandler { it.consume() }
		outputCircle.isPickOnBounds = true

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

		// --- Для блока INPUT_DATA ---
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

			val tabPane = TabPane(radioTab, codeTab)

			val saveButton = Button("Сохранить").apply {
				setOnAction {
					name = titleTextArea.text
					label.text = name
					// сохранить выбранный формат
					inputFormat = formats[radioButtons.indexOfFirst { it.isSelected }]
					code = codeTextArea.text
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
		val otherTextArea = TextArea().apply {
			prefWidth = 400.0
			prefHeight = 250.0
			text = otherInfo
		}

		val codeTab = Tab("Код", codeTextArea).apply { isClosable = false }
		val docsTab = Tab("DataDocs", dataDocsTextArea).apply { isClosable = false }
		val otherTab = Tab("Другое", otherTextArea).apply { isClosable = false }
		val tabPane = TabPane(codeTab, docsTab, otherTab)

		val saveButton = Button("Сохранить").apply {
			setOnAction {
				code = codeTextArea.text
				dataDocs = dataDocsTextArea.text
				otherInfo = otherTextArea.text
				name = titleTextArea.text
				label.text = name
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

	fun inputPoint() = Pair(layoutX + inputCircle.centerX, layoutY + inputCircle.centerY)
	fun outputPoint() = Pair(layoutX + outputCircle.centerX, layoutY + outputCircle.centerY)

	fun updateConnectedLines() {
		connectedLines.forEach { it.updateLine() }
	}
}