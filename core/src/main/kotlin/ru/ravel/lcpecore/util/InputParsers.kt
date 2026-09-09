package ru.ravel.lcpecore.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import org.yaml.snakeyaml.Yaml
import ru.ravel.lcpecore.model.InputFormatType

object InputParsers {
	fun parse(format: InputFormatType, text: String?): MutableMap<String, Any> = try {
		when (format) {
			InputFormatType.JSON -> {
				(ObjectMapper().readValue(text ?: "{}", Map::class.java) as Map<String, Any>).toMutableMap()
			}
			InputFormatType.YAML -> {
				(Yaml().load(text ?: "") as? Map<String, Any> ?: emptyMap()).toMutableMap()
			}
			InputFormatType.XML -> {
				(XmlMapper().readValue(text ?: "<root/>", Map::class.java) as Map<String, Any>).toMutableMap()
			}
//			InputFormatType.PROTOBUF -> {
//				mutableMapOf("_error" to "PROTOBUF not implemented")
//			}
//			InputFormatType.TOML -> {
//				mutableMapOf("_error" to "TOML not implemented")
//			}
		}
	} catch (e: Exception) {
		mutableMapOf("_parseError" to (e.message ?: "parse error"))
	}
}
