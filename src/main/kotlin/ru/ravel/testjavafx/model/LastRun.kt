package ru.ravel.testjavafx.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class LastRun @JsonCreator constructor(
	@JsonProperty("outputsData")
	val outputsData: Map<String, List<Map<String, Any>>>,
	@JsonProperty("mapKeySettings")
	val mapKeySettings: Map<String, Map<String, String>>
)