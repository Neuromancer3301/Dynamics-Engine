package physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PhysicsEngine#step} with a negative {@code dt} — the
 * mechanism behind {@code simulation.SimulationLoop#setTimeReversed}'s
 * "genuinely reverse the live dynamics" demonstration, not a
 * {@code HistoryBuffer} replay.
 */
class TimeReversalTest {

    private static final double G = 9.81;

    @Test
    void singlePendulumRetracesItselfWhenSteppedBackward() {
        // N=1 is not chaotic — forward then backward by the same number of
        // identical-magnitude steps should land back very close to the
        // start, modulo RK4's own (tiny, not exactly zero) asymmetry.
        PendulumConfig config = new PendulumConfig(
                1, new double[]{1.0}, new double[]{1.0}, new double[]{Math.PI / 3}, G, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);

        double theta0 = engine.getState().angles[0];
        double omega0 = engine.getState().angularVelocities[0];

        for (int i = 0; i < 2000; i++) engine.step(0.002);
        for (int i = 0; i < 2000; i++) engine.step(-0.002);

        SimState back = engine.getState();
        assertEquals(theta0, back.angles[0], 1e-6, "A non-chaotic pendulum should retrace almost exactly");
        assertEquals(omega0, back.angularVelocities[0], 1e-6);
        assertEquals(0.0, back.time, 1e-9, "time should return to its starting value after an equal-and-opposite run");
    }

    @Test
    void negativeDtStaysFiniteForAChaoticChain() {
        // The interesting case isn't perfect retracing — it's that a
        // chaotic N>=2 chain does NOT retrace exactly (see
        // SimulationLoop#setTimeReversed's javadoc) while still never
        // producing NaN/Infinity along the way.
        PendulumConfig config = PendulumTestSupport.uniformConfig(3, 1.0, Math.PI / 2.0, G);
        PhysicsEngine engine = new PhysicsEngine(config);

        for (int i = 0; i < 3000; i++) engine.step(0.002);
        for (int i = 0; i < 3000; i++) {
            engine.step(-0.002);
            assertTrue(PendulumTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at reverse step " + i);
        }
    }

    @Test
    void largeNegativeDtIsClampedJustLikeLargePositiveDt() {
        PendulumConfig config = PendulumTestSupport.uniformConfig(2, 1.0, Math.PI / 4.0, G);
        PhysicsEngine engine = new PhysicsEngine(config);

        engine.step(-10.0); // should clamp to -0.05, not take one huge unstable leap

        assertTrue(PendulumTestSupport.isFiniteState(engine.getState()),
                "An extreme negative dt must be clamped, not applied verbatim");
        assertEquals(-0.05, engine.getState().time, 1e-9, "Clamp should land exactly at -0.05, mirroring the positive-dt clamp");
    }
}
