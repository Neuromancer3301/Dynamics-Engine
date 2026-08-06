package controller;

import component.NavCardController;
import config.AppConfig;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import navigation.Navigable;
import navigation.Route;
import navigation.SceneRouter;

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
 *   <li><b>Settings/About</b> — utility, not simulations, so they're quiet
 *       corner links (plain {@link Label}s styled and wired as links, not
 *       {@code NavCard}s) rather than competing with the hero grid.</li>
 * </ul>
 */
public final class MainMenuController implements Initializable, Navigable {

    @FXML private BorderPane root;
    @FXML private Label versionLabel;
    @FXML private Label settingsLink;
    @FXML private Label aboutLink;

    // fx:include fx:id="cardPendulum" auto-injects both the included root
    // (as `cardPendulum`) and its controller (as `cardPendulumController`).
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
                () -> router.navigate(Route.SIMULATION));

        cardSlotTwoController.configureComingSoon(
                "02", "◌", "Coming Soon",
                "A second dynamical system — reserved for what's next.");

        cardSlotThreeController.configureComingSoon(
                "03", "◌", "Coming Soon",
                "A third slot, waiting for its simulation.");

        wireUtilityLink(settingsLink, () -> router.navigate(Route.SETTINGS));
        wireUtilityLink(aboutLink, () -> router.navigate(Route.ABOUT));
    }

    /**
     * Makes a plain {@code Label} behave like a link: clickable, keyboard-
     * activatable (Enter/Space once focused via Tab), and cursor feedback —
     * without pulling in a full {@code NavCard} or a {@code Button}'s
     * heavier default chrome for what's meant to read as understated.
     */
    private static void wireUtilityLink(Label link, Runnable onActivate) {
        link.setFocusTraversable(true);
        link.setOnMouseClicked(e -> onActivate.run());
        link.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) onActivate.run();
        });
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }
}
