package ru.ravel.testjavafx

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import javafx.application.Platform
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
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Modality
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import ru.ravel.testjavafx.model.BlockType
import ru.ravel.testjavafx.model.DeleteBlockCommand
import ru.ravel.testjavafx.model.InputFormatType
import ru.ravel.testjavafx.model.MapAction
import java.io.File
import java.util.*
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
	var subProjectPath: String = "",
	var inputNames: MutableList<String> = MutableList(inputCount) { "in${it}" },
	var outputNames: MutableList<String> = MutableList(outputCount) { "out${it}" },
	var outputsData: MutableList<MutableMap<String, Any>> = mutableListOf(),
	var packagesNames: MutableList<String> = mutableListOf(),
	private var mapKeySettings: MutableMap<String, MapAction> = mutableMapOf(),
	var subProjectProps: MutableMap<String, Any> = mutableMapOf(),
	var endpoint: String = "",
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
//				event.consume()
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

		this.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				onBlockPressed(event)
			}
		}
		this.onMouseDragged = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				onBlockDragged(event)
			}
		}
		this.onMouseReleased = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				snapToGrid()
				updateConnectedLines()
				// Здесь можно дергать MainApp для записи MoveBlockCommand
				val app = scene?.window?.userData as? MainApp
				app?.onBlockReleased(this)
			}
		}

		// Контекстное меню по ПКМ на блоке
		this.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.SECONDARY) {
				val contextMenu = ContextMenu()

				// Пункт «Открыть подпроект» — только для SUB_PROJECT
				if (blockType == BlockType.SUB_PROJECT) {
					val openProjItem = MenuItem("Открыть подпроект")
					openProjItem.setOnAction {
						File(subProjectPath).takeIf { it.exists() }?.also { file ->
							val stage = Stage()
							val subApp = MainApp()
							subApp.importBlocksFromFile(file)
							subApp.importOutputsData(file)
							subApp.start(stage)
						}
					}
					contextMenu.items.add(openProjItem)
				}

				val deleteItem = MenuItem("Удалить")
				deleteItem.setOnAction {
					(scene?.window?.userData as? MainApp)?.runCommand(DeleteBlockCommand(scene?.window?.userData as MainApp, this))
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
			(scene?.window?.userData as? MainApp)?.setPressCoords(this, layoutX, layoutY)
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


	private fun createCodeAreaContextMenu(codeArea: CodeArea): ContextMenu {
		val copy = MenuItem("Копировать")
		copy.setOnAction { codeArea.copy() }
		val cut = MenuItem("Вырезать")
		cut.setOnAction { codeArea.cut() }
		val paste = MenuItem("Вставить")
		paste.setOnAction { codeArea.paste() }
		val selectAll = MenuItem("Выделить всё")
		selectAll.setOnAction { codeArea.selectAll() }
		return ContextMenu(copy, cut, paste, selectAll)
	}


	private fun showCodeEditor() {
		val dialog = Stage()
		dialog.title = "Редактор блока \"$name\""

		val titleTextArea = TextArea().apply {
			prefHeight = 40.0
			text = name
			font = Font("Consolas", 16.0)
		}

		if (blockType in arrayOf(BlockType.INPUT_DATA, BlockType.START, BlockType.PROPERTIES)) {
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

			//                      FIXME
			val (tabPane, codeArea) = if (blockType == BlockType.PROPERTIES) {
				val propsBox = buildEditablePropertiesBox()
				val propsTab = Tab("Свойства", propsBox).apply { isClosable = false }
				Pair(TabPane(propsTab, radioTab), null)
			} else {
				// старое поведение для INPUT_DATA и START
				val codeArea = CodeArea().apply {
					replaceText(code)
					paragraphGraphicFactory = LineNumberFactory.get(this)
					isWrapText = true
					contextMenu = createCodeAreaContextMenu(this)
					style = "-fx-font-size: 16px; -fx-font-family: 'Consolas', 'monospace';"
				}
				val codeScroll = VirtualizedScrollPane(codeArea)
				VBox.setVgrow(codeScroll, Priority.ALWAYS)
				val codeTab = Tab("Код", VBox(codeScroll)).apply { isClosable = false }
				Pair(TabPane(codeTab, radioTab), codeArea)
			}

			VBox.setVgrow(tabPane, Priority.ALWAYS)
			val saveButton = Button("Сохранить").apply {
				setOnAction {
					name = titleTextArea.text
					label.text = name
					inputFormat = formats[radioButtons.indexOfFirst { it.isSelected }]

					if (blockType == BlockType.PROPERTIES) {
						val scrollPane = (tabPane.tabs[0].content as VBox).children[1] as ScrollPane
						val rowsBox = scrollPane.content as VBox
						val newProps = mutableListOf<String>()
						val newData = mutableListOf<MutableMap<String, Any>>()
						for (row in rowsBox.children) {
							val box = row as HBox
							val tfKey = box.children[0] as TextField
							val tfVal = box.children[1] as TextField
							val key = tfKey.text
							newProps.add(key)
							newData.add(mutableMapOf(key to tfVal.text))
						}
						outputNames = newProps
						outputCount = newProps.size
						outputsData = newData
					} else {
						val codeArea =
							((tabPane.tabs[0].content as VBox).children[0] as VirtualizedScrollPane<*>).content as CodeArea
						code = codeArea.text
					}
					recreateIOCircles()
					dialog.close()
				}
			}

			val vbox = VBox(10.0, titleTextArea, tabPane, saveButton).apply {
				padding = Insets(15.0)
				VBox.setVgrow(tabPane, Priority.ALWAYS)
			}
			dialog.scene = Scene(vbox, 720.0, 600.0)
			Platform.runLater { codeArea?.requestFocus() }
			dialog.initModality(Modality.APPLICATION_MODAL)
			dialog.showAndWait()
		} else {
			// --- Для других типов (оставить старый редактор) ---
			val codeArea = CodeArea().apply {
				replaceText(code)
				paragraphGraphicFactory = LineNumberFactory.get(this)
				isWrapText = true
				contextMenu = createCodeAreaContextMenu(this)
				style = "-fx-font-size: 16px; -fx-font-family: 'Consolas', 'monospace';"
			}
			val codeScroll = VirtualizedScrollPane(codeArea)
			VBox.setVgrow(codeScroll, Priority.ALWAYS)

			// Новая вкладка
			val editableInputsBox = buildEditableInputsBox()
			val editableOutputsBox = buildEditableOutputsBox()
			val configBox = VBox(10.0, editableInputsBox, editableOutputsBox).apply {
				padding = Insets(5.0)
			}
			val configTab = Tab("Конфигурация входов и выходов", configBox).apply { isClosable = false }

			val codeTab = Tab("Код", VBox(codeScroll)).apply { isClosable = false }
			val docsTab = Tab("DataDocs", VBox(10.0, buildKeysPane())).apply { isClosable = false }
			val tabPane = if (blockType != BlockType.SUB_PROJECT) {
				TabPane(codeTab, docsTab, configTab)
			} else {
				val tabs = mutableListOf<Tab>()
				tabs += docsTab

				// Загружаем подпроект
				val subFile = File(subProjectPath)
				if (subFile.exists()) {
					val subApp = MainApp()
					subApp.importBlocksFromFile(subFile)

					// все блоки PROPERTIES
					val propBlocks = subApp.blocks.filter { it.blockType == BlockType.PROPERTIES }

					if (propBlocks.isNotEmpty()) {
						val propsBox = VBox(8.0).apply {
							padding = Insets(8.0)
							children.add(Label("Свойства подпроекта:"))

							propBlocks.forEach { propBlock ->
								if (propBlock.outputNames.isNotEmpty()) {
									children.add(Label("Блок: ${propBlock.name}").apply { style = "-fx-font-weight: bold" })
								}
								propBlock.outputNames.forEachIndexed { idx, propName ->
									val tf = TextField().apply {
										promptText = propName
										// читаем сохранённое значение из subProjectProps
										val existing = this@BlockNode.subProjectProps[propName] as? String
										if (existing != null) text = existing

										// при изменении — пишем в subProjectProps
										textProperty().addListener { _, _, newValue ->
											this@BlockNode.subProjectProps[propName] = newValue

											// синхронизируем предпросмотр во внутреннем PROPERTIES
											while (propBlock.outputsData.size <= idx) propBlock.outputsData.add(mutableMapOf())
											propBlock.outputsData[idx] = mutableMapOf(propName to newValue)
										}
									}
									val row = HBox(6.0, Label("$propName:"), tf).apply { alignment = Pos.CENTER_LEFT }
									children.add(row)
								}
							}
						}

						val propsTab = Tab("Свойства", ScrollPane(propsBox).apply {
							isFitToWidth = true
							prefHeight = 220.0
						}).apply { isClosable = false }
						tabs.add(propsTab)
					}
				}

				TabPane(*tabs.toTypedArray())
			}
			VBox.setVgrow(tabPane, Priority.ALWAYS)

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
					code = codeArea.text
					name = titleTextArea.text
					label.text = name
					recreateIOCircles()
					dialog.close()
				}
			}
			val vbox = VBox(10.0, titleTextArea, tabPane, saveButton).apply {
				padding = Insets(15.0)
				VBox.setVgrow(tabPane, Priority.ALWAYS)
			}
			dialog.scene = Scene(vbox, 720.0, 600.0)
			Platform.runLater { codeArea.requestFocus() }
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


	// Новый редактор именно для PROPERTIES
	private fun buildEditablePropertiesBox(): VBox {
		val propsBox = VBox(4.0)
		val scrollContent = VBox(4.0)
		val scrollPane = ScrollPane(scrollContent).apply {
			prefHeight = 180.0
			isFitToWidth = true
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		}
		val addBtn = Button("+").apply {
			setOnAction { addPropertyRow(scrollContent) }
		}
		val header = HBox(6.0, Label("Свойства:"), addBtn)
		propsBox.children.addAll(header, scrollPane)

		// заполняем из outputsData
		outputsData.forEach { map ->
			val (k, v) = map.entries.first()
			addPropertyRow(scrollContent, k, v.toString())
		}
		if (scrollContent.children.isEmpty()) {
			addPropertyRow(scrollContent)
		}
		return propsBox
	}

	// строка для пары ключ-значение
	private fun addPropertyRow(container: VBox, keyText: String = "", valueText: String = "") {
		val tfKey = TextField(keyText.ifBlank { "prop${container.children.size}" })
		val tfVal = TextField(valueText)
		lateinit var box: HBox
		val delBtn = Button("–").apply {
			setOnAction { if (container.children.size > 1) container.children.remove(box) }
		}
		box = HBox(6.0, tfKey, tfVal, delBtn).apply { alignment = Pos.CENTER_LEFT }
		container.children.add(box)
	}


	fun recreateIOCircles() {
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
		if (blockType !in arrayOf(BlockType.START, BlockType.INPUT_DATA, BlockType.PROPERTIES)) {
			val step = newHeight / (inputCount + 1)
			for (i in 0 until inputCount) {
				val y = step * (i + 1)
				val circle = Circle(0.0, y, 7.0, Color.LIGHTSKYBLUE).apply {
					stroke = Color.DARKBLUE
					strokeWidth = 1.6
					Tooltip.install(this, Tooltip(inputNames[i]))
				}
				inputCircles.add(circle)
				children.add(circle)
				circle.isMouseTransparent = true
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

		val codeArea = CodeArea().apply {
			paragraphGraphicFactory = LineNumberFactory.get(this)
			isWrapText = true
			contextMenu = createCodeAreaContextMenu(this)
			style = "-fx-font-size: 16px; -fx-font-family: 'Consolas', 'monospace';"
			isEditable = false
		}
		val scrollPane = VirtualizedScrollPane(codeArea)
		VBox.setVgrow(scrollPane, Priority.ALWAYS)

		val copyBtn = Button("Скопировать в буфер").apply {
			setOnAction {
				val clipboard = Clipboard.getSystemClipboard()
				val content = ClipboardContent()
				content.putString(codeArea.text)
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
			codeArea.replaceText(formatted)
		}
		radioButtons.forEach { btn ->
			btn.setOnAction { updateTextArea() }
		}
		updateTextArea()
		val vbox = VBox(10.0, hBox, scrollPane, copyBtn).apply {
			padding = Insets(12.0)
			VBox.setVgrow(scrollPane, Priority.ALWAYS)
		}
		dialog.scene = Scene(vbox, 720.0, 600.0)
		Platform.runLater { codeArea.requestFocus() }
		dialog.initModality(Modality.APPLICATION_MODAL)
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
		connectedLines.forEach { conn ->
			conn.updateLine()
			val (sx, sy) = conn.from.outputPoint(conn.fromPort)
			val (ex, ey) = conn.to.inputPoint(conn.toPort)
			conn.line.startX = sx
			conn.line.startY = sy
			conn.line.endX = ex
			conn.line.endY = ey
		}
	}

	fun rebuildCirclesHandlers(handler: (outIndex: Int, outCircle: Circle) -> Unit) {
		outputCircles.forEachIndexed { outIndex, outCircle ->
			handler(outIndex, outCircle)
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


	fun updatePorts(newInputs: Int, newOutputs: Int) {
		if (newInputs == inputCount && newOutputs == outputCount) {
			return
		}
		inputCount = newInputs.coerceAtLeast(0)
		outputCount = newOutputs.coerceAtLeast(0)
		inputNames = MutableList(inputCount) { i -> inputNames.getOrElse(i) { "in$i" } }
		outputNames = MutableList(outputCount) { i -> outputNames.getOrElse(i) { "out$i" } }
		recreateIOCircles()
	}


	/** Возвращает ScrollPane с перечнем ключей и RadioButton-ами. */
	private fun buildKeysPane(): ScrollPane {
		val inputsWithMaps = connectedLines
			.filter { it.to == this }
			.sortedBy { it.toPort }
			.map { conn ->
				val portIndex = conn.toPort
				val inName = inputNames
					.getOrNull(portIndex)
					?: "in$portIndex"
				val mp = (conn.from.outputsData
					.getOrNull(conn.fromPort) as? Map<String, Any>)
					?: emptyMap()
				inName to mp
			}
		val compositeKeys = inputsWithMaps
			.flatMap { (inName, mp) -> mp.keys.map { keyName -> "$inName.$keyName" } }
			.toSet()
			.sorted()

		val rows = VBox(8.0).apply { padding = Insets(10.0) }

		if (compositeKeys.isEmpty()) {
			rows.children += Label("Нет входных данных — запустите процесс, чтобы сформировать last_run.json")
		}

		for (compositeKey in compositeKeys) {
			val tg = ToggleGroup()
			val rbSkip = RadioButton("Пропустить дальше").apply { toggleGroup = tg }
			val rbRead = RadioButton("Чтение").apply { toggleGroup = tg }
			val rbEdit = RadioButton("Редактирование").apply { toggleGroup = tg }
			when (mapKeySettings.getOrPut(compositeKey) { MapAction.SKIP }) {
				MapAction.READ -> rbRead.isSelected = true
				MapAction.EDIT -> rbEdit.isSelected = true
				else -> rbSkip.isSelected = true
			}
			tg.selectedToggleProperty().addListener { _, _, newToggle ->
				mapKeySettings[compositeKey] = when (newToggle) {
					rbRead -> MapAction.READ
					rbEdit -> MapAction.EDIT
					else -> MapAction.SKIP
				}
			}
			val row = HBox(10.0, Label(compositeKey), rbSkip, rbRead, rbEdit).apply {
				alignment = Pos.CENTER_LEFT
			}
			rows.children += row
		}

		return ScrollPane(rows).apply {
			isFitToWidth = true
			prefHeight = 220.0
		}
	}

}
