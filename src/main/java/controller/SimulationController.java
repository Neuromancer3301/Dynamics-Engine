package controller;

import audio.Sonifier;
import javafx.animation.AnimationTimer;
import javafx.concurrent.Task;
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
import physics.BifurcationSweep;
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
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(SimulationController.class.getName());

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

    // Bifurcation sweep parameters — see physics.BifurcationSweep. Kept
    // modest (rather than the hundreds of columns/tens of settle-seconds a
    // textbook figure might use) specifically so this stays a "background
    // task that finishes in a reasonable time on typical hardware," not a
    // multi-minute wait — the tradeoff is a coarser-looking diagram, an
    // acceptable one for a demonstration feature.
    private static final double BIFURCATION_PARAM_MIN      = 0.1;
    private static final double BIFURCATION_PARAM_MAX      = Math.PI - 0.05;
    private static final int    BIFURCATION_COLUMNS        = 90;
    private static final double BIFURCATION_SETTLE_SECONDS = 6.0;
    private static final double BIFURCATION_SAMPLE_SECONDS = 5.0;

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

    // Mirrors PhysicsEngine's own gravityAngle — kept here for the same
    // reason selectedIntegratorType is: a structural rebuild (see
    // applyStructuralEdit) replaces the engine with a fresh one that
    // defaults back to 0 (straight down), so whatever the user last painted
    // needs re-applying rather than silently reverting.
    private double currentGravityAngle = 0.0;

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

    // Off by default — an unrequested tone playing on launch would be an
    // unpleasant surprise. See buildRenderTimer for the per-frame frequency
    // update and audio.Sonifier's javadoc for why this can never throw even
    // on a machine with no audio output.
    private final Sonifier sonifier = new Sonifier();
    private boolean sonifyActive = false;

    // Non-null only while a sweep is in flight — see generateBifurcationMap
    // and onHide (which cancels it if the user navigates away mid-sweep).
    private Task<BifurcationSweep.Result> bifurcationTask;

    // Added on onShow() / removed on onHide() — see those methods. The
    // Scene object is reused across navigations (SceneRouter only swaps its
    // root), so an event FILTER added here without being removed would
    // silently accumulate one extra copy every time this screen is revisited.
    private EventHandler<KeyEvent> keyHandler;

        /**
     * Builds the entire simulation screen. Called once by {@code FXMLLoader}
     * after the FXML is parsed and {@code @FXML} fields are injected.
     *
     * <p>Assembly order: engine and loop, then the two canvases, then the
     * sidebar panels, then callback wiring, then the render timer. Nothing
     * is STARTED here — the physics thread and render loop only begin in
     * {@link #onShow()}, so the screen consumes no CPU until it is visible.
     */
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
                        /** Every bob is grabbable, so this always accepts. Returning false would let the canvas veto a drag. */
            @Override
            public boolean onGrab(int linkIndex) {
                return true; // every rendered bob accepts a grab
            }

                        /** While held: pin the link to the pointer's angle with ZERO velocity, so it follows the mouse instead of fighting it. */
            @Override
            public void onDrag(int linkIndex, double angle) {
                simLoop.submit(e -> e.setLinkState(linkIndex, angle, 0.0));
            }

                        /** On release: hand back the estimated fling velocity, so letting go mid-swing throws the bob rather than dropping it. */
            @Override
            public void onRelease(int linkIndex, double angle, double angularVelocity) {
                simLoop.submit(e -> e.setLinkState(linkIndex, angle, angularVelocity));
            }
        });

        pendulumCanvas.setGravityListener(angle -> {
            currentGravityAngle = angle;
            simLoop.setGravityAngle(angle);
        });

        controlPanel = new ControlPanel();
        controlPanel.setOnResetCallback(() -> {
            initialEnergy = null;
            history.clear();
            scrubbing = false;
        });
        controlPanel.setOnEnsembleToggle(this::setEnsembleActive);
        controlPanel.setOnSonifyToggle(this::setSonifyActive);
        controlPanel.setOnGenerateBifurcation(this::generateBifurcationMap);
        controlPanel.setOnCompareToggle(this::setCompareActive);
        controlPanel.setOnResetGravityDirection(() -> {
            currentGravityAngle = 0.0;
            simLoop.setGravityAngle(0.0);
            pendulumCanvas.setGravityAngleVisual(0.0);
        });
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
        simLoop.setGravityAngle(currentGravityAngle);
        pendulumCanvas.setGravityAngleVisual(currentGravityAngle);

        // An active ensemble was built from the old N/lengths/masses — it's
        // not just stale, it no longer corresponds to what the primary chain
        // even looks like, so drop it rather than confuse the demo.
        simLoop.setEnsemble(null);
        controlPanel.setEnsembleVisual(false);

        // Same reasoning for an active A/B compare "B" engine.
        simLoop.setCompareEngine(null);
        controlPanel.setCompareVisual(false);

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
     * Activates or deactivates A/B compare: a single "B" engine, structurally
     * identical to the primary but with link 0's initial angle offset by
     * {@code deltaTheta1Radians}, spawned from the primary's exact current
     * state (mirroring {@link #setEnsembleActive}'s own "from right now, not
     * from a reset" reasoning) and stepped alongside it thereafter. Unlike
     * the ensemble, this is one deliberately, visibly different scenario —
     * see {@code ui.PendulumCanvas#drawCompareChain}.
     */
    private void setCompareActive(boolean active, double deltaTheta1Radians) {
        if (!active) {
            simLoop.setCompareEngine(null);
            return;
        }
        SimState current = stateBuffer.read();
        if (current == null) return; // physics thread hasn't published a first state yet

        PhysicsEngine compareEngine = new PhysicsEngine(currentConfig);
        for (int i = 0; i < current.getN(); i++) {
            double offset = (i == 0) ? deltaTheta1Radians : 0.0;
            compareEngine.setLinkState(i, current.angles[i] + offset, current.angularVelocities[i]);
        }
        simLoop.setCompareEngine(compareEngine);
    }

    /** Starts or stops the audio tone. The render loop feeds it a frequency each frame while active. */
    private void setSonifyActive(boolean active) {
        sonifyActive = active;
        if (active) sonifier.start();
        else        sonifier.stop();
    }

    /**
     * Runs {@link BifurcationSweep} on a background thread — see its own
     * javadoc for what it computes and why this can't run on the JavaFX
     * thread directly. {@code currentConfig} is captured once up front
     * (deliberately not read live from mid-sweep): the sweep already takes
     * a while, and racing it against the user editing links while it runs
     * would make "what does this diagram describe" ambiguous.
     */
    private void generateBifurcationMap() {
        if (bifurcationTask != null && bifurcationTask.isRunning()) return;

        PendulumConfig base = currentConfig;
        controlPanel.setBifurcationRunning(true);

        Task<BifurcationSweep.Result> task = new Task<>() {
            @Override
            protected BifurcationSweep.Result call() {
                return BifurcationSweep.sweep(base,
                        BIFURCATION_PARAM_MIN, BIFURCATION_PARAM_MAX, BIFURCATION_COLUMNS,
                        BIFURCATION_SETTLE_SECONDS, BIFURCATION_SAMPLE_SECONDS,
                        frac -> updateProgress(frac, 1.0), this::isCancelled);
            }
        };
        task.progressProperty().addListener((obs, oldVal, newVal) ->
                controlPanel.setBifurcationProgress(newVal.doubleValue()));
        task.setOnSucceeded(e -> {
            BifurcationSweep.Result result = task.getValue();
            graphPanel.setBifurcationData(result.paramValues(), result.samples());
            graphPanel.setMode(GraphPanel.Mode.BIFURCATION);
            controlPanel.selectBifurcationMode();
            controlPanel.setBifurcationRunning(false);
            bifurcationTask = null;
        });
        task.setOnFailed(e -> {
            LOG.log(Level.WARNING, "Bifurcation sweep failed", task.getException());
            controlPanel.setBifurcationRunning(false);
            bifurcationTask = null;
        });
        task.setOnCancelled(e -> {
            controlPanel.setBifurcationRunning(false);
            bifurcationTask = null;
        });

        bifurcationTask = task;
        Thread thread = new Thread(task, "BifurcationSweep");
        thread.setDaemon(true);
        thread.start();
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

        // Same scope-channel triad as everywhere else in this palette
        // (magenta/cyan/yellow) — see ui.GraphPanel's own color comment.
        Color[] colors = { Color.web("#EA3F8C"), Color.web("#3DDCC7"), Color.web("#E8D34A") };
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

    /**
     * Creates the 60 fps render loop.
     *
     * <p>{@code AnimationTimer.handle()} is called by JavaFX once per screen
     * refresh, on the UI thread. It reads whatever the physics thread last
     * published — it never waits for it, which is what keeps rendering and
     * physics independent.
     *
     * <p><b>Not everything runs every frame.</b> Work is spread across
     * frames by counting them: the graph ingests and redraws every 2nd
     * frame, the status text updates every 4th, history samples every 3rd.
     * The pendulum itself redraws every frame, because that is the one thing
     * a viewer would notice stuttering. This staggering is a deliberate
     * budget: full-rate updates of all of it would waste time re-rendering
     * text and charts far faster than anyone can read them.
     */
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

                PhysicsEngine compareEngine = simLoop.getCompareEngine();
                SimState compareState = (!scrubbing && compareEngine != null) ? compareEngine.getState() : null;

                pendulumCanvas.render(displayState, ghosts, compareState);

                if (sonifyActive && liveState != null && liveState.getN() > 0) {
                    // Tip bob (last link): the one whose speed already
                    // drives the fastest visual motion, so ear and eye track
                    // the same thing. Mapped linearly into an audible range
                    // rather than 1:1 to rad/s, which would mostly sit below
                    // or above what's pleasant to listen to.
                    double tipOmega = Math.abs(liveState.angularVelocities[liveState.getN() - 1]);
                    double hz = 220.0 + Math.min(tipOmega, 10.0) / 10.0 * 660.0;
                    sonifier.setFrequency(hz);
                }

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

    /** Normalises an angle difference into (−π, π], so a separation measured across the wrap boundary isn't reported as nearly a full turn. */
    private static double wrapAngleDelta(double delta) {
        while (delta >  Math.PI) delta -= 2 * Math.PI;
        while (delta < -Math.PI) delta += 2 * Math.PI;
        return delta;
    }

    /** Wired to the "← Menu" button in {@code Simulation.fxml}. */
    @FXML
    private void handleBack() {
        router.back();
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }

    /**
     * Starts everything this screen owns: the physics thread, the render
     * loop, and the keyboard shortcuts. Paired exactly with {@link #onHide}
     * — anything started here must be stopped there, or it keeps running
     * invisibly after the user navigates away.
     */
    @Override
    public void onShow() {
        simLoop.start();
        renderTimer.start();

        keyHandler = this::handleKeyPress;
        Scene scene = btnBack.getScene();
        if (scene != null) scene.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
    }

    /**
     * Stops everything {@link #onShow} started. Every line here exists to
     * prevent a specific leak: an orphaned physics thread, a render loop
     * drawing an invisible canvas, a tone still playing, a background sweep
     * computing a diagram nobody can see, and — because the {@code Scene}
     * outlives this screen — a key filter that would otherwise accumulate
     * one extra copy per visit.
     */
    @Override
    public void onHide() {
        renderTimer.stop();
        simLoop.stop();

        // Otherwise a live tone would keep playing after navigating away
        // from this screen; sonifyActive/the button are reset alongside it
        // so re-entering the screen doesn't show "Sonify: On" for a
        // sonifier that isn't actually running anymore.
        sonifier.stop();
        sonifyActive = false;
        controlPanel.setSonifyVisual(false);

        // A sweep left running after navigating away would keep a
        // background thread alive computing a diagram nobody can see.
        if (bifurcationTask != null) bifurcationTask.cancel();

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
