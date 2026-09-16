package physics.nbody;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DipoleFieldMathTest {

    @Test
    void testEquatorialAndPolarFieldStrength() {
        // Simple test config: single aligned dipole (tilt = 90 deg -> magnetic axis along +Y, magnetic equator along X)
        double r = 6.371e6; // Earth radius
        double b0MicroTesla = 31.0;
        double moment = CelestialMagnetismRegistry.momentFromField(b0MicroTesla, r);

        NBodyConfig config = new NBodyConfig(
                1,
                new double[]{5.972e24},
                new double[]{r},
                new double[]{0.0},
                new double[]{0.0},
                new double[]{0.0},
                new double[]{0.0},
                new String[]{"Earth"},
                new double[]{86164.0},
                new double[]{moment},
                new double[]{90.0}, // Magnetic axis points +Y
                new double[]{0.0},  // Centered
                1.0e7,
                6.674e-11,
                1.0
        );

        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();

        // Magnetic equator point at surface: (x = 2 * r, y = 0) with softening eps = 0.5 * r
        // r_eff = sqrt((2r)^2 + (0.5r)^2) = sqrt(4.25) * r ≈ 2.0615 * r
        // Let's test at distance d = 10 * r >> eps, where eps softening effect is < 0.2%
        double d = 10.0 * r;
        double[] bEquator = DipoleFieldMath.evaluateB(d, 0.0, state);
        double bEquatorMag = Math.hypot(bEquator[0], bEquator[1]);

        // Expected dipole magnitude at distance d along equator: B_0 * (r / d)^3
        // = 31.0 µT * (1 / 1000) = 0.031 µT = 3.1e-8 T
        double expectedEquatorT = (b0MicroTesla * 1.0e-6) / 1000.0;
        assertEquals(expectedEquatorT, bEquatorMag, expectedEquatorT * 0.02); // within 2% with softening

        // Along magnetic axis (pole) at same distance d: (x = 0, y = d)
        // Dipole field at pole is exactly 2x the equatorial field
        double[] bPole = DipoleFieldMath.evaluateB(0.0, d, state);
        double bPoleMag = Math.hypot(bPole[0], bPole[1]);
        double expectedPoleT = 2.0 * expectedEquatorT;
        assertEquals(expectedPoleT, bPoleMag, expectedPoleT * 0.02);
    }

    @Test
    void testParametricLoopsDeformation() {
        NBodyConfig config = Presets.homeSolarSystem();
        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();

        // Earth is index 3, Sun is index 0
        double[] sunPos = new double[]{state.positionX[0], state.positionY[0]};
        double standoff = MagnetopauseCalculator.computeStandoff(3, state, 0);

        List<double[]> loops = DipoleFieldMath.generateParametricLoops(3, state, standoff, sunPos, 8, 32);
        assertNotNull(loops);
        assertFalse(loops.isEmpty());
        assertEquals(16, loops.size()); // 8 loops * 2 lobes (positive & negative hemispheres)

        for (double[] poly : loops) {
            assertEquals(64, poly.length); // 32 points * 2 (x, y)
            for (double coord : poly) {
                assertTrue(Double.isFinite(coord));
            }
        }
    }

    @Test
    void testRK4StreamlinesIntegration() {
        NBodyConfig config = Presets.homeSolarSystem();
        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();

        // Seed point slightly outside Earth's surface
        double rEarth = state.radius[3];
        double startX = state.positionX[3] + 2.0 * rEarth;
        double startY = state.positionY[3];
        double stepSize = 0.5 * rEarth;

        double[] streamline = DipoleFieldMath.integrateRK4Streamlines(state, startX, startY, stepSize, 50);
        assertNotNull(streamline);
        assertTrue(streamline.length >= 4); // At least 2 points (startX, startY, nextX, nextY)
        for (double coord : streamline) {
            assertTrue(Double.isFinite(coord));
        }
    }
}
