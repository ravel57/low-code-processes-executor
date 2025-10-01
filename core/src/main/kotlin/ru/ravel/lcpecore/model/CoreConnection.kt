package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*


data class CoreConnection @JsonCreator constructor(
	@JsonProperty("fromId")
	val fromId: UUID,

	@JsonProperty("toId")
	val toId: UUID,

	@JsonProperty("fromOutputId")
	val fromOutputId: UUID = UUID.randomUUID(),

	@JsonProperty("toInputId")
	val toInputId: UUID = UUID.randomUUID(),

	@JsonProperty("fromOutputIndex")
	val fromOutputIndex: Int? = null,

	@JsonProperty("toInputIndex")
	val toInputIndex: Int? = null
) {
	fun resolveFromOutput(block: CoreBlock): UUID {
		return fromOutputId
			?: block.outputIds.getOrNull(fromOutputIndex ?: 0)
			?: throw IllegalStateException("Cannot resolve fromOutput for block ${block.id}")
	}

	fun resolveToInput(block: CoreBlock): UUID {
		return toInputId
			?: block.inputIds.getOrNull(toInputIndex ?: 0)
			?: throw IllegalStateException("Cannot resolve toInput for block ${block.id}")
	}
}