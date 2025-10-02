package ru.ravel.lcpedesktop.model

import ru.ravel.lcpecore.model.CoreBlock
import java.io.File

data class BlockLoc(
	val file: File,
	val block: CoreBlock,
)
