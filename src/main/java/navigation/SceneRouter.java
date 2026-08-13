package navigation;

import config.AppConfig;
import javafx.animation.FadeTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;
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
 *
 * <p><b>It also owns interface scaling</b> (see {@link #applyScaling}),
 * because scaling is a layout concern and this is the class that owns the
 * layout root. {@code theme.ThemeManager} only stores the preference.
 */
public final class SceneRouter {

    private static final Duration TRANSITION = Duration.millis(180);

    private final Stage stage;
    private final Deque<Route> history = new ArrayDeque<>();

    private Scene scene;
    private Route activeRoute;
    private Navigable currentController;

    // The Scene's actual root. A Group is used deliberately: Scene force-
    // resizes its root to the window, but a Group is NOT resizable and does
    // not resize its children — which is exactly what lets applyScaling()
    // lay the real UI out at its own chosen (smaller) logical size.
    private Group scaleHost;

    // The real screen root living inside scaleHost.
    private Parent uiRoot;

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

    /** Whether there is any history to return to. */
    public boolean canGoBack() {
        return !history.isEmpty();
    }

    /**
     * The single code path every screen change goes through.
     *
     * <p>Order matters: the outgoing controller's {@code onHide()} runs
     * before the new root is installed, so background work stops before its
     * screen disappears; and {@code onShow()} runs last, after theming and
     * the fade have been set up, so a screen never starts work against a
     * half-configured scene.
     *
     * <p>The {@link Scene} itself is created once and thereafter only has
     * its root swapped — that is why the stylesheet is attached a single
     * time, and why {@code SimulationController} must remove its key filter
     * on hide (the Scene outlives the screen).
     */
    private void load(Route route, boolean pushCurrent) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(route.fxmlPath()));
            Parent root = loader.load();

            if (currentController != null) currentController.onHide();

            Object controller = loader.getController();
            currentController = (controller instanceof Navigable navigable) ? navigable : null;
            if (currentController != null) currentController.setRouter(this);

            if (scene == null) {
                scaleHost = new Group(root);
                scene = new Scene(scaleHost, AppConfig.DEFAULT_WIDTH, AppConfig.DEFAULT_HEIGHT);
                scene.getStylesheets().add(getClass().getResource("/css/theme.css").toExternalForm());
                // Scene.fill defaults to white; without this the ~180ms
                // fadeIn below lets it show through as a white flash while
                // the new (semi-transparent) root doesn't yet fully cover
                // the scene. Set once here for the first screen...
                scene.setFill(ThemeManager.getInstance().getCurrent().backgroundColor());
                stage.setScene(scene);

                // Re-scale whenever the window resizes or the preference
                // changes. Registered once, on the single Scene that
                // outlives every screen.
                scene.widthProperty().addListener((o, a, b) -> applyScaling());
                scene.heightProperty().addListener((o, a, b) -> applyScaling());
                ThemeManager.getInstance().addListener(this::applyScaling);
            } else {
                if (pushCurrent) history.push(activeRoute);
                // ...and again here, every subsequent navigation, in case the
                // theme changed since the last one (the constructor listener
                // above only covers a toggle while a scene already exists).
                scene.setFill(ThemeManager.getInstance().getCurrent().backgroundColor());
                scene.setRoot(root);
            }
            uiRoot = root;
            applyScaling();
            activeRoute = route;

            ThemeManager.getInstance().register(root);
            stage.setTitle(AppConfig.APP_NAME + "  ·  " + route.title());

            fadeIn(root);
            if (currentController != null) currentController.onShow();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to load screen: " + route, e);
        }
    }

    /** Fades a newly loaded screen in — or snaps it straight to full opacity when reduced motion is enabled. */
    /**
     * Applies the interface scale as a true zoom — the same model a browser's
     * Ctrl+/Ctrl− uses.
     *
     * <p><b>The bug this exists to prevent.</b> Simply adding a {@link Scale}
     * transform to the scene root does not work: {@code Scene} first resizes
     * its root to fill the window, and the transform then magnifies that
     * already-full-size layout. At 1.2 a 1280×800 window renders 1536×960
     * of content, so everything past the right and bottom edges is clipped
     * (the menu's corner links vanish) and centred content visibly shifts
     * down and right.
     *
     * <p><b>The fix.</b> Lay the UI out in a logical area of {@code window /
     * scale}, then magnify by {@code scale} — the result lands exactly
     * window-sized. Content genuinely reflows into the smaller logical area
     * first, so nothing is ever pushed off-screen. This requires the UI root
     * NOT to be the Scene root (see {@link #scaleHost}).
     *
     * <p>Size is pinned with min/pref/max together because this root is a
     * fixed viewport, not a component negotiating for space.
     */
    private void applyScaling() {
        if (uiRoot == null || scene == null) return;

        double s = ThemeManager.getInstance().getFontScale();

        uiRoot.getTransforms().removeIf(t -> t instanceof Scale);
        if (s != 1.0) uiRoot.getTransforms().add(new Scale(s, s, 0, 0));

        if (uiRoot instanceof Region region) {
            double logicalW = scene.getWidth()  / s;
            double logicalH = scene.getHeight() / s;
            region.setMinWidth(logicalW);   region.setMinHeight(logicalH);
            region.setPrefWidth(logicalW);  region.setPrefHeight(logicalH);
            region.setMaxWidth(logicalW);   region.setMaxHeight(logicalH);
            region.resize(logicalW, logicalH);
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
