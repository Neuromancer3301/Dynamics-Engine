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
 * The sidebar's "Graphs" group: the eight graph-mode toggles (mutually
 * exclusive, or none selected — see {@link #toggleGraphMode}), the
 * bifurcation-sweep and basin-fractal-sweep buttons/progress bars, and CSV
 * export. Moved out of {@code ControlPanel} — see round 1 §10 of the UI
 * restructuring plan. Round 3: the basin-fractal button/progress bar/toggle
 * were ported from {@code feature/manual-and-bugfixes}'s {@code
 * ui.ControlPanel}, mirroring the bifurcation ones already here.
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
    private final ToggleButton fractalToggle;

    private final ProgressBar bifurcationProgressBar;
    private final Button bifurcationButton;
    private final ProgressBar fractalProgressBar;
    private final Button fractalButton;

    private Runnable onGenerateBifurcation;
    private Runnable onGenerateFractal;

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
        ToggleButton tbFractal     = SidebarControlFactory.graphModeButton("Basin Fractal");
        this.graphModeButtons = new ToggleButton[]{tbAngle, tbEnergy, tbPhase, tbAll, tbPoincare, tbCompare, tbBifurcation, tbFractal};
        this.bifurcationToggle = tbBifurcation;
        this.fractalToggle = tbFractal;

        tbAngle      .setOnAction(e -> toggleGraphMode(tbAngle, PendulumGraphPanel.Mode.ANGLE, graphPanel));
        tbEnergy     .setOnAction(e -> toggleGraphMode(tbEnergy, PendulumGraphPanel.Mode.ENERGY, graphPanel));
        tbPhase      .setOnAction(e -> toggleGraphMode(tbPhase, PendulumGraphPanel.Mode.PHASE, graphPanel));
        tbAll        .setOnAction(e -> toggleGraphMode(tbAll, PendulumGraphPanel.Mode.ALL, graphPanel));
        tbPoincare   .setOnAction(e -> toggleGraphMode(tbPoincare, PendulumGraphPanel.Mode.POINCARE, graphPanel));
        tbCompare    .setOnAction(e -> toggleGraphMode(tbCompare, PendulumGraphPanel.Mode.COMPARISON, graphPanel));
        tbBifurcation.setOnAction(e -> toggleGraphMode(tbBifurcation, PendulumGraphPanel.Mode.BIFURCATION, graphPanel));
        tbFractal    .setOnAction(e -> toggleGraphMode(tbFractal, PendulumGraphPanel.Mode.FRACTAL, graphPanel));

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

        Button btnFractal = new Button("❋  Generate Basin Fractal");
        SidebarControlFactory.styleButton(btnFractal);
        ProgressBar fractalProgress = new ProgressBar(0);
        fractalProgress.setMaxWidth(Double.MAX_VALUE);
        fractalProgress.setVisible(false);
        fractalProgress.setManaged(false);
        Label fractalHint = SidebarControlFactory.hintLabel(
            "Runs a 2-link chain from every pair of starting angles and colours each by "
          + "how fast it flips. Smooth areas never flip; the intricate boundary is the fractal. "
          + "~40,000 simulations, run across all CPU cores.");
        btnFractal.setOnAction(e -> {
            if (onGenerateFractal != null) onGenerateFractal.run();
        });
        this.fractalProgressBar = fractalProgress;
        this.fractalButton = btnFractal;

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
            lGraph, tbAngle, tbEnergy, tbPhase, tbAll, tbPoincare, tbCompare, tbBifurcation, tbFractal,
            btnBifurcation, bifurcationProgress, bifurcationHint,
            btnFractal, fractalProgress, fractalHint,
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

    /** Called when "Generate Basin Fractal" is clicked. */
    public void setOnGenerateFractal(Runnable callback) { this.onGenerateFractal = callback; }

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

    /** Drives the progress bar while a basin sweep runs — see {@code physics.FractalBasinSweep}. */
    public void setFractalProgress(double fraction) {
        fractalProgressBar.setProgress(fraction);
    }

    /** Toggles the fractal button/progress-bar between "idle" and "sweep in progress" — disables re-entrancy. */
    public void setFractalRunning(boolean running) {
        fractalButton.setDisable(running);
        fractalProgressBar.setVisible(running);
        fractalProgressBar.setManaged(running);
        if (!running) fractalProgressBar.setProgress(0);
    }

    /** Selects the Basin Fractal toggle once a sweep finishes — same reasoning as {@link #selectBifurcationMode}. */
    public void selectFractalMode() {
        for (ToggleButton b : graphModeButtons) b.setSelected(b == fractalToggle);
        if (onGraphVisibilityChange != null) onGraphVisibilityChange.accept(true);
    }
}
