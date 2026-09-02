package simulation;

import simulation.command.EngineRebuilder;
import simulation.command.SimCommand;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the physics thread: a fixed-timestep loop decoupled from the render
 * rate, exchanging state with the JavaFX thread lock-free via {@link
 * StateBuffer}.
 *
 * <p><b>Round 2 §2 of the physics-layer modularity pass.</b> Generalized
 * over {@code E extends SimulationEngine<S>} — everything below is engine-
 * agnostic scheduling (drain commands, measure wall time, subdivide into
 * fixed steps, publish), unchanged in behavior from the pendulum-only
 * version. What moved OUT, because it wasn't actually generic:
 * <ul>
 *   <li>{@code getGravity}/{@code setGravity}/{@code getGravityAngle}/
 *       {@code setGravityAngle} — gravity-as-a-scalar is a pendulum concept
 *       (an n-body simulation's "gravity" emerges from pairwise mass
 *       interaction; a smoke simulation likely has no single such knob at
 *       all). Callers now use {@link #submit} directly, e.g. {@code
 *       simLoop.submit(e -> e.setGravity(g))} — already fully expressible
 *       via the generic command channel — and {@link #currentEngine()} for
 *       reads that need the concrete engine type.</li>
 *   <li>{@code setIntegrator(Integrator)} — this one wasn't pendulum-only in
 *       spirit (an n-body engine could plausibly swap integrators the same
 *       way), but it isn't expressible against the generic bound either:
 *       {@code SimulationEngine<S>} has no {@code setIntegrator}, only a
 *       concrete engine like {@code PhysicsEngine} does. It was already a
 *       one-line {@code submit(e -> e.setIntegrator(integrator))} wrapper
 *       internally, so callers now write that lambda directly, same as
 *       gravity above.</li>
 *   <li>{@code rebuildWithConfig(PendulumConfig)} — inlined at its one call
 *       site as {@code submitRebuild(old -> new PhysicsEngine(newConfig))},
 *       which is exactly what it did internally.</li>
 *   <li>The {@code ensemble}/{@code compareEngine} fields and their inline
 *       per-iteration stepping — genuinely pendulum-specific orchestration
 *       that ran synchronized with the primary engine's own stepping. See
 *       {@link StepListener}, the narrow generic hook that replaces the
 *       inline calls; {@code ui.pendulum.PendulumChaosFeatures} now owns
 *       both fields and implements that interface.</li>
 * </ul>
 *
 * <p>Mutation from the JavaFX thread happens through three channels, kept
 * deliberately distinct:
 * <ul>
 *   <li><b>{@link SimCommand}s</b> (via {@link #submit}) — anything that
 *       mutates the current engine in place (gravity, reset, a dragged
 *       link's angle). Applied at the top of a loop iteration, so never
 *       interleaved with a partially-computed integration step.</li>
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
 *
 * @param <E> the concrete engine type this loop steps
 * @param <S> the state snapshot type {@code E} publishes
 */
public final class SimulationLoop<E extends SimulationEngine<S>, S> implements Runnable {

    private static final Logger LOG = Logger.getLogger(SimulationLoop.class.getName());

    // Default only — pendulum-appropriate (a ~1-2s period). Not a shared
    // constant any more: see #fixedDt and the n-body implementation spec
    // §3. A hardcoded 0.002s was safe as long as exactly one engine type
    // (the pendulum) ever used this loop; an orbital-timescale engine
    // subdividing a multi-year simulated frame into 0.002s steps would need
    // on the order of ten million RK4 evaluations per rendered frame —
    // completely unworkable. fixedDt is now a per-instance field so each
    // engine type can pick a step size matched to its own timescale, while
    // this constant stays as the pendulum's zero-code-change default.
    private static final double DEFAULT_FIXED_DT = 0.002;
    private static final double MAX_WALL_DT = 0.1;

    /**
     * The fixed integration step size {@link #run} subdivides every advance
     * into, in the engine's own simulated-time units. Per-instance, not a
     * shared constant — see {@link #DEFAULT_FIXED_DT}'s javadoc for why one
     * size never fit every engine type, once a second one (orbital-scale
     * n-body dynamics) actually existed to test that assumption against.
     */
    private final double fixedDt;
    private final StateBuffer<S> buffer;
    private final Queue<SimCommand<E>>     commandQueue = new ConcurrentLinkedQueue<>();
    private final Queue<EngineRebuilder<E>> rebuildQueue = new ConcurrentLinkedQueue<>();

    // Not final: an EngineRebuilder replaces this reference outright, only
    // ever from the physics thread (see drainRebuilds()). volatile because
    // currentEngine() below reads it from the JavaFX thread — without that,
    // the Java Memory Model gives no guarantee the new reference (or the
    // engine it points to) is even visible cross-thread after a rebuild.
    private volatile E engine;

    private volatile boolean running = false;
    private volatile boolean paused  = false;
    private volatile double  speed;

    // A loop-scheduling parameter in the same sense as `speed` above (not
    // physics state), so it's a plain volatile read fresh each iteration
    // rather than a queued SimCommand — see #setTimeReversed.
    private volatile boolean timeReversed = false;

    // null when nothing needs to ride alongside the primary engine's own
    // stepping — see the class javadoc and StepListener's own. A plain
    // volatile field, not a queued command: swapping the reference doesn't
    // need to be ordered against an in-flight step the way engine mutation
    // does, and a fully-constructed listener is safely published by the
    // volatile write regardless of which thread creates it.
    private volatile StepListener<E> stepListener;

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
     *
     * @param initialSpeed the starting speed multiplier (the pendulum screen
     *                      passes {@code PendulumConfig#getSpeedMultiplier()};
     *                      a future simulation type supplies its own default)
     */
    public SimulationLoop(E engine, StateBuffer<S> buffer, double initialSpeed) {
        this(engine, buffer, initialSpeed, DEFAULT_FIXED_DT); // unchanged default — pendulum call sites untouched
    }

    /**
     * Same as {@link #SimulationLoop(SimulationEngine, StateBuffer, double)},
     * with an explicit fixed-step size instead of the pendulum-appropriate
     * default. See {@link #fixedDt}'s field javadoc — an engine operating on
     * a wildly different timescale (e.g. {@code physics.nbody.NBodyEngine}'s
     * orbital mechanics) needs its own step size, or the fixed-step
     * subdivision in {@link #run} becomes either meaninglessly coarse or
     * (far more likely for a much larger natural timescale) requires
     * millions of integrator evaluations per rendered frame.
     *
     * @param fixedDt the fixed integration step size, in the engine's own
     *                simulated-time units (seconds); must be positive
     */
    public SimulationLoop(E engine, StateBuffer<S> buffer, double initialSpeed, double fixedDt) {
        this.engine  = engine;
        this.buffer  = buffer;
        this.speed   = initialSpeed;
        this.fixedDt = fixedDt;
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
     * of the live engine's dynamics via negative {@code dt} (see {@code
     * PhysicsEngine#step}), not a replay of recorded states the way {@code
     * HistoryBuffer} scrubbing is. Deliberately distinct from that feature:
     * this keeps advancing the <em>real</em> engine (so dragging a bob,
     * changing gravity, etc. all still work while reversed), and — because
     * RK4 isn't time-symmetric and a chaotic N&ge;2 chain is sensitive to
     * the tiny numerical error each step accumulates — running it forward
     * and then backward for the same number of steps will <em>not</em> land
     * exactly back where it started except for the simplest (e.g. N=1)
     * configurations. That mismatch is itself the demonstration: it's a
     * visible illustration of both numerical irreversibility and chaotic
     * sensitivity, not a bug to hide.
     */
    public void setTimeReversed(boolean reversed) { this.timeReversed = reversed; }

    /** Whether the simulation is currently integrating backward. */
    public boolean isTimeReversed()               { return timeReversed; }

    /**
     * The engine currently live, read fresh (safe cross-thread — see the
     * field's javadoc). For reads/writes needing the concrete engine type
     * beyond what {@link SimulationEngine} exposes (e.g. {@code
     * PhysicsEngine#getGravity}) — mutation should still go through {@link
     * #submit} so it lands between steps, never mid-step.
     */
    public E currentEngine() { return engine; }

    /** Queues a mutation for the physics thread to apply before its next step. See {@link SimCommand}. */
    public void submit(SimCommand<E> command) { commandQueue.add(command); }

    /** Queues a structural engine replacement. See {@link EngineRebuilder}. */
    public void submitRebuild(EngineRebuilder<E> rebuilder) { rebuildQueue.add(rebuilder); }

    /**
     * Queues a reset to the engine's configured initial state. Unlike
     * gravity/integrator, this needs no engine-specific access — {@code
     * reset()} is part of {@link SimulationEngine} itself — so it stays
     * here as a convenience rather than pushing every caller to write
     * {@code submit(e -> e.reset())} by hand. Round 2 §2: replaces the old
     * {@code simulation.command.ResetCommand}, which existed only to do
     * exactly this and is now dead code with the interface in hand.
     */
    public void reset() { submit(SimulationEngine::reset); }

    /**
     * Queues exactly one step of {@link #fixedDt}, applied on the next loop
     * iteration regardless of {@link #paused} — frame-stepping is
     * specifically meant to work <em>while</em> paused. Calling this
     * repeatedly while already running just blends into the normal
     * per-frame stepping; there's no special case for that, since skipping
     * one iteration's worth of wall-clock time (~1ms) is imperceptible.
     */
    public void stepOnce() { pendingManualSteps.incrementAndGet(); }

    /**
     * Installs (or, given {@code null}, removes) the hook called once per
     * loop iteration alongside the primary engine's own stepping. See
     * {@link StepListener}'s javadoc for why this exists instead of a
     * generalized "ensemble" feature in this class.
     */
    public void setStepListener(StepListener<E> listener) { this.stepListener = listener; }

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
     *       subdivided into fixed {@link #fixedDt} steps.</li>
     *   <li><b>Publish</b> the new state for the renderer.</li>
     * </ol>
     *
     * <p><b>Why fixed-timestep subdivision instead of one variable step?</b>
     * Numerical integrators become inaccurate — and eventually unstable —
     * as the step grows. Rather than take one big step when a frame runs
     * long, the loop takes several small ones of a known-good size. This
     * keeps accuracy independent of machine speed and frame timing. (A
     * fixed-timestep loop is a reasonable default for any of this engine's
     * planned simulation types, including a PDE solver picking its own
     * internal substeps inside one {@code step()} call.)
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
                double stepDt = direction * fixedDt;
                for (int i = 0; i < manualSteps; i++) engine.step(stepDt);

                StepListener<E> listener = stepListener; // one volatile read; avoids a null-check race against a concurrent setStepListener()
                if (listener != null) listener.onStep(engine, stepDt, manualSteps);

                buffer.write(engine.getState());
            } else if (!paused && wallDt > 0) {
                // (3) Normal advance. Convert real time to simulated time via
                // the speed multiplier, then subdivide into however many
                // fixed-size steps that needs — this is what keeps accuracy
                // independent of frame rate. abs() because simDt is negative
                // when running in reverse, and a step COUNT must be positive.
                double simDt = direction * wallDt * speed;
                int    steps = Math.max(1, (int) Math.ceil(Math.abs(simDt) / fixedDt));
                double dt    = simDt / steps;
                for (int i = 0; i < steps; i++) engine.step(dt);

                StepListener<E> listener = stepListener;
                if (listener != null) listener.onStep(engine, dt, steps);

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
        EngineRebuilder<E> rebuilder;
        while ((rebuilder = rebuildQueue.poll()) != null) {
            try {
                E rebuilt = rebuilder.rebuild(engine);
                LOG.fine("Engine rebuilt"); // per-engine detail (N, etc.) is a concern for the caller, which already knows its own concrete type
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
        SimCommand<E> cmd;
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
