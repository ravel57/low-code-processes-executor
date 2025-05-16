package ru.ravel.testjavafx.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class ConnectionSerialized @JsonCreator constructor(
	@JsonProperty("fromId") val fromId: Int,
	@JsonProperty("toId") val toId: Int,
	@JsonProperty("fromOutputIndex") val fromOutputIndex: Int,
	@JsonProperty("toInputIndex") val toInputIndex: Int
)
