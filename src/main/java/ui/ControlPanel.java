package ui;

import javafx.collections.FXCollections;
import physics.SimState;
import physics.integrator.IntegratorType;
import simulation.SimulationLoop;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builder for the simulation sidebar's controls — parameter sliders, the
 * graph-mode selector, playback controls, and live status.
 *
 * <p>This class no longer displays its own controls directly (it stopped
 * extending a shown container's role once the sidebar became a tabbed
 * shell — see {@link SidebarTabs}); it builds every control once in {@link
 * #build}, then exposes six grouped panels ({@link #getMotionGroup} through
 * {@link #getDisplayGroup}) plus an always-visible {@link #getStatusBlock},
 * for {@code SidebarTabs} to arrange. Splitting the build step from the
 * display step this way means every existing callback/field wiring below is
 * unchanged from the single-flat-list version; only the final assembly at
 * the bottom of {@link #build} changed.
 *
 * <p>Styled entirely through {@code theme.css}'s {@code .sidebar-*} classes
 * rather than inline {@code setStyle()} — being a plain-Java {@code VBox}
 * rather than FXML doesn't stop a style class from applying; CSS only cares
 * about a node's position in the scene graph.
 */
public final class ControlPanel extends VBox {

    private static final Logger LOG = Logger.getLogger(ControlPanel.class.getName());

    // Small enough to be imperceptible against typical swing speeds
    // (order 1-3 rad/s), large enough to seed a real chaotic divergence —
    // the same reasoning as the butterfly ensemble's epsilon, applied
    // directly to the primary instead of a separate copy.
    private static final double PERTURB_MAGNITUDE = 1.0e-6;

    // Live status labels — updated each frame by controller.SimulationController
    private final Label lblTime      = styledLabel("t  = ---");
    private final Label lblEnergy    = styledLabel("E  = ---");
    private final Label lblDrift     = styledLabel("Drift = ---");
    private final Label lblLyapunov  = styledLabel("λ  = ---");

    // Instability banner: the drift number above is easy to miss buried in a
    // sidebar, so once drift crosses a "this run is no longer trustworthy"
    // threshold (or a NaN/Infinity slips through — see updateStatus), this
    // surfaces the same fact as an impossible-to-miss banner instead.
    private static final double INSTABILITY_DRIFT_THRESHOLD_PERCENT = 5.0;
    private final Label lblInstabilityWarning = new Label(
            "⚠ Simulation is numerically unstable — try Reset or a smaller speed multiplier.");

    // Kept as a field so the render loop can move it (auto-tracking "now")
    // without disturbing an in-progress user drag — see setHistoryPositionLive.
    private final Slider historySlider = new Slider(0, 0, 0);
    private final Label historyLabel = historyValueLabel();

    // Kept as a field (rather than a build()-local) so it can be refreshed
    // after a structural rebuild changes N — see updateLinkCount().
    private final Label header = new Label();

    // Kept as a field so a keyboard shortcut (Space, wired in
    // controller.SimulationController) can keep this button's visual state
    // — selection and label text — in sync when it toggles pause/resume
    // through code rather than a direct click.
    private final ToggleButton btnPause = new ToggleButton("⏸  Pause");

    // Kept as a field so a structural rebuild (which invalidates any active
    // ensemble — see controller.SimulationController#applyStructuralEdit)
    // can reset this button's visual state without the caller needing to
    // know it's a ToggleButton.
    private final ToggleButton btnEnsemble = new ToggleButton("🦋  Butterfly Effect");

    // Kept as a field so controller.SimulationController#applyStructuralEdit
    // can reset this button's visual state when a structural edit drops an
    // active A/B compare — same reasoning as btnEnsemble above.
    private final ToggleButton btnCompare2 = new ToggleButton("🆚  A/B Compare");

    // Kept as a field so PendulumCanvas.cycleTrailMode()'s new state can be
    // reflected in this button's label after each click.
    private final Button btnTrailMode = new Button();

    // Kept as a field so controller.SimulationController#onHide can force
    // this off (and reflect that in the button) when leaving the screen —
    // audio.Sonifier stops there regardless of what this button shows, so
    // leaving it visually "on" would be a lie.
    private final ToggleButton btnSonify = new ToggleButton("🔊  Sonify: Off");

    // Assigned during build() (they're constructed alongside the rest of
    // the Graph Mode section) but read/written afterward by
    // controller.SimulationController while a bifurcation sweep runs on a
    // background Task — see setBifurcationProgress/setBifurcationRunning.
    private ProgressBar bifurcationProgressBar;
    private Button bifurcationButton;

    // Graph-mode picker — independent ToggleButtons (not a ToggleGroup) so
    // clicking the already-active one can deselect it: §10.2 requires "no
    // mode selected" to be a reachable state (that's what collapses
    // graphHost back to zero width), which a single-selection ToggleGroup
    // doesn't normally allow. toggleGraphMode enforces the exclusivity a
    // ToggleGroup would otherwise have given us for free.
    private ToggleButton[] graphModeButtons;
    private ToggleButton bifurcationToggle;

    // Groups assembled in build(); see the class javadoc.
    private VBox statusBlock;
    private VBox motionGroup;
    private VBox chaosGroup;
    private VBox graphsGroup;
    private VBox historyGroup;
    private VBox displayGroup;

    // Callbacks wired in controller.SimulationController
    private Runnable onReset;
    private Consumer<Boolean> onEnsembleToggle;
    private Consumer<Boolean> onSonifyToggle;
    private Runnable onGenerateBifurcation;
    private Runnable onResetGravityDirection;
    private BiConsumer<Boolean, Double> onCompareToggle;
    private Consumer<IntegratorType> onIntegratorChange;
    private Runnable onCompareIntegrators;
    private Runnable onScrubStart;
    private Consumer<Integer> onScrubTo;
    private Runnable onScrubEnd;

    // Fired true when a graph mode becomes selected, false when the active
    // one is clicked again (deselected) — see controller.SimulationController's
    // graphHost width animation (§10 of the UI overhaul spec).
    private Consumer<Boolean> onGraphVisibilityChange;

    // Fired with the new paused state whenever the Pause button is clicked
    // — lets controller.SimulationController clear a bob selection on
    // resume (§7.1: "resuming always clears the current selection")
    // without this class needing to know selection exists at all.
    private Consumer<Boolean> onPauseChange;

    public ControlPanel() {
        super(10);

        lblInstabilityWarning.getStyleClass().add("sidebar-warning-banner");
        lblInstabilityWarning.setWrapText(true);
        lblInstabilityWarning.setVisible(false);
        lblInstabilityWarning.setManaged(false);
    }

    // -------------------------------------------------------------------------
    // Builder — called once from controller.SimulationController after wiring callbacks
    // -------------------------------------------------------------------------

    /**
     * Constructs all child controls and assembles them into the grouped
     * panels retrievable via {@link #getMotionGroup} etc. Call after
     * setting all callbacks.
     */
    public void build(SimulationLoop simLoop,
                      GraphPanel graphPanel,
                      PendulumCanvas pendulumCanvas,
                      int n) {

        // ---- Header ----
        header.setText("N-Pendulum  [N=" + n + "]");
        header.getStyleClass().add("sidebar-header");
        header.setPadding(new Insets(0, 0, 6, 0));

        // ---- Gravity slider ----
        Label lGrav = sectionLabel("Gravity  (m/s²)");
        Slider sGrav = slider(0.5, 30.0, 9.81);
        TextField tfGrav = numericField(9.81);
        sGrav.setAccessibleText("Gravity in meters per second squared, from 0.5 to 30");
        tfGrav.setAccessibleText("Gravity value in meters per second squared");
        lGrav.setLabelFor(sGrav);
        Label gravityDirHint = hintLabel("Drag the small \"g\" handle near the pivot to tilt which way gravity pulls.");
        Button btnResetGravityDir = new Button("↺  Reset Gravity Direction");
        styleButton(btnResetGravityDir);
        btnResetGravityDir.setOnAction(e -> { if (onResetGravityDirection != null) onResetGravityDirection.run(); });
        sGrav.valueProperty().addListener((o, ov, nv) -> {
            simLoop.setGravity(nv.doubleValue());
            tfGrav.setText(String.format("%.2f", nv.doubleValue()));
        });
        wireNumericFieldToSlider(tfGrav, sGrav);

        // ---- Speed multiplier ----
        Label lSpeed = sectionLabel("Sim Speed");
        Slider sSpeed = slider(0.05, 8.0, 1.0);
        TextField tfSpeed = numericField(1.0);
        sSpeed.setAccessibleText("Simulation speed multiplier, from 0.05 to 8 times real time");
        tfSpeed.setAccessibleText("Simulation speed multiplier value");
        lSpeed.setLabelFor(sSpeed);
        sSpeed.valueProperty().addListener((o, ov, nv) -> {
            simLoop.setSpeedMultiplier(nv.doubleValue());
            tfSpeed.setText(String.format("%.2f", nv.doubleValue()));
        });
        wireNumericFieldToSlider(tfSpeed, sSpeed);

        // ---- Playback buttons ----
        styleButton(btnPause);
        btnPause.setOnAction(e -> {
            boolean p = btnPause.isSelected();
            simLoop.setPaused(p);
            btnPause.setText(p ? "▶  Resume" : "⏸  Pause");
            if (onPauseChange != null) onPauseChange.accept(p);
        });

        Button btnStep = new Button("⏭  Step");
        styleButton(btnStep);
        btnStep.setOnAction(e -> simLoop.stepOnce());

        Button btnReset = new Button("↺  Reset");
        styleButton(btnReset);

        // Reset scope: by default a reset wipes trails/graphs/history too
        // (the old, only behavior) — but that throws away a Poincaré map or
        // integrator-comparison chart you were mid-way through building just
        // to re-center the pendulum. Unchecking this keeps that visual
        // history and only resets the physics itself.
        CheckBox cbResetClearsGraphs = new CheckBox("Clear graphs too");
        cbResetClearsGraphs.setSelected(true);
        cbResetClearsGraphs.getStyleClass().add("sidebar-checkbox");

        // ---- Time reversal ----
        ToggleButton btnReverse = new ToggleButton("⏪  Reverse Time: Off");
        styleButton(btnReverse);
        btnReverse.setOnAction(e -> {
            boolean on = btnReverse.isSelected();
            simLoop.setTimeReversed(on);
            btnReverse.setText(on ? "⏪  Reverse Time: On" : "⏪  Reverse Time: Off");
        });
        Label reverseHint = hintLabel(
            "Genuinely integrates backward (not a replay) — watch a single pendulum "
          + "retrace itself almost exactly, and a chaotic chain fail to.");

        btnReset.setOnAction(e -> {
            simLoop.reset();
            if (cbResetClearsGraphs.isSelected()) {
                pendulumCanvas.clearTrail();
                graphPanel.clear();
            }
            // A reset physics state running backward is a confusing
            // combination — a reset always means "start over, forward."
            if (btnReverse.isSelected()) {
                btnReverse.setSelected(false);
                simLoop.setTimeReversed(false);
                btnReverse.setText("⏪  Reverse Time: Off");
            }
            if (onReset != null) onReset.run();
        });

        HBox playRow = new HBox(6, btnPause, btnStep, btnReset);
        playRow.setAlignment(Pos.CENTER_LEFT);

        // ---- Integrator ----
        Label lIntegrator = sectionLabel("Integrator");
        ComboBox<IntegratorType> integratorBox =
                new ComboBox<>(FXCollections.observableArrayList(IntegratorType.values()));
        integratorBox.setValue(IntegratorType.RK4);
        integratorBox.setMaxWidth(Double.MAX_VALUE);
        integratorBox.setAccessibleText("Numerical integrator used to advance the simulation");
        lIntegrator.setLabelFor(integratorBox);
        integratorBox.setOnAction(e -> {
            if (onIntegratorChange != null) onIntegratorChange.accept(integratorBox.getValue());
        });

        Button btnCompare = new Button("🔬  Compare Integrators");
        styleButton(btnCompare);
        btnCompare.setOnAction(e -> { if (onCompareIntegrators != null) onCompareIntegrators.run(); });
        Label compareHint = hintLabel("Runs all three from right now, plots energy drift — see Graph Mode.");

        // ---- Time-travel scrubbing ----
        Label lHistory = sectionLabel("History");
        historySlider.setMaxWidth(Double.MAX_VALUE);
        historySlider.getStyleClass().add("sidebar-slider");
        historySlider.setAccessibleText("Scrub through the last 30 seconds of simulation history");
        lHistory.setLabelFor(historySlider);
        historySlider.setOnMousePressed(e -> { if (onScrubStart != null) onScrubStart.run(); });
        historySlider.setOnMouseReleased(e -> { if (onScrubEnd != null) onScrubEnd.run(); });
        historySlider.valueProperty().addListener((o, ov, nv) -> {
            if (onScrubTo != null) onScrubTo.accept(nv.intValue());
        });
        Label historyHint = hintLabel("Drag to replay the last ~30s. The simulation keeps running live underneath.");

        // ---- Trail mode ----
        styleButton(btnTrailMode);
        refreshTrailModeLabel(pendulumCanvas.getTrailMode());
        btnTrailMode.setOnAction(e -> {
            pendulumCanvas.cycleTrailMode();
            refreshTrailModeLabel(pendulumCanvas.getTrailMode());
        });

        ToggleButton btnVelocityTint = new ToggleButton("🌡  Velocity Tint: Off");
        styleButton(btnVelocityTint);
        btnVelocityTint.setSelected(pendulumCanvas.isVelocityTint());
        btnVelocityTint.setOnAction(e -> {
            boolean on = btnVelocityTint.isSelected();
            pendulumCanvas.setVelocityTint(on);
            btnVelocityTint.setText(on ? "🌡  Velocity Tint: On" : "🌡  Velocity Tint: Off");
        });
        Label velocityTintHint = hintLabel("Colours the trail by speed (blue=slow, red=fast) instead of by link.");

        Button btnExportTraceArt = new Button("🖼  Export Trace Art (PNG)");
        styleButton(btnExportTraceArt);
        Label traceArtHint = hintLabel("Saves the pendulum view exactly as shown — try it with a long trail on.");
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

        // ---- Butterfly-effect ensemble + perturb ----
        Label lEnsemble = sectionLabel("Chaos");
        styleButton(btnEnsemble);
        btnEnsemble.setOnAction(e -> {
            if (onEnsembleToggle != null) onEnsembleToggle.accept(btnEnsemble.isSelected());
        });
        Label ensembleHint = hintLabel("Spawns 50 near-identical copies from right now — watch them fan apart.");

        Button btnPerturb = new Button("⚡  Perturb");
        styleButton(btnPerturb);
        btnPerturb.setOnAction(e -> simLoop.perturb(PERTURB_MAGNITUDE));
        Label perturbHint = hintLabel("Nudges every link's velocity by a microscopic random amount.");

        styleButton(btnSonify);
        btnSonify.setOnAction(e -> {
            boolean on = btnSonify.isSelected();
            if (onSonifyToggle != null) onSonifyToggle.accept(on);
            btnSonify.setText(on ? "🔊  Sonify: On" : "🔊  Sonify: Off");
        });
        Label sonifyHint = hintLabel("Tip bob's speed as a live tone — hear two nearly-identical runs drift apart.");

        // ---- A/B compare ----
        Label lCompare = sectionLabel("A/B Compare");
        Slider sCompareOffset = slider(-1.0, 1.0, 0.1);
        sCompareOffset.setAccessibleText("B's initial angle offset from A, in radians, from -1 to 1");
        Label compareOffsetLabel = hintLabel("Δθ₁ = 0.10 rad");
        sCompareOffset.valueProperty().addListener((o, ov, nv) ->
                compareOffsetLabel.setText(String.format("Δθ₁ = %.2f rad", nv.doubleValue())));
        styleButton(btnCompare2);
        btnCompare2.setOnAction(e -> {
            boolean on = btnCompare2.isSelected();
            if (onCompareToggle != null) onCompareToggle.accept(on, sCompareOffset.getValue());
            setCompareVisual(on);
        });
        Label compareHint2 = hintLabel(
            "Spawns one deliberately different \"B\" chain from right now, offset by Δθ₁ — "
          + "unlike Butterfly Effect's 50 near-identical copies, this is one clear side-by-side comparison.");

        // ---- Graph mode ----
        Label lGraph = sectionLabel("Graph Mode");

        ToggleButton tbAngle       = graphModeButton("θ₁(t) — Angle");
        ToggleButton tbEnergy      = graphModeButton("E(t)  — Energy");
        ToggleButton tbPhase       = graphModeButton("Phase Portrait (θ₁,ω₁)");
        ToggleButton tbAll         = graphModeButton("All (small multiples)");
        ToggleButton tbPoincare    = graphModeButton("Poincaré Section (θ₂,ω₂)");
        ToggleButton tbCompare     = graphModeButton("Integrator Comparison");
        ToggleButton tbBifurcation = graphModeButton("Bifurcation Map");
        this.graphModeButtons = new ToggleButton[]{tbAngle, tbEnergy, tbPhase, tbAll, tbPoincare, tbCompare, tbBifurcation};
        this.bifurcationToggle = tbBifurcation;

        tbAngle      .setOnAction(e -> toggleGraphMode(tbAngle, GraphPanel.Mode.ANGLE, graphPanel));
        tbEnergy     .setOnAction(e -> toggleGraphMode(tbEnergy, GraphPanel.Mode.ENERGY, graphPanel));
        tbPhase      .setOnAction(e -> toggleGraphMode(tbPhase, GraphPanel.Mode.PHASE, graphPanel));
        tbAll        .setOnAction(e -> toggleGraphMode(tbAll, GraphPanel.Mode.ALL, graphPanel));
        tbPoincare   .setOnAction(e -> toggleGraphMode(tbPoincare, GraphPanel.Mode.POINCARE, graphPanel));
        tbCompare    .setOnAction(e -> toggleGraphMode(tbCompare, GraphPanel.Mode.COMPARISON, graphPanel));
        tbBifurcation.setOnAction(e -> toggleGraphMode(tbBifurcation, GraphPanel.Mode.BIFURCATION, graphPanel));

        Button btnBifurcation = new Button("🌀  Generate Bifurcation Map");
        styleButton(btnBifurcation);
        ProgressBar bifurcationProgress = new ProgressBar(0);
        bifurcationProgress.setMaxWidth(Double.MAX_VALUE);
        bifurcationProgress.setVisible(false);
        bifurcationProgress.setManaged(false);
        Label bifurcationHint = hintLabel(
            "Sweeps the first link's initial angle and re-runs the sim many times — "
          + "can take up to a minute depending on N and this machine's speed.");
        btnBifurcation.setOnAction(e -> {
            if (onGenerateBifurcation != null) onGenerateBifurcation.run();
        });
        this.bifurcationProgressBar = bifurcationProgress;
        this.bifurcationButton = btnBifurcation;

        Button btnExportCsv = new Button("⬇  Export CSV");
        styleButton(btnExportCsv);
        Label exportHint = hintLabel("Exports the buffered angle/energy series shown in the graph.");
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

        // ---- Status display ----
        Label lStatus = sectionLabel("Live Status");
        VBox statusBox = new VBox(4, lblTime, lblEnergy, lblDrift, lblLyapunov);
        statusBox.getStyleClass().add("sidebar-status-box");
        statusBox.setPadding(new Insets(6, 8, 6, 8));

        // ---- Hints ----
        Label hint = hintLabel("dt = 2 ms · Space: pause · R: reset · →: step");

        // ---- Assemble into groups (see the class javadoc) ----
        statusBlock = new VBox(8, header, lblInstabilityWarning, lStatus, statusBox, hint);

        motionGroup = new VBox(10,
            lGrav,  hRow(sGrav, tfGrav), gravityDirHint, btnResetGravityDir,
            sep(), lSpeed, hRow(sSpeed, tfSpeed),
            sep(), playRow, cbResetClearsGraphs,
            sep(), btnReverse, reverseHint,
            sep(), lIntegrator, integratorBox, btnCompare, compareHint
        );

        chaosGroup = new VBox(10,
            lEnsemble, btnEnsemble, ensembleHint, btnPerturb, perturbHint, btnSonify, sonifyHint,
            sep(), lCompare, hRow(sCompareOffset, compareOffsetLabel), btnCompare2, compareHint2
        );

        graphsGroup = new VBox(10,
            lGraph, tbAngle, tbEnergy, tbPhase, tbAll, tbPoincare, tbCompare, tbBifurcation,
            btnBifurcation, bifurcationProgress, bifurcationHint,
            btnExportCsv, exportHint
        );

        historyGroup = new VBox(10,
            lHistory, hRow(historySlider, historyLabel), historyHint
        );

        displayGroup = new VBox(10,
            btnTrailMode, btnVelocityTint, velocityTintHint, btnExportTraceArt, traceArtHint
        );
    }

    /** Enforces "at most one graph mode selected, or none" — see the class javadoc on {@link #graphModeButtons}. */
    private void toggleGraphMode(ToggleButton button, GraphPanel.Mode mode, GraphPanel graphPanel) {
        if (button.isSelected()) {
            for (ToggleButton b : graphModeButtons) if (b != button) b.setSelected(false);
            graphPanel.setMode(mode);
            if (onGraphVisibilityChange != null) onGraphVisibilityChange.accept(true);
        } else {
            if (onGraphVisibilityChange != null) onGraphVisibilityChange.accept(false);
        }
    }

    // ---- Group/status accessors — see the class javadoc ----

    public VBox getStatusBlock()  { return statusBlock; }
    public VBox getMotionGroup()  { return motionGroup; }
    public VBox getChaosGroup()   { return chaosGroup; }
    public VBox getGraphsGroup()  { return graphsGroup; }
    public VBox getHistoryGroup() { return historyGroup; }
    public VBox getDisplayGroup() { return displayGroup; }

    // ---- Called from controller.SimulationController every render tick ----

    public void setOnResetCallback(Runnable r) { this.onReset = r; }

    /** Called with {@code true}/{@code false} when the butterfly-effect toggle is clicked. */
    public void setOnEnsembleToggle(Consumer<Boolean> callback) { this.onEnsembleToggle = callback; }
    public void setOnSonifyToggle(Consumer<Boolean> callback)   { this.onSonifyToggle = callback; }
    public void setOnGenerateBifurcation(Runnable callback)     { this.onGenerateBifurcation = callback; }
    public void setOnResetGravityDirection(Runnable callback)   { this.onResetGravityDirection = callback; }
    public void setOnCompareToggle(BiConsumer<Boolean, Double> callback) { this.onCompareToggle = callback; }

    /** Called with {@code true} once a graph mode is selected, {@code false} when deselected. See §10 of the UI overhaul spec. */
    public void setOnGraphVisibilityChange(Consumer<Boolean> callback) { this.onGraphVisibilityChange = callback; }

    /** Called with the new paused state whenever the Pause button is clicked. See §7.1 of the UI overhaul spec. */
    public void setOnPauseChange(Consumer<Boolean> callback) { this.onPauseChange = callback; }

    /** Resets the A/B compare button's visual state — used when a structural edit drops an active compare (see controller.SimulationController#applyStructuralEdit). */
    public void setCompareVisual(boolean active) {
        btnCompare2.setSelected(active);
        btnCompare2.setText(active ? "🆚  A/B Compare: On" : "🆚  A/B Compare");
    }

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

    /** Called with the newly selected type whenever the integrator picker changes. */
    public void setOnIntegratorChange(Consumer<IntegratorType> callback) { this.onIntegratorChange = callback; }

    /** Called when "Compare Integrators" is clicked. */
    public void setOnCompareIntegrators(Runnable callback) { this.onCompareIntegrators = callback; }

    /** Called when the user presses down on the history slider — the start of a scrub gesture. */
    public void setOnScrubStart(Runnable callback) { this.onScrubStart = callback; }

    /** Called with the target history index on every value change while scrubbing. */
    public void setOnScrubTo(Consumer<Integer> callback) { this.onScrubTo = callback; }

    /** Called when the user releases the history slider — the end of a scrub gesture. */
    public void setOnScrubEnd(Runnable callback) { this.onScrubEnd = callback; }

    /** Keeps the slider's range in sync as history accumulates. Safe to call every frame, including mid-drag. */
    public void updateHistoryRange(int maxIndex) {
        historySlider.setMax(Math.max(0, maxIndex));
    }

    /** Moves the slider to track "now" — only call while the user isn't actively scrubbing, or this fights their drag. */
    public void setHistoryPositionLive(int index) {
        historySlider.setValue(index);
        historyLabel.setText("LIVE");
    }

    /** Updates only the label, deliberately leaving the slider's value alone so an in-progress drag isn't disturbed. */
    public void setHistoryPositionScrubbed(double secondsAgo) {
        historyLabel.setText(String.format("-%.1fs", secondsAgo));
    }

    public void updateLyapunov(Double lambda) {
        lblLyapunov.setText(lambda == null ? "λ  = ---" : String.format("λ  ≈ %+.3f /s", lambda));
    }

    /** Resets the ensemble toggle to off without firing the callback — used when a structural edit invalidates it. */
    public void setEnsembleVisual(boolean active) { btnEnsemble.setSelected(active); }

    /** Refreshes the "N=" header after a structural rebuild changes the link count. */
    public void updateLinkCount(int n) { header.setText("N-Pendulum  [N=" + n + "]"); }

    /** Keeps the pause button's visual state in sync when pause is toggled from outside (the Space shortcut). */
    public void setPausedVisual(boolean paused) {
        btnPause.setSelected(paused);
        btnPause.setText(paused ? "▶  Resume" : "⏸  Pause");
    }

    /** Keeps the sonify button's visual state in sync when it's force-stopped from outside (leaving the screen). */
    public void setSonifyVisual(boolean on) {
        btnSonify.setSelected(on);
        btnSonify.setText(on ? "🔊  Sonify: On" : "🔊  Sonify: Off");
    }

    public void updateStatus(SimState state, Double initialEnergy) {
        if (state == null) return;
        lblTime.setText(String.format("t  = %8.2f s", state.time));
        lblEnergy.setText(String.format("E  = %8.3f J", state.totalEnergy));

        boolean unstable = !Double.isFinite(state.totalEnergy);
        for (double v : state.angularVelocities) unstable |= !Double.isFinite(v);

        if (initialEnergy != null && Math.abs(initialEnergy) > 1e-6) {
            double drift = Math.abs(state.totalEnergy - initialEnergy)
                         / Math.abs(initialEnergy) * 100.0;
            String driftStr = drift < 0.001
                ? "Drift = < 0.001 %"
                : String.format("Drift = %.4f %%", drift);
            // Colour-coded by threshold, not theme — kept as direct Color
            // values since the meaning (good/warning/bad) is independent of
            // light/dark, unlike everything styled through theme.css above.
            // Matches theme.css's -success/-danger tokens exactly (green/red);
            // the middle "warning" amber is semantic, kept deliberately out
            // of the magenta accent's hue family so it never reads as "active."
            lblDrift.setText(driftStr);
            lblDrift.setTextFill(drift < 0.1 ? Color.web("#3DD68C")
                               : drift < 1.0 ? Color.web("#E8A33D")
                                             : Color.web("#E5484D"));
            unstable |= drift >= INSTABILITY_DRIFT_THRESHOLD_PERCENT;
        }

        lblInstabilityWarning.setVisible(unstable);
        lblInstabilityWarning.setManaged(unstable);
    }

    private void refreshTrailModeLabel(PendulumCanvas.TrailMode mode) {
        btnTrailMode.setText(switch (mode) {
            case OFF       -> "〜  Trail: Off";
            case TIP_ONLY  -> "〜  Trail: Tip Only";
            case ALL_LINKS -> "〜  Trail: All Links";
        });
    }

    // -------------------------------------------------------------------------
    // Small factory helpers
    // -------------------------------------------------------------------------

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        l.setPadding(new Insets(4, 0, 0, 0));
        return l;
    }

    private static Label styledLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-status-label");
        return l;
    }

    private static Label historyValueLabel() {
        Label l = styledLabel("LIVE");
        l.setMinWidth(50);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
    }

    private static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-hint");
        l.setWrapText(true);
        return l;
    }

    private static Slider slider(double min, double max, double val) {
        Slider s = new Slider(min, max, val);
        s.setMaxWidth(Double.MAX_VALUE);
        s.getStyleClass().add("sidebar-slider");
        return s;
    }

    private static TextField numericField(double initial) {
        TextField f = new TextField(String.format("%.2f", initial));
        f.getStyleClass().add("sidebar-numeric-field");
        f.setPrefWidth(52);
        f.setAlignment(Pos.CENTER_RIGHT);
        return f;
    }

    /** Enter commits a valid number (clamped to the slider's range); anything invalid reverts to the slider's current value. */
    private static void wireNumericFieldToSlider(TextField field, Slider slider) {
        field.setOnAction(e -> {
            try {
                double value = Double.parseDouble(field.getText().trim());
                slider.setValue(Math.max(slider.getMin(), Math.min(slider.getMax(), value)));
            } catch (NumberFormatException ex) {
                field.setText(String.format("%.2f", slider.getValue()));
            }
        });
    }

    private static HBox hRow(Slider slider, Node value) {
        HBox box = new HBox(6, slider, value);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    private static void styleButton(ButtonBase b) {
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("sidebar-button");
    }

    private static ToggleButton graphModeButton(String text) {
        ToggleButton b = new ToggleButton(text);
        styleButton(b);
        return b;
    }

    private static Separator sep() {
        Separator s = new Separator();
        s.getStyleClass().add("sidebar-separator");
        return s;
    }
}
