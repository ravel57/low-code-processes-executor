package ru.ravel.testjavafx.model

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class BlocksData(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "block")
	val blocks: List<BlockSerialized> = emptyList(),
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "connection")
	val connections: List<ConnectionSerialized> = emptyList()
)

data class BlockSerialized(
	val id: Int = 0,
	val x: Double = 0.0,
	val y: Double = 0.0,
	val name: String = "",
	val blockType: String = "",
	val inputFormat: String? = null,
	val code: String? = null,
	val dataDocs: String? = null,
	val otherInfo: String? = null
)

data class ConnectionSerialized(
	val fromId: Int = 0,
	val toId: Int = 0
)
