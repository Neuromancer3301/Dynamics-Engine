package physics.integrator;

/**
 * Computes the state derivative at a given point — the callback an {@link
 * Integrator} evaluates as many times as its method needs. Both arrays use
 * this engine's convention: length {@code 2n}, positions in {@code [0,n)},
 * velocities in {@code [n,2n)}.
 */
@FunctionalInterface
public interface DerivativeFunction {
    void compute(double[] state, double[] out);
}
