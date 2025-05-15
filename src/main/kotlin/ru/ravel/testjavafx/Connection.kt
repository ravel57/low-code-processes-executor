package ru.ravel.testjavafx

import javafx.scene.paint.Color
import javafx.scene.shape.Line

class Connection(val from: BlockNode, val to: BlockNode, val line: Line) {
	var selected: Boolean = false
		set(value) {
			field = value
			if (value) {
				line.stroke = Color.RED
				line.strokeWidth = 4.0
			} else {
				line.stroke = Color.BLUE
				line.strokeWidth = 2.0
			}
		}

	fun updateLine() {
		val (startX, startY) = from.outputPoint()
		val (endX, endY) = to.inputPoint()
		line.startX = startX
		line.startY = startY
		line.endX = endX
		line.endY = endY
	}
}