package physics.nbody;

import org.junit.jupiter.api.Test;
import physics.integrator.IntegratorType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying N = 0 pipeline hardening: empty config, empty engine stepping,
 * state properties, and relativistic classification methods.
 */
public class NBodyZeroBodyTest {

    private static final double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

    @Test
    void emptyConfigInitializesSafely() {
        NBodyConfig config = new NBodyConfig(0,
                new double[0], new double[0],
                new double[0], new double[0],
                new double[0], new double[0],
                new String[0],
                NBodyConfig.DEFAULT_SOFTENING_LENGTH, G, 1.0);

        assertEquals(0, config.getN());
        assertEquals(0, config.getMasses().length);
        assertEquals(0, config.getRadii().length);
        assertEquals(0, config.getPositionsX().length);
        assertEquals(0, config.getPositionsY().length);
        assertEquals(0, config.getVelocitiesX().length);
        assertEquals(0, config.getVelocitiesY().length);
        assertEquals(0, config.getNames().length);
        assertEquals(0, config.getRotationPeriods().length);
    }

    @Test
    void emptyEngineStepsAndProducesZeroState() {
        NBodyConfig config = new NBodyConfig(0,
                new double[0], new double[0],
                new double[0], new double[0],
                new double[0], new double[0],
                new String[0],
                NBodyConfig.DEFAULT_SOFTENING_LENGTH, G, 1.0);

        NBodyEngine engine = new NBodyEngine(config);
        assertEquals(0, engine.getN());
        assertEquals(0.0, engine.getTime());

        // Step engine forward
        engine.step(3600.0);
        assertEquals(3600.0, engine.getTime());

        NBodyState state = engine.getState();
        assertNotNull(state);
        assertEquals(0, state.getN());
        assertEquals(3600.0, state.time);
        assertEquals(0.0, state.totalEnergy);
        assertEquals(0.0, state.kineticEnergy);
        assertEquals(0.0, state.potentialEnergy);
        assertEquals(0.0, state.totalMomentumX);
        assertEquals(0.0, state.totalMomentumY);
        assertEquals(0.0, state.totalAngularMomentum);

        // Reset
        engine.reset();
        assertEquals(0.0, engine.getTime());

        // Stepping with different integrators sized for 0
        engine.setIntegrator(IntegratorType.SYMPLECTIC_EULER.create(0));
        engine.step(100.0);
        assertEquals(100.0, engine.getTime());

        engine.setIntegrator(IntegratorType.VELOCITY_VERLET.create(0));
        engine.step(50.0);
        assertEquals(150.0, engine.getTime());
    }

    @Test
    void relativisticClassifications() {
        // Sun is a star (M >= 0.08 M_sun)
        assertTrue(NBodyState.isStar(NBodyState.SOLAR_MASS));
        assertTrue(NBodyState.isStar(0.08 * NBodyState.SOLAR_MASS));
        assertFalse(NBodyState.isStar(0.079 * NBodyState.SOLAR_MASS));
        assertFalse(NBodyState.isStar(5.972e24)); // Earth is not a star

        // Schwarzschild radius for Sun: ~ 2 * 6.674e-11 * 1.989e30 / (3e8)^2 ≈ 2954 m
        double rsSun = NBodyState.schwarzschildRadius(NBodyState.SOLAR_MASS, G);
        assertEquals(2954.0, rsSun, 10.0);

        // Sun radius is ~6.957e8 m, so rs << radius -> not a compact object
        assertFalse(NBodyState.isCompactObject(NBodyState.SOLAR_MASS, 6.957e8, G));

        // Black hole: mass = 10 solar masses, radius = 20 km (20,000 m)
        // rs for 10 solar masses ≈ 29.5 km (29540 m) > 20000 m -> compact object!
        double mass10 = 10 * NBodyState.SOLAR_MASS;
        double rs10 = NBodyState.schwarzschildRadius(mass10, G);
        assertTrue(rs10 > 20000.0);
        assertTrue(NBodyState.isCompactObject(mass10, 20000.0, G));

        // Test with NBodyState instance methods
        NBodyState testState = new NBodyState(0.0,
                new double[]{0.0, 1.0e11}, new double[]{0.0, 0.0},
                new double[]{0.0, 0.0}, new double[]{0.0, 30000.0},
                new double[]{mass10, 5.972e24}, new double[]{20000.0, 6.371e6},
                new String[]{"Black Hole", "Earth"}, 0.0, 0.0);

        assertTrue(testState.isStar(0));
        assertFalse(testState.isStar(1));
        assertTrue(testState.isCompactObject(0, G));
        assertFalse(testState.isCompactObject(1, G));
    }
}
