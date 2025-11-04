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
import java.io.File
import kotlin.math.roundToInt
import com.fasterxml.jackson.databind.ObjectMapper
import javafx.collections.FXCollections
import javafx.scene.Group
import javafx.scene.input.ClipboardContent
import javafx.scene.input.MouseEvent
import javafx.scene.input.TransferMode
import javafx.util.Callback
import ru.ravel.lcpecore.model.*
import java.util.*


class BlockNode(
	val core: CoreBlock,
	var x: Double,
	var y: Double,
	// внешние зависимости
	var loadCode: (CoreBlock) -> String = { "" },
	var saveCode: (CoreBlock, String) -> Unit = { _, _ -> },
	var loadPreProcessingCode: (CoreBlock) -> String = { "" },
	var loadPostProcessingCode: (CoreBlock, String, String) -> String = { _, _, _ -> "" },
	var savePreProcessingCode: (CoreBlock, String) -> Unit = { _, _ -> },
	var savePostProcessingCode: (CoreBlock, String, String, String) -> Unit = { _, _, _, _ -> },
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
		val pressHandler = EventHandler<MouseEvent> { e ->
			if (e.button == MouseButton.PRIMARY) {
				when (val p = parent) {
					is Pane -> {
						p.children.remove(this)
						p.children.add(this)
					}

					is Group -> {
						p.children.remove(this)
						p.children.add(this)
					}
				}
				dragOffsetX = e.sceneX - layoutX
				dragOffsetY = e.sceneY - layoutY
				callbacks.onSelect(this)
				e.consume()
			}
		}
		val dragHandler = EventHandler<MouseEvent> { e ->
			if (e.button == MouseButton.PRIMARY) {
				layoutX = e.sceneX - dragOffsetX
				layoutY = e.sceneY - dragOffsetY
				updateConnectedLines()
				onMove?.invoke()
				e.consume()
			}
		}
		val releaseHandler = EventHandler<MouseEvent> { e ->
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
				if (core.type == BlockType.SUB_PROCESS) {
					menu.items += MenuItem("Открыть подпроцесс").apply {
						setOnAction {
							core.subProjectPath.let { path ->
								val app = (scene?.window?.userData as? MainApp)
								val file = app?.resolveProjectFile(path) ?: File(path)
								if (file.exists()) {
									callbacks.onOpenSubProject(file)
								} else {
									Alert(Alert.AlertType.WARNING).apply {
										title = "Подпроцесс"
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

		fun makePreProcessingTab(): Tab {
			val codeArea = CodeArea().apply {
				replaceText(loadPreProcessingCode(core))
				paragraphGraphicFactory = LineNumberFactory.get(this)
				isWrapText = true
				contextMenu = createCodeAreaContextMenu(this)
				style = "-fx-font-size: 16px; -fx-font-family: 'Consolas', 'monospace';"
			}
			val codeScroll = VirtualizedScrollPane(codeArea)
			VBox.setVgrow(codeScroll, Priority.ALWAYS)
			return Tab("PreProcessing", VBox(codeScroll)).apply { isClosable = false }
		}

		fun makePostProcessingTab(): Tab {
			val codeAreasBox = VBox(16.0)
			val baseCode = loadCode(core)

			// --- 1. Парсим JSON и собираем все действия ("action") ---
			val actions = try {
				val root = ObjectMapper().readTree(baseCode)
				val children = root.get("children")
				children?.flatMap { ch ->
					val act = ch.get("action")?.asText()
					if (act != null) listOf(act) else emptyList()
				} ?: emptyList()
			} catch (_: Exception) {
				emptyList()
			}

			val allActions = actions.ifEmpty { listOf("default") }

			// --- 2. Создаём по 2 CodeArea для каждого action ---
			allActions.forEach { act ->
				val lblMain = Label("Postprocessing для действия: $act")
				val lblSubmit = Label("Данные в функцию submit")

				// Основное поле (используем переданную функцию loadPostProcessingCode)
				val codeAreaMain = CodeArea().apply {
					replaceText(loadPostProcessingCode(core, act, "main"))
					paragraphGraphicFactory = LineNumberFactory.get(this)
					isWrapText = true
					contextMenu = createCodeAreaContextMenu(this)
					style = "-fx-font-size: 15px; -fx-font-family: 'Consolas', 'monospace';"
				}

				// Поле onSubmit — используем ту же логику, но можно сделать отдельное имя файла
				val codeAreaSubmit = CodeArea().apply {
					val submitFile = loadPostProcessingCode(core, act, "submit") // или отдельная loadSubmitCode(core)
					replaceText(submitFile.ifBlank { "" })
					paragraphGraphicFactory = LineNumberFactory.get(this)
					isWrapText = true
					contextMenu = createCodeAreaContextMenu(this)
					style = "-fx-font-size: 15px; -fx-font-family: 'Consolas', 'monospace';"
				}

				val scrollMain = VirtualizedScrollPane(codeAreaMain).apply { VBox.setVgrow(this, Priority.ALWAYS) }
				val scrollSubmit = VirtualizedScrollPane(codeAreaSubmit).apply { VBox.setVgrow(this, Priority.ALWAYS) }
				val mainBox = VBox(6.0, lblMain, scrollMain).apply {
					VBox.setVgrow(scrollMain, Priority.ALWAYS)
					prefWidth = 0.5
				}
				val submitBox = VBox(6.0, lblSubmit, scrollSubmit).apply {
					VBox.setVgrow(scrollSubmit, Priority.ALWAYS)
					prefWidth = 0.5
				}
				val row = HBox(12.0, mainBox, submitBox).apply {
					HBox.setHgrow(mainBox, Priority.ALWAYS)
					HBox.setHgrow(submitBox, Priority.ALWAYS)
					alignment = Pos.TOP_CENTER
				}
				codeAreasBox.children += row
			}

			return Tab("PostProcessing", ScrollPane(codeAreasBox).apply {
				isFitToWidth = true
				prefHeight = 600.0
			}).apply { isClosable = false }
		}

		fun makeFormatTab(): Tab {
			val group = ToggleGroup()
			val radios = InputFormatType.entries.map { fmt ->
				RadioButton(fmt.name).apply {
					toggleGroup = group
					isSelected = (fmt == core.inputFormat)
					setOnAction {
						core.inputFormat = fmt
					}
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
			val app = (scene?.window?.userData as? MainApp)
			val projectFile = (app?.resolveProjectFile(path) ?: File(path)).normalize()
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
			if (propsBlocks.isEmpty()) {
				return null
			}
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
					val s = if (codePathStr.startsWith("/")) {
						codePathStr.substring(1)
					} else {
						codePathStr
					}
					val file = File(s).let {
						if (it.isAbsolute) {
							it
						} else {
							File(baseDir, s)
						}
					}
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
					core.outputsData.isNotEmpty() -> core.outputsData.mapIndexed { idx, m ->
						m.keys.firstOrNull() ?: "prop$idx"
					}

					core.subProjectProps.isNotEmpty() -> core.subProjectProps.keys.toList()
					else -> emptyList()
				}

				fun addPropRow(initialKey: String = "prop${rows.children.size}", initialValue: String = "") {
					val tfKey = TextField(initialKey)
					val tfVal = TextField(initialValue)
					lateinit var row: HBox
					val delBtn = Button("–").apply {
						setOnAction { if (rows.children.size > 1) rows.children.remove(row) }
					}
					row = HBox(8.0, tfKey, tfVal, delBtn).apply { alignment = Pos.CENTER_LEFT }
					rows.children += row
				}

				names.forEachIndexed { idx, name ->
					val value = core.outputsData.getOrNull(idx)?.get(name)?.toString()
						?: (core.subProjectProps[name] as? String)
						?: ""
					addPropRow(name, value)
				}
				if (rows.children.isEmpty()) {
					addPropRow("prop0", "")
				}

				val addBtn = Button("+").apply { setOnAction { addPropRow() } }
				val header = HBox(8.0, Label("Свойства:"), addBtn).apply { alignment = Pos.CENTER_LEFT }

				val propsTab = Tab(
					"Свойства", VBox(
						6.0, header,
						ScrollPane(rows).apply { isFitToWidth = true; prefHeight = 240.0 })
				).apply { isClosable = false }

				TabPane(propsTab)
			}

			BlockType.SUB_PROCESS -> {
				val tabPane = TabPane()
				makeSubProjectPropsTab()?.let { tabPane.tabs += it }
				tabPane
			}

			BlockType.FORM -> {
				val tabPane = TabPane(makeCodeTab(), makeIOTab(), makePreProcessingTab(), makePostProcessingTab())
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
				core.name = titleText.text
				label.text = core.name
				// TODO Переписать то что тут ***
				tabs?.tabs?.firstOrNull { it.text == "Код" }?.let { codeTab ->
					val codeArea =
						(((codeTab.content as VBox).children[0]) as VirtualizedScrollPane<*>).content as CodeArea
					saveCode(core, codeArea.text)
				}
				tabs?.tabs?.firstOrNull { it.text == "PreProcessing" }?.let { codeTab ->
					val codeArea =
						(((codeTab.content as VBox).children[0]) as VirtualizedScrollPane<*>).content as CodeArea
					savePreProcessingCode(core, codeArea.text)
				}
				tabs?.tabs?.firstOrNull { it.text == "PostProcessing" }?.let { tab ->
					val rootBox = ((tab.content as ScrollPane).content as VBox)
					rootBox.children.filterIsInstance<VBox>().forEach { vb ->
						val labels = vb.children.filterIsInstance<Label>()
						val scrolls = vb.children.filterIsInstance<VirtualizedScrollPane<*>>()
						if (labels.size == 2 && scrolls.size == 2) {
							val codeMain = (scrolls[0].content as? CodeArea)?.text ?: ""
							val codeSubmit = (scrolls[1].content as? CodeArea)?.text ?: ""
							val lbl = vb.children.filterIsInstance<Label>().firstOrNull()
							val act = lbl?.text?.substringAfter(": ")?.trim() ?: "default"
							// сохраняем через переданные лямбды
							savePostProcessingCode(core, codeMain, act, "main")
							savePostProcessingCode(core, codeSubmit, act, "submit")
						}
					}
				}
				// TODO Переписать то что тут ***
				if (core.type == BlockType.PROPERTIES) {
					val tab = tabs?.tabs?.firstOrNull { it.text == "Свойства" }
					if (tab != null) {
						val rows: VBox? = when (val content = tab.content) {
							is ScrollPane -> content.content as? VBox
							is VBox -> (content.children.getOrNull(1) as? ScrollPane)?.content as? VBox
							else -> null
						}
						rows?.let { vbox ->
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
							val json = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(mapForFile)
							saveCode(core, json)
						}
					}
				}
				tabs?.tabs?.firstOrNull { it.text == "I/O" }?.let { ioTab ->
					val ioRoot = ioTab.content as VBox
					val inputsBox = ioRoot.children[0] as VBox
					val inputsListView = inputsBox.children.filterIsInstance<ListView<IOItem>>().first()
					val newInputNames = mutableListOf<String>()
					inputsListView.items.forEachIndexed { idx, item ->
						val base = item.name.ifBlank { "in$idx" }
						newInputNames += base
					}
					val normalized = run {
						val fix = mutableListOf<String>()
						val seen = mutableSetOf<String>()
						for (i in newInputNames.indices) {
							var n = newInputNames[i].ifBlank { "in$i" }
							if (!seen.add(n)) {
								var k = 2
								var c: String
								do {
									c = "${n}_$k"; k++
								} while (!seen.add(c))
								n = c
							}
							fix += n
						}
						fix
					}
					core.inputNames = normalized
					if (core.inputIds.size < normalized.size) {
						repeat(normalized.size - core.inputIds.size) {
							core.inputIds.add(UUID.randomUUID())
						}
					} else if (core.inputIds.size > normalized.size) {
						core.inputIds = core.inputIds.take(normalized.size).toMutableList()
					}
					core.inputCount = normalized.size
					val outputsBox = ioRoot.children[1] as VBox
					val outRows = (outputsBox.children[1] as ScrollPane).content as VBox
					core.outputNames = outRows.children.mapIndexed { idx, row ->
						((row as HBox).children[0] as TextField).text.ifBlank { "out$idx" }
					}.toMutableList()
					if (core.outputIds.size < core.outputNames.size) {
						repeat(core.outputNames.size - core.outputIds.size) {
							core.outputIds.add(UUID.randomUUID())
						}
					} else if (core.outputIds.size > core.outputNames.size) {
						core.outputIds = core.outputIds.take(core.outputNames.size).toMutableList()
					}
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
		core.ensureIoIds()
		val names = normalizeInputNames()
		core.inputNames = names

		val items = FXCollections.observableArrayList<IOItem>().apply {
			names.forEachIndexed { i, nm ->
				val id = core.inputIds.getOrNull(i) ?: UUID.randomUUID()
				if (i >= core.inputIds.size) {
					core.inputIds += id
				}
				add(IOItem(nm, id))
			}
			if (isEmpty()) {
				add(IOItem("in0", UUID.randomUUID()))
			}
		}
		val listView = ListView(items).apply {
			prefHeight = 200.0
			cellFactory = Callback {
				object : ListCell<IOItem>() {
					private val nameField = TextField()
					private val delBtn = Button("–")
					private val row = HBox(6.0, nameField, delBtn).apply {
						alignment = Pos.CENTER_LEFT
					}

					init {
						nameField.textProperty().addListener { _, _, v ->
							item?.name = v
						}
						delBtn.setOnAction {
							item?.let { ci ->
								listView.items.remove(ci)
							}
						}
						setOnDragDetected { e ->
							if (item == null) return@setOnDragDetected
							val db = startDragAndDrop(TransferMode.MOVE)
							val cc = ClipboardContent().apply {
								putString(index.toString())
							}
							db.setContent(cc)
							e.consume()
						}
						setOnDragOver { e ->
							if (e.gestureSource != this && e.dragboard.hasString()) {
								e.acceptTransferModes(*TransferMode.COPY_OR_MOVE)
							}
							e.consume()
						}
						setOnDragDropped { e ->
							val db = e.dragboard
							if (db.hasString()) {
								val from = db.string.toInt()
								val dragged = listView.items.removeAt(from)
								val to = if (index < 0) listView.items.size else index
								listView.items.add(to, dragged)
								e.isDropCompleted = true
								listView.selectionModel.select(to)
							}
							e.consume()
						}
					}

					override fun updateItem(value: IOItem?, empty: Boolean) {
						super.updateItem(value, empty)
						if (empty || value == null) {
							text = null
							graphic = null
						} else {
							if (nameField.text != value.name) {
								nameField.text = value.name
							}
							graphic = row
						}
					}
				}
			}
		}
		val addBtn = Button("+").apply {
			setOnAction {
				listView.items.add(IOItem("in${listView.items.size}", UUID.randomUUID()))
			}
		}
		val header = HBox(8.0, Label("Входы:"), addBtn).apply { alignment = Pos.CENTER_LEFT }
		return VBox(6.0, header, listView)
	}


	private fun buildEditableOutputsBox(): VBox {
		val rows = VBox(4.0)
		val scroll = ScrollPane(rows).apply {
			prefHeight = 180.0
			isFitToWidth = true
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		}
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
		children.removeAll(inputCircles)
		children.removeAll(outputCircles)
		inputCircles.clear()
		outputCircles.clear()
		core.ensureIoIds()
		createIOCircles()
		val invalid: List<Connection> = connectedLines.filter { conn ->
			(conn.from == this && core.outputIds.none { it == conn.fromPort }) ||
					(conn.to == this && core.inputIds.none { it == conn.toPort })
		}
		callbacks.onInvalidConnections(invalid)
		connectedLines.removeAll(invalid)
		updateConnectedLines()
		outputCircles.forEachIndexed { outIndex, outCircle ->
			outCircle.onMousePressed = EventHandler { e ->
				if (e.button == MouseButton.PRIMARY) {
					callbacks.onDragStartConnection(this, outIndex)
					e.consume()
				}
			}
			outCircle.onMouseDragged = EventHandler { e ->
				if (e.button == MouseButton.PRIMARY) {
					callbacks.onDragContinueConnection(e.sceneX, e.sceneY)
					e.consume()
				}
			}
			outCircle.onMouseReleased = EventHandler { e ->
				if (e.button == MouseButton.PRIMARY) {
					callbacks.onDragFinishConnection()
					e.consume()
				}
			}
		}
		inputCircles.forEachIndexed { idx, c ->
			val nm = core.inputNames.getOrNull(idx) ?: "in$idx"
			Tooltip.install(c, Tooltip(nm))
		}
		outputCircles.forEachIndexed { idx, c ->
			val nm = core.outputNames.getOrNull(idx) ?: "out$idx"
			Tooltip.install(c, Tooltip(nm))
		}
		val app = (scene?.window?.userData as? MainApp)
		app?.setupHandlersForBlock(this)
	}

	private fun createIOCircles() {
		// 0) Выровнять размеры списков под фактические счётчики
		val inTarget = maxOf(core.inputCount, core.inputNames.size, core.inputIds.size)
		val outTarget = maxOf(core.outputCount, core.outputNames.size, core.outputIds.size)

		// входы
		while (core.inputNames.size < inTarget) core.inputNames += "in${core.inputNames.size}"
		while (core.inputIds.size < inTarget) core.inputIds += UUID.randomUUID()
		core.inputCount = inTarget

		// выходы
		while (core.outputNames.size < outTarget) core.outputNames += "out${core.outputNames.size}"
		while (core.outputIds.size < outTarget) core.outputIds += UUID.randomUUID()
		core.outputCount = outTarget

		// 1) дальше — как у вас было
		val newHeight = computeBlockHeight()
		rect.height = newHeight; this.prefHeight = newHeight

		if (core.type !in arrayOf(BlockType.START, BlockType.INPUT_DATA, BlockType.PROPERTIES)) {
			val step = newHeight / (core.inputCount + 1)
			repeat(core.inputCount) { i ->
				val c = Circle(0.0, step * (i + 1), 7.0, Color.LIGHTSKYBLUE).apply {
					stroke = Color.DARKBLUE
					strokeWidth = 1.6
					Tooltip.install(this, Tooltip(core.inputNames.getOrNull(i) ?: "in$i"))
					properties["portId"] = core.inputIds[i]
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
							app.showOutputFor(this@BlockNode, i)
							ev.consume()
						}
					}
				}
				outputCircles += c; children += c
			}
		}
	}

	// Точка входа по UUID
	fun inputPoint(id: UUID): Pair<Double, Double> {
		val idx = core.inputIds.indexOf(id)
		val circle = if (idx >= 0) inputCircles.getOrNull(idx) else null
		return if (circle != null)
			(layoutX + circle.centerX) to (layoutY + circle.centerY)
		else
			layoutX to (layoutY + height / 2)
	}

	// Точка выхода по UUID
	fun outputPoint(id: UUID): Pair<Double, Double> {
		val idx = core.outputIds.indexOf(id)
		val circle = if (idx >= 0) outputCircles.getOrNull(idx) else null
		return if (circle != null)
			(layoutX + circle.centerX) to (layoutY + circle.centerY)
		else
			(layoutX + width) to (layoutY + height / 2)
	}

	fun updateConnectedLines() {
		connectedLines.forEach { conn ->
			conn.updateLine()
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


	private fun normalizeInputNames(): MutableList<String> {
		val count = core.inputCount

		// Берём текущие имена по индексам; пустые пока оставляем пустыми
		val names = MutableList(count) { i ->
			core.inputNames.getOrNull(i)?.trim().orEmpty()
		}

		val autoLike = Regex("""^in\d+$""")
		val nonBlank = names.filter { it.isNotBlank() }
		val hasDup = nonBlank.size != nonBlank.toSet().size
		val allAutoLikeOrBlank = names.all { it.isBlank() || autoLike.matches(it) }

		// Если все имена «технические» (in\d+) ИЛИ есть дубликаты — жёстко перенумеровываем
		if (allAutoLikeOrBlank || hasDup) {
			return MutableList(count) { i -> "in$i" }
		}

		// Иначе: заполняем пропуски и уникализируем пользовательские
		val seen = mutableSetOf<String>()
		for (i in names.indices) {
			var n = names[i].ifBlank { "in$i" }
			if (!seen.add(n)) {
				var k = 2
				var c: String
				do {
					c = "${n}_$k"
					k++
				} while (!seen.add(c))
				n = c
			}
			names[i] = n
		}
		return names
	}


	private data class IOItem(var name: String, val id: UUID)

}