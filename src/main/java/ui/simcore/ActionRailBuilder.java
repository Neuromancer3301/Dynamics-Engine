package ui.simcore;

import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;

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
}
