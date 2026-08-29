package ui.simcore;

import javafx.scene.Node;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Simulation-agnostic base for a screen's left action rail: a vertical strip
 * of tool buttons hosted in an FXML-declared {@link VBox}. Owns only the
 * "clear and rebuild" lifecycle and a couple of shared small factories; a
 * subclass (see {@code ui.pendulum.PendulumActionRailBuilder}) supplies the
 * actual buttons and whatever tool-selection state they represent.
 */
public abstract class ActionRailBuilder {

    protected final VBox rail;

    protected ActionRailBuilder(VBox rail) {
        this.rail = rail;
        installVerticalArrowTraversal();
    }

    /** Clears the rail and rebuilds its buttons. Safe to call more than once. */
    public final void build() {
        rail.getChildren().clear();
        buildButtons();
    }

    /** Populates {@link #rail} with this rail's buttons. Called once per {@link #build()}. */
    protected abstract void buildButtons();

    /** A themed divider between rail sections. A new instance each call, since a JavaFX node can only occupy one place in the scene graph. */
    protected static Separator railSeparator() {
        Separator sep = new Separator();
        sep.getStyleClass().add("rail-separator");
        return sep;
    }

    /**
     * Round 3.1: JavaFX's own default arrow-key focus traversal moves focus
     * down through this rail's buttons fine — including skipping over a
     * non-traversable {@link Separator} correctly — but fails to move focus
     * back <em>up</em> across that same separator (confirmed directly:
     * traversal between two ordinary sibling buttons works both directions;
     * only the direction that has to skip a separator to reach the next
     * traversable sibling breaks, and only going up). That's a JavaFX
     * traversal-engine asymmetry, not anything this app's own code does —
     * there is no application code anywhere that touches {@code KeyCode.UP}.
     *
     * <p>Rather than depend on a partially-working default for this
     * container, this installs a small explicit handler that owns Up/Down
     * navigation itself: walk {@link #rail}'s direct children from whichever
     * one currently has focus, skipping non-{@code focusTraversable} ones
     * (the separator) exactly the way default traversal already does going
     * down, and request focus on the next real button found. Deterministic
     * in both directions regardless of how many separators the rail ends up
     * with, so a future rail gaining more sections doesn't reopen this.
     *
     * <p>Registered as a capturing {@code addEventFilter}, not a bubbling
     * {@code addEventHandler} — confirmed empirically (not just assumed):
     * JavaFX's own default traversal consumes the arrow key at (or before)
     * the focused node itself even on the failing separator-crossing case,
     * so a bubbling handler on {@code rail} never even sees the event by
     * the time it would run. A filter runs before that default handling
     * gets the chance, so this can supersede it — including the case where
     * the default would otherwise consume the key and silently go nowhere.
     */
    private void installVerticalArrowTraversal() {
        rail.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCode code = e.getCode();
            if (code != KeyCode.UP && code != KeyCode.DOWN) return;
            if (e.isConsumed()) return;

            List<Node> children = rail.getChildren();
            int from = children.indexOf(rail.getScene() != null ? rail.getScene().getFocusOwner() : null);
            if (from < 0) return; // focus isn't on a direct child of this rail

            int step = (code == KeyCode.DOWN) ? 1 : -1;
            int i = from + step;
            while (i >= 0 && i < children.size() && !children.get(i).isFocusTraversable()) i += step;

            if (i >= 0 && i < children.size()) {
                children.get(i).requestFocus();
                e.consume();
            }
        });
    }
}
