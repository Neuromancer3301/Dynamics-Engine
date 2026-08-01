package ui;

import physics.SimState;
import simulation.SimulationLoop;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Control panel — left sidebar with parameter sliders, graph-mode selector,
 * playback controls, and live status display.
 */
public final class ControlPanel extends VBox {

    // Live status labels — updated each frame by MainWindow
    private final Label lblTime    = styledLabel("t  = ---");
    private final Label lblEnergy  = styledLabel("E  = ---");
    private final Label lblDrift   = styledLabel("Drift = ---");

    // Callbacks wired in MainWindow
    private Runnable onReset;

    public ControlPanel() {
        super(10);
        setPadding(new Insets(14, 12, 14, 12));
        setStyle("-fx-background-color: #0E0E22;"
               + "-fx-border-color: #25255A; -fx-border-width: 0 0 0 1;");
        setMinWidth(210);
        setMaxWidth(230);
    }

    // -------------------------------------------------------------------------
    // Builder — called once from MainWindow after wiring callbacks
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
        Label header = new Label("N-Pendulum  [N=" + n + "]");
        header.setFont(Font.font("System", FontWeight.BOLD, 14));
        header.setTextFill(Color.web("#8888FF"));
        header.setPadding(new Insets(0, 0, 6, 0));

        // ---- Gravity slider ----
        Label lGrav = sectionLabel("Gravity  (m/s²)");
        Label vGrav = valueLabel("9.81");
        Slider sGrav = slider(0.5, 30.0, 9.81);
        sGrav.valueProperty().addListener((o, ov, nv) -> {
            simLoop.setGravity(nv.doubleValue());
            vGrav.setText(String.format("%.2f", nv.doubleValue()));
        });

        // ---- Speed multiplier ----
        Label lSpeed = sectionLabel("Sim Speed");
        Label vSpeed = valueLabel("1.0×");
        Slider sSpeed = slider(0.05, 8.0, 1.0);
        sSpeed.valueProperty().addListener((o, ov, nv) -> {
            simLoop.setSpeedMultiplier(nv.doubleValue());
            vSpeed.setText(String.format("%.2f×", nv.doubleValue()));
        });

        // ---- Playback buttons ----
        ToggleButton btnPause = new ToggleButton("⏸  Pause");
        styleButton(btnPause);
        btnPause.setOnAction(e -> {
            boolean p = btnPause.isSelected();
            simLoop.setPaused(p);
            btnPause.setText(p ? "▶  Resume" : "⏸  Pause");
        });

        Button btnReset = new Button("↺  Reset");
        styleButton(btnReset);
        btnReset.setOnAction(e -> {
            simLoop.reset();
            pendulumCanvas.clearTrail();
            graphPanel.clear();
            if (onReset != null) onReset.run();
        });

        HBox playRow = new HBox(6, btnPause, btnReset);
        playRow.setAlignment(Pos.CENTER_LEFT);

        // ---- Graph mode ----
        Label lGraph = sectionLabel("Graph Mode");
        ToggleGroup tgGraph = new ToggleGroup();

        RadioButton rbAngle  = radioBtn("θ₁(t) — Angle",         tgGraph);
        RadioButton rbEnergy = radioBtn("E(t)  — Energy",         tgGraph);
        RadioButton rbPhase  = radioBtn("Phase Portrait (θ₁,ω₁)", tgGraph);
        rbAngle.setSelected(true);

        rbAngle .setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.ANGLE));
        rbEnergy.setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.ENERGY));
        rbPhase .setOnAction(e -> graphPanel.setMode(GraphPanel.Mode.PHASE));

        // ---- Status display ----
        Label lStatus = sectionLabel("Live Status");
        VBox statusBox = new VBox(4, lblTime, lblEnergy, lblDrift);
        statusBox.setPadding(new Insets(6, 8, 6, 8));
        statusBox.setStyle("-fx-background-color: #080818;"
                         + "-fx-border-color: #25255A; -fx-border-width: 1;"
                         + "-fx-border-radius: 4; -fx-background-radius: 4;");

        // ---- Hints ----
        Label hint = new Label("RK4 integrator · dt = 2 ms");
        hint.setTextFill(Color.web("#444466"));
        hint.setFont(Font.font("Monospaced", 9));

        // ---- Assemble ----
        getChildren().addAll(
            header,
            sep(), lGrav,  hRow(sGrav, vGrav),
            sep(), lSpeed, hRow(sSpeed, vSpeed),
            sep(), playRow,
            sep(), lGraph, rbAngle, rbEnergy, rbPhase,
            sep(), lStatus, statusBox,
            sep(), hint
        );
    }

    // ---- Called from MainWindow every render tick ----

    public void setOnResetCallback(Runnable r) { this.onReset = r; }

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
            // Colour-code: green < 0.1%, yellow < 1%, red >= 1%
            lblDrift.setText(driftStr);
            lblDrift.setTextFill(drift < 0.1 ? Color.web("#55EFC4")
                               : drift < 1.0 ? Color.web("#FDCB6E")
                                             : Color.web("#FF6B6B"));
        }
    }

    // -------------------------------------------------------------------------
    // Small factory helpers
    // -------------------------------------------------------------------------

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("System", FontWeight.BOLD, 11));
        l.setTextFill(Color.web("#7777AA"));
        l.setPadding(new Insets(4, 0, 0, 0));
        return l;
    }

    private static Label valueLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("Monospaced", 11));
        l.setTextFill(Color.web("#AAAADD"));
        l.setMinWidth(48);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
    }

    private static Label styledLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("Monospaced", 11));
        l.setTextFill(Color.web("#CCCCEE"));
        return l;
    }

    private static Slider slider(double min, double max, double val) {
        Slider s = new Slider(min, max, val);
        s.setMaxWidth(Double.MAX_VALUE);
        s.setStyle("-fx-accent: #6C63FF;");
        return s;
    }

    private static HBox hRow(Slider slider, Label value) {
        HBox box = new HBox(6, slider, value);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    private static void styleButton(ButtonBase b) {
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("-fx-background-color: #22225A;"
                 + "-fx-text-fill: #BBBBEE;"
                 + "-fx-font-size: 11;"
                 + "-fx-border-color: #35357A; -fx-border-width: 1;"
                 + "-fx-border-radius: 4; -fx-background-radius: 4;");
    }

    private static RadioButton radioBtn(String text, ToggleGroup group) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.setTextFill(Color.web("#AAAACC"));
        rb.setFont(Font.font("System", 11));
        return rb;
    }

    private static Separator sep() {
        Separator s = new Separator();
        s.setStyle("-fx-background-color: #1E1E4A; -fx-padding: 2 0;");
        return s;
    }
}
