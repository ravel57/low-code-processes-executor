package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class CoreBlock @JsonCreator constructor(
	@JsonProperty("id")
	var id: UUID = UUID.randomUUID(),

	@JsonProperty("name")
	var name: String = "",

	@JsonProperty("type")
	@JsonAlias("blockType")
	var type: BlockType = BlockType.START,

	@JsonProperty("codePath")
	@JsonAlias("codeFile")
	var codePath: String? = null,

	@JsonProperty("inputFormat")
	var inputFormat: InputFormatType = InputFormatType.JSON,

	@JsonProperty("subProjectPath")
	var subProjectPath: String = "",

	@JsonProperty("inputNames")
	var inputNames: MutableList<String> = mutableListOf(),

	@JsonProperty("outputNames")
	var outputNames: MutableList<String> = mutableListOf(),

	var outputsData: MutableList<MutableMap<String, Any?>> = mutableListOf(),

	@JsonProperty("packagesNames")
	var packagesNames: MutableList<String> = mutableListOf(),

	@JsonProperty("endpoint")
	var endpoint: String = "",

	var subProjectProps: MutableMap<String, Any?> = mutableMapOf(),

	@JsonProperty("mapKeySettings")
	var mapKeySettings: MutableMap<String, MapAction> = mutableMapOf(),

	@JsonProperty("outputCount")
	var outputCount: Int = 1,

	@JsonProperty("inputCount")
	var inputCount: Int = 1,

	@JsonProperty("uiX")
	@JsonAlias("x")
	var uiX: Double? = null,

	@JsonProperty("uiY")
	@JsonAlias("y")
	var uiY: Double? = null,
)