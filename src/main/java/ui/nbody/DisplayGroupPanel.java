package ui.nbody;

import physics.nbody.NBodyConfig;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The sidebar's "Display" group: the camera-follow dropdown (round 1.1's
 * Follow-COM toggle, extended round 1.4 with a Selected Body option), and
 * (round 1.1) per-body motion trails — all pure view concerns with zero
 * physics effect, the same reasoning {@code ui.pendulum.DisplayGroupPanel}
 * already applies to its own trail mode/velocity tint. See the n-body
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

        // Round 1.4: was a plain on/off "Follow Center of Mass" toggle;
        // now a dropdown, since there's a second thing worth following —
        // see NBodyCanvas.FollowMode's own javadoc for what each option
        // actually does.
        Label followHeader = sectionLabel("Camera Follow");
        ComboBox<NBodyCanvas.FollowMode> followBox =
                new ComboBox<>(FXCollections.observableArrayList(NBodyCanvas.FollowMode.values()));
        followBox.setMaxWidth(Double.MAX_VALUE);
        followBox.setValue(canvas.getFollowMode());
        followBox.setCellFactory(lv -> followModeCell());
        followBox.setButtonCell(followModeCell());
        followBox.setOnAction(e -> canvas.setFollowMode(followBox.getValue()));

        Label followHint = hintLabel("Off leaves the camera exactly where you left it. Center of Mass keeps the "
                + "scene's mass-weighted average centered — useful once bodies have drifted far from the world "
                + "origin; pan/zoom still work normally while following. Selected Body follows whichever body is "
                + "currently selected and zooms in to frame it — that body can't be dragged while followed this "
                + "way; click empty space, or switch this back to Off/Center of Mass, to release it.");

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
                followHeader, followBox, followHint,
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

    /** A fresh cell for the follow-mode dropdown — used for both the popup list and the closed box's own display cell, matching {@code BodiesGroupPanel}'s preset-picker pattern. */
    private static ListCell<NBodyCanvas.FollowMode> followModeCell() {
        return new ListCell<>() {
            @Override protected void updateItem(NBodyCanvas.FollowMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : followModeLabel(item));
            }
        };
    }

    private static String followModeLabel(NBodyCanvas.FollowMode mode) {
        return switch (mode) {
            case OFF -> "Off";
            case CENTER_OF_MASS -> "Center of Mass";
            case SELECTED_BODY -> "Selected Body";
        };
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
