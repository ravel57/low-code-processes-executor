package ru.ravel.lcpecore.runtime

import java.util.*

data class BlockResult(
	val blockId: UUID,
	val outputs: List<Map<String, Any?>>
)