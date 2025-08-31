package ru.ravel.testjavafx.model

import javafx.scene.layout.Pane
import ru.ravel.testjavafx.BlockNode
import ru.ravel.testjavafx.MainApp

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
		block = app.addBlock(parent, x, y, name, blockType)
	}

	override fun undo() {
		block?.let { app.deleteBlockRequest(it) }
	}
}