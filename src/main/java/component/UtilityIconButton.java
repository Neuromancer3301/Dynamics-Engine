package component;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import theme.ThemeManager;
import ui.icon.Icons;

/**
 * A top-right menu utility button (Settings/About, see MainMenu.fxml):
 * icon-only at rest with a real &ge;40&times;40 hit target, revealing its
 * text label on hover/focus (fade) and tinting both icon and text with the
 * accent color — the same "hover reveals + accents" convention {@code
 * .nav-card:hover} already uses elsewhere on this screen.
 *
 * <p>The icon is one of {@link ui.icon.Icons}'s hand-rolled Canvas glyphs,
 * not a styleable node, so its color is set here in Java (see {@link
 * #refreshIconColor}) rather than through {@code theme.css}; the revealed
 * label is a real {@link Label} and is styled entirely through the
 * {@code .menu-utility-*} classes in {@code theme.css}.
 */
public final class UtilityIconButton extends HBox {

    private static final double ICON_SIZE = 20;
    private static final Duration FADE_DURATION = Duration.millis(140);

    private final Icons.IconView icon;
    private final Label label;
    private Runnable onActivate;

    // Tracked independently (not one boolean overwritten by whichever event
    // fired last) so the label stays revealed for as long as EITHER is true —
    // otherwise a mouse-exit while still keyboard-focused would clear focus's
    // reveal too. See refreshRevealState.
    private boolean hovering = false;
    private boolean focused = false;

    public UtilityIconButton(Icons.Glyph glyph, String text) {
        super(6);
        getStyleClass().add("menu-utility-button");
        setAlignment(Pos.CENTER_RIGHT);
        setPadding(new Insets(9, 10, 9, 10));
        setMinSize(40, 40);
        setCursor(Cursor.HAND);
        setFocusTraversable(true);

        label = new Label(text);
        label.getStyleClass().add("menu-utility-label");
        label.setOpacity(0.0);

        icon = Icons.create(glyph, ICON_SIZE, Icons.idleColor(ThemeManager.getInstance().getCurrent()));

        getChildren().addAll(label, icon);

        setOnMouseEntered(e -> { hovering = true; refreshRevealState(); });
        setOnMouseExited(e -> { hovering = false; refreshRevealState(); });
        focusedProperty().addListener((obs, was, is) -> { focused = is; refreshRevealState(); });
        setOnMouseClicked(e -> activate());
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) activate();
        });

        ThemeManager.getInstance().addListener(() -> refreshIconColor(hovering || focused));
    }

    public void setOnActivate(Runnable onActivate) {
        this.onActivate = onActivate;
    }

    private void activate() {
        if (onActivate != null) onActivate.run();
    }

    /** Reveals the label whenever either hover or focus is active; hides it only once both have cleared. */
    private void refreshRevealState() {
        boolean on = hovering || focused;
        refreshIconColor(on);

        if (ThemeManager.getInstance().isReducedMotion()) {
            label.setOpacity(on ? 1.0 : 0.0);
            return;
        }
        FadeTransition fade = new FadeTransition(FADE_DURATION, label);
        fade.setToValue(on ? 1.0 : 0.0);
        fade.play();
    }

    private void refreshIconColor(boolean on) {
        theme.Theme t = ThemeManager.getInstance().getCurrent();
        icon.setColor(on ? Icons.activeColor(t) : Icons.idleColor(t));
    }
}
