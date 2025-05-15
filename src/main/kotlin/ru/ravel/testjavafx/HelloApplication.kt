package ru.ravel.testjavafx

import javafx.application.Application
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.ScrollPane
import javafx.scene.input.MouseButton
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Stage

class BlockNode(x: Double, y: Double, val text: String) : Pane() {
	private val width = 100.0
	private val height = 40.0
	private val rect = Rectangle(width, height)
	private val label = Text(text)
	val inputCircle = Circle(10.0, height / 2, 7.0, Color.LIGHTGRAY)
	val outputCircle = Circle(width - 10.0, height / 2, 7.0, Color.DODGERBLUE)

	val connectedLines = mutableListOf<Connection>()

	private var dragOffsetX = 0.0
	private var dragOffsetY = 0.0

	init {
		layoutX = x
		layoutY = y
		rect.stroke = Color.BLACK
		rect.fill = Color.LIGHTGRAY
		children.add(rect)
		label.font = Font(14.0)
		label.x = 22.0
		label.y = height / 2 + 5.0
		children.add(label)
		children.add(inputCircle)
		children.add(outputCircle)

		// Корректный drag по прямоугольнику
		rect.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
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

		// Кружки блокируют drag блока
		inputCircle.isPickOnBounds = true
		inputCircle.onMousePressed = EventHandler { it.consume() }
		inputCircle.onMouseDragged = EventHandler { it.consume() }
		outputCircle.isPickOnBounds = true
		// outputCircle — соединения обрабатываются в VisualProgrammingDemo
	}

	fun inputPoint() = Pair(layoutX + inputCircle.centerX, layoutY + inputCircle.centerY)
	fun outputPoint() = Pair(layoutX + outputCircle.centerX, layoutY + outputCircle.centerY)

	fun updateConnectedLines() {
		connectedLines.forEach { it.updateLine() }
	}
}

class Connection(val from: BlockNode, val to: BlockNode, val line: Line) {
	fun updateLine() {
		val (startX, startY) = from.outputPoint()
		val (endX, endY) = to.inputPoint()
		line.startX = startX
		line.startY = startY
		line.endX = endX
		line.endY = endY
	}
}

class VisualProgrammingDemo : Application() {
	private val blocks = mutableListOf<BlockNode>()
	private val connections = mutableListOf<Connection>()
	private var draggingLine: Line? = null
	private var draggingFromBlock: BlockNode? = null

	override fun start(primaryStage: Stage) {
		val windowW = 600.0
		val windowH = 400.0

		val contentPane = Pane().apply {
			prefWidth = windowW * 3
			prefHeight = windowH * 3
			padding = Insets(10.0)
		}

		val scrollPane = ScrollPane(contentPane).apply {
			isPannable = false
			hbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
			viewportBoundsProperty().addListener { _, _, _ ->
				hvalue = 0.5
				vvalue = 0.5
			}
		}

		// Панорамирование средней кнопкой мыши
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

		addBlock(contentPane, windowW * 1.5 - 100, windowH * 1.5 - 100, "Старт")
		addBlock(contentPane, windowW * 1.5 + 150, windowH * 1.5, "Действие")

		contentPane.onMouseClicked = EventHandler { event ->
			if (event.button == MouseButton.SECONDARY) {
				addBlock(contentPane, event.x, event.y, "Блок ${blocks.size + 1}")
			}
		}

		primaryStage.scene = Scene(scrollPane, windowW, windowH)
		primaryStage.title = "Блочное визуальное программирование (JavaFX Kotlin)"
		primaryStage.show()
	}

	private fun addBlock(parent: Pane, x: Double, y: Double, text: String) {
		val block = BlockNode(x, y, text)
		blocks.add(block)
		parent.children.add(block)

		block.outputCircle.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				val (startX, startY) = block.outputPoint()
				val line = Line(startX, startY, startX, startY).apply {
					stroke = Color.BLUE
					strokeWidth = 2.0
					mouseTransparentProperty().set(true)
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
							other.inputCircle.localToScene(other.inputCircle.centerX, other.inputCircle.centerY).let { p ->
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

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			launch(VisualProgrammingDemo::class.java)
		}
	}
}
