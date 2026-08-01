import ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

public final class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        new MainWindow().show(primaryStage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}