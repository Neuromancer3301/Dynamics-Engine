package app;

import javafx.application.Application;
import javafx.stage.Stage;
import navigation.Route;
import navigation.SceneRouter;

/**
 * Application entry point.
 *
 * <p>Responsibility is deliberately thin: construct the {@link SceneRouter}
 * (the single owner of the primary {@link Stage} and scene graph) and hand it
 * the first route. All screen composition, theming, and navigation logic
 * lives in {@code navigation} and {@code controller} — this class should
 * never grow beyond a few lines as the app gains screens.
 */
public final class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        SceneRouter router = new SceneRouter(primaryStage);
        router.navigate(Route.MAIN_MENU);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
