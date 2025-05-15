module ru.ravel.testjavafx {
	requires javafx.controls;
	requires javafx.fxml;
	requires kotlin.stdlib;


	opens ru.ravel.testjavafx to javafx.fxml;
	exports ru.ravel.testjavafx;
}