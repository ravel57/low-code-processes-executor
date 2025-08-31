package ru.ravel.testjavafx.model

import ru.ravel.testjavafx.BlockNode
import ru.ravel.testjavafx.MainApp


class DeleteBlockCommand(
	private val app: MainApp,
	private val block: BlockNode
) : Command {
	// сохраняем данные блока
	private val savedX = block.layoutX
	private val savedY = block.layoutY
	private val savedName = block.name
	private val savedType = block.blockType
	private val savedCode = block.code
	private val savedInputCount = block.inputCount
	private val savedOutputCount = block.outputCount
	private val savedSerializedId = block.serializedId
	private val savedInputFormat = block.inputFormat
	private val savedDataDocs = block.dataDocs
	private val savedSubProjectPath = block.subProjectPath
	private val savedInputNames = block.inputNames.toList()
	private val savedOutputNames = block.outputNames.toList()
	private val savedOutputsData = block.outputsData.map { it.toMutableMap() }.toMutableList()
	private val savedPackagesNames = block.packagesNames.toList()
	private val savedSubProjectProps = block.subProjectProps.toMutableMap()
	private val savedEndpoint = block.endpoint

	override fun execute() {
		app.deleteBlockRequest(block)
	}

	override fun undo() {
		// восстанавливаем из сохранённых данных
		val restored = BlockNode(
			x = savedX,
			y = savedY,
			name = savedName,
			blockType = savedType,
			code = savedCode,
			inputCount = savedInputCount,
			outputCount = savedOutputCount,
			serializedId = savedSerializedId,
			inputFormat = savedInputFormat,
			dataDocs = savedDataDocs,
			subProjectPath = savedSubProjectPath,
			inputNames = savedInputNames.toMutableList(),
			outputNames = savedOutputNames.toMutableList(),
			outputsData = savedOutputsData.map { it.toMutableMap() }.toMutableList(),
			packagesNames = savedPackagesNames.toMutableList(),
			subProjectProps = savedSubProjectProps.toMutableMap(),
			endpoint = savedEndpoint
		)
		app.blocks.add(restored)
		app.contentPane.children.add(restored)
		app.setupHandlersForBlock(restored)
	}
}

