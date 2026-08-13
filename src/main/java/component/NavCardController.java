package component;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import theme.Theme;
import theme.ThemeManager;
import ui.icon.Icons;

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
 *
 * <p>A live card can also optionally grow downward on hover to reveal one
 * extra {@code detail} line beyond {@code descriptionLabel}, plus a
 * placeholder demo-video box beneath it — see the
 * {@link #configure(String, ui.icon.Icons.Glyph, String, String, String, Runnable)}
 * overload. {@link #configureComingSoon} never opts into this: a disabled,
 * reserved-but-unbuilt slot has nothing further to reveal.
 */
public final class NavCardController {

    // Matches theme.css's .nav-card -fx-min-height (base) — kept as
    // duplicated constants rather than read back from CSS since JavaFX
    // exposes no clean way to read a looked-up -fx- value from Java; both
    // values are small, deliberate, and co-located with the animation code
    // that actually needs them as literal doubles.
    private static final double BASE_HEIGHT     = 220.0;
    // Widened from 56.0 to comfortably fit both detailLabel's text and the
    // new videoBox placeholder (see setExpanded) without clipping.
    private static final double EXPANDED_GROWTH = 240.0;
    private static final Duration EXPAND_DURATION = Duration.millis(160);
    // Matches the old .nav-card-icon font-size (30px) this replaces — see
    // iconHost below — so the swap to a Canvas glyph keeps the same visual
    // weight.
    private static final double ICON_SIZE = 30.0;

    @FXML private VBox root;
    @FXML private Label indexLabel;
    @FXML private StackPane iconHost;
    @FXML private Label titleLabel;
    @FXML private Label descriptionLabel;
    @FXML private Label detailLabel;
    @FXML private StackPane videoBox;

    private Runnable onActivate;
    private boolean expandable = false;
    private boolean comingSoon = false;
    private Icons.IconView iconView;
    private Timeline expandAnim;

    /** Wires hover, click, and keyboard activation once, at load. Content arrives later via {@link #configure}. */
    @FXML
    private void initialize() {
        root.setFocusTraversable(true);
        root.setPrefHeight(BASE_HEIGHT);

        ScaleTransition grow = scaleTransition(1.03);
        ScaleTransition shrink = scaleTransition(1.00);

        root.setOnMouseEntered(e -> {
            if (!ThemeManager.getInstance().isReducedMotion()) grow.playFromStart();
            setExpanded(true);
        });
        root.setOnMouseExited(e -> {
            if (!ThemeManager.getInstance().isReducedMotion()) shrink.playFromStart();
            setExpanded(false);
        });
        root.setOnMouseClicked(e -> activate());
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) activate();
        });

        // Keeps the icon's tint in sync with a live theme toggle, the same
        // way component.UtilityIconButton and ui.SidebarTabs do for their
        // own Canvas glyphs.
        ThemeManager.getInstance().addListener(this::refreshIconColor);
    }

    /** Configures a live, clickable card with no extra hover-reveal detail. */
    public void configure(String index, Icons.Glyph icon, String title, String description, Runnable onActivate) {
        configure(index, icon, title, description, null, onActivate);
    }

    /**
     * Configures a live, clickable card. {@code index} is the card's
     * position in the simulation suite ("01", "02", ...) — a small,
     * genuinely informative label (there really is a sequence; see
     * {@link #configureComingSoon} for the slots later in it that aren't
     * built yet) rather than decoration.
     *
     * <p>{@code detail}, if non-null/non-blank, is shown only once the card
     * expands on hover (see {@link #initialize}) — a short "what this
     * demonstrates" blurb or feature list, one tier more detail than
     * {@code description} without cluttering the card's resting state.
     */
    public void configure(String index, Icons.Glyph icon, String title, String description,
                           String detail, Runnable onActivate) {
        indexLabel.setText(index);
        titleLabel.setText(title);
        descriptionLabel.setText(description);
        this.onActivate = onActivate;
        this.expandable = detail != null && !detail.isBlank();
        if (expandable) detailLabel.setText(detail);
        this.comingSoon = false;
        setIcon(icon);
    }

    /**
     * Configures a reserved-but-not-yet-built slot: same layout as a live
     * card, but disabled — {@link javafx.scene.Node#setDisable} stops mouse
     * and key events from reaching it at all (so {@link #initialize}'s
     * hover/click wiring above simply never fires, no extra guards needed
     * there) and lets {@code nav-card-coming-soon}'s CSS dim it and switch
     * its border to dashed, reading as "an empty slot" rather than "a
     * broken button." Never expandable — there is no detail to reveal for a
     * slot that isn't built yet.
     */
    public void configureComingSoon(String index, Icons.Glyph icon, String title, String description) {
        indexLabel.setText(index);
        titleLabel.setText(title);
        descriptionLabel.setText(description);
        this.onActivate = null;
        this.expandable = false;
        this.comingSoon = true;
        setIcon(icon);
        root.getStyleClass().add("nav-card-coming-soon");
        root.setDisable(true);
    }

    /**
     * Builds (or repaints, if a card is ever reconfigured) the icon Canvas
     * and colors it for the current state/theme — {@code -accent} for a
     * live card, {@code -ink-3} for a coming-soon one, matching what {@code
     * .nav-card-coming-soon .nav-card-index} already does for the index
     * label via CSS. See {@link #refreshIconColor} for the live-toggle path.
     */
    private void setIcon(Icons.Glyph glyph) {
        Theme current = ThemeManager.getInstance().getCurrent();
        Color color = comingSoon ? Icons.dimColor(current) : Icons.activeColor(current);
        iconView = Icons.create(glyph, ICON_SIZE, color);
        iconHost.getChildren().setAll(iconView);
    }

    private void refreshIconColor() {
        if (iconView == null) return;
        Theme current = ThemeManager.getInstance().getCurrent();
        iconView.setColor(comingSoon ? Icons.dimColor(current) : Icons.activeColor(current));
    }

    private void activate() {
        if (onActivate != null) onActivate.run();
    }

    /**
     * Grows/shrinks the card's height to reveal/hide {@code detailLabel}
     * and, below it, the placeholder {@code videoBox} — one animation
     * drives both, not two independent ones. Respects {@link
     * ThemeManager#isReducedMotion()} by jumping straight to the target
     * height instead of tweening — content visibility (unlike the purely
     * decorative hover-scale above) shouldn't depend on motion being
     * enabled.
     */
    private void setExpanded(boolean expanded) {
        if (!expandable) return;
        if (expandAnim != null) expandAnim.stop();

        double target = expanded ? BASE_HEIGHT + EXPANDED_GROWTH : BASE_HEIGHT;

        if (ThemeManager.getInstance().isReducedMotion()) {
            root.setPrefHeight(target);
            detailLabel.setVisible(expanded);
            detailLabel.setManaged(expanded);
            detailLabel.setOpacity(expanded ? 1.0 : 0.0);
            videoBox.setVisible(expanded);
            videoBox.setManaged(expanded);
            videoBox.setOpacity(expanded ? 1.0 : 0.0);
            return;
        }

        detailLabel.setVisible(true);
        detailLabel.setManaged(true);
        videoBox.setVisible(true);
        videoBox.setManaged(true);
        expandAnim = new Timeline(new KeyFrame(EXPAND_DURATION,
                new KeyValue(root.prefHeightProperty(), target, Interpolator.EASE_BOTH),
                new KeyValue(detailLabel.opacityProperty(), expanded ? 1.0 : 0.0, Interpolator.EASE_BOTH),
                new KeyValue(videoBox.opacityProperty(), expanded ? 1.0 : 0.0, Interpolator.EASE_BOTH)));
        if (!expanded) {
            expandAnim.setOnFinished(e -> {
                detailLabel.setVisible(false);
                detailLabel.setManaged(false);
                videoBox.setVisible(false);
                videoBox.setManaged(false);
            });
        }
        expandAnim.playFromStart();
    }

    private ScaleTransition scaleTransition(double target) {
        ScaleTransition t = new ScaleTransition(Duration.millis(120), root);
        t.setToX(target);
        t.setToY(target);
        return t;
    }
}
