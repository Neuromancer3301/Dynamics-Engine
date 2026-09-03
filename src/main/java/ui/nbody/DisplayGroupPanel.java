package ui.nbody;

import physics.nbody.NBodyConfig;
import theme.Theme;
import theme.ThemeManager;
import ui.icon.Icons;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The sidebar's "Display" group: the Follow-COM toggle, and (round 1.1)
 * per-body motion trails — both pure view concerns with zero physics
 * effect, the same reasoning {@code ui.pendulum.DisplayGroupPanel} already
 * applies to its own trail mode/velocity tint. See the n-body
 * implementation spec §8.
 *
 * <p><b>Why checkboxes, not a single OFF/SELECTED/ALL cycle</b> (the
 * pendulum's own {@code TrailMode} shape): the pendulum's "tip" and "all
 * links" are meaningful because a chain has an inherent order — there's no
 * such privileged single body here, and a solar system's actual moments of
 * interest are usually a handful of specific bodies (e.g. "the Earth-Moon
 * pair"), not literally every body or a canvas-selection-driven single one.
 * A checkbox per body covers "one," "several," "all," and "none" with one
 * mechanism; {@link #DisplayGroupPanel}'s All/None buttons are bulk
 * convenience actions over the same underlying per-body state, not a
 * separate mode.
 */
public final class DisplayGroupPanel extends VBox {

    private final NBodyCanvas canvas;
    private final VBox trailCheckboxes = new VBox(4);

    public DisplayGroupPanel(NBodyCanvas canvas, NBodyConfig initialConfig) {
        super(10);
        this.canvas = canvas;

        Theme theme = ThemeManager.getInstance().getCurrent();
        boolean initiallyOn = canvas.isFollowingCenterOfMass();

        ToggleButton btnFollowCom = new ToggleButton(followLabel(initiallyOn));
        btnFollowCom.setMaxWidth(Double.MAX_VALUE);
        btnFollowCom.getStyleClass().add("sidebar-button");
        btnFollowCom.setSelected(initiallyOn);

        Icons.IconView followIcon = Icons.create(Icons.Glyph.FOLLOW, 18,
                initiallyOn ? Icons.activeColor(theme) : Icons.idleColor(theme));
        btnFollowCom.setGraphic(followIcon);

        btnFollowCom.setOnAction(e -> {
            boolean on = btnFollowCom.isSelected();
            canvas.setFollowCenterOfMass(on);
            btnFollowCom.setText(followLabel(on));
            Theme t = ThemeManager.getInstance().getCurrent();
            followIcon.setColor(on ? Icons.activeColor(t) : Icons.idleColor(t));
        });

        ThemeManager.getInstance().addListener(() -> {
            Theme t = ThemeManager.getInstance().getCurrent();
            followIcon.setColor(btnFollowCom.isSelected() ? Icons.activeColor(t) : Icons.idleColor(t));
        });

        Label followHint = hintLabel("Keeps the scene's center of mass centered on screen — useful once bodies "
                + "have drifted far from the world origin. Pan/zoom still work normally while following.");

        Label trailHeader = sectionLabel("Motion Trails");

        Button btnAllTrails = smallButton("All");
        btnAllTrails.setOnAction(e -> { canvas.setAllTrailsEnabled(true); syncCheckboxesFromCanvas(); });
        Button btnNoTrails = smallButton("None");
        btnNoTrails.setOnAction(e -> { canvas.setAllTrailsEnabled(false); syncCheckboxesFromCanvas(); });
        HBox trailButtonsRow = new HBox(6, btnAllTrails, btnNoTrails);

        ScrollPane trailScroll = new ScrollPane(trailCheckboxes);
        trailScroll.setFitToWidth(true);
        trailScroll.setPrefHeight(220);
        trailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // Same inline-fallback reasoning as BodiesGroupPanel's own list —
        // theme.css isn't part of this phase's edit list, so a ScrollPane
        // and its content need their dark styling applied here instead of
        // through a stylesheet rule that doesn't exist yet.
        trailScroll.setStyle(
                "-fx-background: -bg-surface;"
              + "-fx-background-color: -bg-surface;"
              + "-fx-border-color: -line;"
              + "-fx-border-width: 1;");
        trailCheckboxes.setStyle("-fx-background-color: -bg-surface; -fx-padding: 4;");

        Label trailHint = hintLabel("Check a body to trace its recent path. History clears on Reset; "
                + "which bodies are checked does not.");

        rebuildTrailCheckboxes(initialConfig);

        getChildren().setAll(
                btnFollowCom, followHint,
                sep(), trailHeader, trailButtonsRow, trailScroll, trailHint);
    }

    /** Repopulates the checkbox list from the current config — call after every structural edit (Add/Delete/preset load changes N and/or names). */
    public void refreshBodies(NBodyConfig config) {
        rebuildTrailCheckboxes(config);
    }

    private void rebuildTrailCheckboxes(NBodyConfig config) {
        trailCheckboxes.getChildren().clear();
        for (int i = 0; i < config.getN(); i++) {
            int index = i; // effectively-final copy for the lambda below
            CheckBox cb = new CheckBox(config.getName(i));
            cb.getStyleClass().add("sidebar-checkbox");
            cb.setTextFill(javafx.scene.paint.Color.web("#D6D6DC"));
            cb.setSelected(canvas.isTrailEnabled(index));
            cb.setOnAction(e -> canvas.setTrailEnabled(index, cb.isSelected()));
            trailCheckboxes.getChildren().add(cb);
        }
    }

    /** Keeps each checkbox's visual state in sync after a bulk change (the All/None buttons) made directly on the canvas. */
    private void syncCheckboxesFromCanvas() {
        var children = trailCheckboxes.getChildren();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) instanceof CheckBox cb) {
                cb.setSelected(canvas.isTrailEnabled(i));
            }
        }
    }

    private static String followLabel(boolean on) {
        return "Follow Center of Mass: " + (on ? "On" : "Off");
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        return l;
    }

    private static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-hint");
        l.setWrapText(true);
        return l;
    }

    private static Button smallButton(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("sidebar-button");
        return b;
    }

    private static Separator sep() {
        Separator s = new Separator();
        s.getStyleClass().add("sidebar-separator");
        return s;
    }
}
