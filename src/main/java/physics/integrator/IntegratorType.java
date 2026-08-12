package physics.integrator;

/**
 * The user-facing choice of {@link Integrator}, paired with a factory that
 * sizes a fresh instance for a given state length.
 *
 * <p>This indirection exists because of a real interaction with structural
 * edits: {@code physics.PhysicsEngine}'s constructor always starts with a
 * fresh {@link Rk4Integrator} sized for the new N — an {@code Integrator}'s
 * scratch buffers are fixed-size, so the old one (sized for the old N)
 * cannot simply carry over after a rebuild. {@code
 * controller.SimulationController} keeps the last-selected {@code
 * IntegratorType} and re-applies {@link #create} at the new size after
 * every rebuild, so the dropdown's selection doesn't silently revert to
 * RK4 the moment a link is added or removed.
 */
public enum IntegratorType {
    RK4,
    SYMPLECTIC_EULER,
    VELOCITY_VERLET;

    /**
     * Builds a fresh integrator of this type, sized for a {@code stateSize}-
     * length state vector (2n for an n-link chain). A new instance is
     * required on every structural rebuild because each integrator's
     * scratch buffers are allocated once at a fixed size — see this enum's
     * class javadoc.
     */
    public Integrator create(int stateSize) {
        return switch (this) {
            case RK4              -> new Rk4Integrator(stateSize);
            case SYMPLECTIC_EULER -> new SymplecticEulerIntegrator(stateSize);
            case VELOCITY_VERLET  -> new VelocityVerletIntegrator(stateSize);
        };
    }

    /** Display name shown in the picker — a plain ComboBox<IntegratorType> renders via toString(). */
    @Override
    public String toString() {
        return switch (this) {
            case RK4              -> "RK4";
            case SYMPLECTIC_EULER -> "Symplectic Euler";
            case VELOCITY_VERLET  -> "Velocity Verlet (approx.)";
        };
    }
}
