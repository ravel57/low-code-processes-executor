package ru.ravel.testjavafx.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class ConnectionSerialized @JsonCreator constructor(
	@JsonProperty("fromId") val fromId: UUID,
	@JsonProperty("toId") val toId: UUID,
	@JsonProperty("fromOutputIndex") val fromOutputIndex: Int,
	@JsonProperty("toInputIndex") val toInputIndex: Int
)
