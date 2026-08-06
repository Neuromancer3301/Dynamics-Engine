package component;

import javafx.animation.ScaleTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import theme.ThemeManager;

/**
 * Controller behind {@code fxml/component/NavCard.fxml} — a reusable,
 * self-contained navigation tile used on the main menu today and intended
 * for any future screen that needs a clickable card (a scenario gallery, a
 * plugin list, a settings-section chooser).
 *
 * <p>Because it's brought in via {@code <fx:include>}, every usage gets its
 * own controller instance; callers configure content and behaviour through
 * {@link #configure} rather than subclassing, so the same FXML serves every
 * caller without any Java inheritance.
 */
public final class NavCardController {

    @FXML private VBox root;
    @FXML private Label indexLabel;
    @FXML private Label iconLabel;
    @FXML private Label titleLabel;
    @FXML private Label descriptionLabel;

    private Runnable onActivate;

    @FXML
    private void initialize() {
        root.setFocusTraversable(true);

        ScaleTransition grow = scaleTransition(1.03);
        ScaleTransition shrink = scaleTransition(1.00);

        root.setOnMouseEntered(e -> { if (!ThemeManager.getInstance().isReducedMotion()) grow.playFromStart(); });
        root.setOnMouseExited(e -> { if (!ThemeManager.getInstance().isReducedMotion()) shrink.playFromStart(); });
        root.setOnMouseClicked(e -> activate());
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) activate();
        });
    }

    /**
     * Configures a live, clickable card. {@code index} is the card's
     * position in the simulation suite ("01", "02", ...) — a small,
     * genuinely informative label (there really is a sequence; see
     * {@link #configureComingSoon} for the slots later in it that aren't
     * built yet) rather than decoration. Call once, right after
     * {@code fx:include}.
     */
    public void configure(String index, String icon, String title, String description, Runnable onActivate) {
        indexLabel.setText(index);
        iconLabel.setText(icon);
        titleLabel.setText(title);
        descriptionLabel.setText(description);
        this.onActivate = onActivate;
    }

    /**
     * Configures a reserved-but-not-yet-built slot: same layout as a live
     * card, but disabled — {@link javafx.scene.Node#setDisable} stops mouse
     * and key events from reaching it at all (so {@link #initialize}'s
     * hover/click wiring above simply never fires, no extra guards needed
     * there) and lets {@code nav-card-coming-soon}'s CSS dim it and switch
     * its border to dashed, reading as "an empty slot" rather than "a
     * broken button."
     */
    public void configureComingSoon(String index, String icon, String title, String description) {
        indexLabel.setText(index);
        iconLabel.setText(icon);
        titleLabel.setText(title);
        descriptionLabel.setText(description);
        this.onActivate = null;
        root.getStyleClass().add("nav-card-coming-soon");
        root.setDisable(true);
    }

    private void activate() {
        if (onActivate != null) onActivate.run();
    }

    private ScaleTransition scaleTransition(double target) {
        ScaleTransition t = new ScaleTransition(Duration.millis(120), root);
        t.setToX(target);
        t.setToY(target);
        return t;
    }
}
