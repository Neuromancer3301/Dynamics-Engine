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

    private final double[] k1, k2, k3, k4, temp;

    public Rk4Integrator(int stateSize) {
        k1   = new double[stateSize];
        k2   = new double[stateSize];
        k3   = new double[stateSize];
        k4   = new double[stateSize];
        temp = new double[stateSize];
    }

    @Override
    public String name() { return "RK4"; }

    @Override
    public void step(double[] state, double dt, DerivativeFunction derivative, int n) {
        derivative.compute(state, k1);
        buildTemp(state, k1, dt * 0.5);
        derivative.compute(temp, k2);
        buildTemp(state, k2, dt * 0.5);
        derivative.compute(temp, k3);
        buildTemp(state, k3, dt);
        derivative.compute(temp, k4);

        for (int i = 0; i < state.length; i++) {
            state[i] += (dt / 6.0) * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]);
        }
    }

    private void buildTemp(double[] base, double[] k, double scaleFactor) {
        for (int i = 0; i < base.length; i++) temp[i] = base[i] + k[i] * scaleFactor;
    }
}
