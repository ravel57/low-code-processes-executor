package ru.ravel.lcpecore.model

import java.util.*


data class CoreConnection(
	val fromId: UUID,
	val toId: UUID,
	val fromOutputIndex: Int,
	val toInputIndex: Int
)