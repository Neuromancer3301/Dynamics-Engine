package ui.pendulum;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The sidebar's "Display" group: trail mode, velocity tinting, and trace-art
 * export. Moved out of {@code ControlPanel} — see round 1 §10 of the UI
 * restructuring plan.
 */
public final class DisplayGroupPanel extends VBox {

    private static final Logger LOG = Logger.getLogger(DisplayGroupPanel.class.getName());

    // Kept as a field so PendulumCanvas.cycleTrailMode()'s new state can be
    // reflected in this button's label after each click.
    private final Button btnTrailMode = new Button();

    public DisplayGroupPanel(PendulumCanvas pendulumCanvas) {
        super(10);

        SidebarControlFactory.styleButton(btnTrailMode);
        refreshTrailModeLabel(pendulumCanvas.getTrailMode());
        btnTrailMode.setOnAction(e -> {
            pendulumCanvas.cycleTrailMode();
            refreshTrailModeLabel(pendulumCanvas.getTrailMode());
        });

        ToggleButton btnVelocityTint = new ToggleButton("🌡  Velocity Tint: Off");
        SidebarControlFactory.styleButton(btnVelocityTint);
        btnVelocityTint.setSelected(pendulumCanvas.isVelocityTint());
        btnVelocityTint.setOnAction(e -> {
            boolean on = btnVelocityTint.isSelected();
            pendulumCanvas.setVelocityTint(on);
            btnVelocityTint.setText(on ? "🌡  Velocity Tint: On" : "🌡  Velocity Tint: Off");
        });
        Label velocityTintHint = SidebarControlFactory.hintLabel("Colours the trail by speed (blue=slow, red=fast) instead of by link.");

        Button btnExportTraceArt = new Button("🖼  Export Trace Art (PNG)");
        SidebarControlFactory.styleButton(btnExportTraceArt);
        Label traceArtHint = SidebarControlFactory.hintLabel("Saves the pendulum view exactly as shown — try it with a long trail on.");
        btnExportTraceArt.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export Trace Art");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
            chooser.setInitialFileName("dynamics-engine-trace-art.png");
            File file = chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
            if (file == null) return;
            try {
                pendulumCanvas.exportSnapshot(file.toPath());
                traceArtHint.setText("Saved to " + file.getName());
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Failed to export trace art to " + file, ex);
                traceArtHint.setText("Export failed: " + ex.getMessage());
            }
        });

        getChildren().setAll(btnTrailMode, btnVelocityTint, velocityTintHint, btnExportTraceArt, traceArtHint);
    }

    /** Keeps the trail button's label showing the mode that is actually active on the canvas. */
    private void refreshTrailModeLabel(PendulumCanvas.TrailMode mode) {
        btnTrailMode.setText(switch (mode) {
            case OFF       -> "〜  Trail: Off";
            case TIP_ONLY  -> "〜  Trail: Tip Only";
            case ALL_LINKS -> "〜  Trail: All Links";
        });
    }
}
