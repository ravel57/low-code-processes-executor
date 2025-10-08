package ru.ravel.lcpecore.model

data class EdgeGate(
	val mode: GateMode = GateMode.ALWAYS,
	val key: String? = null,
	val equals: String? = null
)