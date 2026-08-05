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

    private final double[] deriv;

    public SymplecticEulerIntegrator(int stateSize) {
        deriv = new double[stateSize];
    }

    @Override
    public String name() { return "Symplectic Euler"; }

    @Override
    public void step(double[] state, double dt, DerivativeFunction derivative, int n) {
        derivative.compute(state, deriv);
        for (int i = 0; i < n; i++) state[n + i] += dt * deriv[n + i]; // omega += dt * alpha(theta, omega)
        for (int i = 0; i < n; i++) state[i]     += dt * state[n + i]; // theta += dt * the just-updated omega
    }
}
