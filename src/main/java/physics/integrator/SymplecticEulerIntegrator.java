package physics.integrator;

/**
 * Semi-implicit (symplectic) Euler: update velocity from the derivative at
 * the current state, then update position using the just-updated velocity
 * — first-order accurate, but its energy error oscillates around the true
 * value rather than drifting monotonically the way explicit (forward)
 * Euler's does, which is the entire reason it's the standard cheap
 * alternative to RK4 in physics engines.
 *
 * <p>Intended for side-by-side comparison against {@link Rk4Integrator},
 * not as this project's default — see {@code ui.GraphPanel}'s
 * integrator-comparison mode.
 */
public final class SymplecticEulerIntegrator implements Integrator {

    /** Single slope buffer — this method needs only one derivative evaluation per step. */
    private final double[] deriv;

    /** @param stateSize length of the state vector this integrator will step (2n for an n-link chain). */
    public SymplecticEulerIntegrator(int stateSize) {
        deriv = new double[stateSize];
    }

    /** Display name shown in the integrator picker and the comparison chart legend. */
    @Override
    public String name() { return "Symplectic Euler"; }

    /**
     * <b>One semi-implicit Euler step.</b> Only ONE derivative evaluation,
     * versus RK4's four — so roughly a quarter of the cost.
     *
     * <p><b>The one detail that matters is the ORDER of the two lines
     * below.</b> Plain (explicit) Euler would update both angle and
     * velocity from the old values. This version updates velocity first,
     * then uses that <em>brand new</em> velocity to update the angle. That
     * one-line difference is the whole method.
     *
     * <p><b>Why it matters.</b> Explicit Euler steadily pumps energy into
     * the system — a pendulum simulated with it will swing higher and
     * higher until it flies apart. Semi-implicit Euler is
     * <em>symplectic</em>: its energy error oscillates around the correct
     * value rather than growing without bound. It is less accurate than
     * RK4 at any given instant, but it stays stable indefinitely, which is
     * why it is the standard cheap integrator in real-time physics engines.
     *
     * <p>Run the app's "Compare Integrators" tool to see this directly:
     * RK4's drift curve stays lowest, but this one stays flat rather than
     * climbing.
     */
    @Override
    public void step(double[] state, double dt, DerivativeFunction derivative, int n) {
        derivative.compute(state, deriv);
        // 1. Velocity FIRST, from the acceleration at the current state.
        for (int i = 0; i < n; i++) state[n + i] += dt * deriv[n + i]; // omega += dt * alpha(theta, omega)
        // 2. Angle SECOND, using the velocity just updated above — this
        //    ordering is precisely what makes the method symplectic.
        for (int i = 0; i < n; i++) state[i]     += dt * state[n + i]; // theta += dt * the just-updated omega
    }
}
