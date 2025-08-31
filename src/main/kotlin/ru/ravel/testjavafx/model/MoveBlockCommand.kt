package ru.ravel.testjavafx.model

import ru.ravel.testjavafx.BlockNode

class MoveBlockCommand(
	private val block: BlockNode,
	private val oldX: Double,
	private val oldY: Double,
	private val newX: Double,
	private val newY: Double
) : Command {
	override fun execute() {
		block.layoutX = newX
		block.layoutY = newY
		block.updateConnectedLines()
	}

	override fun undo() {
		block.layoutX = oldX
		block.layoutY = oldY
		block.updateConnectedLines()
	}
}
