package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.*
import java.io.File
import java.util.*


@JsonIgnoreProperties(ignoreUnknown = true)
data class CoreBlock @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
	@JsonProperty("id")
	var id: UUID = UUID.randomUUID(),

	@JsonProperty("name")
	var name: String = "",

	@JsonProperty("blockType")
	@JsonAlias("type")
	var type: BlockType = BlockType.START,

	@JsonProperty("codeFile")
	@JsonAlias("codePath")
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

	@JsonProperty("packagesNames")
	var packagesNames: MutableList<String> = mutableListOf(),

	@JsonProperty("endpoint")
	var endpoint: String = "",

	@JsonProperty("subProjectProps")
	var subProjectProps: MutableMap<String, Any?> = mutableMapOf(),

	@JsonProperty("mapKeySettings")
	var mapKeySettings: MutableMap<String, MapAction> = mutableMapOf(),

	@JsonProperty("outputCount")
	var outputCount: Int = 1,

	@JsonProperty("inputCount")
	var inputCount: Int = 1,

	@JsonProperty("x")
	@JsonAlias("uiX")
	var uiX: Double? = null,

	@JsonProperty("y")
	@JsonAlias("uiY")
	var uiY: Double? = null,

	@JsonProperty("groovyClassName")
	var groovyClassName: String? = null,

	@JsonProperty("preProcessingCode")
	var preProcessingCodePath: String? = null,

	@JsonProperty("postProcessing")
	@JsonSetter(nulls = Nulls.AS_EMPTY)
	var postProcessingNodes: List<PostProcessingNode> = emptyList(),
) {

	@JsonIgnore
	var outputsData: MutableList<MutableMap<String, Any?>> = mutableListOf()

	@JsonIgnore
	var codeAbsolutePath: File? = null


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