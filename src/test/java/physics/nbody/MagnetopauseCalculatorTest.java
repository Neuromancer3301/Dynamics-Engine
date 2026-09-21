package physics.nbody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MagnetopauseCalculatorTest {

    @Test
    void testEarthStandoffDistance() {
        NBodyConfig config = Presets.homeSolarSystem();
        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();

        // Earth is index 3 in home solar system
        assertEquals("Earth", state.name[3]);
        assertTrue(state.hasMagneticField(3));
        assertEquals(31.0, state.equatorialFieldMicroTesla(3), 1e-1);

        double rEarth = state.radius[3];
        double rMp = MagnetopauseCalculator.computeStandoff(3, state, 0); // Sun is index 0
        double ratio = rMp / rEarth;

        // Specification §4.2: Earth's dayside standoff calculates to 10–11 R_E (nominal ~10.4 R_E)
        assertTrue(ratio >= 10.0 && ratio <= 11.0, "Earth standoff ratio should be 10-11 R_E, was " + ratio);
    }

    @Test
    void testInactiveBodyStandoffFloor() {
        NBodyConfig config = Presets.homeSolarSystem();
        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();

        // Venus is index 2 (inactive dynamo)
        assertEquals("Venus", state.name[2]);
        assertFalse(state.hasMagneticField(2));
        double rVenus = state.radius[2];
        double rMpVenus = MagnetopauseCalculator.computeStandoff(2, state, 0);
        assertEquals(1.2 * rVenus, rMpVenus, 1e-3);

        // Mars is index 5 (inactive dynamo)
        assertEquals("Mars", state.name[5]);
        assertFalse(state.hasMagneticField(5));
        double rMars = state.radius[5];
        double rMpMars = MagnetopauseCalculator.computeStandoff(5, state, 0);
        assertEquals(1.2 * rMars, rMpMars, 1e-3);
    }

    @Test
    void testNoStarInterstellarPressure() {
        // Isolated Earth without any star
        double mEarth = 5.972e24;
        double rEarth = 6.371e6;
        double moment = CelestialMagnetismRegistry.momentFromField(31.0, rEarth);

        NBodyConfig isolatedConfig = new NBodyConfig(
                1,
                new double[]{mEarth},
                new double[]{rEarth},
                new double[]{0.0},
                new double[]{0.0},
                new double[]{0.0},
                new double[]{0.0},
                new String[]{"Isolated Earth"},
                new double[]{86164.0},
                new double[]{moment},
                new double[]{11.3},
                new double[]{0.08},
                1.0e7,
                6.674e-11,
                1.0
        );

        NBodyEngine engine = new NBodyEngine(isolatedConfig);
        NBodyState state = engine.getState();

        // With no star, standoff expands up to clamp ceiling (50.0 R_body) due to ultra-low P_ISM
        double rMp = MagnetopauseCalculator.computeStandoff(0, state, -1);
        assertEquals(50.0 * rEarth, rMp, 1e-3);
    }
}
