package ui.pendulum;

import theme.Theme;
import theme.ThemeManager;
import ui.icon.Icons;
import ui.simcore.ActionRailBuilder;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * The pendulum screen's left action rail (round 3 §4-e / round 4 §2): Edit
 * and Add are mutually exclusive tools governing what a press/double-click
 * on a bob does; Snap-to-Unit is an independent toggle. Moved verbatim from
 * {@code controller.SimulationController#buildActionRail}/{@code
 * #setActiveTool} — see round 1 §6 of the UI restructuring plan.
 */
public final class PendulumActionRailBuilder extends ActionRailBuilder {

    /** Which rail tool governs press/double-click behavior on the pendulum canvas. */
    public enum Tool { EDIT, ADD }

    private final PendulumCanvas canvas;
    private Tool activeTool = Tool.EDIT;

    public PendulumActionRailBuilder(VBox rail, PendulumCanvas canvas) {
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
        Tooltip.install(editButton, new Tooltip("Edit — click a link to pose it; drag to pose live."));

        Button addButton = new Button();
        addButton.getStyleClass().add("rail-button");
        Icons.IconView addIcon = Icons.create(Icons.Glyph.ADD, 20, Icons.idleColor(theme));
        addButton.setGraphic(addIcon);
        Tooltip.install(addButton, new Tooltip("Add — double-click a link to insert a new one right after it."));

        editButton.setOnAction(e -> setActiveTool(Tool.EDIT, editButton, editIcon, addButton, addIcon));
        addButton.setOnAction(e -> setActiveTool(Tool.ADD, editButton, editIcon, addButton, addIcon));

        ToggleButton snapButton = new ToggleButton();
        snapButton.getStyleClass().add("rail-button");
        Icons.IconView snapIcon = Icons.create(Icons.Glyph.SNAP, 20, Icons.idleColor(theme));
        snapButton.setGraphic(snapIcon);
        Tooltip.install(snapButton, new Tooltip("Snap to 15° angle / 0.25m length increments"));
        snapButton.setOnAction(e -> {
            boolean on = snapButton.isSelected();
            canvas.setSnapEnabled(on);
            Theme t = ThemeManager.getInstance().getCurrent();
            snapIcon.setColor(on ? Icons.activeColor(t) : Icons.idleColor(t));
        });

        rail.getChildren().addAll(editButton, addButton, railSeparator(), snapButton);

        ThemeManager.getInstance().addListener(() -> {
            Theme t = ThemeManager.getInstance().getCurrent();
            editIcon.setColor(activeTool == Tool.EDIT ? Icons.activeColor(t) : Icons.idleColor(t));
            addIcon.setColor(activeTool == Tool.ADD ? Icons.activeColor(t) : Icons.idleColor(t));
            snapIcon.setColor(snapButton.isSelected() ? Icons.activeColor(t) : Icons.idleColor(t));
        });
    }

    /**
     * Switches the active rail tool: moves the "locked-active" style to
     * whichever button is now current, recolors both icons, and gates
     * {@link PendulumCanvas}'s drag-posing accordingly — Add's only job is
     * inserting a link, not posing the chain, so left/right-drag are
     * intentionally no-ops while it's active.
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
