package ui.pendulum;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The sidebar's "Graphs" group: the seven graph-mode toggles (mutually
 * exclusive, or none selected — see {@link #toggleGraphMode}), the
 * bifurcation-sweep button/progress bar, and CSV export. Moved out of
 * {@code ControlPanel} — see round 1 §10 of the UI restructuring plan.
 */
public final class GraphsGroupPanel extends VBox {

    private static final Logger LOG = Logger.getLogger(GraphsGroupPanel.class.getName());

    // Independent ToggleButtons (not a ToggleGroup) so clicking the
    // already-active one can deselect it: §10.2 requires "no mode selected"
    // to be a reachable state (that's what collapses graphHost back to zero
    // width), which a single-selection ToggleGroup doesn't normally allow.
    // toggleGraphMode enforces the exclusivity a ToggleGroup would
    // otherwise have given us for free.
    private final ToggleButton[] graphModeButtons;
    private final ToggleButton bifurcationToggle;

    private final ProgressBar bifurcationProgressBar;
    private final Button bifurcationButton;

    private Runnable onGenerateBifurcation;

    // Fired true when a graph mode becomes selected, false when the active
    // one is clicked again (deselected) — see controller.SimulationController's
    // graphHost width animation (§10 of the UI overhaul spec).
    private Consumer<Boolean> onGraphVisibilityChange;

    public GraphsGroupPanel(PendulumGraphPanel graphPanel) {
        super(10);

        Label lGraph = SidebarControlFactory.sectionLabel("Graph Mode");

        ToggleButton tbAngle       = SidebarControlFactory.graphModeButton("θ₁(t) — Angle");
        ToggleButton tbEnergy      = SidebarControlFactory.graphModeButton("E(t)  — Energy");
        ToggleButton tbPhase       = SidebarControlFactory.graphModeButton("Phase Portrait (θ₁,ω₁)");
        ToggleButton tbAll         = SidebarControlFactory.graphModeButton("All (small multiples)");
        ToggleButton tbPoincare    = SidebarControlFactory.graphModeButton("Poincaré Section (θ₂,ω₂)");
        ToggleButton tbCompare     = SidebarControlFactory.graphModeButton("Integrator Comparison");
        ToggleButton tbBifurcation = SidebarControlFactory.graphModeButton("Bifurcation Map");
        this.graphModeButtons = new ToggleButton[]{tbAngle, tbEnergy, tbPhase, tbAll, tbPoincare, tbCompare, tbBifurcation};
        this.bifurcationToggle = tbBifurcation;

        tbAngle      .setOnAction(e -> toggleGraphMode(tbAngle, PendulumGraphPanel.Mode.ANGLE, graphPanel));
        tbEnergy     .setOnAction(e -> toggleGraphMode(tbEnergy, PendulumGraphPanel.Mode.ENERGY, graphPanel));
        tbPhase      .setOnAction(e -> toggleGraphMode(tbPhase, PendulumGraphPanel.Mode.PHASE, graphPanel));
        tbAll        .setOnAction(e -> toggleGraphMode(tbAll, PendulumGraphPanel.Mode.ALL, graphPanel));
        tbPoincare   .setOnAction(e -> toggleGraphMode(tbPoincare, PendulumGraphPanel.Mode.POINCARE, graphPanel));
        tbCompare    .setOnAction(e -> toggleGraphMode(tbCompare, PendulumGraphPanel.Mode.COMPARISON, graphPanel));
        tbBifurcation.setOnAction(e -> toggleGraphMode(tbBifurcation, PendulumGraphPanel.Mode.BIFURCATION, graphPanel));

        Button btnBifurcation = new Button("🌀  Generate Bifurcation Map");
        SidebarControlFactory.styleButton(btnBifurcation);
        ProgressBar bifurcationProgress = new ProgressBar(0);
        bifurcationProgress.setMaxWidth(Double.MAX_VALUE);
        bifurcationProgress.setVisible(false);
        bifurcationProgress.setManaged(false);
        Label bifurcationHint = SidebarControlFactory.hintLabel(
            "Sweeps the first link's initial angle and re-runs the sim many times — "
          + "can take up to a minute depending on N and this machine's speed.");
        btnBifurcation.setOnAction(e -> {
            if (onGenerateBifurcation != null) onGenerateBifurcation.run();
        });
        this.bifurcationProgressBar = bifurcationProgress;
        this.bifurcationButton = btnBifurcation;

        Button btnExportCsv = new Button("⬇  Export CSV");
        SidebarControlFactory.styleButton(btnExportCsv);
        Label exportHint = SidebarControlFactory.hintLabel("Exports the buffered angle/energy series shown in the graph.");
        btnExportCsv.setOnAction(e -> {
            if (!graphPanel.hasData()) {
                exportHint.setText("Nothing to export yet — let the simulation run first.");
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export Graph Data");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            chooser.setInitialFileName("dynamics-engine-export.csv");
            File file = chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
            if (file == null) return;
            try {
                graphPanel.exportCsv(file.toPath());
                exportHint.setText("Exported to " + file.getName());
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Failed to export graph data to " + file, ex);
                exportHint.setText("Export failed: " + ex.getMessage());
            }
        });

        getChildren().setAll(
            lGraph, tbAngle, tbEnergy, tbPhase, tbAll, tbPoincare, tbCompare, tbBifurcation,
            btnBifurcation, bifurcationProgress, bifurcationHint,
            btnExportCsv, exportHint
        );
    }

    /** Enforces "at most one graph mode selected, or none". */
    private void toggleGraphMode(ToggleButton button, PendulumGraphPanel.Mode mode, PendulumGraphPanel graphPanel) {
        if (button.isSelected()) {
            for (ToggleButton b : graphModeButtons) if (b != button) b.setSelected(false);
            graphPanel.setMode(mode);
            if (onGraphVisibilityChange != null) onGraphVisibilityChange.accept(true);
        } else {
            if (onGraphVisibilityChange != null) onGraphVisibilityChange.accept(false);
        }
    }

    /** Called when "Generate Bifurcation Map" is clicked. */
    public void setOnGenerateBifurcation(Runnable callback) { this.onGenerateBifurcation = callback; }

    /** Called with {@code true} once a graph mode is selected, {@code false} when deselected. See §10 of the UI overhaul spec. */
    public void setOnGraphVisibilityChange(Consumer<Boolean> callback) { this.onGraphVisibilityChange = callback; }

    /** Drives the progress bar while a background sweep runs — see {@code physics.BifurcationSweep}. */
    public void setBifurcationProgress(double fraction) {
        bifurcationProgressBar.setProgress(fraction);
    }

    /** Toggles the button/progress-bar between "idle" and "sweep in progress" — disables re-entrancy. */
    public void setBifurcationRunning(boolean running) {
        bifurcationButton.setDisable(running);
        bifurcationProgressBar.setVisible(running);
        bifurcationProgressBar.setManaged(running);
        if (!running) bifurcationProgressBar.setProgress(0);
    }

    /**
     * Selects the Bifurcation Map toggle once a sweep finishes (so the
     * result is immediately visible) and reveals the graph — mirrors what
     * a manual click on that toggle would do, since this path bypasses
     * {@link #toggleGraphMode} (the sweep finishes on a background task
     * callback, not a button click).
     */
    public void selectBifurcationMode() {
        for (ToggleButton b : graphModeButtons) b.setSelected(b == bifurcationToggle);
        if (onGraphVisibilityChange != null) onGraphVisibilityChange.accept(true);
    }
}
