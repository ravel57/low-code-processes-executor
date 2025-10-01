package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonCreator
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

	@JsonProperty("fromOutputIndex")
	/*TODO delete*/ val fromOutputIndex: Int? = null,

	@JsonProperty("toInputIndex")
	/*TODO delete*/ val toInputIndex: Int? = null,
) {
	constructor(fromId: UUID, toId: UUID, fromOutputId: UUID, toInputId: UUID)
			: this(fromId, toId, fromOutputId, toInputId, null, null)
}