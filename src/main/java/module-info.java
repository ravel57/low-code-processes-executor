module ru.ravel.testjavafx {
	requires javafx.controls;
	requires javafx.fxml;
	requires com.fasterxml.jackson.dataformat.xml;
	requires com.fasterxml.jackson.kotlin;
	requires com.fasterxml.jackson.databind;
	requires kotlin.stdlib;

	opens ru.ravel.testjavafx to javafx.fxml;
	opens ru.ravel.testjavafx.model to com.fasterxml.jackson.databind, com.fasterxml.jackson.dataformat.xml, kotlin.reflect;
	exports ru.ravel.testjavafx.model to com.fasterxml.jackson.databind, com.fasterxml.jackson.dataformat.xml;
	exports ru.ravel.testjavafx;
}