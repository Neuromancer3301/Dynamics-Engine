package ui;

import physics.PendulumConfig;
import physics.SimState;
import simulation.SimulationLoop;
import simulation.StateBuffer;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

/**
 * Top-level JavaFX window.
 *
 * Layout (left-to-right):
 *   [ PendulumCanvas ] [ GraphPanel ] [ ControlPanel ]
 *
 * Architecture:
 *   - Physics runs on a dedicated daemon thread (SimulationLoop)
 *   - JavaFX AnimationTimer fires ~60fps on the Application Thread
 *   - State is exchanged via AtomicReference<SimState> (lock-free)
 *   - Graph receives data at ~30fps (every other AnimationTimer tick)
 */
public final class MainWindow {

    // ---- Canvas dimensions ----
    private static final double PENDULUM_W = 500;
    private static final double PENDULUM_H = 580;
    private static final double GRAPH_W    = 490;
    private static final double GRAPH_H    = 580;

    // ---- State ----
    private SimulationLoop simLoop;
    private StateBuffer    stateBuffer;
    private Double         initialEnergy  = null;
    private long           graphFrameCount = 0;

    private ControlPanel   controlPanel;

    public void show(Stage stage) {
        PendulumConfig config = PendulumConfig.defaultConfig();
        stateBuffer = new StateBuffer();
        simLoop     = new SimulationLoop(config, stateBuffer);

        // ---- Build canvases ----
        PendulumCanvas pendulumCanvas =
                new PendulumCanvas(PENDULUM_W, PENDULUM_H, config.getTotalLength());
        GraphPanel graphPanel = new GraphPanel(GRAPH_W, GRAPH_H);

        controlPanel = new ControlPanel();
        controlPanel.setOnResetCallback(() -> initialEnergy = null);
        controlPanel.build(simLoop, graphPanel, pendulumCanvas, config.getN());

        // ---- Layout ----
        HBox root = new HBox(0);
        root.setStyle("-fx-background-color: #0D0D1F;");

        StackPane pendulumPane = wrappedPane(pendulumCanvas);
        StackPane graphPane    = wrappedPane(graphPanel);

        HBox.setHgrow(pendulumPane, Priority.ALWAYS);
        HBox.setHgrow(graphPane,    Priority.ALWAYS);

        root.getChildren().addAll(pendulumPane, graphPane, controlPanel);

        BorderPane outerLayout = new BorderPane(root);
        outerLayout.setTop(buildTitleBar(config.getN()));
        outerLayout.setStyle("-fx-background-color: #0D0D1F;");

        // ---- Scene ----
        Scene scene = new Scene(outerLayout,
                PENDULUM_W + GRAPH_W + controlPanel.getMaxWidth() + 6,
                PENDULUM_H + 44);
        scene.setFill(Color.web("#0D0D1F"));

        stage.setTitle("N-Pendulum Chain Simulation — N=" + config.getN());
        stage.setScene(scene);
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> simLoop.stop());
        stage.show();

        // ---- Start physics thread ----
        simLoop.start();

        // ---- Render loop (JavaFX Application Thread, ~60fps) ----
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                SimState state = stateBuffer.read();

                // Capture initial energy once on first non-null state
                if (state != null && initialEnergy == null) {
                    initialEnergy = state.totalEnergy;
                }

                // Render pendulum every frame (~60fps)
                pendulumCanvas.render(state);

                // Feed graph and re-render at ~30fps (every 2nd frame)
                if (graphFrameCount % 2 == 0) {
                    graphPanel.addDataPoint(state);
                    graphPanel.render();
                }
                graphFrameCount++;

                // Update control-panel status at ~15fps (every 4th frame)
                if (graphFrameCount % 4 == 0) {
                    controlPanel.updateStatus(state, initialEnergy);
                }
            }
        };
        timer.start();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private StackPane wrappedPane(javafx.scene.canvas.Canvas canvas) {
        StackPane pane = new StackPane(canvas);
        pane.setStyle("-fx-background-color: #0D0D1F;"
                    + "-fx-border-color: #20204A; -fx-border-width: 0 1 0 0;");
        return pane;
    }

    private HBox buildTitleBar(int n) {
        Label title = new Label(
                "  ⚛  N-Pendulum Chain Simulator   ·   N = " + n
                + "   ·   RK4 / Lagrangian Mechanics");
        title.setFont(Font.font("System", FontWeight.BOLD, 12));
        title.setTextFill(Color.web("#7777BB"));

        HBox bar = new HBox(title);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setStyle("-fx-background-color: #09091A;"
                   + "-fx-border-color: #20204A; -fx-border-width: 0 0 1 0;");
        return bar;
    }
}
