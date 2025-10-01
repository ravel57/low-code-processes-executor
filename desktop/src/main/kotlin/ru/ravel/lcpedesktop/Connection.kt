package ru.ravel.lcpedesktop

import javafx.scene.paint.Color
import javafx.scene.shape.Line
import ru.ravel.lcpecore.model.CoreConnection
import java.util.UUID
import kotlin.math.sqrt


class Connection constructor(
	val from: BlockNode,
	val to: BlockNode,
	val line: Line,
	val fromPort: UUID,
	val toPort: UUID,
	val pick: Line,
) {
	var selected: Boolean = false
		set(value) {
			field = value
			if (value) {
				line.stroke = Color.RED
				line.strokeWidth = 3.0
			} else {
				line.stroke = Color.BLUE
				line.strokeWidth = 2.0
			}
		}

	fun updateLine() {
		val (startX, startY) = from.outputPoint(fromPort)
		val (endX, endY) = to.inputPoint(toPort)

		// обновляем основную линию
		line.startX = startX
		line.startY = startY
		line.endX = endX
		line.endY = endY

		// Вектор направления
		val dx = endX - startX
		val dy = endY - startY
		val len = sqrt(dx * dx + dy * dy)

		val offset = 8.0 // сколько пикселей «урезать» на концах у pick
		val ratio = if (len > 0) offset / len else 0.0

		val sx = startX + dx * ratio
		val sy = startY + dy * ratio
		val ex = endX - dx * ratio
		val ey = endY - dy * ratio

		// обновляем толстую прозрачную линию
		pick.startX = sx
		pick.startY = sy
		pick.endX = ex
		pick.endY = ey
	}


	/**
	 * Преобразование UI-связи (между BlockNode) в чистую core-модель.
	 * ВАЖНО: используем UUID из core-модели узлов.
	 */
	fun toCoreConnection(): CoreConnection = CoreConnection(
		fromId = from.core.id,
		toId = to.core.id,
		fromOutputId = fromPort,
		toInputId = toPort
	)

}
