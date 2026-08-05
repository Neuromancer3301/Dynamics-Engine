package controller;

import component.NavCardController;
import config.AppConfig;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import navigation.Navigable;
import navigation.Route;
import navigation.SceneRouter;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controller for the application's landing screen.
 *
 * <p>Its only job is to describe the available destinations and hand off to
 * {@link SceneRouter}. Adding a fourth destination later is: one more
 * {@code <fx:include>} in {@code MainMenu.fxml}, one more {@link
 * NavCardController#configure} call below, and — if it needs its own
 * screen — one more {@link Route} constant. No other file changes.
 */
public final class MainMenuController implements Initializable, Navigable {

    @FXML private BorderPane root;
    @FXML private Label versionLabel;

    // fx:include fx:id="cardSimulation" auto-injects both the included root
    // (as `cardSimulation`) and its controller (as `cardSimulationController`).
    @FXML private NavCardController cardSimulationController;
    @FXML private NavCardController cardSettingsController;
    @FXML private NavCardController cardAboutController;

    private SceneRouter router;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        versionLabel.setText("v" + AppConfig.APP_VERSION);

        cardSimulationController.configure(
                "⚛", "Run Simulation",
                "Launch the N-pendulum chain — configure links, watch it swing, drag it live.",
                () -> router.navigate(Route.SIMULATION));

        cardSettingsController.configure(
                "⚙", "Settings",
                "Theme, defaults, and preferences.",
                () -> router.navigate(Route.SETTINGS));

        cardAboutController.configure(
                "ℹ", "About",
                "Architecture, credits, and version information.",
                () -> router.navigate(Route.ABOUT));
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }
}
