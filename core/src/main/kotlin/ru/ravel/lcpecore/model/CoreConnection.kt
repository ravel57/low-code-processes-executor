package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.*


data class CoreConnection /*@JsonCreator(mode = JsonCreator.Mode.PROPERTIES)*/ constructor(
	@JsonProperty("fromId")
	val fromId: UUID,

	@JsonProperty("toId")
	val toId: UUID,

	@JsonProperty("fromOutputId")
	val fromOutputId: UUID = UUID.randomUUID(),

	@JsonProperty("toInputId")
	val toInputId: UUID = UUID.randomUUID(),

	@JsonProperty("incomeDataType")
	val incomeDataType: IncomeDataType = IncomeDataType.REQUIRED_DATA,

	@JsonProperty("gate")
	val gate: EdgeGate? = null,
)