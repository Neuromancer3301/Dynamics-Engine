package controller;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import navigation.Navigable;
import navigation.SceneRouter;
import simulation.boids.Boid;
import simulation.boids.Flock;
import ui.boids.BoidsCanvas;
import ui.icon.Icons;
import ui.simcore.LayoutShell;
import ui.simcore.SidebarTabs;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.ThreadLocalRandom;

public final class BoidsController implements Initializable, Navigable {

    @FXML
    private Button btnBack;
    @FXML
    private Label titleLabel;
    @FXML
    private StackPane canvasGraphStack;
    @FXML
    private StackPane canvasHost;
    @FXML
    private ScrollPane sidebarScroll;
    @FXML
    private VBox controlHost;

    private SceneRouter router;
    private AnimationTimer renderTimer;
    private BoidsCanvas boidsCanvas;
    private Flock flock;
    private LayoutShell layoutShell;
    private SidebarTabs sidebarTabs;

    private boolean leftMouseDown = false;
    private boolean rightMouseDown = false;
    private double mouseX = 0;
    private double mouseY = 0;

    // Config parameters
    private float configAlignmentWeight = 0.0f;
    private float configCohesionWeight = 0.0f;
    private float configSeparationWeight = 0.0f;
    private float configMaxSpeed = 36.0f; // Base 6 * 6 (scale factor for 9000 canvas)
    private float configNoiseScale = 0.0f;
    private int configSimSpeed = 1;
    private int configNumThreads = -1;
    private int configFlockSize = 75;

    private boolean isPaused = false;
    private boolean stepRequested = false;
    private long totalSimulatedNanos = 0;
    private long lastFrameTime = -1;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if (configNumThreads < 0) {
            configNumThreads = Runtime.getRuntime().availableProcessors();
        }

        titleLabel.setText("Boids Flocking Simulator   ·   Craig Reynolds' Boids Paper");

        flock = new Flock();
        boidsCanvas = new BoidsCanvas(2000, 2000);
        boidsCanvas.setFlock(flock);
        boidsCanvas.setManaged(false);

        boidsCanvas.widthProperty().bind(canvasHost.widthProperty());
        boidsCanvas.heightProperty().bind(canvasHost.heightProperty());
        canvasHost.getChildren().add(boidsCanvas);

        layoutShell = new LayoutShell(sidebarScroll, new StackPane(), canvasGraphStack);
        layoutShell.buildSidebarToggle();

        setupControls();
        setupInputHandlers();

        for (int i = 0; i < configFlockSize; ++i) {
            addBoid(getRandomFloat() * 2000, getRandomFloat() * 2000, false);
        }

    }

    private void setupControls() {
        VBox settingsGroup = new VBox(14);
        settingsGroup.getStyleClass().add("control-group");

        VBox countRow = createConfigRow("Boid Count", 0, 1000, configFlockSize, val -> {
            configFlockSize = val.intValue();
            while (flock.size() > configFlockSize) {
                flock.removeLast();
            }
            while (flock.size() < configFlockSize) {
                addBoid(getRandomFloat() * 2000, getRandomFloat() * 2000, false);
            }
        }, true);

        VBox simSpeedRow = createConfigRow("Sim Speed", 1, 5, configSimSpeed, val -> {
            configSimSpeed = val.intValue();
        }, true);

        VBox alignRow = createConfigRow("Alignment", 0, 1, configAlignmentWeight, val -> {
            configAlignmentWeight = val.floatValue();
            updateBoidParameters();
        }, false);

        VBox cohesionRow = createConfigRow("Cohesion", 0, 1, configCohesionWeight, val -> {
            configCohesionWeight = val.floatValue();
            updateBoidParameters();
        }, false);

        VBox separationRow = createConfigRow("Separation", 0, 1, configSeparationWeight, val -> {
            configSeparationWeight = val.floatValue();
            updateBoidParameters();
        }, false);

        VBox noiseRow = createConfigRow("Random Force", 0, 5, configNoiseScale, val -> {
            configNoiseScale = val.floatValue();
            updateBoidParameters();
        }, false);

        // We add a listener to update the time label in the canvas
        renderTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                handleInput();
                if (lastFrameTime < 0)
                    lastFrameTime = now;
                long dt = now - lastFrameTime;
                lastFrameTime = now;

                if (!isPaused || stepRequested) {
                    float worldW = (float) boidsCanvas.getWorldWidth();
                    float worldH = (float) boidsCanvas.getWorldHeight();
                    for (int i = 0; i < flock.size(); i++) {
                        simulation.boids.Boid b = flock.get(i);
                        b.maxWidth = worldW;
                        b.maxHeight = worldH;
                    }
                    int steps = stepRequested ? 1 : configSimSpeed;
                    for (int s = 0; s < steps; s++) {
                        flock.update(worldW, worldH, configNumThreads);
                    }
                    totalSimulatedNanos += dt * steps;
                    stepRequested = false;
                }
                double seconds = totalSimulatedNanos / 1_000_000_000.0;
                boidsCanvas.setSimulatedTime(seconds);
                boidsCanvas.render();
            }
        };

        javafx.scene.control.ToggleButton btnPause = new javafx.scene.control.ToggleButton("⏸  Pause");
        btnPause.setMaxWidth(Double.MAX_VALUE);
        btnPause.getStyleClass().add("sidebar-button");
        btnPause.setOnAction(e -> {
            isPaused = btnPause.isSelected();
            btnPause.setText(isPaused ? "▶  Resume" : "⏸  Pause");
        });

        Button btnStep = new Button("⏭  Step");
        btnStep.setMaxWidth(Double.MAX_VALUE);
        btnStep.getStyleClass().add("sidebar-button");
        btnStep.setOnAction(e -> {
            isPaused = true;
            btnPause.setSelected(true);
            btnPause.setText("▶  Resume");
            stepRequested = true;
        });

        Button btnReset = new Button("↺  Reset");
        btnReset.setMaxWidth(Double.MAX_VALUE);
        btnReset.getStyleClass().add("sidebar-button");
        btnReset.setOnAction(e -> {
            flock.clear();
            for (int i = 0; i < configFlockSize; ++i) {
                addBoid(getRandomFloat() * 2000, getRandomFloat() * 2000, false);
            }
            totalSimulatedNanos = 0;
            boidsCanvas.render();
        });

        HBox playRow = new HBox(6, btnPause, btnStep, btnReset);
        playRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        javafx.scene.control.CheckBox cbShowPerception = new javafx.scene.control.CheckBox("Show Perception radius");
        cbShowPerception.getStyleClass().add("sidebar-checkbox");
        cbShowPerception.setOnAction(e -> {
            boidsCanvas.setShowPerceptionRadius(cbShowPerception.isSelected());
        });

        javafx.scene.control.CheckBox cbShowBoundary = new javafx.scene.control.CheckBox("Show Canvas Boundary");
        cbShowBoundary.getStyleClass().add("sidebar-checkbox");
        cbShowBoundary.setOnAction(e -> {
            boidsCanvas.setShowCanvasBoundary(cbShowBoundary.isSelected());
        });

        settingsGroup.getChildren().addAll(
                countRow,
                simSpeedRow,
                alignRow,
                cohesionRow,
                separationRow,
                noiseRow,
                new javafx.scene.control.Separator(),
                cbShowPerception,
                cbShowBoundary,
                new javafx.scene.control.Separator(),
                playRow);

        sidebarTabs = new SidebarTabs();
        sidebarTabs.build(
                new VBox(),
                new SidebarTabs.Tab("Parameters", Icons.Glyph.SETTINGS, settingsGroup));
        VBox.setVgrow(sidebarTabs, Priority.ALWAYS);
        controlHost.getChildren().setAll(sidebarTabs);
        controlHost.minHeightProperty().bind(sidebarScroll.heightProperty());
    }

    // titleLabel updated statically via FXML or just keeping its original value.

    private VBox createConfigRow(String labelText, double min, double max, double value,
            java.util.function.Consumer<Double> onUpdate, boolean isInteger) {
        Label label = new Label(labelText);
        label.getStyleClass().add("sidebar-section-label");

        Slider slider = new Slider(min, max, value);
        slider.setMaxWidth(Double.MAX_VALUE);
        slider.getStyleClass().add("sidebar-slider");

        String formatStr = isInteger ? "%.0f" : "%.2f";
        javafx.scene.control.TextField field = new javafx.scene.control.TextField(String.format(formatStr, value));
        field.getStyleClass().add("sidebar-numeric-field");
        field.setPrefWidth(60);
        field.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        field.setOnAction(e -> {
            try {
                double val = Double.parseDouble(field.getText().trim());
                slider.setValue(Math.max(slider.getMin(), Math.min(slider.getMax(), val)));
            } catch (NumberFormatException ex) {
                field.setText(String.format(formatStr, slider.getValue()));
            }
        });

        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            field.setText(String.format(formatStr, newVal.doubleValue()));
            onUpdate.accept(newVal.doubleValue());
        });

        HBox row = new HBox(6, slider, field);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);

        VBox group = new VBox(4, label, row);
        return group;
    }

    private void updateBoidParameters() {
        for (int i = 0; i < flock.size(); i++) {
            Boid b = flock.get(i);
            b.alignmentWeight = configAlignmentWeight * 2.0f;
            b.cohesionWeight = configCohesionWeight * 2.0f;
            b.separationWeight = configSeparationWeight * 10.0f;
            b.maxSpeed = configMaxSpeed;
            b.noiseScale = configNoiseScale;
        }
    }

    private void addBoid(float x, float y, boolean isPredator) {
        float worldW = (float) boidsCanvas.getWorldWidth();
        float worldH = (float) boidsCanvas.getWorldHeight();
        // Scale factor S = 6.0 (1500 original -> 9000 new)
        Boid b = new Boid(x, y,
                worldW,
                worldH,
                configMaxSpeed, 6.0f, 1.8f,
                configCohesionWeight * 2.0f, configAlignmentWeight * 2.0f, configSeparationWeight * 10.0f, 600f,
                120f, configNoiseScale, isPredator);
        flock.add(b);
    }

    private void setupInputHandlers() {
        // We track mouse on the canvas
        boidsCanvas.setOnMousePressed(this::updateMouseState);
        boidsCanvas.setOnMouseDragged(this::updateMouseState);
        boidsCanvas.setOnMouseReleased(event -> {
            leftMouseDown = false;
            rightMouseDown = false;
        });
        boidsCanvas.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
        });
    }

    private void updateMouseState(MouseEvent event) {
        mouseX = event.getX();
        mouseY = event.getY();
        if (event.getButton() == MouseButton.PRIMARY) {
            leftMouseDown = true;
            rightMouseDown = false;
        } else if (event.getButton() == MouseButton.SECONDARY) {
            rightMouseDown = true;
            leftMouseDown = false;
        }
    }

    private void handleInput() {
        if (leftMouseDown) {
            addBoid((float) boidsCanvas.screenToWorldX(mouseX), (float) boidsCanvas.screenToWorldY(mouseY), false);
        } else if (rightMouseDown) {
            addBoid((float) boidsCanvas.screenToWorldX(mouseX), (float) boidsCanvas.screenToWorldY(mouseY), true);
        }
    }

    private float getRandomFloat() {
        return ThreadLocalRandom.current().nextFloat();
    }

    @FXML
    private void handleBack() {
        router.back();
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }

    @Override
    public void onShow() {
        lastFrameTime = -1;
        renderTimer.start();
        Platform.runLater(() -> boidsCanvas.fitToContent());
    }

    @Override
    public void onHide() {
        renderTimer.stop();
    }
}
