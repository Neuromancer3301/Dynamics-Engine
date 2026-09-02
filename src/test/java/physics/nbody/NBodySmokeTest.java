package physics.nbody;

import org.junit.jupiter.api.Test;
import physics.integrator.IntegratorType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minimal smoke tests for the n-body engine — the n-body analogue of {@code
 * physics.PhysicsEngineSmokeTest}: config validation, {@code setBodyState}'s
 * dragging contract, and (the test that matters most here) a direct,
 * exercised check of the n-body implementation spec §2.3's warning rather
 * than trusting it by inspection alone.
 */
class NBodySmokeTest {

    private static final double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

    @Test
    void soleBodyWithNoOthersAndNoVelocityStaysAtRest() {
        // A single body has no other mass to attract it — nothing should move it.
        NBodyConfig config = new NBodyConfig(1, new double[]{1.0e24}, new double[]{1.0e6},
                new double[]{0.0}, new double[]{0.0}, new double[]{0.0}, new double[]{0.0},
                new String[]{"Lonely"}, NBodyConfig.DEFAULT_SOFTENING_LENGTH, G, 1.0);
        NBodyEngine engine = new NBodyEngine(config);

        for (int i = 0; i < 1000; i++) engine.step(1000.0);

        NBodyState state = engine.getState();
        assertEquals(0.0, state.positionX[0], 1e-9);
        assertEquals(0.0, state.positionY[0], 1e-9);
        assertEquals(0.0, state.velocityX[0], 1e-9);
        assertEquals(0.0, state.velocityY[0], 1e-9);
    }

    @Test
    void setBodyStateOverwritesPositionAndVelocityForDragging() {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(3, G);
        NBodyEngine engine = new NBodyEngine(config);

        engine.setBodyState(1, 1.0e11, 2.0e11, 500.0, -250.0);

        NBodyState state = engine.getState();
        assertEquals(1.0e11, state.positionX[1], 1.0, "setBodyState should overwrite the targeted body's x position");
        assertEquals(2.0e11, state.positionY[1], 1.0, "setBodyState should overwrite the targeted body's y position");
        assertEquals(500.0, state.velocityX[1], 1e-9, "setBodyState should overwrite the targeted body's x velocity");
        assertEquals(-250.0, state.velocityY[1], 1e-9, "setBodyState should overwrite the targeted body's y velocity");
    }

    @Test
    void setBodyStateIgnoresNonFiniteInputRatherThanCorruptingState() {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(2, G);
        NBodyEngine engine = new NBodyEngine(config);
        NBodyState before = engine.getState();

        engine.setBodyState(1, Double.NaN, 1.0, 0.0, 0.0);
        engine.setBodyState(1, 1.0, 1.0, Double.POSITIVE_INFINITY, 0.0);

        NBodyState after = engine.getState();
        assertEquals(before.positionX[1], after.positionX[1], 1e-9, "A NaN position must be rejected, not applied");
        assertEquals(before.velocityX[1], after.velocityX[1], 1e-9, "An infinite velocity must be rejected, not applied");
    }

    @Test
    void configRejectsNonFiniteParametersInsteadOfSilentlyAcceptingThem() {
        assertThrows(IllegalArgumentException.class, () -> new NBodyConfig(1,
                new double[]{Double.NaN}, new double[]{1.0}, new double[]{0.0}, new double[]{0.0},
                new double[]{0.0}, new double[]{0.0}, null, 1.0e7, G, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new NBodyConfig(1,
                new double[]{1.0}, new double[]{Double.POSITIVE_INFINITY}, new double[]{0.0}, new double[]{0.0},
                new double[]{0.0}, new double[]{0.0}, null, 1.0e7, G, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new NBodyConfig(1,
                new double[]{1.0}, new double[]{1.0}, new double[]{Double.NaN}, new double[]{0.0},
                new double[]{0.0}, new double[]{0.0}, null, 1.0e7, G, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new NBodyConfig(1,
                new double[]{1.0}, new double[]{1.0}, new double[]{0.0}, new double[]{0.0},
                new double[]{0.0}, new double[]{0.0}, null, Double.NaN, G, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new NBodyConfig(1,
                new double[]{1.0}, new double[]{1.0}, new double[]{0.0}, new double[]{0.0},
                new double[]{0.0}, new double[]{0.0}, null, 1.0e7, Double.NaN, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new NBodyConfig(0,
                new double[]{}, new double[]{}, new double[]{}, new double[]{},
                new double[]{}, new double[]{}, null, 1.0e7, G, 1.0));
    }

    @Test
    void unnamedBodyDefaultsToBodyPlusOneIndexedNumber() {
        NBodyConfig config = new NBodyConfig(2, new double[]{1.0, 1.0}, new double[]{1.0, 1.0},
                new double[]{0.0, 1.0}, new double[]{0.0, 0.0}, new double[]{0.0, 0.0}, new double[]{0.0, 0.0},
                new String[]{"Custom", null}, 1.0e7, G, 1.0);
        assertEquals("Custom", config.getName(0));
        assertEquals("Body 2", config.getName(1));
    }

    /**
     * <b>The regression test for the n-body implementation spec §2.3's
     * central warning.</b> {@code Rk4Integrator} never reads its own {@code
     * n} parameter, so it would pass even if the engine sized/called it
     * wrong — this deliberately switches to {@link
     * physics.integrator.SymplecticEulerIntegrator}, the one that would
     * silently freeze bodies if the engine passed body count instead of the
     * position/velocity boundary (2N). Every body in a multi-planet scene
     * has nonzero gravitational acceleration, so a correctly-wired
     * integrator must change every single body's velocity — not just some
     * prefix of them.
     */
    @Test
    void symplecticEulerAdvancesEveryBodysVelocity() {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(6, G);
        NBodyEngine engine = new NBodyEngine(config);
        engine.setIntegrator(IntegratorType.SYMPLECTIC_EULER.create(4 * config.getN()));

        NBodyState before = engine.getState();
        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 500.0;
        for (int i = 0; i < 50; i++) engine.step(dt);
        NBodyState after = engine.getState();

        for (int i = 0; i < config.getN(); i++) {
            double dvx = Math.abs(after.velocityX[i] - before.velocityX[i]);
            double dvy = Math.abs(after.velocityY[i] - before.velocityY[i]);
            assertTrue(dvx > 1.0e-9 || dvy > 1.0e-9,
                    "Body " + i + "'s velocity never changed under Symplectic Euler — the integrator may have "
                            + "been sized/called with body count instead of the position/velocity boundary (2N), "
                            + "per the n-body implementation spec §2.3");
        }
        assertTrue(NBodyTestSupport.isFiniteState(after));
    }

    /** Same regression, for {@link physics.integrator.VelocityVerletIntegrator}. */
    @Test
    void velocityVerletAdvancesEveryBodysVelocity() {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(6, G);
        NBodyEngine engine = new NBodyEngine(config);
        engine.setIntegrator(IntegratorType.VELOCITY_VERLET.create(4 * config.getN()));

        NBodyState before = engine.getState();
        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 500.0;
        for (int i = 0; i < 50; i++) engine.step(dt);
        NBodyState after = engine.getState();

        for (int i = 0; i < config.getN(); i++) {
            double dvx = Math.abs(after.velocityX[i] - before.velocityX[i]);
            double dvy = Math.abs(after.velocityY[i] - before.velocityY[i]);
            assertTrue(dvx > 1.0e-9 || dvy > 1.0e-9,
                    "Body " + i + "'s velocity never changed under Velocity Verlet — the integrator may have "
                            + "been sized/called with body count instead of the position/velocity boundary (2N), "
                            + "per the n-body implementation spec §2.3");
        }
        assertTrue(NBodyTestSupport.isFiniteState(after));
    }

    @Test
    void switchingIntegratorsMidSimulationDoesNotCorruptState() {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(5, G);
        NBodyEngine engine = new NBodyEngine(config);
        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 500.0;

        for (int i = 0; i < 200; i++) engine.step(dt);
        engine.setIntegrator(IntegratorType.SYMPLECTIC_EULER.create(4 * config.getN()));
        for (int i = 0; i < 200; i++) engine.step(dt);
        engine.setIntegrator(IntegratorType.VELOCITY_VERLET.create(4 * config.getN()));
        for (int i = 0; i < 200; i++) engine.step(dt);

        assertTrue(NBodyTestSupport.isFiniteState(engine.getState()),
                "State became non-finite after switching integrators mid-run");
    }

    @Test
    void resetReturnsToConfiguredInitialState() {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(4, G);
        NBodyEngine engine = new NBodyEngine(config);
        NBodyState initial = engine.getState();

        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 500.0;
        for (int i = 0; i < 300; i++) engine.step(dt);
        engine.reset();

        NBodyState afterReset = engine.getState();
        assertEquals(0.0, afterReset.time, 1e-12);
        for (int i = 0; i < config.getN(); i++) {
            assertEquals(initial.positionX[i], afterReset.positionX[i], 1e-6);
            assertEquals(initial.positionY[i], afterReset.positionY[i], 1e-6);
            assertEquals(initial.velocityX[i], afterReset.velocityX[i], 1e-9);
            assertEquals(initial.velocityY[i], afterReset.velocityY[i], 1e-9);
        }
    }
}
