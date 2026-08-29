package ui.pendulum;

import audio.Sonifier;
import physics.BifurcationSweep;
import physics.PendulumConfig;
import physics.PhysicsEngine;
import physics.SimState;
import physics.integrator.Integrator;
import physics.integrator.IntegratorType;
import simulation.Ensemble;
import simulation.SimulationLoop;
import javafx.concurrent.Task;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Butterfly-effect ensemble, A/B compare, sonification, bifurcation sweep,
 * and integrator-comparison orchestration for the pendulum screen. Moved
 * out of {@code controller.SimulationController} — see round 1 §11 of the
 * UI restructuring plan.
 */
public final class PendulumChaosFeatures {

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

    /** What this class needs from its host screen — implemented by {@code controller.SimulationController}. */
    public interface Host {
        PendulumConfig currentConfig();
        SimState liveState();
        SimulationLoop simLoop();
        PendulumGraphPanel graphPanel();

        void setEnsembleVisual(boolean active);
        void setCompareVisual(boolean active);
        void setBifurcationRunning(boolean running);
        void setBifurcationProgress(double fraction);

        /** Forwards a finished sweep's results to the graph (data + mode) and the sidebar (selects the Bifurcation Map toggle). */
        void onBifurcationComplete(double[] params, List<double[]> samples);
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

    public PendulumChaosFeatures(Host host) {
        this.host = host;
    }

    /**
     * Turns the butterfly-effect ensemble on or off. Spawning captures
     * whatever the primary engine's live state is <em>right now</em>.
     */
    public void setEnsembleActive(boolean active) {
        if (!active) {
            host.simLoop().setEnsemble(null);
            ensembleStartSimTime = null;
            return;
        }
        SimState current = host.liveState();
        if (current == null) return; // physics thread hasn't published a first state yet
        PendulumConfig currentConfig = host.currentConfig();
        host.simLoop().setEnsemble(new Ensemble(
                currentConfig, current.angles, current.angularVelocities,
                ENSEMBLE_SIZE, ENSEMBLE_EPSILON));
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
            host.simLoop().setCompareEngine(null);
            return;
        }
        SimState current = host.liveState();
        if (current == null) return; // physics thread hasn't published a first state yet

        PhysicsEngine compareEngine = new PhysicsEngine(host.currentConfig());
        for (int i = 0; i < current.getN(); i++) {
            double offset = (i == 0) ? deltaTheta1Radians : 0.0;
            compareEngine.setLinkState(i, current.angles[i] + offset, current.angularVelocities[i]);
        }
        host.simLoop().setCompareEngine(compareEngine);
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
