package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.io.File

data class PostProcessingNode(
	@JsonProperty("action")
	var action: String,
	@JsonProperty("mainProcessingUuid")
	var mainProcessingUuid: String?,
	@JsonProperty("mainProcessingClassName")
	var mainProcessingClassName: String?,
	@JsonProperty("mainProcessingCodePath")
	var mainProcessingCodePath: String?,
	@JsonProperty("submitDataUuid")
	var submitDataUuid: String?,
	@JsonProperty("submitDataClassName")
	var submitDataClassName: String?,
	@JsonProperty("submitDataCodePath")
	var submitDataCodePath: String?,
) {

	@JsonIgnore
	var  mainProcessingAbsolutePath: File? = null

	@JsonIgnore
	var submitDataAbsolutePath: File? = null

}
