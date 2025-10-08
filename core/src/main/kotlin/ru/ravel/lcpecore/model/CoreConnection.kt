package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
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

	@get:JsonIgnore
	@JsonProperty("isOptional")
	@Deprecated("Use incomeDataType")
	var isOptional: Boolean = false,

	@get:JsonIgnore
	@JsonProperty("isNeedDataToRun")
	@Deprecated("Use incomeDataType")
	var isNeedDataToRun: Boolean = false,

	@JsonProperty("incomeDataType")
	val incomeDataType: IncomeDataType = when {
		isOptional -> IncomeDataType.OPTIONAL
		isNeedDataToRun -> IncomeDataType.REQUIRED_FRESH_DATA
		else -> IncomeDataType.REQUIRED_DATA
	},

	@JsonProperty("gate")
	val gate: EdgeGate? = null,
)