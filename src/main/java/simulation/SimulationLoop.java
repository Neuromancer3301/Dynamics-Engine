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

    // null when the butterfly-effect ensemble isn't active. A plain
    // volatile field, not a queued command: swapping the reference doesn't
    // need to be ordered against an in-flight RK4 step the way engine
    // mutation does, and a fully-constructed Ensemble is safely published
    // by the volatile write regardless of which thread creates it.
    private volatile Ensemble ensemble;

    // Frame-stepping: incremented from the JavaFX thread (a button click or
    // the -> shortcut), drained and reset from the physics thread. Atomic
    // rather than a plain volatile int because both the increment and the
    // drain-and-reset are two-step operations — a bare volatile could lose
    // an increment under a rare race; an AtomicInteger can't.
    private final AtomicInteger pendingManualSteps = new AtomicInteger(0);

    private Thread thread;

    public SimulationLoop(PendulumConfig config, StateBuffer buffer) {
        this.config = config;
        this.engine = new PhysicsEngine(config);
        this.buffer = buffer;
        this.speed  = config.getSpeedMultiplier();
        buffer.write(engine.getState());
    }

    public void start() {
        running = true;
        thread  = new Thread(this, "PhysicsThread");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() { running = false; if (thread != null) thread.interrupt(); }

    public void setPaused(boolean paused)       { this.paused = paused; }
    public boolean isPaused()                   { return paused; }
    public void setSpeedMultiplier(double s)    { this.speed  = Math.max(0.01, s); }
    public double getSpeedMultiplier()          { return speed; }

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

    public void setGravity(double g) { submit(e -> e.setGravity(g)); }
    public void reset()              { submit(new ResetCommand()); }

    /** Swaps the active integration strategy — see {@link Integrator}. A {@code SimCommand}: mutates the current engine, no rebuild needed. */
    public void setIntegrator(Integrator integrator) { submit(e -> e.setIntegrator(integrator)); }

    /** Activates the butterfly-effect ensemble; {@code null} deactivates it. See {@link Ensemble}. */
    public void setEnsemble(Ensemble ensemble) { this.ensemble = ensemble; }
    public Ensemble getEnsemble()              { return ensemble; }

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

    @Override
    public void run() {
        long lastNanos = System.nanoTime();
        while (running) {
            boolean changed = drainRebuilds();
            changed |= drainCommands();
            if (changed) buffer.write(engine.getState());

            long now      = System.nanoTime();
            double wallDt = Math.min((now - lastNanos) / 1_000_000_000.0, MAX_WALL_DT);
            lastNanos     = now;

            int manualSteps = pendingManualSteps.getAndSet(0);
            if (manualSteps > 0) {
                for (int i = 0; i < manualSteps; i++) engine.step(FIXED_DT);

                Ensemble ens = ensemble;
                if (ens != null) ens.step(FIXED_DT, manualSteps);

                buffer.write(engine.getState());
            } else if (!paused && wallDt > 0) {
                double simDt = wallDt * speed;
                int    steps = Math.max(1, (int) Math.ceil(simDt / FIXED_DT));
                double dt    = simDt / steps;
                for (int i = 0; i < steps; i++) engine.step(dt);

                Ensemble ens = ensemble; // one volatile read; avoids a null-check race against a concurrent setEnsemble()
                if (ens != null) ens.step(dt, steps);

                buffer.write(engine.getState());
            }
            try { Thread.sleep(1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    /** Applies every queued rebuild, in order. Returns true if at least one was applied. */
    private boolean drainRebuilds() {
        boolean any = false;
        EngineRebuilder rebuilder;
        while ((rebuilder = rebuildQueue.poll()) != null) {
            engine = rebuilder.rebuild(engine);
            any = true;
        }
        return any;
    }

    /** Applies every queued command. Returns true if at least one was applied, so callers know to publish state. */
    private boolean drainCommands() {
        boolean any = false;
        SimCommand cmd;
        while ((cmd = commandQueue.poll()) != null) {
            cmd.apply(engine);
            any = true;
        }
        return any;
    }
}