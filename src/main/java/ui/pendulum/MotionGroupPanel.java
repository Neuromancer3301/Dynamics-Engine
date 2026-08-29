package ui.pendulum;

import javafx.collections.FXCollections;
import physics.PhysicsEngine;
import physics.SimState;
import physics.integrator.IntegratorType;
import simulation.SimulationLoop;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;

import java.util.function.Consumer;

/**
 * The sidebar's "Motion" group: gravity, sim speed, playback (pause/step/
 * reset), time reversal, and the integrator picker. Moved out of {@code
 * ControlPanel} — see round 1 §10 of the UI restructuring plan.
 */
public final class MotionGroupPanel extends VBox {

    // Kept as a field so a keyboard shortcut (Space, wired in
    // controller.SimulationController) can keep this button's visual state
    // in sync when it toggles pause/resume through code rather than a
    // direct click.
    private final ToggleButton btnPause = new ToggleButton("⏸  Pause");

    private Runnable onResetGravityDirection;
    private Runnable onReset;
    private Consumer<IntegratorType> onIntegratorChange;
    private Runnable onCompareIntegrators;
    private Consumer<Boolean> onPauseChange;

    public MotionGroupPanel(SimulationLoop<PhysicsEngine, SimState> simLoop, PendulumGraphPanel graphPanel, PendulumCanvas pendulumCanvas) {
        super(10);

        // ---- Gravity slider ----
        Label lGrav = SidebarControlFactory.sectionLabel("Gravity  (m/s²)");
        Slider sGrav = SidebarControlFactory.slider(0.5, 30.0, 9.81);
        TextField tfGrav = SidebarControlFactory.numericField(9.81);
        sGrav.setAccessibleText("Gravity in meters per second squared, from 0.5 to 30");
        tfGrav.setAccessibleText("Gravity value in meters per second squared");
        lGrav.setLabelFor(sGrav);
        Label gravityDirHint = SidebarControlFactory.hintLabel("Drag the small \"g\" handle near the pivot to tilt which way gravity pulls.");
        Button btnResetGravityDir = new Button("↺  Reset Gravity Direction");
        SidebarControlFactory.styleButton(btnResetGravityDir);
        btnResetGravityDir.setOnAction(e -> { if (onResetGravityDirection != null) onResetGravityDirection.run(); });
        sGrav.valueProperty().addListener((o, ov, nv) -> {
            // Round 2 §2: setGravity moved off SimulationLoop (a pendulum-only
            // concept) — submit() is the generic replacement.
            simLoop.submit(e -> e.setGravity(nv.doubleValue()));
            tfGrav.setText(String.format("%.2f", nv.doubleValue()));
        });
        SidebarControlFactory.wireNumericFieldToSlider(tfGrav, sGrav);

        // ---- Speed multiplier ----
        Label lSpeed = SidebarControlFactory.sectionLabel("Sim Speed");
        Slider sSpeed = SidebarControlFactory.slider(0.05, 8.0, 1.0);
        TextField tfSpeed = SidebarControlFactory.numericField(1.0);
        sSpeed.setAccessibleText("Simulation speed multiplier, from 0.05 to 8 times real time");
        tfSpeed.setAccessibleText("Simulation speed multiplier value");
        lSpeed.setLabelFor(sSpeed);
        sSpeed.valueProperty().addListener((o, ov, nv) -> {
            simLoop.setSpeedMultiplier(nv.doubleValue());
            tfSpeed.setText(String.format("%.2f", nv.doubleValue()));
        });
        SidebarControlFactory.wireNumericFieldToSlider(tfSpeed, sSpeed);

        // ---- Playback buttons ----
        SidebarControlFactory.styleButton(btnPause);
        btnPause.setOnAction(e -> {
            boolean p = btnPause.isSelected();
            simLoop.setPaused(p);
            btnPause.setText(p ? "▶  Resume" : "⏸  Pause");
            if (onPauseChange != null) onPauseChange.accept(p);
        });

        Button btnStep = new Button("⏭  Step");
        SidebarControlFactory.styleButton(btnStep);
        btnStep.setOnAction(e -> simLoop.stepOnce());

        Button btnReset = new Button("↺  Reset");
        SidebarControlFactory.styleButton(btnReset);

        // Reset scope: by default a reset wipes trails/graphs/history too —
        // unchecking this keeps that visual history and only resets the
        // physics itself.
        CheckBox cbResetClearsGraphs = new CheckBox("Clear graphs too");
        cbResetClearsGraphs.setSelected(true);
        cbResetClearsGraphs.getStyleClass().add("sidebar-checkbox");

        // ---- Time reversal ----
        ToggleButton btnReverse = new ToggleButton("⏪  Reverse Time: Off");
        SidebarControlFactory.styleButton(btnReverse);
        btnReverse.setOnAction(e -> {
            boolean on = btnReverse.isSelected();
            simLoop.setTimeReversed(on);
            btnReverse.setText(on ? "⏪  Reverse Time: On" : "⏪  Reverse Time: Off");
        });
        Label reverseHint = SidebarControlFactory.hintLabel(
            "Genuinely integrates backward (not a replay) — watch a single pendulum "
          + "retrace itself almost exactly, and a chaotic chain fail to.");

        btnReset.setOnAction(e -> {
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
        Label lIntegrator = SidebarControlFactory.sectionLabel("Integrator");
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
        SidebarControlFactory.styleButton(btnCompare);
        btnCompare.setOnAction(e -> { if (onCompareIntegrators != null) onCompareIntegrators.run(); });
        Label compareHint = SidebarControlFactory.hintLabel("Runs all three from right now, plots energy drift — see Graph Mode.");

        getChildren().setAll(
            lGrav,  SidebarControlFactory.hRow(sGrav, tfGrav), gravityDirHint, btnResetGravityDir,
            SidebarControlFactory.sep(), lSpeed, SidebarControlFactory.hRow(sSpeed, tfSpeed),
            SidebarControlFactory.sep(), playRow, cbResetClearsGraphs,
            SidebarControlFactory.sep(), btnReverse, reverseHint,
            SidebarControlFactory.sep(), lIntegrator, integratorBox, btnCompare, compareHint
        );
    }

    public void setOnResetGravityDirection(Runnable callback) { this.onResetGravityDirection = callback; }
    public void setOnReset(Runnable callback) { this.onReset = callback; }
    public void setOnIntegratorChange(Consumer<IntegratorType> callback) { this.onIntegratorChange = callback; }
    public void setOnCompareIntegrators(Runnable callback) { this.onCompareIntegrators = callback; }

    /** Called with the new paused state whenever the Pause button is clicked. See §7.1 of the UI overhaul spec. */
    public void setOnPauseChange(Consumer<Boolean> callback) { this.onPauseChange = callback; }

    /** Keeps the pause button's visual state in sync when pause is toggled from outside (the Space shortcut). */
    public void setPausedVisual(boolean paused) {
        btnPause.setSelected(paused);
        btnPause.setText(paused ? "▶  Resume" : "⏸  Pause");
    }
}
