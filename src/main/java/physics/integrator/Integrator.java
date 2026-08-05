package physics.integrator;

/**
 * A numerical integration strategy, decoupled from {@code
 * physics.PhysicsEngine}'s own Lagrangian derivative computation via {@link
 * DerivativeFunction}. Implementations own their scratch buffers (sized
 * once, at construction, to the state length) so that switching integrators
 * never introduces per-step allocation — the same zero-allocation property
 * {@code PhysicsEngine} was rewritten for.
 *
 * <p>Swappable specifically so their energy-conservation behavior can be
 * compared directly against each other on the same initial conditions —
 * see {@code ui.GraphPanel}'s integrator-comparison mode.
 */
public interface Integrator {

    String name();

    /** Advances {@code state} (length {@code 2n}) in place by {@code dt}. */
    void step(double[] state, double dt, DerivativeFunction derivative, int n);
}
