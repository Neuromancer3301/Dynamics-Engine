package physics.nbody;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FieldPresenceEvaluatorTest {

    @BeforeEach
    void setUp() {
        PresetFieldRollCache.getInstance().clear();
    }

    @Test
    void testCatalogKnownBodies() {
        // Earth: 31.0 µT, 11.3°, 0.08 offset, KNOWN_YES
        CelestialMagnetismRegistry.MagneticProperties earth =
                FieldPresenceEvaluator.evaluateField("Earth", 5.972e24, 6.371e6, 86164.0);
        assertEquals(31.0, earth.surfaceFieldMicroTesla(), 1e-3);
        assertEquals(11.3, earth.tiltDegrees(), 1e-3);
        assertEquals(0.08, earth.offsetRadiusFraction(), 1e-3);
        assertEquals(CelestialMagnetismRegistry.EpistemicStatus.KNOWN_YES, earth.status());
        assertEquals("Active Geodynamo", earth.dynamoMechanism());

        // Jupiter: 428.0 µT, 9.6°, KNOWN_YES
        CelestialMagnetismRegistry.MagneticProperties jup =
                FieldPresenceEvaluator.evaluateField("Jupiter", 1.898e27, 6.991e7, 35730.0);
        assertEquals(428.0, jup.surfaceFieldMicroTesla(), 1e-3);
        assertEquals(9.6, jup.tiltDegrees(), 1e-3);
        assertEquals(0.13, jup.offsetRadiusFraction(), 1e-3);
        assertEquals(CelestialMagnetismRegistry.EpistemicStatus.KNOWN_YES, jup.status());

        // Venus: 0.0 µT, KNOWN_NO
        CelestialMagnetismRegistry.MagneticProperties venus =
                FieldPresenceEvaluator.evaluateField("Venus", 4.867e24, 6.052e6, 2.0997e7);
        assertEquals(0.0, venus.surfaceFieldMicroTesla(), 1e-6);
        assertEquals(CelestialMagnetismRegistry.EpistemicStatus.KNOWN_NO, venus.status());
        assertEquals("Inactive / Crustal Only", venus.dynamoMechanism());

        // Mars: 0.0 µT, KNOWN_NO
        CelestialMagnetismRegistry.MagneticProperties mars =
                FieldPresenceEvaluator.evaluateField("Mars", 6.417e23, 3.390e6, 88642.0);
        assertEquals(0.0, mars.surfaceFieldMicroTesla(), 1e-6);
        assertEquals(CelestialMagnetismRegistry.EpistemicStatus.KNOWN_NO, mars.status());
    }

    @Test
    void testProbabilityCurveClamping() {
        // M <= 10^20 kg -> 0.05
        assertEquals(0.05, FieldPresenceEvaluator.probabilityFromMass(1.0e18), 1e-6);
        assertEquals(0.05, FieldPresenceEvaluator.probabilityFromMass(1.0e20), 1e-6);

        // M >= 10^27 kg -> 0.85
        assertEquals(0.85, FieldPresenceEvaluator.probabilityFromMass(1.0e27), 1e-6);
        assertEquals(0.85, FieldPresenceEvaluator.probabilityFromMass(1.0e29), 1e-6);

        // Intermediate mass: 10^23.5 kg -> midway between 20 and 27 is 3.5/7 = 0.5
        // clamped between 0.05 and 0.85 -> 0.50
        double pMid = FieldPresenceEvaluator.probabilityFromMass(Math.pow(10, 23.5));
        assertEquals(0.50, pMid, 1e-2);
    }

    @Test
    void testDeterministicRollCaching() {
        String testExoplanet = "Kepler-452b";
        double mass = 5.0 * 5.972e24;
        double radius = 1.63 * 6.371e6;
        double period = 86400.0 * 20.0;

        CelestialMagnetismRegistry.MagneticProperties roll1 =
                FieldPresenceEvaluator.evaluateField(testExoplanet, mass, radius, period);
        assertNotNull(roll1);
        assertEquals(CelestialMagnetismRegistry.EpistemicStatus.UNKNOWN_PROBABLE, roll1.status());

        // Second evaluation should return identical cached result
        CelestialMagnetismRegistry.MagneticProperties roll2 =
                FieldPresenceEvaluator.evaluateField(testExoplanet, mass, radius, period);
        assertSame(roll1, roll2);
        assertEquals(roll1.surfaceFieldMicroTesla(), roll2.surfaceFieldMicroTesla(), 1e-9);
        assertEquals(roll1.tiltDegrees(), roll2.tiltDegrees(), 1e-9);
    }
}
