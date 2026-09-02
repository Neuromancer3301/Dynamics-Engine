package ui.nbody;

import javafx.collections.FXCollections;
import physics.nbody.NBodyConfig;
import physics.nbody.NBodyEngine;
import physics.nbody.NBodyState;
import physics.integrator.IntegratorType;
import simulation.SimulationLoop;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * The sidebar's "Motion" group: G, sim speed, playback (pause/step/reset),
 * and the integrator picker — direct parallel to {@code
 * ui.pendulum.MotionGroupPanel}. See the n-body implementation spec §8.
 *
 * <p><b>The speed slider is log-scale, not linear</b> — a deliberate
 * departure from the pendulum's plain linear slider, and a judgment call
 * flagged here because nothing in the grounding documents specifies it.
 * The pendulum's speed multiplier only ever needs to span roughly 0.05x to
 * 8x; this engine's own worked example (spec §3) needs roughly 1x up to
 * ~1.6 million x just to watch Earth complete one orbit in ~20 real
 * seconds. A linear slider across that range would put every practically
 * useful value within the first fraction of a percent of the track. The
 * slider's domain is the <em>exponent</em> (0 to 7, i.e. speeds from 1x to
 * 10,000,000x); the paired numeric field still shows/accepts the actual
 * multiplier, in scientific notation.
 */
public final class MotionGroupPanel extends VBox {

    private static final double SPEED_EXPONENT_MIN = 0.0;   // 10^0  = 1x (real time)
    private static final double SPEED_EXPONENT_MAX = 7.0;   // 10^7  = 10,000,000x

    // G's slider spans 0 to 2x the physical constant, so the realistic
    // value sits at the midpoint with usable resolution either side of it
    // — also a judgment call; NBodyConfig itself places no upper bound on G.
    private static final double G_SLIDER_MAX = 2.0 * NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

    private final ToggleButton btnPause = new ToggleButton("⏸  Pause");

    private Runnable onReset;
    private Consumer<IntegratorType> onIntegratorChange;
    private Consumer<Boolean> onPauseChange;

    public MotionGroupPanel(SimulationLoop<NBodyEngine, NBodyState> simLoop, double initialG, double initialSpeed) {
        super(10);

        // ---- Gravitational constant ----
        Label lGrav = sectionLabel("G  (m³·kg⁻¹·s⁻²)");
        Slider sGrav = slider(0.0, G_SLIDER_MAX, initialG);
        TextField tfGrav = numericField(initialG);
        sGrav.setAccessibleText("Gravitational constant, from 0 to twice the physical value");
        tfGrav.setAccessibleText("Gravitational constant value");
        lGrav.setLabelFor(sGrav);
        sGrav.valueProperty().addListener((o, ov, nv) -> {
            simLoop.submit(e -> e.setGravitationalConstant(nv.doubleValue()));
            tfGrav.setText(String.format("%.4e", nv.doubleValue()));
        });
        wireNumericFieldToSlider(tfGrav, sGrav, "%.4e");

        // ---- Speed multiplier (log-scale — see class javadoc) ----
        Label lSpeed = sectionLabel("Sim Speed (×)");
        double initialExponent = clamp(Math.log10(Math.max(initialSpeed, 1.0)), SPEED_EXPONENT_MIN, SPEED_EXPONENT_MAX);
        Slider sSpeed = slider(SPEED_EXPONENT_MIN, SPEED_EXPONENT_MAX, initialExponent);
        TextField tfSpeed = numericField(initialSpeed);
        sSpeed.setAccessibleText("Simulation speed multiplier, log scale from 1x to 10,000,000x real time");
        tfSpeed.setAccessibleText("Simulation speed multiplier value");
        lSpeed.setLabelFor(sSpeed);
        sSpeed.valueProperty().addListener((o, ov, nv) -> {
            double multiplier = Math.pow(10.0, nv.doubleValue());
            simLoop.setSpeedMultiplier(multiplier);
            tfSpeed.setText(String.format("%.3e", multiplier));
        });
        tfSpeed.setOnAction(e -> {
            try {
                double value = Double.parseDouble(tfSpeed.getText().trim());
                double exponent = clamp(Math.log10(Math.max(value, 1.0)), SPEED_EXPONENT_MIN, SPEED_EXPONENT_MAX);
                sSpeed.setValue(exponent);
            } catch (NumberFormatException ex) {
                tfSpeed.setText(String.format("%.3e", simLoop.getSpeedMultiplier()));
            }
        });
        Label speedHint = hintLabel("Log scale — orbital timescales span many orders of magnitude (spec §3).");

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
        btnReset.setOnAction(e -> { if (onReset != null) onReset.run(); });

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

        getChildren().setAll(
            lGrav, hRow(sGrav, tfGrav),
            sep(), lSpeed, hRow(sSpeed, tfSpeed), speedHint,
            sep(), playRow,
            sep(), lIntegrator, integratorBox
        );
    }

    public void setOnReset(Runnable callback) { this.onReset = callback; }
    public void setOnIntegratorChange(Consumer<IntegratorType> callback) { this.onIntegratorChange = callback; }

    /** Called with the new paused state whenever the Pause button is clicked. */
    public void setOnPauseChange(Consumer<Boolean> callback) { this.onPauseChange = callback; }

    /** Keeps the pause button's visual state in sync when pause is toggled from outside. */
    public void setPausedVisual(boolean paused) {
        btnPause.setSelected(paused);
        btnPause.setText(paused ? "▶  Resume" : "⏸  Pause");
    }

    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    // -------------------------------------------------------------------------
    // Small local styling helpers — duplicated rather than shared, matching
    // ui.pendulum.LinkEditorPanel's own precedent (it doesn't reuse
    // ui.pendulum.SidebarControlFactory either, since that class is
    // package-private to ui.pendulum).
    // -------------------------------------------------------------------------

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        l.setPadding(new Insets(4, 0, 0, 0));
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
        TextField f = new TextField(String.format("%.3e", initial));
        f.getStyleClass().add("sidebar-numeric-field");
        f.setPrefWidth(90);
        f.setAlignment(Pos.CENTER_RIGHT);
        return f;
    }

    private static void wireNumericFieldToSlider(TextField field, Slider slider, String format) {
        field.setOnAction(e -> {
            try {
                double value = Double.parseDouble(field.getText().trim());
                slider.setValue(Math.max(slider.getMin(), Math.min(slider.getMax(), value)));
            } catch (NumberFormatException ex) {
                field.setText(String.format(format, slider.getValue()));
            }
        });
    }

    private static HBox hRow(Slider slider, javafx.scene.Node value) {
        HBox box = new HBox(6, slider, value);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        return box;
    }

    private static void styleButton(javafx.scene.control.ButtonBase b) {
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("sidebar-button");
    }

    private static Separator sep() {
        Separator s = new Separator();
        s.getStyleClass().add("sidebar-separator");
        return s;
    }
}
