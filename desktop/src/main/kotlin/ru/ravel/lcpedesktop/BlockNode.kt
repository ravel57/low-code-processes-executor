package ru.ravel.lcpedesktop

import com.fasterxml.jackson.databind.JsonNode
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Modality
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.InputFormatType
import java.io.File
import kotlin.math.roundToInt
import com.fasterxml.jackson.databind.ObjectMapper


class BlockNode(
	val core: CoreBlock,
	var x: Double,
	var y: Double,
	// внешние зависимости
	var loadCode: (CoreBlock) -> String = { "" },
	var saveCode: (CoreBlock, String) -> Unit = { _, _ -> },
	private var callbacks: BlockNodeCallbacks = BlockNodeCallbacks(),
) : Pane() {

	private val width = 150.0
	private val height = 40.0
	private val rect = Rectangle(width, height)
	private val label = Text(core.name)
	val inputCircles = mutableListOf<Circle>()
	val outputCircles = mutableListOf<Circle>()
	val connectedLines = mutableListOf<Connection>()   // UI-связи (отрисовка)
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
		rect.fill = Paint.valueOf(core.type.color)
		rect.arcWidth = 14.0
		rect.arcHeight = 14.0
		rect.stroke = Color.DARKGRAY
		rect.strokeWidth = 2.0

		label.font = Font(14.0)
		label.x = 15.0
		label.y = 30.0
		label.text = core.name

		children.addAll(rect, label)
		createIOCircles()

		// --- поведение: выбор/перетаскивание/выпуск ---
		val pressHandler = EventHandler<javafx.scene.input.MouseEvent> { e ->
			if (e.button == MouseButton.PRIMARY) {
				val parentPane = parent as Pane
				val p = parentPane.sceneToLocal(e.sceneX, e.sceneY)
				dragOffsetX = p.x - layoutX
				dragOffsetY = p.y - layoutY
				callbacks.onSelect(this)
				e.consume()
			}
		}
		val dragHandler = EventHandler<javafx.scene.input.MouseEvent> { e ->
			if (e.button == MouseButton.PRIMARY) {
				val parentPane = parent as Pane
				val p = parentPane.sceneToLocal(e.sceneX, e.sceneY)
				layoutX = p.x - dragOffsetX
				layoutY = p.y - dragOffsetY
				updateConnectedLines()
				onMove?.invoke()
				e.consume()
			}
		}
		val releaseHandler = EventHandler<javafx.scene.input.MouseEvent> { e ->
			if (e.button == MouseButton.PRIMARY) {
				snapToGrid()
				updateConnectedLines()
				callbacks.onReleased(this)
				e.consume()
			}
		}

		rect.onMousePressed = pressHandler
		rect.onMouseDragged = dragHandler
		rect.onMouseReleased = releaseHandler
		label.onMousePressed = pressHandler
		label.onMouseDragged = dragHandler
		label.onMouseReleased = releaseHandler

		// Двойной клик — открыть редактор блока
		this.onMouseClicked = EventHandler { e ->
			if (e.button == MouseButton.PRIMARY && e.clickCount == 2) {
				showCodeEditor()
				e.consume()
			}
		}

		// Контекстное меню (без бизнес-логики)
		this.onMousePressed = EventHandler { e ->
			if (e.button == MouseButton.SECONDARY) {
				val menu = ContextMenu()
				if (core.type == BlockType.SUB_PROJECT) {
					menu.items += MenuItem("Открыть подпроект").apply {
						setOnAction {
							core.subProjectPath.let { path ->
								val file = File(path)
								if (file.exists()) {
									callbacks.onOpenSubProject(file)
								} else {
									Alert(Alert.AlertType.WARNING).apply {
										title = "Подпроект"
										headerText = "Файл не найден"
										contentText = path
									}.showAndWait()
								}
							}
						}
					}
				}
				menu.items += MenuItem("Удалить").apply {
					setOnAction { callbacks.onDeleteRequested(this@BlockNode) }
				}
				// simple инспекция связей (UI)
				menu.items += MenuItem("Предыдущие блоки").apply {
					setOnAction {
						val prev = connectedLines.filter { it.to == this@BlockNode }
							.joinToString("\n") { it.from.core.name }
						showInfo("Предыдущие блоки", prev.ifBlank { "Нет связанных" })
					}
				}
				menu.items += MenuItem("Следующие блоки").apply {
					setOnAction {
						val next = connectedLines.filter { it.from == this@BlockNode }
							.joinToString("\n") { it.to.core.name }
						showInfo("Следующие блоки", next.ifBlank { "Нет связанных" })
					}
				}
				menu.show(this, e.screenX, e.screenY)
				e.consume()
			}
		}
	}

	// ---------- UI-вспомогательные ----------

	private fun showInfo(title: String, message: String) {
		Alert(Alert.AlertType.INFORMATION).apply {
			this.title = title
			headerText = null
			contentText = message
		}.showAndWait()
	}

	private fun createCodeAreaContextMenu(codeArea: CodeArea) = ContextMenu(
		MenuItem("Копировать").apply { setOnAction { codeArea.copy() } },
		MenuItem("Вырезать").apply { setOnAction { codeArea.cut() } },
		MenuItem("Вставить").apply { setOnAction { codeArea.paste() } },
		MenuItem("Выделить всё").apply { setOnAction { codeArea.selectAll() } },
	)

	/** Только UI редактор. Текст кода берём/сохраняем через `loadCode/saveCode`. */
	private fun showCodeEditor() {
		val dialog = Stage()
		dialog.title = "Редактор блока \"${core.name}\""

		val titleText = TextArea(core.name).apply {
			prefHeight = 40.0
			font = Font("Consolas", 16.0)
		}

		// фабрики вкладок
		fun makeCodeTab(): Tab {
			val codeArea = CodeArea().apply {
				replaceText(loadCode(core))
				paragraphGraphicFactory = LineNumberFactory.get(this)
				isWrapText = true
				contextMenu = createCodeAreaContextMenu(this)
				style = "-fx-font-size: 16px; -fx-font-family: 'Consolas', 'monospace';"
			}
			val codeScroll = VirtualizedScrollPane(codeArea)
			VBox.setVgrow(codeScroll, Priority.ALWAYS)
			return Tab("Код", VBox(codeScroll)).apply { isClosable = false }
		}

		fun makeFormatTab(): Tab {
			val group = ToggleGroup()
			val radios = InputFormatType.entries.map { fmt ->
				RadioButton(fmt.name).apply {
					toggleGroup = group
					isSelected = (fmt == core.inputFormat)
					setOnAction { core.inputFormat = fmt }
				}
			}
			val box = VBox(8.0, *radios.toTypedArray()).apply { padding = Insets(8.0) }
			return Tab("Формат", box).apply { isClosable = false }
		}

		fun makeIOTab(): Tab {
			val inputsBox = buildEditableInputsBox()
			val outputsBox = buildEditableOutputsBox()
			val io = VBox(10.0, inputsBox, outputsBox).apply { padding = Insets(8.0) }
			return Tab("I/O", io).apply { isClosable = false }
		}

		fun makeSubProjectPropsTab(): Tab? {
			val path = core.subProjectPath
			val projectFile = File(path)
			if (!projectFile.exists()) {
				return null
			}
			val baseDir = projectFile.parentFile

			val mapper = ObjectMapper().findAndRegisterModules()
			val root = try {
				mapper.readTree(projectFile)
			} catch (_: Exception) {
				return null
			}
			val blocksNode = root.get("blocks") ?: return null

			// 1) Соберём все PROPERTIES
			val propsBlocks: MutableList<JsonNode> = mutableListOf()
			blocksNode.forEach { b ->
				val t: String? = b.get("type")?.asText() ?: b.get("blockType")?.asText()
				if (t == "PROPERTIES") propsBlocks.add(b)
			}
			if (propsBlocks.isEmpty()) return null

			// 2) Выберем «правильный» PROPERTIES:
			//    приоритет — совпадение имени с именем SUB_PROJECT блока
			val selected = propsBlocks.firstOrNull { pb ->
				val nm = pb.get("name")?.asText() ?: ""
				nm.equals(core.name, ignoreCase = true)
			} ?: propsBlocks.first()

			// 3) Имена свойств из выбранного PROPERTIES
			val propNames: List<String> = selected.get("outputNames")?.map { it.asText() } ?: emptyList()
			if (propNames.isEmpty()) {
				return null
			}
			// 4) Дефолты из codePath/codeFile выбранного PROPERTIES
			val codePathStr = selected.get("codeFile")?.asText()
			val defaults: Map<String, Any?> = try {
				if (codePathStr != null) {
					val file = File(codePathStr).let { if (it.isAbsolute) it else File(baseDir, codePathStr) }
					if (file.exists()) {
						mapper.readValue(file, Map::class.java) as Map<String, Any?>
					} else {
						emptyMap()
					}
				} else {
					emptyMap()
				}
			} catch (_: Exception) {
				emptyMap()
			}

			// 5) UI
			val rows = VBox(6.0).apply { padding = Insets(8.0) }
			propNames.forEach { propName ->
				val value = (core.subProjectProps[propName] as? String)?.takeIf { it.isNotBlank() }
					?: defaults[propName]?.toString()
					?: ""
				val tf = TextField(value).apply {
					promptText = propName
					textProperty().addListener { _, _, v -> core.subProjectProps[propName] = v }
				}
				rows.children += HBox(8.0, Label("$propName:"), tf).apply { alignment = Pos.CENTER_LEFT }
			}

			return Tab("Свойства", ScrollPane(rows).apply { isFitToWidth = true; prefHeight = 240.0 })
				.apply { isClosable = false }
		}

		val tabs = when (core.type) {
			BlockType.START, BlockType.EXIT -> {
				null
			}

			BlockType.INPUT_DATA -> {
				TabPane(makeCodeTab(), makeFormatTab())
			}

			BlockType.PROPERTIES -> {
				val rows = VBox(6.0).apply { padding = Insets(8.0) }

				// --- ПОДГРУЗКА ИЗ ФАЙЛА/КОДА ---
				if (core.outputNames.isEmpty() && core.outputsData.isEmpty()) {
					// Берём текст через лямбду, чтобы работали относительные пути
					val text = loadCode(core).trim()
					if (text.isNotBlank()) {
						try {
							val parsed = ObjectMapper()
								.readValue(text, Map::class.java) as Map<String, Any?>
							if (parsed.isNotEmpty()) {
								core.outputNames = parsed.keys.toMutableList()
								core.outputCount = core.outputNames.size
								core.outputsData = parsed.map { (k, v) -> mutableMapOf(k to v) }.toMutableList()
							}
						} catch (_: Exception) {
							// тихо игнорируем, оставим пустой ui
						}
					}
				}

				// --- СБОР ИМЁН ДЛЯ UI ---
				val names: List<String> = when {
					core.outputNames.isNotEmpty() -> core.outputNames
					core.outputsData.isNotEmpty() -> core.outputsData.mapIndexed { idx, m -> m.keys.firstOrNull() ?: "prop$idx" }
					core.subProjectProps.isNotEmpty() -> core.subProjectProps.keys.toList()
					else -> emptyList()
				}

				names.forEachIndexed { idx, name ->
					val value = core.outputsData.getOrNull(idx)?.get(name)?.toString()
						?: (core.subProjectProps[name] as? String)
						?: ""
					val tfKey = TextField(name)
					val tfVal = TextField(value)
					rows.children += HBox(8.0, tfKey, tfVal).apply { alignment = Pos.CENTER_LEFT }
				}
				if (rows.children.isEmpty()) {
					rows.children += HBox(8.0, TextField("prop0"), TextField(""))
						.apply { alignment = Pos.CENTER_LEFT }
				}

				TabPane(
					Tab("Свойства", ScrollPane(rows).apply { isFitToWidth = true; prefHeight = 240.0 })
						.apply { isClosable = false }
				)
			}

			BlockType.SUB_PROJECT -> {
				val tabPane = TabPane()
				makeSubProjectPropsTab()?.let { tabPane.tabs += it }
				tabPane
			}

			else -> {
				val tabPane = TabPane(makeCodeTab(), makeIOTab())
				if (core.type == BlockType.MAPPING_PYTHON) {
					val pipBox = buildEditablePipBox()
					val pipTab = Tab("pip", pipBox).apply { isClosable = false }
					tabPane.tabs.add(pipTab)
				}
				tabPane
			}
		}

		val saveBtn = Button("Сохранить").apply {
			setOnAction {
				// имя
				core.name = titleText.text
				label.text = core.name

				// сохранить код, если есть вкладка «Код»
				tabs?.tabs?.firstOrNull { it.text == "Код" }?.let { codeTab ->
					val codeArea = (((codeTab.content as VBox).children[0]) as VirtualizedScrollPane<*>).content as CodeArea
					saveCode(core, codeArea.text)
				}

				if (core.type == BlockType.PROPERTIES) {
					val tab = tabs?.tabs?.firstOrNull { it.text == "Свойства" }
					if (tab != null) {
						val vbox = ((tab.content as ScrollPane).content as VBox)
						val newProps = mutableListOf<String>()
						val newData = mutableListOf<MutableMap<String, Any?>>()
						vbox.children.forEachIndexed { idx, row ->
							val box = row as HBox
							val tfKey = box.children[0] as TextField
							val tfVal = box.children[1] as TextField
							val key = tfKey.text.ifBlank { "prop$idx" }
							newProps += key
							newData += mutableMapOf(key to tfVal.text)
						}
						core.outputNames = newProps.toMutableList()
						core.outputCount = newProps.size
						core.outputsData = newData.toMutableList()

						val mapForFile = core.outputsData
							.mapNotNull { it.entries.firstOrNull()?.let { e -> e.key to e.value } }
							.toMap()
						val json = ObjectMapper()
							.writerWithDefaultPrettyPrinter()
							.writeValueAsString(mapForFile)
						saveCode(core, json)   // сохраняем туда же, куда читали
					}
				}

				// если есть «I/O» — забираем поля
				tabs?.tabs?.firstOrNull { it.text == "I/O" }?.let { ioTab ->
					val vbox = ioTab.content as VBox
					val inputsBox = (vbox.children[0] as VBox)
					val outputsBox = (vbox.children[1] as VBox)
					val inRows = (inputsBox.children[1] as ScrollPane).content as VBox
					core.inputNames = inRows.children.mapIndexed { idx, row ->
						((row as HBox).children[0] as TextField).text.ifBlank { "in$idx" }
					}.toMutableList()
					core.inputCount = core.inputNames.size

					val outRows = (outputsBox.children[1] as ScrollPane).content as VBox
					core.outputNames = outRows.children.mapIndexed { idx, row ->
						((row as HBox).children[0] as TextField).text.ifBlank { "out$idx" }
					}.toMutableList()
					core.outputCount = core.outputNames.size
				}

				if (core.type == BlockType.MAPPING_PYTHON) {
					tabs?.tabs?.firstOrNull { it.text == "pip" }?.let { pipTab ->
						val vbox = pipTab.content as VBox
						val scroll = vbox.children[1] as ScrollPane
						val rows = scroll.content as VBox
						val newPackages = rows.children.mapNotNull { row ->
							val tf = (row as HBox).children[0] as TextField
							tf.text.trim().takeIf { it.isNotBlank() }
						}
						core.packagesNames = newPackages.toMutableList()
					}
				}

				recreateIOCircles()
				callbacks.onModelChanged(core)
				dialog.close()
			}
		}

		val content = if (tabs == null) VBox(10.0, titleText, saveBtn) else VBox(10.0, titleText, tabs, saveBtn)
		content.padding = Insets(15.0)
		if (tabs != null) VBox.setVgrow(tabs, Priority.ALWAYS)

		dialog.scene = Scene(content, 720.0, if (tabs == null) 180.0 else 600.0)
		Platform.runLater {
			tabs?.tabs?.firstOrNull { it.text == "Код" }?.let { _ -> content.lookupAll(".code-area") }
		}
		dialog.initModality(Modality.APPLICATION_MODAL)
		dialog.showAndWait()
	}


	private fun buildEditablePipBox(): VBox {
		val pipBox = VBox(4.0)
		val scrollContent = VBox(4.0)
		val scrollPane = ScrollPane(scrollContent).apply {
			prefHeight = 180.0
			isFitToWidth = true
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		}
		val addBtn = Button("+").apply {
			setOnAction { addPipRow(scrollContent) }
		}
		val header = HBox(6.0, Label("pip пакеты:"), addBtn)
		pipBox.children.addAll(header, scrollPane)
		core.packagesNames.forEach { pkg ->
			addPipRow(scrollContent, pkg)
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
		box = HBox(6.0, tf, delBtn).apply { alignment = Pos.CENTER_LEFT }
		container.children.add(box)
	}


	private fun buildEditableInputsBox(): VBox {
		val rows = VBox(4.0)
		val scroll =
			ScrollPane(rows).apply { prefHeight = 180.0; isFitToWidth = true; vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS }
		core.inputNames.forEach { addInputRow(rows, it) }
		if (rows.children.isEmpty()) addInputRow(rows)
		val addBtn = Button("+").apply { setOnAction { addInputRow(rows) } }
		return VBox(4.0, HBox(6.0, Label("Входы:"), addBtn), scroll)
	}

	private fun addInputRow(container: VBox, initial: String = "") {
		val tf = TextField(initial.ifBlank { "in${container.children.size}" })
		lateinit var row: HBox
		row = HBox(6.0, tf, Button("–").apply {
			setOnAction { if (container.children.size > 1) container.children.remove(row) }
		}).apply { alignment = Pos.CENTER_LEFT }
		container.children += row
	}

	private fun buildEditableOutputsBox(): VBox {
		val rows = VBox(4.0)
		val scroll =
			ScrollPane(rows).apply { prefHeight = 180.0; isFitToWidth = true; vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS }
		core.outputNames.forEach { addOutputRow(rows, it) }
		if (rows.children.isEmpty()) addOutputRow(rows)
		val addBtn = Button("+").apply { setOnAction { addOutputRow(rows) } }
		return VBox(4.0, HBox(6.0, Label("Выходы:"), addBtn), scroll)
	}

	private fun addOutputRow(container: VBox, initial: String = "") {
		val tf = TextField(initial.ifBlank { "out${container.children.size}" })
		lateinit var row: HBox
		row = HBox(6.0, tf, Button("–").apply {
			setOnAction { if (container.children.size > 1) container.children.remove(row) }
		}).apply { alignment = Pos.CENTER_LEFT }
		container.children += row
	}

	fun recreateIOCircles() {
		children.removeAll(inputCircles); children.removeAll(outputCircles)
		inputCircles.clear(); outputCircles.clear()
		createIOCircles()

		// отдать контейнеру список связей, ставших недействительными
		val invalid = connectedLines.filter {
			it.from == this && it.fromPort >= outputCircles.size ||
					it.to == this && it.toPort >= inputCircles.size
		}
		callbacks.onInvalidConnections(invalid)
		connectedLines.removeAll(invalid)

		updateConnectedLines()

		// переназначить обработчики на новых кружках
		rebuildCirclesHandlers { outIndex, outCircle ->
			outCircle.onMousePressed = EventHandler { e ->
				if (e.button == MouseButton.PRIMARY) {
					callbacks.onDragStartConnection(this, outIndex); e.consume()
				}
			}
			outCircle.onMouseDragged = EventHandler { e ->
				if (e.button == MouseButton.PRIMARY) {
					callbacks.onDragContinueConnection(e.sceneX, e.sceneY); e.consume()
				}
			}
			outCircle.onMouseReleased = EventHandler { e ->
				if (e.button == MouseButton.PRIMARY) {
					callbacks.onDragFinishConnection(); e.consume()
				}
			}
		}
	}

	private fun createIOCircles() {
		val newHeight = computeBlockHeight()
		rect.height = newHeight; this.prefHeight = newHeight

		if (core.type !in arrayOf(BlockType.START, BlockType.INPUT_DATA, BlockType.PROPERTIES)) {
			val step = newHeight / (core.inputCount + 1)
			repeat(core.inputCount) { i ->
				val c = Circle(0.0, step * (i + 1), 7.0, Color.LIGHTSKYBLUE).apply {
					stroke = Color.DARKBLUE; strokeWidth = 1.6
					Tooltip.install(this, Tooltip(core.inputNames.getOrNull(i) ?: "in$i"))
				}
				inputCircles += c; children += c
			}
		}
		if (core.type != BlockType.EXIT) {
			val step = newHeight / (core.outputCount + 1)
			repeat(core.outputCount) { i ->
				val c = Circle(rect.width, step * (i + 1), 7.0, Color.ORANGE).apply {
					stroke = Color.DARKRED; strokeWidth = 1.6
					Tooltip.install(this, Tooltip(core.outputNames.getOrNull(i) ?: "out$i"))
					onMouseClicked = EventHandler { ev ->
						if (ev.button == MouseButton.PRIMARY && ev.clickCount == 1) {
							val app = (scene?.window?.userData as? MainApp) ?: return@EventHandler
							app.showOutputFor(this@BlockNode, i)   // «как раньше»
							ev.consume()
						}
					}
				}
				outputCircles += c; children += c
			}
		}
	}

	fun inputPoint(index: Int = 0): Pair<Double, Double> {
		val circle = inputCircles.getOrNull(index) ?: inputCircles.firstOrNull()
		return if (circle != null) layoutX + circle.centerX to layoutY + circle.centerY
		else layoutX to (layoutY + height / 2)
	}

	fun outputPoint(index: Int = 0): Pair<Double, Double> {
		val circle = outputCircles.getOrNull(index) ?: outputCircles.firstOrNull()
		return if (circle != null) layoutX + circle.centerX to layoutY + circle.centerY
		else (layoutX + width) to (layoutY + height / 2)
	}

	fun updateConnectedLines() {
		connectedLines.forEach { conn ->
			conn.updateLine()
			val (sx, sy) = conn.from.outputPoint(conn.fromPort)
			val (ex, ey) = conn.to.inputPoint(conn.toPort)
			conn.line.startX = sx; conn.line.startY = sy
			conn.line.endX = ex; conn.line.endY = ey
		}
	}

	fun rebuildCirclesHandlers(handler: (outIndex: Int, outCircle: Circle) -> Unit) {
		outputCircles.forEachIndexed(handler)
	}

	private fun computeBlockHeight(): Double {
		val count = maxOf(core.inputCount, core.outputCount)
		val minHeight = 50.0
		val step = 30.0
		return maxOf(minHeight, step * (count + 1))
	}

	private fun snapToGrid(gridSize: Double = 10.0) {
		layoutX = (layoutX / gridSize).roundToInt() * gridSize
		layoutY = (layoutY / gridSize).roundToInt() * gridSize
	}

}