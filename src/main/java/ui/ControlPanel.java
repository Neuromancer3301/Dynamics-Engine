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

import java.util.function.Consumer;

/**
 * Control panel — left sidebar with parameter sliders, graph-mode selector,
 * playback controls, and live status display.
 *
 * <p>Styled entirely through {@code theme.css}'s {@code .sidebar-*} classes
 * rather than inline {@code setStyle()} — being a plain-Java {@code VBox}
 * rather than FXML doesn't stop a style class from applying; CSS only cares
 * about a node's position in the scene graph. This is also what makes the
 * sidebar follow the light/dark toggle: it's ordinary JavaFX controls, so
 * the same token system the FXML screens use reaches it for free.
 */
public final class ControlPanel extends VBox {

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

    // Kept as a field so PendulumCanvas.cycleTrailMode()'s new state can be
    // reflected in this button's label after each click.
    private final Button btnTrailMode = new Button();

    // Callbacks wired in controller.SimulationController
    private Runnable onReset;
    private Consumer<Boolean> onEnsembleToggle;
    private Consumer<IntegratorType> onIntegratorChange;
    private Runnable onCompareIntegrators;
    private Runnable onScrubStart;
    private Consumer<Integer> onScrubTo;
    private Runnable onScrubEnd;

    public ControlPanel() {
        super(10);
        setPadding(new Insets(14, 12, 14, 12));
        getStyleClass().add("sidebar-panel");
        setMinWidth(210);
        setMaxWidth(230);
    }

    // -------------------------------------------------------------------------
    // Builder — called once from controller.SimulationController after wiring callbacks
    // -------------------------------------------------------------------------

    /**
     * Constructs all child controls. Call after setting all callbacks.
     */
    public void build(SimulationLoop simLoop,
                      GraphPanel graphPanel,
                      PendulumCanvas pendulumCanvas,
                      int n) {

        getChildren().clear();

        // ---- Header ----
        header.setText("N-Pendulum  [N=" + n + "]");
        header.getStyleClass().add("sidebar-header");
        header.setPadding(new Insets(0, 0, 6, 0));

        // ---- Gravity slider ----
        Label lGrav = sectionLabel("Gravity  (m/s²)");
        Slider sGrav = slider(0.5, 30.0, 9.81);
        TextField tfGrav = numericField(9.81);
        sGrav.valueProperty().addListener((o, ov, nv) -> {
            simLoop.setGravity(nv.doubleValue());
            tfGrav.setText(String.format("%.2f", nv.doubleValue()));
        });
        wireNumericFieldToSlider(tfGrav, sGrav);

        // ---- Speed multiplier ----
        Label lSpeed = sectionLabel("Sim Speed");
        Slider sSpeed = slider(0.05, 8.0, 1.0);
        TextField tfSpeed = numericField(1.0);
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
        });

        Button btnStep = new Button("⏭  Step");
        styleButton(btnStep);
        btnStep.setOnAction(e -> simLoop.stepOnce());

        Button btnReset = new Button("↺  Reset");
        styleButton(btnReset);
        btnReset.setOnAction(e -> {
            simLoop.reset();
            pendulumCanvas.clearTrail();
            graphPanel.clear();
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

        // ---- Graph mode ----
        Label lGraph = sectionLabel("Graph Mode");
        ToggleGroup tgGraph = new ToggleGroup();

        RadioButton rbAngle    = radioBtn("θ₁(t) — Angle",              tgGraph);
        RadioButton rbEnergy   = radioBtn("E(t)  — Energy",              tgGraph);
        RadioButton rbPhase    = radioBtn("Phase Portrait (θ₁,ω₁)",      tgGraph);
        RadioButton rbAll      = radioBtn("All (small multiples)",       tgGraph);
        RadioButton rbPoincare = radioBtn("Poincaré Section (θ₂,ω₂)",    tgGraph);
        RadioButton rbCompare  = radioBtn("Integrator Comparison",       tgGraph);
        rbAngle.setSelected(true);

        rbAngle   .setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.ANGLE));
        rbEnergy  .setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.ENERGY));
        rbPhase   .setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.PHASE));
        rbAll     .setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.ALL));
        rbPoincare.setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.POINCARE));
        rbCompare .setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.COMPARISON));

        // ---- Status display ----
        Label lStatus = sectionLabel("Live Status");
        VBox statusBox = new VBox(4, lblTime, lblEnergy, lblDrift, lblLyapunov);
        statusBox.getStyleClass().add("sidebar-status-box");
        statusBox.setPadding(new Insets(6, 8, 6, 8));

        // ---- Hints ----
        Label hint = hintLabel("dt = 2 ms · Space: pause · R: reset · →: step");

        // ---- Assemble ----
        getChildren().addAll(
            header,
            sep(), lGrav,  hRow(sGrav, tfGrav),
            sep(), lSpeed, hRow(sSpeed, tfSpeed),
            sep(), playRow,
            sep(), lIntegrator, integratorBox, btnCompare, compareHint,
            sep(), lHistory, hRow(historySlider, historyLabel), historyHint,
            sep(), btnTrailMode,
            sep(), lEnsemble, btnEnsemble, ensembleHint, btnPerturb, perturbHint,
            sep(), lGraph, rbAngle, rbEnergy, rbPhase, rbAll, rbPoincare, rbCompare,
            sep(), lStatus, statusBox,
            sep(), hint
        );
    }

    // ---- Called from controller.SimulationController every render tick ----

    public void setOnResetCallback(Runnable r) { this.onReset = r; }

    /** Called with {@code true}/{@code false} when the butterfly-effect toggle is clicked. */
    public void setOnEnsembleToggle(Consumer<Boolean> callback) { this.onEnsembleToggle = callback; }

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

    public void updateStatus(SimState state, Double initialEnergy) {
        if (state == null) return;
        lblTime.setText(String.format("t  = %8.2f s", state.time));
        lblEnergy.setText(String.format("E  = %8.3f J", state.totalEnergy));

        if (initialEnergy != null && Math.abs(initialEnergy) > 1e-6) {
            double drift = Math.abs(state.totalEnergy - initialEnergy)
                         / Math.abs(initialEnergy) * 100.0;
            String driftStr = drift < 0.001
                ? "Drift = < 0.001 %"
                : String.format("Drift = %.4f %%", drift);
            // Colour-coded by threshold, not theme — kept as direct Color
            // values since the meaning (good/warning/bad) is independent of
            // light/dark, unlike everything styled through theme.css above.
            lblDrift.setText(driftStr);
            lblDrift.setTextFill(drift < 0.1 ? Color.web("#55EFC4")
                               : drift < 1.0 ? Color.web("#FDCB6E")
                                             : Color.web("#FF6B6B"));
        }
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

    private static RadioButton radioBtn(String text, ToggleGroup group) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.getStyleClass().add("sidebar-radio");
        return rb;
    }

    private static Separator sep() {
        Separator s = new Separator();
        s.getStyleClass().add("sidebar-separator");
        return s;
    }
}
