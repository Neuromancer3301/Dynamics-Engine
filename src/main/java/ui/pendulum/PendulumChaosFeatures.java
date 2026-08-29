package ui.pendulum;

import audio.Sonifier;
import physics.BifurcationSweep;
import physics.FractalBasinSweep;
import physics.PendulumConfig;
import physics.PhysicsEngine;
import physics.SimState;
import physics.integrator.Integrator;
import physics.integrator.IntegratorType;
import simulation.Ensemble;
import simulation.SimulationLoop;
import simulation.StepListener;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Butterfly-effect ensemble, A/B compare, sonification, bifurcation sweep,
 * and integrator-comparison orchestration for the pendulum screen. Moved
 * out of {@code controller.SimulationController} — see round 1 §11 of the
 * UI restructuring plan.
 *
 * <p><b>Round 2 §3.</b> Also now owns the {@code ensemble}/{@code
 * compareEngine} fields and their per-tick stepping, moved out of {@code
 * simulation.SimulationLoop} — both are genuinely pendulum-specific (an
 * ensemble means perturbing angle/velocity; A/B compare offsets link 0's
 * angle), so unlike the loop's own scheduling machinery they don't belong
 * generalized into it. This class implements {@link StepListener}&lt;{@link
 * PhysicsEngine}&gt; and is wired via {@code
 * host.simLoop().setStepListener(this)} so both still step in lock-step with
 * the primary engine exactly as before. {@link #perturb} moved the same way
 * — it was already a plain {@code SimCommand<PhysicsEngine>} lambda, so only
 * which class calls {@code simLoop.submit(...)} for it changed.
 *
 * <p><b>Round 3.</b> Also owns {@link #generateBasinFractal}, ported from
 * {@code feature/manual-and-bugfixes} — the basin-of-attraction fractal
 * sweep, background-task orchestration lifted verbatim from {@link
 * #generateBifurcationMap}'s pattern (same {@code Task}/progress-listener/
 * daemon-thread shape) since it's the same kind of "run a long pure-physics
 * computation off the JavaFX thread, hand the result to the graph" feature.
 */
public final class PendulumChaosFeatures implements StepListener<PhysicsEngine> {

    private static final Logger LOG = Logger.getLogger(PendulumChaosFeatures.class.getName());

    // 50 members, 1e-7 rad apart: small enough to be an imperceptible
    // cluster at spawn, large enough to seed a real chaotic divergence
    // (well above floating-point noise, ~1e-16).
    private static final int    ENSEMBLE_SIZE    = 50;
    private static final double ENSEMBLE_EPSILON = 1.0e-7;

    // How long each integrator runs for "Compare Integrators".
    private static final double COMPARISON_DURATION_SECONDS = 10.0;
    private static final double COMPARISON_DT = 0.002;

    // Bifurcation sweep parameters — see physics.BifurcationSweep. Kept
    // modest so this stays a "background task that finishes in a
    // reasonable time," not a multi-minute wait.
    private static final double BIFURCATION_PARAM_MIN      = 0.1;
    private static final double BIFURCATION_PARAM_MAX      = Math.PI - 0.05;
    private static final int    BIFURCATION_COLUMNS        = 90;
    private static final double BIFURCATION_SETTLE_SECONDS = 6.0;
    private static final double BIFURCATION_SAMPLE_SECONDS = 5.0;

    // Basin-fractal sweep parameters — see physics.FractalBasinSweep.
    // 200x200 = 40,000 independent simulations, ~1.4 ms each, so ~55 s of
    // CPU work. Measured end-to-end at ~11 s wall clock on an 8-core
    // reference machine (a ~5x speedup, not the theoretical 8x — memory
    // bandwidth and per-cell engine setup keep it short of linear). The
    // resolution comes from that measurement, not a guess; drop it to 160
    // for roughly half the wait at visibly coarser detail. 12 s of
    // simulated time is long enough that the "never flips" region is
    // genuinely stable rather than merely slow to get going.
    private static final int    FRACTAL_RESOLUTION  = 200;
    private static final double FRACTAL_MAX_SECONDS = 12.0;

    /** What this class needs from its host screen — implemented by {@code controller.SimulationController}. */
    public interface Host {
        PendulumConfig currentConfig();
        SimState liveState();
        SimulationLoop<PhysicsEngine, SimState> simLoop();
        PendulumGraphPanel graphPanel();

        void setEnsembleVisual(boolean active);
        void setCompareVisual(boolean active);
        void setBifurcationRunning(boolean running);
        void setBifurcationProgress(double fraction);
        void setFractalRunning(boolean running);
        void setFractalProgress(double fraction);

        /** Forwards a finished sweep's results to the graph (data + mode) and the sidebar (selects the Bifurcation Map toggle). */
        void onBifurcationComplete(double[] params, List<double[]> samples);

        /** Forwards a finished basin sweep's grid to the graph (data + mode) and the sidebar (selects the Basin Fractal toggle). */
        void onFractalComplete(double[][] timeToFlip, double maxSeconds);
    }

    private final Host host;

    // Off by default — an unrequested tone playing on launch would be an
    // unpleasant surprise.
    private final Sonifier sonifier = new Sonifier();
    private boolean sonifyActive = false;

    // Set to the sim-time at which the current ensemble was spawned, so
    // estimateLyapunov can measure elapsed time. Null when no ensemble is
    // active.
    private Double ensembleStartSimTime;

    // Non-null only while a sweep is in flight.
    private Task<BifurcationSweep.Result> bifurcationTask;

    // Non-null only while a basin sweep is in flight — cancelled in cancelFractalIfRunning.
    private Task<FractalBasinSweep.Result> fractalTask;

    // Round 2 §3: moved from simulation.SimulationLoop — see this class's
    // own javadoc. null when the butterfly-effect ensemble isn't active. A
    // plain volatile field, not a queued command: swapping the reference
    // doesn't need to be ordered against an in-flight step the way engine
    // mutation does, and a fully-constructed Ensemble is safely published by
    // the volatile write regardless of which thread creates it (setEnsembleActive
    // runs on the JavaFX thread; onStep reads it from the physics thread).
    private volatile Ensemble ensemble;

    // null when A/B compare isn't active. Same volatile-reference reasoning
    // as `ensemble` above, but this is exactly one engine representing a
    // deliberately, visibly different "B" scenario (not 50 near-identical
    // copies) — see #setCompareActive for how it's built and
    // ui.pendulum.PendulumCanvas#drawCompareChain for how it's drawn
    // distinctly from both the primary and the ensemble ghosts.
    private volatile PhysicsEngine compareEngine;

    public PendulumChaosFeatures(Host host) {
        this.host = host;
        host.simLoop().setStepListener(this);
    }

    /**
     * Steps the ensemble and/or the A/B compare engine alongside whichever
     * primary-engine advance ({@link SimulationLoop}'s manual-step or
     * normal-advance branch) just ran, using the exact same {@code dt}/
     * {@code steps} it used — deliberate for compare: attributing any
     * difference to the deliberate B-vs-A parameter change, not to a
     * different integration schedule. Called once per loop iteration from
     * the physics thread; see {@link StepListener}.
     */
    @Override
    public void onStep(PhysicsEngine primaryEngine, double dt, int steps) {
        Ensemble ens = ensemble; // one volatile read; avoids a null-check race against a concurrent setEnsembleActive()
        if (ens != null) ens.step(dt, steps);

        PhysicsEngine cmp = compareEngine;
        if (cmp != null) for (int i = 0; i < steps; i++) cmp.step(dt);
    }

    /** The active ensemble, or {@code null}. Read each frame by the renderer to draw ghost chains. */
    public Ensemble getEnsemble() { return ensemble; }

    /** The active A/B "B" engine, or {@code null}. Read each frame by the renderer to draw the comparison chain. */
    public PhysicsEngine getCompareEngine() { return compareEngine; }

    /**
     * Nudges every link's angular velocity by an independent tiny random
     * amount — sensitive dependence on initial conditions, demonstrated on
     * demand rather than only by comparing to a separate {@link Ensemble}.
     * Applied as a single {@code SimCommand} (via {@link
     * SimulationLoop#submit}) so it can't be interleaved with an in-flight
     * integration step. Round 2 §3: moved from {@code SimulationLoop#perturb}
     * — it was already exactly this lambda, just called from the wrong class.
     */
    public void perturb(double magnitude) {
        host.simLoop().submit(e -> {
            SimState s = e.getState();
            for (int i = 0; i < s.getN(); i++) {
                double delta = (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * magnitude;
                e.setLinkState(i, s.angles[i], s.angularVelocities[i] + delta);
            }
        });
    }

    /**
     * Turns the butterfly-effect ensemble on or off. Spawning captures
     * whatever the primary engine's live state is <em>right now</em>.
     */
    public void setEnsembleActive(boolean active) {
        if (!active) {
            ensemble = null;
            ensembleStartSimTime = null;
            return;
        }
        SimState current = host.liveState();
        if (current == null) return; // physics thread hasn't published a first state yet
        PendulumConfig currentConfig = host.currentConfig();
        ensemble = new Ensemble(
                currentConfig, current.angles, current.angularVelocities,
                ENSEMBLE_SIZE, ENSEMBLE_EPSILON);
        ensembleStartSimTime = current.time;
    }

    /**
     * Activates or deactivates A/B compare: a single "B" engine, structurally
     * identical to the primary but with link 0's initial angle offset by
     * {@code deltaTheta1Radians}, spawned from the primary's exact current
     * state and stepped alongside it thereafter.
     */
    public void setCompareActive(boolean active, double deltaTheta1Radians) {
        if (!active) {
            compareEngine = null;
            return;
        }
        SimState current = host.liveState();
        if (current == null) return; // physics thread hasn't published a first state yet

        PhysicsEngine built = new PhysicsEngine(host.currentConfig());
        for (int i = 0; i < current.getN(); i++) {
            double offset = (i == 0) ? deltaTheta1Radians : 0.0;
            built.setLinkState(i, current.angles[i] + offset, current.angularVelocities[i]);
        }
        compareEngine = built;
    }

    /** Starts or stops the audio tone. */
    public void setSonifyActive(boolean active) {
        sonifyActive = active;
        if (active) sonifier.start();
        else        sonifier.stop();
    }

    public boolean isSonifyActive() { return sonifyActive; }

    /**
     * Feeds the sonifier a fresh frequency each frame while active — the tip
     * bob's (last link's) speed, the one whose motion already drives the
     * fastest visual, mapped linearly into an audible range.
     */
    public void updateSonifyFrequency(SimState liveState) {
        if (!sonifyActive || liveState == null || liveState.getN() == 0) return;
        double tipOmega = Math.abs(liveState.angularVelocities[liveState.getN() - 1]);
        double hz = 220.0 + Math.min(tipOmega, 10.0) / 10.0 * 660.0;
        sonifier.setFrequency(hz);
    }

    /** Stops the tone and clears the active flag — for {@code onHide}, so a live tone doesn't keep playing after navigating away. */
    public void stopSonifier() {
        sonifier.stop();
        sonifyActive = false;
    }

    /**
     * Runs {@link BifurcationSweep} on a background thread — see its own
     * javadoc for what it computes and why this can't run on the JavaFX
     * thread directly. {@code currentConfig} is captured once up front
     * (deliberately not read live from mid-sweep).
     */
    public void generateBifurcationMap() {
        if (bifurcationTask != null && bifurcationTask.isRunning()) return;

        PendulumConfig base = host.currentConfig();
        host.setBifurcationRunning(true);

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
                host.setBifurcationProgress(newVal.doubleValue()));
        task.setOnSucceeded(e -> {
            BifurcationSweep.Result result = task.getValue();
            host.onBifurcationComplete(result.paramValues(), result.samples());
            host.setBifurcationRunning(false);
            bifurcationTask = null;
        });
        task.setOnFailed(e -> {
            LOG.log(Level.WARNING, "Bifurcation sweep failed", task.getException());
            host.setBifurcationRunning(false);
            bifurcationTask = null;
        });
        task.setOnCancelled(e -> {
            host.setBifurcationRunning(false);
            bifurcationTask = null;
        });

        bifurcationTask = task;
        Thread thread = new Thread(task, "BifurcationSweep");
        thread.setDaemon(true);
        thread.start();
    }

    /** Cancels an in-flight sweep, if any — for {@code onHide}, so navigating away doesn't leave a background thread computing a diagram nobody can see. */
    public void cancelBifurcationIfRunning() {
        if (bifurcationTask != null) bifurcationTask.cancel();
    }

    /**
     * Runs {@link FractalBasinSweep} on a background thread and hands the
     * finished grid to the graph. Round 3 — ported from {@code
     * feature/manual-and-bugfixes}, same shape as {@link
     * #generateBifurcationMap}.
     *
     * <p>Unlike every other analysis here, this one ignores the live chain
     * entirely: the basin fractal is defined for a two-link pendulum
     * released from rest, so it always simulates that regardless of the
     * current N. Only gravity is taken from the live engine, so the picture
     * still reflects the world the user has set up.
     *
     * <p>{@code onProgress} fires from worker threads (the sweep is
     * parallel), so it is marshalled onto the JavaFX thread with {@link
     * Platform#runLater} before touching the progress bar.
     */
    public void generateBasinFractal() {
        if (fractalTask != null && fractalTask.isRunning()) return;

        double gravity = host.simLoop().currentEngine().getGravity();
        host.setFractalRunning(true);

        Task<FractalBasinSweep.Result> task = new Task<>() {
            @Override
            protected FractalBasinSweep.Result call() {
                return FractalBasinSweep.sweep(
                        gravity, FRACTAL_RESOLUTION, FRACTAL_MAX_SECONDS,
                        frac -> Platform.runLater(() -> host.setFractalProgress(frac)),
                        this::isCancelled);
            }
        };
        task.setOnSucceeded(e -> {
            FractalBasinSweep.Result result = task.getValue();
            host.onFractalComplete(result.timeToFlip(), result.maxSeconds());
            host.setFractalRunning(false);
            fractalTask = null;
        });
        task.setOnFailed(e -> {
            LOG.log(Level.WARNING, "Basin fractal sweep failed", task.getException());
            host.setFractalRunning(false);
            fractalTask = null;
        });
        task.setOnCancelled(e -> {
            host.setFractalRunning(false);
            fractalTask = null;
        });

        fractalTask = task;
        Thread thread = new Thread(task, "FractalBasinSweep");
        thread.setDaemon(true);
        thread.start();
    }

    /** Cancels an in-flight basin sweep, if any — for {@code onHide}, same reasoning as {@link #cancelBifurcationIfRunning}. */
    public void cancelFractalIfRunning() {
        if (fractalTask != null) fractalTask.cancel();
    }

    /**
     * Runs each {@link IntegratorType} from the primary's exact current
     * state for {@link #COMPARISON_DURATION_SECONDS}, using temporary
     * engine instances that never touch the live simulation, and plots the
     * resulting energy drift as {@link PendulumGraphPanel.ComparisonSeries}.
     */
    public void compareIntegrators() {
        SimState current = host.liveState();
        if (current == null) return;
        PendulumConfig currentConfig = host.currentConfig();

        // Same scope-channel triad as everywhere else in this palette
        // (magenta/cyan/yellow).
        Color[] colors = { Color.web("#EA3F8C"), Color.web("#3DDCC7"), Color.web("#E8D34A") };
        List<PendulumGraphPanel.ComparisonSeries> series = new ArrayList<>();
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

            series.add(new PendulumGraphPanel.ComparisonSeries(type.toString(), times, drift, colors[colorIdx++ % colors.length]));
        }

        host.graphPanel().setComparisonData(series);
        host.graphPanel().setMode(PendulumGraphPanel.Mode.COMPARISON);
    }

    /**
     * Simplified two-trajectory Lyapunov estimate: lambda = ln(separation /
     * epsilon) / elapsed, using link 0's angular separation between the
     * primary and one ensemble member. Returns null whenever there's
     * nothing to measure yet (no ensemble, too little elapsed time, or
     * separation not yet above floating-point noise).
     */
    public Double estimateLyapunov(SimState liveState, List<SimState> ghosts) {
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
}
