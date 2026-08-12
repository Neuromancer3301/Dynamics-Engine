package simulation;

import physics.PhysicsEngine;
import physics.PendulumConfig;
import physics.SimState;
import physics.integrator.Integrator;
import simulation.command.EngineRebuilder;
import simulation.command.ResetCommand;
import simulation.command.SimCommand;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the physics thread: a fixed-timestep RK4 loop decoupled from the
 * render rate, exchanging state with the JavaFX thread lock-free via
 * {@link StateBuffer}.
 *
 * <p>Mutation from the JavaFX thread happens through three channels, kept
 * deliberately distinct:
 * <ul>
 *   <li><b>{@link SimCommand}s</b> (via {@link #submit}) — anything that
 *       mutates the current {@link PhysicsEngine} in place (gravity, reset,
 *       a dragged link's angle). Applied at the top of a loop iteration, so
 *       never interleaved with a partially-computed RK4 step.</li>
 *   <li><b>{@link EngineRebuilder}s</b> (via {@link #submitRebuild}) —
 *       structural changes that replace the engine outright (edited link
 *       count, length, or mass). See its javadoc for why this is a separate
 *       channel from {@code SimCommand}.</li>
 *   <li><b>{@code volatile} fields</b> ({@code paused}, {@code speed}) —
 *       loop-scheduling parameters, not physics state. Read fresh every
 *       iteration, so there is no torn-state risk in applying them
 *       immediately rather than queuing.</li>
 * </ul>
 *
 * <p>Frame-stepping ({@link #stepOnce}) is a fourth, narrower mechanism:
 * an {@link AtomicInteger} counter drained once per loop iteration
 * regardless of {@code paused}, since its entire purpose is to advance the
 * simulation by exactly one step while otherwise paused.
 */
public final class SimulationLoop implements Runnable {

    private static final Logger LOG = Logger.getLogger(SimulationLoop.class.getName());

    private static final double FIXED_DT    = 0.002;
    private static final double MAX_WALL_DT = 0.1;

    private final StateBuffer    buffer;
    private final PendulumConfig config;
    private final Queue<SimCommand>     commandQueue  = new ConcurrentLinkedQueue<>();
    private final Queue<EngineRebuilder> rebuildQueue = new ConcurrentLinkedQueue<>();

    // Not final: an EngineRebuilder replaces this reference outright, only
    // ever from the physics thread (see drainRebuilds()). volatile because
    // getGravity() below reads it from the JavaFX thread — without that,
    // the Java Memory Model gives no guarantee the new reference (or the
    // engine it points to) is even visible cross-thread after a rebuild.
    private volatile PhysicsEngine engine;

    private volatile boolean running = false;
    private volatile boolean paused  = false;
    private volatile double  speed;

    // A loop-scheduling parameter in the same sense as `speed` above (not
    // physics state), so it's a plain volatile read fresh each iteration
    // rather than a queued SimCommand — see #setTimeReversed.
    private volatile boolean timeReversed = false;

    // null when the butterfly-effect ensemble isn't active. A plain
    // volatile field, not a queued command: swapping the reference doesn't
    // need to be ordered against an in-flight RK4 step the way engine
    // mutation does, and a fully-constructed Ensemble is safely published
    // by the volatile write regardless of which thread creates it.
    private volatile Ensemble ensemble;

    // null when A/B compare isn't active. Same volatile-reference reasoning
    // as `ensemble` above, but this is exactly one engine representing a
    // deliberately, visibly different "B" scenario (not 50 near-identical
    // copies) — see controller.SimulationController#setCompareActive for
    // how it's built and ui.PendulumCanvas#drawCompareChain for how it's
    // drawn distinctly from both the primary and the ensemble ghosts.
    private volatile PhysicsEngine compareEngine;

    // Frame-stepping: incremented from the JavaFX thread (a button click or
    // the -> shortcut), drained and reset from the physics thread. Atomic
    // rather than a plain volatile int because both the increment and the
    // drain-and-reset are two-step operations — a bare volatile could lose
    // an increment under a rare race; an AtomicInteger can't.
    private final AtomicInteger pendingManualSteps = new AtomicInteger(0);

    private Thread thread;

    /**
     * Builds the loop and immediately publishes the engine's initial state,
     * so the UI has something to render before the thread is even started.
     */
    public SimulationLoop(PendulumConfig config, StateBuffer buffer) {
        this.config = config;
        this.engine = new PhysicsEngine(config);
        this.buffer = buffer;
        this.speed  = config.getSpeedMultiplier();
        buffer.write(engine.getState());
    }

    /**
     * Starts the physics thread. Marked <b>daemon</b> so it cannot keep the
     * JVM alive after the window closes — a non-daemon thread spinning in
     * {@link #run} would leave the process running invisibly forever.
     * Named "PhysicsThread" so it is identifiable in a debugger or profiler.
     */
    public void start() {
        running = true;
        thread  = new Thread(this, "PhysicsThread");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Signals the loop to exit and interrupts the thread so it wakes from
     * its {@code Thread.sleep} immediately rather than after the remaining
     * millisecond. Called when leaving the simulation screen.
     */
    public void stop() { running = false; if (thread != null) thread.interrupt(); }

    /** Freezes stepping without stopping the thread; the loop keeps spinning and stays responsive to commands. */
    public void setPaused(boolean paused)       { this.paused = paused; }

    /** Whether stepping is currently frozen — read by the Space-key shortcut to toggle. */
    public boolean isPaused()                   { return paused; }

    /** Sets how fast simulated time advances relative to wall-clock time. Clamped above zero so the loop can never stall. */
    public void setSpeedMultiplier(double s)    { this.speed  = Math.max(0.01, s); }

    /** Current speed multiplier — pulled by the link editor at apply time so a slider change is never silently reverted. */
    public double getSpeedMultiplier()          { return speed; }

    /**
     * Toggles between integrating forward and backward — a genuine reversal
     * of the live {@link PhysicsEngine}'s dynamics via negative {@code dt}
     * (see {@link PhysicsEngine#step}), not a replay of recorded states the
     * way {@code HistoryBuffer} scrubbing is. Deliberately distinct from
     * that feature: this keeps advancing the <em>real</em> engine (so
     * dragging a bob, changing gravity, etc. all still work while reversed),
     * and — because RK4 isn't time-symmetric and a chaotic N&ge;2 chain is
     * sensitive to the tiny numerical error each step accumulates — running
     * it forward and then backward for the same number of steps will
     * <em>not</em> land exactly back where it started except for the
     * simplest (e.g. N=1) configurations. That mismatch is itself the
     * demonstration: it's a visible illustration of both numerical
     * irreversibility and chaotic sensitivity, not a bug to hide.
     */
    public void setTimeReversed(boolean reversed) { this.timeReversed = reversed; }

    /** Whether the simulation is currently integrating backward. */
    public boolean isTimeReversed()               { return timeReversed; }

    /** Current gravity, read from whichever engine is live right now. Safe cross-thread — see the field's javadoc. */
    public double getGravity() { return engine.getGravity(); }

    /** Queues a mutation for the physics thread to apply before its next step. See {@link SimCommand}. */
    public void submit(SimCommand command) { commandQueue.add(command); }

    /** Queues a structural engine replacement. See {@link EngineRebuilder}. */
    public void submitRebuild(EngineRebuilder rebuilder) { rebuildQueue.add(rebuilder); }

    /** Convenience for the common case: replace the engine outright with one built from {@code newConfig}. */
    public void rebuildWithConfig(PendulumConfig newConfig) {
        submitRebuild(old -> new PhysicsEngine(newConfig));
    }

    /** Queues a gravity-magnitude change. Goes through the command queue rather than a direct setter so it lands between steps, never mid-step. */
    public void setGravity(double g) { submit(e -> e.setGravity(g)); }

    /** Queues a gravity-direction change — see {@link PhysicsEngine#setGravityAngle}. */
    public void setGravityAngle(double angle) { submit(e -> e.setGravityAngle(angle)); }

    /** Current gravity direction, read from whichever engine is live right now. Safe cross-thread — see {@link #engine}'s javadoc. */
    public double getGravityAngle() { return engine.getGravityAngle(); }
    /** Queues a reset to the configured initial angles at rest. */
    public void reset()              { submit(new ResetCommand()); }

    /** Swaps the active integration strategy — see {@link Integrator}. A {@code SimCommand}: mutates the current engine, no rebuild needed. */
    public void setIntegrator(Integrator integrator) { submit(e -> e.setIntegrator(integrator)); }

    /** Activates the butterfly-effect ensemble; {@code null} deactivates it. See {@link Ensemble}. */
    public void setEnsemble(Ensemble ensemble) { this.ensemble = ensemble; }

    /** The active ensemble, or {@code null}. Read each frame by the renderer to draw ghost chains. */
    public Ensemble getEnsemble()              { return ensemble; }

    /** Activates A/B compare with a fully-built "B" engine; {@code null} deactivates it. See {@link #compareEngine}. */
    public void setCompareEngine(PhysicsEngine compareEngine) { this.compareEngine = compareEngine; }

    /** The active A/B "B" engine, or {@code null}. Read each frame by the renderer to draw the comparison chain. */
    public PhysicsEngine getCompareEngine()                   { return compareEngine; }

    /**
     * Queues exactly one {@link PhysicsEngine#step} of {@link #FIXED_DT},
     * applied on the next loop iteration regardless of {@link #paused} —
     * frame-stepping is specifically meant to work <em>while</em> paused.
     * Calling this repeatedly while already running just blends into the
     * normal per-frame stepping; there's no special case for that, since
     * skipping one iteration's worth of wall-clock time (~1ms) is imperceptible.
     */
    public void stepOnce() { pendingManualSteps.incrementAndGet(); }

    /**
     * Nudges every link's angular velocity by an independent tiny random
     * amount — sensitive dependence on initial conditions, demonstrated on
     * demand rather than only by comparing to a separate {@link Ensemble}.
     * Applied as a single {@link SimCommand} so it can't be interleaved
     * with an in-flight RK4 step.
     */
    public void perturb(double magnitude) {
        submit(e -> {
            SimState s = e.getState();
            for (int i = 0; i < s.getN(); i++) {
                double delta = (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * magnitude;
                e.setLinkState(i, s.angles[i], s.angularVelocities[i] + delta);
            }
        });
    }

    /**
     * <b>The physics thread's main loop.</b> Runs continuously from {@link
     * #start} until {@link #stop}, and is the only place the engine is ever
     * advanced or mutated.
     *
     * <p><b>Each iteration does four things, in this order:</b>
     * <ol>
     *   <li><b>Drain the queues</b> — apply any structural rebuilds, then
     *       any pending commands. Doing this at the TOP of the iteration is
     *       what guarantees a mutation can never land midway through a
     *       partially-computed integration step.</li>
     *   <li><b>Measure elapsed wall-clock time</b> since the last iteration,
     *       capped at {@link #MAX_WALL_DT}.</li>
     *   <li><b>Advance the simulation</b> by that much simulated time,
     *       subdivided into fixed {@link #FIXED_DT} steps.</li>
     *   <li><b>Publish</b> the new state for the renderer.</li>
     * </ol>
     *
     * <p><b>Why fixed-timestep subdivision instead of one variable step?</b>
     * Numerical integrators become inaccurate — and eventually unstable —
     * as the step grows. Rather than take one big step when a frame runs
     * long, the loop takes several small ones of a known-good size. This
     * keeps accuracy independent of machine speed and frame timing.
     *
     * <p><b>Why {@code MAX_WALL_DT} exists.</b> If the app is paused by the
     * OS, or the window is dragged, or a breakpoint is hit, the elapsed
     * time could be many seconds. Without the cap the loop would try to
     * catch up all at once, freezing the app — the classic "spiral of
     * death". Capping it means the simulation simply loses that time
     * instead, which is the right trade.
     *
     * <p>The trailing {@code Thread.sleep(1)} yields the CPU so this loop
     * does not spin at 100% on one core.
     */
    @Override
    public void run() {
        long lastNanos = System.nanoTime();
        while (running) {
            // (1) Apply queued mutations BEFORE stepping — never mid-step.
            boolean changed = drainRebuilds();
            changed |= drainCommands();
            if (changed) buffer.write(engine.getState()); // publish so the UI sees the edit immediately, even while paused

            // (2) How much real time passed? Capped to avoid a catch-up spiral.
            long now      = System.nanoTime();
            double wallDt = Math.min((now - lastNanos) / 1_000_000_000.0, MAX_WALL_DT);
            lastNanos     = now;

            double direction = timeReversed ? -1.0 : 1.0;

            int manualSteps = pendingManualSteps.getAndSet(0);
            if (manualSteps > 0) {
                double stepDt = direction * FIXED_DT;
                for (int i = 0; i < manualSteps; i++) engine.step(stepDt);

                Ensemble ens = ensemble;
                if (ens != null) ens.step(stepDt, manualSteps);

                PhysicsEngine cmp = compareEngine;
                if (cmp != null) for (int i = 0; i < manualSteps; i++) cmp.step(stepDt);

                buffer.write(engine.getState());
            } else if (!paused && wallDt > 0) {
                // (3) Normal advance. Convert real time to simulated time via
                // the speed multiplier, then subdivide into however many
                // fixed-size steps that needs — this is what keeps accuracy
                // independent of frame rate. abs() because simDt is negative
                // when running in reverse, and a step COUNT must be positive.
                double simDt = direction * wallDt * speed;
                int    steps = Math.max(1, (int) Math.ceil(Math.abs(simDt) / FIXED_DT));
                double dt    = simDt / steps;
                for (int i = 0; i < steps; i++) engine.step(dt);

                Ensemble ens = ensemble; // one volatile read; avoids a null-check race against a concurrent setEnsemble()
                if (ens != null) ens.step(dt, steps);

                // Same dt/step-count as the primary, deliberately: the whole
                // point of A/B compare is attributing any difference to the
                // deliberate B-vs-A parameter change, not to a different
                // integration schedule.
                PhysicsEngine cmp = compareEngine;
                if (cmp != null) for (int i = 0; i < steps; i++) cmp.step(dt);

                buffer.write(engine.getState());
            }
            try { Thread.sleep(1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    /**
     * Applies every queued rebuild, in order. Returns true if at least one
     * was applied. A rebuild that throws is logged and skipped rather than
     * propagating — an uncaught exception here would silently kill this
     * daemon thread, after which the UI would just... stop updating, with
     * no indication why. That failure mode is worse than losing one
     * structural edit.
     */
    private boolean drainRebuilds() {
        boolean any = false;
        EngineRebuilder rebuilder;
        while ((rebuilder = rebuildQueue.poll()) != null) {
            try {
                PhysicsEngine rebuilt = rebuilder.rebuild(engine);
                LOG.info(() -> "Engine rebuilt: N=" + rebuilt.getState().getN());
                engine = rebuilt;
                any = true;
            } catch (RuntimeException ex) {
                LOG.log(Level.SEVERE, "EngineRebuilder threw; keeping the previous engine", ex);
            }
        }
        return any;
    }

    /** Applies every queued command. Returns true if at least one was applied, so callers know to publish state. Same fail-safe reasoning as {@link #drainRebuilds}. */
    private boolean drainCommands() {
        boolean any = false;
        SimCommand cmd;
        while ((cmd = commandQueue.poll()) != null) {
            try {
                cmd.apply(engine);
                any = true;
            } catch (RuntimeException ex) {
                LOG.log(Level.SEVERE, "SimCommand threw; skipping it", ex);
            }
        }
        return any;
    }
}