package ru.ravel.lcpecore.model

enum class BlockType(val displayName: String, val color: String) {
	MAPPING_GROOVY("Mapping (Groovy)", "#F0FFFF"),
	MAPPING_PYTHON("Mapping (Python)", "#FFFACD"),
	MAPPING_JAVA_SCRIPT("Mapping (JavaScript)", "#FFF0F5"),
	CONNECTOR("Connector", "#ADD8E6"),
	INPUT_DATA("InputData", "#D3D3D3"),
	START("Start", "#90EE90"),
	EXIT("Exit", "#FFA07A"),
	SUB_PROJECT("Проект", "#FFD700"),
	PROPERTIES("Свойства", "#D2B48C"),
	FORM("Форма", "#87CEFA"),
	;

	fun isService() = this in listOf(SUB_PROJECT, PROPERTIES, START, FORM)
}
