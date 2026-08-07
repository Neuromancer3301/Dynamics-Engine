package navigation;

import config.AppConfig;
import javafx.animation.FadeTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;
import theme.ThemeManager;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Owns the primary {@link Stage} and mediates every screen transition.
 *
 * <p>This is the one place in the app that knows how a screen is loaded,
 * shown, and torn down. Controllers ask it to {@link #navigate(Route)} or
 * {@link #back()}; they never touch the {@code Stage} or {@code Scene}
 * directly. That indirection is what lets screens gain a forward stack,
 * modal-style overlays, or transition variants later without any controller
 * changing — and it's a single shared fade-in, rather than each screen
 * hand-rolling its own entrance animation.
 */
public final class SceneRouter {

    private static final Duration TRANSITION = Duration.millis(180);

    private final Stage stage;
    private final Deque<Route> history = new ArrayDeque<>();

    private Scene scene;
    private Route activeRoute;
    private Navigable currentController;

    public SceneRouter(Stage stage) {
        this.stage = stage;
        stage.setTitle(AppConfig.APP_NAME);
        stage.setMinWidth(AppConfig.MIN_WIDTH);
        stage.setMinHeight(AppConfig.MIN_HEIGHT);

        // Scene.fill defaults to white and paints underneath the whole scene
        // graph. Without this, a theme switch made from Settings wouldn't
        // affect the flash color (see fadeIn/load below) until the *next*
        // app launch — this keeps it in sync with a live toggle too.
        ThemeManager.getInstance().addListener(() -> {
            if (scene != null) {
                scene.setFill(ThemeManager.getInstance().getCurrent().backgroundColor());
            }
        });
    }

    /** Navigates forward to {@code route}, pushing the current screen onto the back-stack. */
    public void navigate(Route route) {
        load(route, true);
    }

    /** Returns to the previous screen, if any. No-op when there is no history. */
    public void back() {
        if (history.isEmpty()) return;
        load(history.pop(), false);
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    private void load(Route route, boolean pushCurrent) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(route.fxmlPath()));
            Parent root = loader.load();

            if (currentController != null) currentController.onHide();

            Object controller = loader.getController();
            currentController = (controller instanceof Navigable navigable) ? navigable : null;
            if (currentController != null) currentController.setRouter(this);

            if (scene == null) {
                scene = new Scene(root, AppConfig.DEFAULT_WIDTH, AppConfig.DEFAULT_HEIGHT);
                scene.getStylesheets().add(getClass().getResource("/css/theme.css").toExternalForm());
                // Scene.fill defaults to white; without this the ~180ms
                // fadeIn below lets it show through as a white flash while
                // the new (semi-transparent) root doesn't yet fully cover
                // the scene. Set once here for the first screen...
                scene.setFill(ThemeManager.getInstance().getCurrent().backgroundColor());
                stage.setScene(scene);
            } else {
                if (pushCurrent) history.push(activeRoute);
                // ...and again here, every subsequent navigation, in case the
                // theme changed since the last one (the constructor listener
                // above only covers a toggle while a scene already exists).
                scene.setFill(ThemeManager.getInstance().getCurrent().backgroundColor());
                scene.setRoot(root);
            }
            activeRoute = route;

            ThemeManager.getInstance().register(root);
            stage.setTitle(AppConfig.APP_NAME + "  ·  " + route.title());

            fadeIn(root);
            if (currentController != null) currentController.onShow();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to load screen: " + route, e);
        }
    }

    private void fadeIn(Parent root) {
        if (ThemeManager.getInstance().isReducedMotion()) {
            root.setOpacity(1);
            return;
        }
        FadeTransition fade = new FadeTransition(TRANSITION, root);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }
}
