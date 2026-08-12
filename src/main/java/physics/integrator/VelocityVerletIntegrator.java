package physics.integrator;

/**
 * Velocity Verlet, generalized to this system's velocity-<em>dependent</em>
 * acceleration (the centrifugal terms in the N-pendulum Lagrangian couple
 * every link's angular velocity into every other link's derivative).
 *
 * <p><b>Important caveat, stated plainly because it matters for how to read
 * this integrator's results:</b> classic Velocity Verlet is derived for
 * {@code a(x)} — acceleration depending on position only — where it is
 * genuinely symplectic and time-reversible. This system's acceleration is
 * {@code a(theta, omega)}, so the textbook derivation doesn't strictly
 * apply. What's implemented here is the standard practical extension: the
 * corrector step evaluates the new acceleration at the new position using
 * the <em>old</em> velocity as an approximation, then updates velocity from
 * the average of the two accelerations. It is a legitimate, commonly used
 * integrator — just not one with Verlet's usual rigorous energy-conservation
 * guarantee for this specific class of system. That gap is exactly why it's
 * worth comparing against RK4 rather than assuming it behaves identically.
 */
public final class VelocityVerletIntegrator implements Integrator {

    // Two slope buffers (start and end of the step) plus one scratch state.
    private final double[] derivAtOld, tempState, derivAtNew;

    /** @param stateSize length of the state vector this integrator will step (2n for an n-link chain). */
    public VelocityVerletIntegrator(int stateSize) {
        derivAtOld = new double[stateSize];
        tempState  = new double[stateSize];
        derivAtNew = new double[stateSize];
    }

    /** Display name — the "(approx.)" is deliberate; see the class javadoc for exactly what is approximated. */
    @Override
    public String name() { return "Velocity Verlet (approx.)"; }

    /**
     * <b>One Velocity Verlet step</b>, in three parts:
     *
     * <ol>
     *   <li><b>Predict position</b> using the standard constant-acceleration
     *       formula {@code x + v·dt + ½·a·dt²} — the same kinematics
     *       equation from introductory mechanics. Including the {@code
     *       ½·a·dt²} term is what makes this more accurate than plain Euler,
     *       which drops it.</li>
     *   <li><b>Re-evaluate acceleration</b> at that predicted position.</li>
     *   <li><b>Correct velocity</b> using the <em>average</em> of the
     *       accelerations at the start and end of the step, rather than
     *       either one alone.</li>
     * </ol>
     *
     * <p>Two derivative evaluations per step: cheaper than RK4's four,
     * more accurate than Euler's one. See the class javadoc for why the
     * textbook guarantee doesn't strictly hold for this particular system.
     */
    @Override
    public void step(double[] state, double dt, DerivativeFunction derivative, int n) {
        derivative.compute(state, derivAtOld); // acceleration where we start

        // 1. Predict the new position: x + v*dt + 0.5*a*dt^2
        for (int i = 0; i < n; i++) {
            tempState[i]     = state[i] + state[n + i] * dt + 0.5 * derivAtOld[n + i] * dt * dt;
            tempState[n + i] = state[n + i]; // provisional velocity, needed only to evaluate the derivative below
        }

        derivative.compute(tempState, derivAtNew); // acceleration at the new position, old velocity — see class javadoc

        // 3. Correct the velocity using the AVERAGE of start and end
        //    accelerations, then commit the predicted position.
        for (int i = 0; i < n; i++) {
            state[n + i] += 0.5 * (derivAtOld[n + i] + derivAtNew[n + i]) * dt;
            state[i]      = tempState[i];
        }
    }
}
