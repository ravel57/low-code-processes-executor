package ru.ravel.lcpedesktop.model

import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpedesktop.BlockNode
import ru.ravel.lcpedesktop.MainApp


class DeleteBlockCommand(
	private val app: MainApp,
	private val block: BlockNode
) : Command {
	// сохраняем данные блока
	private val id = block.core.id
	private val savedX = block.layoutX
	private val savedY = block.layoutY
	private val savedName = block.core.name
	private val savedType = block.core.type
	private val savedCodePath = block.core.codePath
	private val savedInputCount = block.core.inputCount
	private val savedOutputCount = block.core.outputCount
	private val savedInputFormat = block.core.inputFormat
	private val savedSubProjectPath = block.core.subProjectPath
	private val savedInputNames = block.core.inputNames.toList()
	private val savedOutputNames = block.core.outputNames.toList()
	private val savedPackagesNames = block.core.packagesNames.toList()
	private val savedSubProjectProps = block.core.subProjectProps.toMutableMap()
	private val savedEndpoint = block.core.endpoint

	override fun execute() {
		app.deleteBlockRequest(block)
	}

	override fun undo() {
		// восстанавливаем из сохранённых данных
		val restored = BlockNode(
			x = savedX,
			y = savedY,
			core = CoreBlock(
				id = id,
				name = savedName,
				type = savedType,
				codePath = savedCodePath,
				inputCount = savedInputCount,
				outputCount = savedOutputCount,
				inputFormat = savedInputFormat,
				subProjectPath = savedSubProjectPath,
				inputNames = savedInputNames.toMutableList(),
				outputNames = savedOutputNames.toMutableList(),
				packagesNames = savedPackagesNames.toMutableList(),
				subProjectProps = savedSubProjectProps.toMutableMap(),
				endpoint = savedEndpoint,
			),
		)
		app.blocks.add(restored)
		app.contentPane.children.add(restored)
		app.setupHandlersForBlock(restored)
	}
}

