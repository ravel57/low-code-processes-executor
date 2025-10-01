package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.*
import java.util.*

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

	@JsonProperty("inputIds")
	var inputIds: MutableList<UUID> = mutableListOf(),

	@JsonProperty("outputIds")
	var outputIds: MutableList<UUID> = mutableListOf(),

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
) {
	@JsonIgnore
	fun ensureIoIds() {
		fun ensure(names: MutableList<String>, ids: MutableList<UUID>) {
			while (ids.size < names.size) ids += UUID.randomUUID()
			while (ids.size > names.size) ids.removeLast()

			val seen = HashSet<UUID>()
			for (i in ids.indices) {
				if (!seen.add(ids[i])) {
					ids[i] = UUID.randomUUID()
				}
			}
		}
		ensure(inputNames, inputIds)
		ensure(outputNames, outputIds)
	}

	init {
		ensureIoIds()
	}
}