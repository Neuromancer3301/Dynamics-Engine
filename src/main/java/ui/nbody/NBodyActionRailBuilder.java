package ui.nbody;

import theme.Theme;
import theme.ThemeManager;
import ui.icon.Icons;
import ui.simcore.ActionRailBuilder;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * The n-body screen's left action rail: Edit and Add as mutually exclusive
 * tools, same {@code Tool} enum + locked-active-style pattern as {@code
 * ui.pendulum.PendulumActionRailBuilder}. See the n-body implementation
 * spec §8.
 *
 * <p>No Snap toggle — nothing analogous to angle/length snapping exists for
 * free-form body placement, so this rail is deliberately smaller than the
 * pendulum's three-control one.
 *
 * <p><b>Round 1.1:</b> the Add tool now also disables dragging an existing
 * body, mirroring the pendulum's own Add-tool gating after all. The original
 * round 1 reasoning ("n-body's Add gesture targets empty space, never
 * competing with dragging") is true at the level of "can these two gestures
 * physically conflict," but it produces a rail where switching tools has no
 * noticeable effect on the one thing most people try first — grabbing a
 * body — which reads as "both buttons do the same thing," not as two
 * distinct tools with a subtle third-case difference in behavior on empty
 * space. Disabling dragging while Add is active makes the switch felt
 * immediately, not just discoverable by deliberately clicking blank canvas.
 */
public final class NBodyActionRailBuilder extends ActionRailBuilder {

    /** Which rail tool governs click/double-click/drag behavior on the n-body canvas. */
    public enum Tool { EDIT, ADD }

    private final NBodyCanvas canvas;
    private Tool activeTool = Tool.EDIT;

    public NBodyActionRailBuilder(VBox rail, NBodyCanvas canvas) {
        super(rail);
        this.canvas = canvas;
    }

    public Tool getActiveTool() { return activeTool; }

    @Override
    protected void buildButtons() {
        Theme theme = ThemeManager.getInstance().getCurrent();

        Button editButton = new Button();
        editButton.getStyleClass().addAll("rail-button", "rail-button-locked-active"); // starts active
        Icons.IconView editIcon = Icons.create(Icons.Glyph.SELECT, 20, Icons.activeColor(theme));
        editButton.setGraphic(editIcon);
        Tooltip.install(editButton, new Tooltip("Edit — click a body to select it; drag to reposition it live."));

        Button addButton = new Button();
        addButton.getStyleClass().add("rail-button");
        Icons.IconView addIcon = Icons.create(Icons.Glyph.ADD, 20, Icons.idleColor(theme));
        addButton.setGraphic(addIcon);
        Tooltip.install(addButton, new Tooltip("Add — click empty space to place a new body there. Dragging is off while this tool is active."));

        editButton.setOnAction(e -> setActiveTool(Tool.EDIT, editButton, editIcon, addButton, addIcon));
        addButton.setOnAction(e -> setActiveTool(Tool.ADD, editButton, editIcon, addButton, addIcon));

        rail.getChildren().addAll(editButton, addButton);

        ThemeManager.getInstance().addListener(() -> {
            Theme t = ThemeManager.getInstance().getCurrent();
            editIcon.setColor(activeTool == Tool.EDIT ? Icons.activeColor(t) : Icons.idleColor(t));
            addIcon.setColor(activeTool == Tool.ADD ? Icons.activeColor(t) : Icons.idleColor(t));
        });
    }

    /**
     * Switches the active rail tool: moves the "locked-active" style to
     * whichever button is now current, recolors both icons, and gates the
     * canvas's drag-to-reposition gesture accordingly — see the class
     * javadoc for why this gating was added in round 1.1.
     */
    private void setActiveTool(Tool tool, Button editButton, Icons.IconView editIcon,
                                Button addButton, Icons.IconView addIcon) {
        activeTool = tool;
        boolean editActive = tool == Tool.EDIT;

        editButton.getStyleClass().remove("rail-button-locked-active");
        addButton.getStyleClass().remove("rail-button-locked-active");
        (editActive ? editButton : addButton).getStyleClass().add("rail-button-locked-active");

        Theme t = ThemeManager.getInstance().getCurrent();
        editIcon.setColor(editActive ? Icons.activeColor(t) : Icons.idleColor(t));
        addIcon.setColor(editActive ? Icons.idleColor(t) : Icons.activeColor(t));

        canvas.setDragEditingEnabled(editActive);
    }
}
