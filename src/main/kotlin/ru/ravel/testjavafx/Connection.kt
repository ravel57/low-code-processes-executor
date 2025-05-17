package ru.ravel.testjavafx

import javafx.scene.paint.Color
import javafx.scene.shape.Line
import ru.ravel.testjavafx.model.ConnectionSerialized

class Connection(
	val from: BlockNode,
	val to: BlockNode,
	val line: Line,
	val fromPort: Int = 0, // Индекс выхода
	val toPort: Int = 0    // Индекс входа
) {
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
		val (startX, startY) = from.outputPoint(fromPort)
		val (endX, endY) = to.inputPoint(toPort)
		line.startX = startX
		line.startY = startY
		line.endX = endX
		line.endY = endY
	}

	fun toSerialized(): ConnectionSerialized = ConnectionSerialized(
		fromId = from.serializedId,
		toId = to.serializedId,
		fromOutputIndex = fromPort,
		toInputIndex = toPort
	)

}
