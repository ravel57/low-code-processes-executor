package ru.ravel.testjavafx.model

import javafx.scene.paint.Color

enum class BlockType(val displayName: String, val color: Color) {
	MAPPING_GROOVY("Mapping (Groovy)", Color.BEIGE),
	MAPPING_PYTHON("Mapping (Python)", Color.LIGHTGRAY),
	CONNECTOR("Connector", Color.LIGHTBLUE),
	INPUT_DATA("InputData", Color.LIGHTYELLOW),
	START("Start", Color.LIGHTGREEN),
	EXIT("Exit", Color.LIGHTSALMON)
}