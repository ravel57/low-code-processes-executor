package ru.ravel.lcpedesktop

import ru.ravel.lcpecore.model.CoreBlock
import java.io.File


data class BlockNodeCallbacks(
	val onSelect: (BlockNode) -> Unit = {},
	val onReleased: (BlockNode) -> Unit = {},
	val onDeleteRequested: (BlockNode) -> Unit = {},
	val onOpenSubProject: (File) -> Unit = {},
	val onInvalidConnections: (List<Connection>) -> Unit = {},
	val onModelChanged: (CoreBlock) -> Unit = {},
	val onShowOutput: (block: BlockNode, index: Int) -> Unit = { _, _ -> },
	// drag-to-connect (контейнер рисует временную линию и создаёт Connection)
	val onDragStartConnection: (from: BlockNode, fromPort: Int) -> Unit = { _, _ -> },
	val onDragContinueConnection: (sceneX: Double, sceneY: Double) -> Unit = { _, _ -> },
	val onDragFinishConnection: () -> Unit = {},
)