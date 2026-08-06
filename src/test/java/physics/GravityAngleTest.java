package physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PhysicsEngine#setGravityAngle} — the "paint the gravity
 * field" mechanism behind {@code ui.PendulumCanvas}'s draggable handle. See
 * that method's javadoc for the convention (0 = straight down, matching
 * this engine's only-ever behavior before the method existed).
 */
class GravityAngleTest {

    private static final double G = 9.81;

    @Test
    void defaultsToStraightDown() {
        PendulumConfig config = new PendulumConfig(
                1, new double[]{1.0}, new double[]{1.0}, new double[]{0.5}, G, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);

        assertEquals(0.0, engine.getGravityAngle(), 1e-9);
    }

    @Test
    void pendulumAlignedWithTiltedGravityStaysAtRest() {
        // theta == gravityAngle is the new equilibrium — the same fact
        // "theta == 0 is the equilibrium at the default gravityAngle == 0"
        // is a special case of.
        double gravityAngle = 0.9;
        PendulumConfig config = new PendulumConfig(
                1, new double[]{1.0}, new double[]{1.0}, new double[]{gravityAngle}, G, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);
        engine.setGravityAngle(gravityAngle);

        for (int i = 0; i < 2000; i++) engine.step(0.002);

        SimState state = engine.getState();
        assertEquals(gravityAngle, state.angles[0], 1e-6,
                "A pendulum released aligned with a tilted gravity direction should not move");
        assertEquals(0.0, state.angularVelocities[0], 1e-6);
    }

    @Test
    void energyIsConservedWithTiltedGravity() {
        // Same shape as PhysicsEngineSmokeTest's energyIsConservedOverTenSimulatedSeconds
        // — the point here specifically is that a nonzero gravityAngle
        // doesn't break the force/potential-energy consistency the way an
        // error in either formula (but not the other) would.
        PendulumConfig config = new PendulumConfig(
                3,
                new double[]{1.0, 0.8, 0.6},
                new double[]{1.5, 1.0, 0.8},
                new double[]{Math.PI / 2, Math.PI / 3, Math.PI / 4},
                G, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);
        engine.setGravityAngle(Math.PI / 4);

        double e0 = engine.getState().totalEnergy;
        for (int i = 0; i < 5000; i++) engine.step(0.002); // 10 simulated seconds

        double e1 = engine.getState().totalEnergy;
        double driftFraction = Math.abs(e1 - e0) / Math.abs(e0);

        assertTrue(driftFraction < 0.001,
                "Energy should not drift by more than 0.1%% with a tilted gravity field; drifted by " + driftFraction * 100 + "%");
    }

    @Test
    void tiltedGravityStaysFiniteForAChaoticChain() {
        PendulumConfig config = PendulumTestSupport.uniformConfig(3, 1.0, Math.PI / 2.0, G);
        PhysicsEngine engine = new PhysicsEngine(config);
        engine.setGravityAngle(1.7);

        for (int i = 0; i < 5000; i++) {
            engine.step(0.002);
            assertTrue(PendulumTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at step " + i + " with a tilted gravity field");
        }
    }
}
