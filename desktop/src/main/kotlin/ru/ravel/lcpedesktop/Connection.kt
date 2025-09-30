package ru.ravel.lcpedesktop

import javafx.scene.paint.Color
import javafx.scene.shape.Line
import ru.ravel.lcpecore.model.CoreConnection

class Connection(
	val from: BlockNode,
	val to: BlockNode,
	val line: Line,
	val fromPort: Int = 0,
	val toPort: Int = 0,
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

	/**
	 * Преобразование UI-связи (между BlockNode) в чистую core-модель.
	 * ВАЖНО: используем UUID из core-модели узлов.
	 */
	fun toCoreConnection(): CoreConnection = CoreConnection(
		fromId = from.core.id,
		toId = to.core.id,
		fromOutputIndex = fromPort,
		toInputIndex = toPort
	)

}
