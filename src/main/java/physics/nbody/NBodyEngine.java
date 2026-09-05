package physics.nbody;

import physics.integrator.Integrator;
import physics.integrator.IntegratorType;
import simulation.SimulationEngine;

/**
 * Softened Newtonian n-body gravity, integrated by a swappable {@link
 * Integrator} strategy (defaulting to RK4) — the n-body analogue of {@code
 * physics.PhysicsEngine}.
 *
 * <p><b>State packing — read this before touching {@link #step} or {@link
 * #derivative}.</b> The flat state vector has length {@code 4n}, interleaved
 * by body:
 * <pre>
 * positions:  state[2i]            = x_i,  state[2i+1]            = y_i   for i in [0, n)
 * velocities: state[halfLen + 2i]  = vx_i, state[halfLen + 2i + 1] = vy_i  where halfLen = 2n
 * </pre>
 * {@link Integrator#step}'s {@code n} parameter is <b>not</b> body count —
 * it's {@code halfLen} (the position/velocity boundary), confirmed by
 * reading all three implementations directly: {@code Rk4Integrator} never
 * even reads it (loops over {@code state.length}), but {@code
 * SymplecticEulerIntegrator}/{@code VelocityVerletIntegrator} use it to pair
 * position-half index {@code i} with velocity-half index {@code n + i} — so
 * passing body count instead of {@code halfLen} would silently leave the
 * back half of each half updated and the front half frozen the moment
 * either of those two integrators is selected. {@link IntegratorType#create}
 * is a separate conversion: it sizes scratch buffers to {@code
 * state.length}, i.e. {@code 4n}, not {@code 2n}.
 *
 * <p>Worked example: N=10 bodies → {@code state.length == 40}. Construct
 * with {@code IntegratorType.RK4.create(40)}; step with {@code
 * integrator.step(state, dt, this::derivative, 20)}.
 *
 * <p><b>Zero heap allocation per {@link #step}</b> — the same discipline
 * {@code PhysicsEngine} was rewritten for. This engine is actually simpler
 * than the pendulum's: no mass matrix, no linear solve, no scratch beyond
 * the state/out arrays the integrator already owns — {@link #derivative}
 * writes the free (velocity) half straight from the input state and
 * accumulates the accelerations directly into the integrator-supplied
 * output buffer.
 */
public final class NBodyEngine implements SimulationEngine<NBodyState> {

    // Sanity backstop, not a normal operating bound — see the n-body
    // implementation spec §3. simulation.SimulationLoop's own per-instance
    // fixedDt (typically ~3600s for this engine) is what actually keeps a
    // rendered frame's worth of stepping tractable; this just prevents one
    // single pathological dt (e.g. a stalled frame) from destabilizing the
    // integrator outright.
    private static final double MAX_ABS_DT = 86400.0; // 1 day

    private final int n;
    private final double[] mass, radius;
    private final String[] name;
    private final double softeningLength;
    private volatile double gravitationalConstant;

    // The scene's initial state, copied once at construction so reset() has
    // something to return to — mirrors PhysicsEngine keeping initAngles
    // separately from the live, mutating state array.
    private final double[] initPositionX, initPositionY, initVelocityX, initVelocityY;

    // Flat state vector, length 4n — see the class javadoc for the packing.
    private final double[] state;
    private double time;

    // Not final: setIntegrator() swaps this out, applied only on the physics
    // thread (see simulation.SimulationLoop). Read from step(), also only
    // ever called on the physics thread.
    private Integrator integrator;

    /**
     * Builds an engine for one specific scene. Everything that cannot change
     * without rebuilding (N, mass, radius, softening length) is copied in
     * here; everything that can change at runtime (G, the integrator) has a
     * setter.
     */
    public NBodyEngine(NBodyConfig cfg) {
        this.n               = cfg.getN();
        this.mass            = cfg.getMasses();
        this.radius          = cfg.getRadii();
        this.name            = cfg.getNames();
        this.softeningLength = cfg.getSofteningLength();
        this.gravitationalConstant = cfg.getGravitationalConstant();

        this.initPositionX = cfg.getPositionsX();
        this.initPositionY = cfg.getPositionsY();
        this.initVelocityX = cfg.getVelocitiesX();
        this.initVelocityY = cfg.getVelocitiesY();

        // 4n: positions (2n) + velocities (2n) — see the class javadoc.
        state = new double[4 * n];
        resetState();

        // IntegratorType.create sizes scratch buffers to state.length (4n),
        // not the 2n an unwary reader of "n-link chain" doc comments
        // elsewhere in this codebase might assume — see the class javadoc.
        integrator = IntegratorType.RK4.create(4 * n);
    }

    /** Sets the gravitational constant. Clamped to a tiny positive floor — see {@link NBodyConfig#setGravitationalConstant}'s identical reasoning. */
    public void setGravitationalConstant(double g) { this.gravitationalConstant = Math.max(1.0e-15, g); }

    /** Current gravitational constant, in m³·kg⁻¹·s⁻². */
    public double getGravitationalConstant() { return gravitationalConstant; }

    /** Swaps the integration strategy. Safe mid-simulation — each {@link Integrator} owns its own scratch, sized at construction. */
    public void setIntegrator(Integrator integrator) { this.integrator = integrator; }

    /** The integration strategy currently in use. */
    public Integrator getIntegrator() { return integrator; }

    /** Number of bodies in this scene. */
    public int getN() { return n; }

    /** Returns every body to its configured initial position/velocity and rewinds the clock. Invoked via {@code SimulationLoop#reset}. */
    @Override
    public void reset() { resetState(); time = 0.0; }

    /**
     * Directly overwrites one body's position and velocity, bypassing
     * integration entirely — the mechanism behind interactive dragging, the
     * direct analogue of {@code PhysicsEngine#setLinkState}. Silently
     * ignores non-finite input (a stray NaN from a pointer event) rather
     * than corrupting the state vector for every other body sharing it.
     */
    public void setBodyState(int index, double x, double y, double vx, double vy) {
        if (index < 0 || index >= n) return;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(vx) || !Double.isFinite(vy)) return;
        int halfLen = 2 * n;
        state[2 * index]         = x;
        state[2 * index + 1]     = y;
        state[halfLen + 2 * index]     = vx;
        state[halfLen + 2 * index + 1] = vy;
    }

    /**
     * Advances (or, given a negative {@code dt}, reverses) the simulation by
     * one step. Clamped to {@link #MAX_ABS_DT} — a sanity backstop sized for
     * orbital timescales, not the pendulum-appropriate ±0.05s {@code
     * PhysicsEngine.step} uses; copying that verbatim would clamp every
     * orbital-timescale step to a physically meaningless bound (see the
     * n-body implementation spec §2.3/§3). The real per-step size in normal
     * operation is controlled upstream by {@code SimulationLoop}'s
     * per-instance {@code fixedDt}, not by this clamp.
     */
    @Override
    public void step(double dt) {
        dt = Math.max(-MAX_ABS_DT, Math.min(dt, MAX_ABS_DT));
        // n-parameter here is halfLen (2n), the position/velocity boundary
        // — NOT body count. See the class javadoc's warning.
        integrator.step(state, dt, this::derivative, 2 * n);
        time += dt;
    }

    /**
     * Produces an immutable snapshot of everything the rest of the app needs
     * to draw or analyse this scene. Two computations happen here beyond
     * unpacking the flat state vector:
     *
     * <p><b>Kinetic energy</b> — the ordinary {@code KE = ½·m·v²} summed over
     * every body; no coupling terms (unlike the pendulum's mass matrix —
     * each body's kinetic energy genuinely only depends on its own
     * velocity).
     *
     * <p><b>Potential energy</b> — {@code PE = -G·Σ_{i<j} m_i·m_j / |r_j -
     * r_i|}, the standard gravitational potential summed once per pair
     * (softened by the same ε used in {@link #derivative}, so PE and the
     * force it's derived from stay consistent). Negative, by the usual
     * convention that a bound system has negative total energy.
     */
    @Override
    public NBodyState getState() {
        double[] posX = new double[n], posY = new double[n];
        double[] velX = new double[n], velY = new double[n];
        int halfLen = 2 * n;
        for (int i = 0; i < n; i++) {
            posX[i] = state[2 * i];
            posY[i] = state[2 * i + 1];
            velX[i] = state[halfLen + 2 * i];
            velY[i] = state[halfLen + 2 * i + 1];
        }

        double ke = 0;
        for (int i = 0; i < n; i++) {
            double v2 = velX[i] * velX[i] + velY[i] * velY[i];
            ke += 0.5 * mass[i] * v2;
        }

        double eps2 = softeningLength * softeningLength;
        double g = gravitationalConstant;
        double pe = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < i; j++) {
                double dx = posX[j] - posX[i], dy = posY[j] - posY[i];
                double dist = Math.sqrt(dx * dx + dy * dy + eps2);
                pe -= g * mass[i] * mass[j] / dist;
            }
        }

        return new NBodyState(time, posX, posY, velX, velY, mass, radius, name, Math.max(0, ke), pe);
    }

    /** Simulation time in seconds since the last reset. */
    @Override
    public double getTime() { return time; }

    /**
     * <b>The heart of the engine.</b> Given a state (all positions + all
     * velocities), returns how fast each of those quantities is currently
     * changing: the velocity half is free (an unknown's derivative is
     * whatever its own paired velocity already is), and the acceleration
     * half is softened Newtonian gravity, formula per the n-body
     * implementation spec §2.3 (Appendix A of the grounding document):
     *
     * <pre>    a_i = G · Σ_{j≠i} m_j·(r_j − r_i) / (|r_j − r_i|² + ε²)^(3/2)</pre>
     *
     * <p>Mirrors {@code PhysicsEngine.derivative()}'s own symmetry
     * exploitation — for {@code i}, for {@code j < i}, computed once,
     * applied to both accumulators with Newton's-third-law opposite signs —
     * rather than a naive double loop: same O(N²) either way, but half the
     * divides/sqrt calls. Unlike the pendulum's derivative, this one needs
     * no persistent scratch buffers of its own: the output buffer the
     * integrator supplies (a fresh {@code out} per call, k1/k2/k3/k4/temp
     * etc. depending on which integrator is active) is fully overwritten
     * every call, so it doubles as the accumulator.
     *
     * <p>Passed to the active {@link Integrator} as a method reference — see
     * {@code physics.integrator.DerivativeFunction} for why that's what makes
     * integrators interchangeable: they never see this class's fields.
     */
    private void derivative(double[] s, double[] out) {
        int halfLen = 2 * n;

        // Free half: d(position)/dt = velocity, copied straight across —
        // same idea as the pendulum's d(theta)/dt = omega.
        for (int i = 0; i < halfLen; i++) out[i] = s[halfLen + i];

        // Acceleration half: zero, then accumulate the pairwise sum below.
        for (int i = halfLen; i < 2 * halfLen; i++) out[i] = 0.0;

        double g = gravitationalConstant;
        double eps2 = softeningLength * softeningLength;

        for (int i = 0; i < n; i++) {
            double xi = s[2 * i], yi = s[2 * i + 1];
            for (int j = 0; j < i; j++) {
                double xj = s[2 * j], yj = s[2 * j + 1];
                double dx = xj - xi, dy = yj - yi;
                double dist2 = dx * dx + dy * dy + eps2;
                double denom = dist2 * Math.sqrt(dist2); // (dist2)^1.5
                double gx = g * dx / denom, gy = g * dy / denom;

                out[halfLen + 2 * i]     += mass[j] * gx;
                out[halfLen + 2 * i + 1] += mass[j] * gy;
                out[halfLen + 2 * j]     -= mass[i] * gx;
                out[halfLen + 2 * j + 1] -= mass[i] * gy;
            }
        }

        // Safety net: substitute zero acceleration for a NaN/Infinity from a
        // pathological configuration (e.g. two bodies coincident enough to
        // defeat the softening) rather than letting it propagate into the
        // state vector, where it would poison every subsequent frame.
        for (int i = halfLen; i < 2 * halfLen; i++)
            if (!Double.isFinite(out[i])) out[i] = 0.0;
    }

    /** Restores every body to its configured initial position/velocity, and rewinds the clock. */
    private void resetState() {
        int halfLen = 2 * n;
        for (int i = 0; i < n; i++) {
            state[2 * i]     = initPositionX[i];
            state[2 * i + 1] = initPositionY[i];
            state[halfLen + 2 * i]     = initVelocityX[i];
            state[halfLen + 2 * i + 1] = initVelocityY[i];
        }
        time = 0.0;
    }
}
