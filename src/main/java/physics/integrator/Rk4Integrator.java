package physics.integrator;

/**
 * Classic 4th-order Runge-Kutta — the integrator this project has used since
 * the original prototype, extracted unchanged from {@code
 * physics.PhysicsEngine} into this strategy. Fourth-order accurate, not
 * symplectic, but in practice conserves energy extremely well at the small
 * fixed step this engine uses (see the project's own energy-conservation
 * test suite).
 */
public final class Rk4Integrator implements Integrator {

    // The four slope estimates RK4 averages, plus one scratch state used to
    // evaluate each successive estimate. Allocated once so step() never
    // allocates — see Integrator's javadoc.
    private final double[] k1, k2, k3, k4, temp;

    /** @param stateSize length of the state vector this integrator will step (2n for an n-link chain). */
    public Rk4Integrator(int stateSize) {
        k1   = new double[stateSize];
        k2   = new double[stateSize];
        k3   = new double[stateSize];
        k4   = new double[stateSize];
        temp = new double[stateSize];
    }

    /** Display name shown in the integrator picker and the comparison chart legend. */
    @Override
    public String name() { return "RK4"; }

    /**
     * <b>One 4th-order Runge-Kutta step.</b>
     *
     * <p><b>The intuition.</b> The naive way to advance a simulation is
     * Euler's method: measure the slope where you are, and follow it
     * blindly for the whole timestep. That is inaccurate for anything
     * curved, because the slope changes <em>during</em> the step.
     *
     * <p>RK4 fixes this by taking four slope measurements and averaging
     * them — like checking your direction four times while crossing a
     * bend instead of once:
     * <ol>
     *   <li><b>k1</b> — slope at the start.</li>
     *   <li><b>k2</b> — slope at the midpoint, reached by following k1 halfway.</li>
     *   <li><b>k3</b> — slope at the midpoint again, this time reached by following k2 halfway (a better midpoint estimate than k2's).</li>
     *   <li><b>k4</b> — slope at the end, reached by following k3 all the way.</li>
     * </ol>
     *
     * <p><b>The weighted average</b> {@code (k1 + 2·k2 + 2·k3 + k4) / 6}
     * gives the two midpoint estimates double weight, because the midpoint
     * is the most representative sample of the interval. This particular
     * weighting is what makes the method <em>fourth-order accurate</em>:
     * halving the timestep cuts the error by roughly 16× (2⁴), versus only
     * 2× for Euler. That is why RK4 is the default here.
     *
     * <p>Cost: four {@code derivative} evaluations per step — and each one
     * solves an N×N linear system — so RK4 is ~4× the work of Euler per
     * step, and buys far more than 4× the accuracy.
     */
    @Override
    public void step(double[] state, double dt, DerivativeFunction derivative, int n) {
        derivative.compute(state, k1);        // k1: slope right where we are
        buildTemp(state, k1, dt * 0.5);       // step halfway along k1...
        derivative.compute(temp, k2);         // k2: slope at that midpoint
        buildTemp(state, k2, dt * 0.5);       // step halfway along k2 instead...
        derivative.compute(temp, k3);         // k3: refined midpoint slope
        buildTemp(state, k3, dt);             // step the FULL way along k3...
        derivative.compute(temp, k4);         // k4: slope at the endpoint

        // Weighted average, midpoints counted double. This is the actual advance.
        for (int i = 0; i < state.length; i++) {
            state[i] += (dt / 6.0) * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]);
        }
    }

    /** Writes {@code base + k * scaleFactor} into {@link #temp} — "step along slope k for this fraction of dt", without allocating. */
    private void buildTemp(double[] base, double[] k, double scaleFactor) {
        for (int i = 0; i < base.length; i++) temp[i] = base[i] + k[i] * scaleFactor;
    }
}
