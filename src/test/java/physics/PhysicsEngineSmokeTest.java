package physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minimal smoke tests proving the {@code src/test/java} wiring works end to
 * end (Maven layout, JUnit 5 dependency, surefire).
 *
 * <p>This is scaffolding, not the Phase 3 verification suite. The real
 * suite — small-angle analytic comparison, energy conservation across
 * N = 1..20, near-zero-mass degradation, period-vs-length scaling — is
 * tracked as its own piece of work; it deserves its own file per concern
 * rather than being bolted onto the architecture pass.
 */
class PhysicsEngineSmokeTest {

    @Test
    void pendulumAtRestStaysAtRest() {
        PendulumConfig config = new PendulumConfig(
                1, new double[]{1.0}, new double[]{1.0}, new double[]{0.0}, 9.81, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);

        for (int i = 0; i < 1000; i++) engine.step(0.002);

        SimState state = engine.getState();
        assertEquals(0.0, state.angles[0], 1e-9, "A pendulum released at theta=0 should not move");
        assertEquals(0.0, state.angularVelocities[0], 1e-9);
    }

    @Test
    void energyIsConservedOverTenSimulatedSeconds() {
        PendulumConfig config = new PendulumConfig(
                3,
                new double[]{1.0, 0.8, 0.6},
                new double[]{1.5, 1.0, 0.8},
                new double[]{Math.PI / 2, Math.PI / 3, Math.PI / 4},
                9.81, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);

        double e0 = engine.getState().totalEnergy;
        for (int i = 0; i < 5000; i++) engine.step(0.002); // 10 simulated seconds

        double e1 = engine.getState().totalEnergy;
        double driftFraction = Math.abs(e1 - e0) / Math.abs(e0);

        assertTrue(driftFraction < 0.001,
                "Energy should not drift by more than 0.1%% over 10s; drifted by " + driftFraction * 100 + "%");
    }

    @Test
    void setLinkStateOverwritesAngleAndVelocityForDragging() {
        PendulumConfig config = new PendulumConfig(
                2, new double[]{1.0, 1.0}, new double[]{1.0, 1.0},
                new double[]{0.0, 0.0}, 9.81, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);

        engine.setLinkState(1, Math.PI / 4, 2.5);

        SimState state = engine.getState();
        assertEquals(Math.PI / 4, state.angles[1], 1e-9, "setLinkState should overwrite the targeted link's angle");
        assertEquals(2.5, state.angularVelocities[1], 1e-9, "setLinkState should overwrite the targeted link's angular velocity");
        assertEquals(0.0, state.angles[0], 1e-9, "setLinkState must not disturb other links");
    }

    @Test
    void setLinkStateIgnoresNonFiniteInputRatherThanCorruptingState() {
        PendulumConfig config = new PendulumConfig(
                1, new double[]{1.0}, new double[]{1.0}, new double[]{0.3}, 9.81, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);

        engine.setLinkState(0, Double.NaN, 1.0);
        engine.setLinkState(0, 0.5, Double.POSITIVE_INFINITY);

        SimState state = engine.getState();
        assertEquals(0.3, state.angles[0], 1e-9, "A NaN angle must be rejected, not applied");
        assertEquals(0.0, state.angularVelocities[0], 1e-9, "An infinite velocity must be rejected, not applied");
    }

    @Test
    void configRejectsNonFiniteParametersInsteadOfSilentlyAcceptingThem() {
        // A plain "<= 0" check does not catch NaN (NaN <= 0 is false in Java),
        // so this specifically guards the gap a malformed text field or a
        // corrupt saved scenario file could otherwise slip through.
        assertThrows(IllegalArgumentException.class, () ->
                new PendulumConfig(1, new double[]{Double.NaN}, new double[]{1.0}, new double[]{0.0}, 9.81, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new PendulumConfig(1, new double[]{1.0}, new double[]{Double.POSITIVE_INFINITY}, new double[]{0.0}, 9.81, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new PendulumConfig(1, new double[]{1.0}, new double[]{1.0}, new double[]{Double.NaN}, 9.81, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new PendulumConfig(1, new double[]{1.0}, new double[]{1.0}, new double[]{0.0}, Double.NaN, 1.0));
    }
}
