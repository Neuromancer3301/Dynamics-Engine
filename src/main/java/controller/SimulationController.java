package controller;

import javafx.animation.AnimationTimer;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import navigation.Navigable;
import navigation.SceneRouter;
import physics.PendulumConfig;
import physics.PhysicsEngine;
import physics.SimState;
import physics.integrator.Integrator;
import physics.integrator.IntegratorType;
import simulation.Ensemble;
import simulation.HistoryBuffer;
import simulation.SimulationLoop;
import simulation.StateBuffer;
import theme.ThemeManager;
import ui.ControlPanel;
import ui.GraphPanel;
import ui.LinkEditorPanel;
import ui.PendulumCanvas;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Hosts the existing Canvas-based simulation view inside the FXML shell.
 *
 * <p>The physics engine, the render loop, and the three {@code ui.*} Canvas
 * components are untouched from the original prototype — this controller's
 * job is purely to own their lifecycle ({@link #onShow} / {@link #onHide})
 * and to size them to FXML-declared containers instead of a hand-built
 * {@code Stage}. Binding each canvas's width/height to its host {@link
 * StackPane} is the entire change that makes the simulation resizable: both
 * {@code PendulumCanvas.render()} and {@code GraphPanel.render()} already
 * read {@code getWidth()}/{@code getHeight()} fresh every frame.
 *
 * <p><b>Interaction:</b> grabbing and dragging a bob (wired below via {@link
 * PendulumCanvas.DragListener}) does not pause the simulation — each drag
 * event submits a {@code SimCommand} that overwrites just the grabbed
 * link's angle and zeroes its velocity, while the physics thread keeps
 * integrating every link, grabbed one included, in between those events.
 * Because the mass matrix couples every link's derivative to every other
 * link's angle, the rest of the chain visibly reacts to the forced one in
 * real time rather than sitting frozen — a standard "kinematic forcing"
 * technique for interactive physics, not a special case bolted onto the
 * integrator. Releasing submits one final command with an angular velocity
 * estimated from the last ~150ms of motion, producing the "fling".
 *
 * <p><b>Structural edits:</b> {@link ui.LinkEditorPanel} covers both the
 * per-link parameter editor and runtime N control in one place — adding or
 * removing a row changes N directly, and both go through the same "Apply"
 * action ({@link #applyStructuralEdit}), which submits an {@code
 * EngineRebuilder} via {@link SimulationLoop#rebuildWithConfig} rather than
 * a {@code SimCommand}, since changing length, mass, or N means replacing
 * the engine's internal arrays rather than mutating a field on them.
 *
 * <p><b>Butterfly effect:</b> the "Chaos" toggle ({@link
 * #setEnsembleActive}) spawns a {@link Ensemble} of near-identical copies
 * from the primary's exact current state, stepped alongside it on the
 * physics thread and rendered as faint ghost chains. A structural edit
 * invalidates any active ensemble (its N/lengths no longer match the
 * primary), so {@link #applyStructuralEdit} always clears it. While the
 * ensemble is active, {@link #estimateLyapunov} turns its divergence from
 * the primary into a live largest-Lyapunov-exponent estimate.
 *
 * <p><b>Integrator comparison:</b> {@link #compareIntegrators} runs every
 * {@code IntegratorType} from the primary's current state on temporary,
 * disposable engines (never touching the live simulation) and plots their
 * energy drift via {@code GraphPanel.Mode.COMPARISON}.
 *
 * <p><b>Time-travel scrubbing:</b> the render loop also feeds a {@link
 * HistoryBuffer}; dragging the sidebar's history slider shows a past frame
 * on the canvas while the live simulation keeps running underneath — see
 * that class's javadoc for the deliberate scope boundary (a view into the
 * past, not a rewind of the live engine).
 */
public final class SimulationController implements Initializable, Navigable {

    // 50 members, 1e-7 rad apart: small enough to be an imperceptible
    // cluster at spawn, large enough to be well above floating-point noise
    // (~1e-16) so the eventual divergence is a real chaos signal, not an
    // artifact of it.
    private static final int    ENSEMBLE_SIZE    = 50;
    private static final double ENSEMBLE_EPSILON = 1.0e-7;

    // History: sampled every 3rd render-timer tick (~20Hz at 60fps) rather
    // than every physics publish (~500Hz) — scrubbing doesn't need physics
    // resolution, and 500Hz would make even a short window a very large
    // buffer. 600 entries at ~20Hz is ~30s of replayable history.
    private static final int HISTORY_CAPACITY          = 600;
    private static final int HISTORY_SAMPLE_EVERY_FRAMES = 3;
    private static final double HISTORY_SAMPLE_HZ = 60.0 / HISTORY_SAMPLE_EVERY_FRAMES;

    // How long each integrator runs for "Compare Integrators" — long enough
    // to show a real divergence trend, short enough to compute and plot
    // instantly given this engine's measured throughput.
    private static final double COMPARISON_DURATION_SECONDS = 10.0;
    private static final double COMPARISON_DT = 0.002;

    @FXML private Button btnBack;
    @FXML private Label titleLabel;
    @FXML private StackPane canvasHost;
    @FXML private StackPane graphHost;
    @FXML private VBox controlHost;

    private SceneRouter router;
    private SimulationLoop simLoop;
    private StateBuffer stateBuffer;
    private ControlPanel controlPanel;
    private LinkEditorPanel linkEditorPanel;
    private PendulumCanvas pendulumCanvas;
    private GraphPanel graphPanel;
    private AnimationTimer renderTimer;

    // The structural shape (N, lengths, masses, gravity, speed) currently
    // applied — kept so the butterfly-effect toggle can build ensemble
    // members with the right structure without re-deriving it from the UI.
    private PendulumConfig currentConfig;

    // physics.PhysicsEngine's constructor always starts with a fresh RK4
    // sized for its own N — an Integrator's scratch buffers are fixed-size,
    // so the previous selection can't just carry over a rebuild. Kept here
    // so applyStructuralEdit can re-apply it at the new size; see
    // IntegratorType's javadoc for the full reasoning.
    private IntegratorType selectedIntegratorType = IntegratorType.RK4;

    private Double initialEnergy;
    private long graphFrameCount;

    // Time-travel scrubbing — see HistoryBuffer's javadoc for the scope
    // boundary (a view into the past, not a rewind of the live simulation).
    private final HistoryBuffer history = new HistoryBuffer(HISTORY_CAPACITY);
    private boolean scrubbing = false;
    private int scrubIndex = 0;

    // Lyapunov estimate: set to the sim-time at which the current ensemble
    // was spawned, so each frame can measure elapsed time and estimate
    // lambda = ln(separation / epsilon) / elapsed. Null when no ensemble
    // is active. See buildRenderTimer for the actual computation.
    private Double ensembleStartSimTime;

    // Added on onShow() / removed on onHide() — see those methods. The
    // Scene object is reused across navigations (SceneRouter only swaps its
    // root), so an event FILTER added here without being removed would
    // silently accumulate one extra copy every time this screen is revisited.
    private EventHandler<KeyEvent> keyHandler;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        PendulumConfig config = PendulumConfig.defaultConfig();
        currentConfig = config;
        stateBuffer = new StateBuffer();
        simLoop = new SimulationLoop(config, stateBuffer);

        pendulumCanvas = new PendulumCanvas(500, 580, config.getTotalLength());
        graphPanel = new GraphPanel(490, 580);

        // Read once at construction, not live — see SettingsController's
        // javadoc for why a change there takes effect next time this
        // screen opens rather than mid-simulation.
        pendulumCanvas.setReducedMotion(ThemeManager.getInstance().isReducedMotion());
        pendulumCanvas.setColorBlindSafe(ThemeManager.getInstance().isColorBlindSafePalette());

        // A Canvas reports its own current size as its "preferred size" — if
        // it stays managed, binding its width/height to its own StackPane
        // host makes the host's preferred size self-referential, which
        // inflates on layout and pushes later HBox/BorderPane siblings
        // (the graph, then the whole sidebar) off the visible window.
        // Unmanaging the canvas excludes it from that computation entirely;
        // it still fills its host exactly, since the binding is unaffected.
        pendulumCanvas.setManaged(false);
        graphPanel.setManaged(false);

        pendulumCanvas.widthProperty().bind(canvasHost.widthProperty());
        pendulumCanvas.heightProperty().bind(canvasHost.heightProperty());
        graphPanel.widthProperty().bind(graphHost.widthProperty());
        graphPanel.heightProperty().bind(graphHost.heightProperty());

        canvasHost.getChildren().add(pendulumCanvas);
        graphHost.getChildren().add(graphPanel);

        pendulumCanvas.setDragListener(new PendulumCanvas.DragListener() {
            @Override
            public boolean onGrab(int linkIndex) {
                return true; // every rendered bob accepts a grab
            }

            @Override
            public void onDrag(int linkIndex, double angle) {
                simLoop.submit(e -> e.setLinkState(linkIndex, angle, 0.0));
            }

            @Override
            public void onRelease(int linkIndex, double angle, double angularVelocity) {
                simLoop.submit(e -> e.setLinkState(linkIndex, angle, angularVelocity));
            }
        });

        controlPanel = new ControlPanel();
        controlPanel.setOnResetCallback(() -> {
            initialEnergy = null;
            history.clear();
            scrubbing = false;
        });
        controlPanel.setOnEnsembleToggle(this::setEnsembleActive);
        controlPanel.setOnIntegratorChange(this::setIntegratorType);
        controlPanel.setOnCompareIntegrators(this::compareIntegrators);
        controlPanel.setOnScrubStart(() -> scrubbing = true);
        controlPanel.setOnScrubTo(idx -> scrubIndex = idx);
        controlPanel.setOnScrubEnd(() -> scrubbing = false);
        controlPanel.build(simLoop, graphPanel, pendulumCanvas, config.getN());

        linkEditorPanel = new LinkEditorPanel();
        linkEditorPanel.loadFrom(config);
        linkEditorPanel.setLiveParameterSuppliers(simLoop::getGravity, simLoop::getSpeedMultiplier);
        linkEditorPanel.setOnApply(this::applyStructuralEdit);

        controlHost.getChildren().addAll(controlPanel, new Separator(), linkEditorPanel);

        titleLabel.setText("N-Pendulum Chain Simulator   ·   N = " + config.getN()
                + "   ·   RK4 / Lagrangian Mechanics");

        renderTimer = buildRenderTimer();
    }

    /**
     * Handles a validated {@link PendulumConfig} from the link editor: swaps
     * the engine via {@link SimulationLoop#rebuildWithConfig}, then refreshes
     * everything downstream that was sized or labeled for the old N/length —
     * the canvas's render scale, its stale trail, the graph's history, and
     * the two sidebar headers that display N.
     */
    private void applyStructuralEdit(PendulumConfig newConfig) {
        currentConfig = newConfig;
        simLoop.rebuildWithConfig(newConfig);
        // The fresh engine rebuildWithConfig just built defaults to RK4 —
        // re-apply whatever the user actually had selected, sized for the
        // new N. See selectedIntegratorType's javadoc.
        simLoop.setIntegrator(selectedIntegratorType.create(2 * newConfig.getN()));

        // An active ensemble was built from the old N/lengths/masses — it's
        // not just stale, it no longer corresponds to what the primary chain
        // even looks like, so drop it rather than confuse the demo.
        simLoop.setEnsemble(null);
        controlPanel.setEnsembleVisual(false);

        pendulumCanvas.setTotalLength(newConfig.getTotalLength());
        pendulumCanvas.clearTrail();
        graphPanel.clear();
        controlPanel.updateLinkCount(newConfig.getN());
        initialEnergy = null;

        // Historical samples describe a different N/length — showing them
        // post-rebuild would be replaying a chain that no longer exists.
        history.clear();
        scrubbing = false;

        titleLabel.setText("N-Pendulum Chain Simulator   ·   N = " + newConfig.getN()
                + "   ·   RK4 / Lagrangian Mechanics");
    }

    /** Switches the integration strategy on the live engine, and remembers the choice for the next structural rebuild. */
    private void setIntegratorType(IntegratorType type) {
        selectedIntegratorType = type;
        simLoop.setIntegrator(type.create(2 * currentConfig.getN()));
    }

    /**
     * Turns the butterfly-effect ensemble on or off. Spawning captures
     * whatever the primary engine's live state is <em>right now</em> — see
     * {@link Ensemble}'s javadoc for why that's deliberate.
     */
    private void setEnsembleActive(boolean active) {
        if (!active) {
            simLoop.setEnsemble(null);
            ensembleStartSimTime = null;
            return;
        }
        SimState current = stateBuffer.read();
        if (current == null) return; // physics thread hasn't published a first state yet
        simLoop.setEnsemble(new Ensemble(
                currentConfig, current.angles, current.angularVelocities,
                ENSEMBLE_SIZE, ENSEMBLE_EPSILON));
        ensembleStartSimTime = current.time;
    }

    /**
     * Runs each {@link IntegratorType} from the primary's exact current
     * state for {@link #COMPARISON_DURATION_SECONDS}, using temporary
     * engine instances that never touch the live simulation, and plots the
     * resulting energy drift as {@link GraphPanel.ComparisonSeries}.
     */
    private void compareIntegrators() {
        SimState current = stateBuffer.read();
        if (current == null) return;

        Color[] colors = { Color.web("#4ECDC4"), Color.web("#FF6B6B"), Color.web("#FDCB6E") };
        List<GraphPanel.ComparisonSeries> series = new ArrayList<>();
        int steps = (int) Math.round(COMPARISON_DURATION_SECONDS / COMPARISON_DT);

        int colorIdx = 0;
        for (IntegratorType type : IntegratorType.values()) {
            PhysicsEngine engine = new PhysicsEngine(currentConfig);
            for (int i = 0; i < current.getN(); i++) {
                engine.setLinkState(i, current.angles[i], current.angularVelocities[i]);
            }
            Integrator integrator = type.create(2 * currentConfig.getN());
            engine.setIntegrator(integrator);

            double e0 = engine.getState().totalEnergy;
            double[] times = new double[steps + 1];
            double[] drift = new double[steps + 1];
            times[0] = 0;
            drift[0] = 0;

            for (int i = 1; i <= steps; i++) {
                engine.step(COMPARISON_DT);
                times[i] = i * COMPARISON_DT;
                drift[i] = Math.abs(engine.getState().totalEnergy - e0);
            }

            series.add(new GraphPanel.ComparisonSeries(type.toString(), times, drift, colors[colorIdx++ % colors.length]));
        }

        graphPanel.setComparisonData(series);
        graphPanel.setMode(GraphPanel.Mode.COMPARISON);
    }

    private AnimationTimer buildRenderTimer() {
        return new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                SimState liveState = stateBuffer.read();

                if (liveState != null && initialEnergy == null) {
                    initialEnergy = liveState.totalEnergy;
                }

                if (graphFrameCount % HISTORY_SAMPLE_EVERY_FRAMES == 0) {
                    history.record(liveState);
                }

                // While scrubbing, the pendulum canvas shows a historical
                // frame; the graph (below) always reflects live data
                // regardless — it already accumulates its own history
                // (trails, Poincaré points), so freezing it while scrubbing
                // the canvas would be a second, inconsistent notion of
                // "the past" layered on top of the first.
                SimState displayState = liveState;
                if (scrubbing && history.size() > 0) {
                    int idx = Math.max(0, Math.min(scrubIndex, history.size() - 1));
                    SimState historical = history.get(idx);
                    if (historical != null) displayState = historical;
                }

                Ensemble ensemble = simLoop.getEnsemble();
                List<SimState> ghosts = (!scrubbing && ensemble != null) ? ensemble.snapshot() : null;
                pendulumCanvas.render(displayState, ghosts);

                if (graphFrameCount % 2 == 0) {
                    graphPanel.addDataPoint(liveState);
                    graphPanel.render();
                }
                graphFrameCount++;

                if (graphFrameCount % 4 == 0) {
                    controlPanel.updateStatus(displayState, initialEnergy);
                    controlPanel.updateLyapunov(estimateLyapunov(liveState, ghosts));
                }

                controlPanel.updateHistoryRange(history.size() - 1);
                if (!scrubbing) {
                    controlPanel.setHistoryPositionLive(history.size() - 1);
                } else {
                    double secondsAgo = (history.size() - 1 - scrubIndex) / HISTORY_SAMPLE_HZ;
                    controlPanel.setHistoryPositionScrubbed(secondsAgo);
                }
            }
        };
    }

    /**
     * Simplified two-trajectory Lyapunov estimate: lambda = ln(separation /
     * epsilon) / elapsed, using link 0's angular separation between the
     * primary and one ensemble member. This is the standard method's
     * single-measurement form, without Benettin's periodic renormalization
     * — accurate for the short initial-divergence window before saturation,
     * which is exactly the regime the ensemble is already used in. Returns
     * null whenever there's nothing to measure yet (no ensemble, too little
     * elapsed time, or separation not yet above floating-point noise).
     */
    private Double estimateLyapunov(SimState liveState, List<SimState> ghosts) {
        if (liveState == null || ghosts == null || ghosts.isEmpty() || ensembleStartSimTime == null) return null;

        double elapsed = liveState.time - ensembleStartSimTime;
        if (elapsed < 0.25) return null; // too little separation yet to measure meaningfully

        double separation = Math.abs(wrapAngleDelta(liveState.angles[0] - ghosts.get(0).angles[0]));
        if (separation < 1.0e-9) return null;

        return Math.log(separation / ENSEMBLE_EPSILON) / elapsed;
    }

    private static double wrapAngleDelta(double delta) {
        while (delta >  Math.PI) delta -= 2 * Math.PI;
        while (delta < -Math.PI) delta += 2 * Math.PI;
        return delta;
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
        simLoop.start();
        renderTimer.start();

        keyHandler = this::handleKeyPress;
        Scene scene = btnBack.getScene();
        if (scene != null) scene.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
    }

    @Override
    public void onHide() {
        renderTimer.stop();
        simLoop.stop();

        Scene scene = btnBack.getScene();
        if (scene != null && keyHandler != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        keyHandler = null;
    }

    /**
     * Space, R, and → — the shortcuts you actually reach for mid-demo. An
     * event FILTER (capturing phase, registered on the Scene in onShow)
     * rather than a handler on a single node: Space on a focused Button
     * would otherwise trigger that button's own click before this ever saw
     * the key.
     */
    private void handleKeyPress(KeyEvent e) {
        switch (e.getCode()) {
            case SPACE -> {
                boolean paused = !simLoop.isPaused();
                simLoop.setPaused(paused);
                controlPanel.setPausedVisual(paused);
                e.consume();
            }
            case R -> {
                simLoop.reset();
                pendulumCanvas.clearTrail();
                graphPanel.clear();
                initialEnergy = null;
                history.clear();
                scrubbing = false;
                e.consume();
            }
            case RIGHT -> {
                simLoop.stepOnce();
                e.consume();
            }
            default -> { /* not a shortcut this screen handles */ }
        }
    }
}
