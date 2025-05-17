module ru.ravel.testjavafx {
	requires javafx.controls;
	requires javafx.fxml;
	requires com.fasterxml.jackson.dataformat.xml;
	requires com.fasterxml.jackson.kotlin;
	requires com.fasterxml.jackson.databind;
	requires org.codehaus.groovy;
	requires kotlinx.coroutines.core;

	opens ru.ravel.testjavafx;
	opens ru.ravel.testjavafx.model;

	exports ru.ravel.testjavafx.model;
	exports ru.ravel.testjavafx;
}
