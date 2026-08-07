package controller;

import component.NavCardController;
import component.UtilityIconButton;
import config.AppConfig;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import navigation.Navigable;
import navigation.Route;
import navigation.SceneRouter;
import ui.icon.Icons;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Controller for the application's landing screen.
 *
 * <p>Two tiers of destination, styled and wired differently on purpose:
 * <ul>
 *   <li><b>Simulations</b> — the hero content, one {@code NavCard} each,
 *       numbered ("01", "02", ...) because they really are a growing,
 *       ordered suite. {@link #cardSlotTwoController} and {@link
 *       #cardSlotThreeController} are reserved-but-unbuilt slots today
 *       ({@link NavCardController#configureComingSoon}) — replacing one is:
 *       swap that call for a real {@link NavCardController#configure} with
 *       its own {@link Route}, same as {@link #cardPendulumController}
 *       already is. No layout change needed either way; the grid was built
 *       for three from the start.</li>
 *   <li><b>Settings/About</b> — utility, not simulations, so they're
 *       icon-first {@link UtilityIconButton}s (built here in Java, not
 *       FXML — see {@link #initialize}) rather than competing with the hero
 *       grid.</li>
 * </ul>
 */
public final class MainMenuController implements Initializable, Navigable {

    @FXML private BorderPane root;
    @FXML private Label versionLabel;
    @FXML private HBox utilityLinksBox;

    // fx:include fx:id="cardPendulum" auto-injects both the included root
    // (as `cardPendulum`) and its controller (as `cardPendulumController`).
    @FXML private Parent cardPendulum;
    @FXML private NavCardController cardPendulumController;
    @FXML private NavCardController cardSlotTwoController;
    @FXML private NavCardController cardSlotThreeController;

    private SceneRouter router;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        versionLabel.setText("v" + AppConfig.APP_VERSION);

        cardPendulumController.configure(
                "01", "⚛", "N-Pendulum Chain",
                "Configure any number of coupled links, watch chaos emerge, and drag it live.",
                "Demonstrates: RK4/Lagrangian mechanics, live angle & length editing, "
                        + "butterfly-effect ensembles, and bifurcation/Poincaré analysis.",
                () -> router.navigate(Route.SIMULATION));

        cardSlotTwoController.configureComingSoon(
                "02", "◌", "Coming Soon",
                "A second dynamical system — reserved for what's next.");

        cardSlotThreeController.configureComingSoon(
                "03", "◌", "Coming Soon",
                "A third slot, waiting for its simulation.");

        UtilityIconButton settingsButton = new UtilityIconButton(Icons.Glyph.SETTINGS, "Settings");
        settingsButton.setOnActivate(() -> router.navigate(Route.SETTINGS));

        UtilityIconButton aboutButton = new UtilityIconButton(Icons.Glyph.INFO, "About");
        aboutButton.setOnActivate(() -> router.navigate(Route.ABOUT));

        utilityLinksBox.getChildren().addAll(settingsButton, aboutButton);
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }

    /**
     * Without this, JavaFX's automatic initial-focus placement lands on the
     * first focus-traversable, enabled node in the scene graph — here, the
     * Settings utility button (built into the top region, ahead of the card
     * grid) — which then keeps its :focused glow/label revealed permanently
     * since nothing else ever takes focus away. Requesting focus on the
     * primary card instead fixes which node gets it, without touching the
     * focus-visibility system itself (still a real accessibility feature for
     * Settings/About when the user actually reaches them).
     *
     * <p>Runs on every return to this screen (this method fires each time,
     * per {@link Navigable}), not just first launch — one mechanism for
     * both. {@link Platform#runLater} defers past JavaFX's own automatic
     * initial-focus pass for this pulse so it reliably overrides it instead
     * of racing it.
     */
    @Override
    public void onShow() {
        Platform.runLater(() -> cardPendulum.requestFocus());
    }
}
