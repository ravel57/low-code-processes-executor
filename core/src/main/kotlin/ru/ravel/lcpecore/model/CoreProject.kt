package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class CoreProject @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
	@JsonProperty("blocks")
	val blocks: MutableList<CoreBlock> = mutableListOf(),
	@JsonProperty("connections")
	val connections: MutableList<CoreConnection> = mutableListOf()
)