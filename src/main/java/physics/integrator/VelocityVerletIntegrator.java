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

    private final double[] derivAtOld, tempState, derivAtNew;

    public VelocityVerletIntegrator(int stateSize) {
        derivAtOld = new double[stateSize];
        tempState  = new double[stateSize];
        derivAtNew = new double[stateSize];
    }

    @Override
    public String name() { return "Velocity Verlet (approx.)"; }

    @Override
    public void step(double[] state, double dt, DerivativeFunction derivative, int n) {
        derivative.compute(state, derivAtOld);

        for (int i = 0; i < n; i++) {
            tempState[i]     = state[i] + state[n + i] * dt + 0.5 * derivAtOld[n + i] * dt * dt;
            tempState[n + i] = state[n + i]; // provisional velocity, needed only to evaluate the derivative below
        }

        derivative.compute(tempState, derivAtNew); // acceleration at the new position, old velocity — see class javadoc

        for (int i = 0; i < n; i++) {
            state[n + i] += 0.5 * (derivAtOld[n + i] + derivAtNew[n + i]) * dt;
            state[i]      = tempState[i];
        }
    }
}
