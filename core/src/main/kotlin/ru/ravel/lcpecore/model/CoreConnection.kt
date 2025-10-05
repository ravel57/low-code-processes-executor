package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*


data class CoreConnection @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
	@JsonProperty("fromId")
	val fromId: UUID,

	@JsonProperty("toId")
	val toId: UUID,

	@JsonProperty("fromOutputId")
	val fromOutputId: UUID = UUID.randomUUID(),

	@JsonProperty("toInputId")
	val toInputId: UUID = UUID.randomUUID(),

	@JsonProperty("isOptional")
	var isOptional: Boolean = false,
)