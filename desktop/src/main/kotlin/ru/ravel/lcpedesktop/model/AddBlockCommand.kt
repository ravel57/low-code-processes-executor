package ru.ravel.lcpedesktop.model

import javafx.scene.layout.Pane
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpedesktop.BlockNode
import ru.ravel.lcpedesktop.MainApp


class AddBlockCommand(
	private val app: MainApp,
	private val parent: Pane,
	private val x: Double,
	private val y: Double,
	private val name: String,
	private val blockType: BlockType
) : Command {
	var block: BlockNode? = null

	override fun execute() {
		block = app.addBlock(x, y, name, blockType)
	}

	override fun undo() {
		block?.let { app.deleteBlockRequest(it) }
	}
}