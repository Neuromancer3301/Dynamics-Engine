package physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the engine degrades gracefully rather than catastrophically at
 * the edge cases named in the project's original spec: near-zero mass and
 * near-vertical (unstable equilibrium) starting angles, plus the largest N
 * the app's own UI actually allows a user to build. "Graceful" here means
 * finite output, not necessarily physically exact — a near-singular mass
 * matrix is expected to lose precision (see PhysicsEngine's own {@code
 * pivotVal < 1e-12} guard); it just must never hand NaN/Infinity to the
 * render loop, which would corrupt every subsequent frame silently.
 */
class RobustnessTest {

    private static final double G = 9.81;

    @Test
    void nearZeroMassStaysFiniteRatherThanProducingNaN() {
        PendulumConfig config = new PendulumConfig(
                2, new double[]{1.0, 1.0}, new double[]{1.0, 1.0e-9},
                new double[]{Math.PI / 3, Math.PI / 4}, G, 1.0);
        PhysicsEngine engine = new PhysicsEngine(config);

        for (int i = 0; i < 5000; i++) {
            engine.step(0.002);
            assertTrue(PendulumTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at step " + i + " with a near-zero mass link");
        }
    }

    @Test
    void nearInvertedStartStaysFiniteDespiteChaoticSensitivity() {
        // Just off the unstable equilibrium (theta = pi, pointing straight up).
        PendulumConfig config = PendulumTestSupport.uniformConfig(3, 2.0, Math.PI - 0.01, G);
        PhysicsEngine engine = new PhysicsEngine(config);

        for (int i = 0; i < 5000; i++) {
            engine.step(0.002);
            assertTrue(PendulumTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at step " + i + " starting near the inverted equilibrium");
        }
    }

    @Test
    void largeNStaysFiniteAtTheLinkEditorsUpperBound() {
        // 60 is LinkEditorPanel.MAX_LINKS — the largest chain a user can actually build via the UI.
        PendulumConfig config = PendulumTestSupport.uniformConfig(60, 3.0, Math.PI / 2.0, G);
        PhysicsEngine engine = new PhysicsEngine(config);

        for (int i = 0; i < 2000; i++) {
            engine.step(0.002);
            assertTrue(PendulumTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at step " + i + " for N=60");
        }
    }
}
