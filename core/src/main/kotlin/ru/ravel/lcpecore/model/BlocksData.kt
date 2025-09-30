package ru.ravel.lcpecore.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class BlocksData(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "block")
	@JsonProperty("blocks")
	val blocks: List<BlockSerialized> = emptyList(),
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "connection")
	@JsonProperty("connections")
	val connections: List<ConnectionSerialized> = emptyList()
)

