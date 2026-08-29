package simulation;

/**
 * The minimal lifecycle contract any simulation engine must expose to run
 * inside {@link SimulationLoop}: advance by a timestep, publish its current
 * state, report simulated time, and reset. Nothing here assumes an ODE, a
 * fixed-size {@code double[]} state, or any particular numerical method —
 * {@code S} is whatever snapshot type the engine hands to the render thread
 * (see {@code physics.SimState} for the pendulum's).
 *
 * <p><b>Round 2 §1 of the physics-layer modularity pass.</b> {@code
 * physics.PhysicsEngine} implements this directly — its {@code step(double)}
 * / {@code getState()} / {@code getTime()} / {@code reset()} already have
 * exactly these signatures, so adding the interface costs nothing and is the
 * concrete proof the contract fits a real engine.
 *
 * <p><b>Why this is the right cut line, and where it stops.</b> {@code
 * physics.integrator.Integrator}/{@code DerivativeFunction} sit one layer
 * below this — they're specifically an ODE strategy over a flat {@code
 * double[]} state advanced by RK4/Verlet/symplectic-Euler, which fits an
 * N-pendulum (and would fit a future N-body gravitational simulation) but
 * not a PDE solver: a smoke/fluid simulation's "step" is a semi-Lagrangian
 * advection + pressure-projection pass with a timestep tied to CFL/grid
 * resolution, not a small ODE state vector. That simulation still fits
 * {@code SimulationEngine<S>} fine — it just does that pass inside {@code
 * step} instead of delegating to an {@code Integrator} — which is exactly
 * why this interface, not the integrator strategy below it, is the layer
 * every future simulation type is expected to implement directly.
 *
 * @param <S> the immutable state snapshot this engine publishes
 */
public interface SimulationEngine<S> {

    /** Advances (or, given a negative {@code dt}, reverses — see implementations) the simulation by one step. */
    void step(double dt);

    /** An immutable snapshot of the current state, safe to hand across to another thread. */
    S getState();

    /** Simulated time elapsed since the last {@link #reset}. */
    double getTime();

    /** Returns the simulation to its configured initial state and rewinds simulated time. */
    void reset();
}
