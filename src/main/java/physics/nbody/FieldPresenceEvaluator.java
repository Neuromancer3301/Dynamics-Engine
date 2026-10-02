package physics.nbody;

import java.util.Locale;
import java.util.Random;

/**
 * Epistemic 3-state pipeline, mass-weighted probability curve, and dynamo scaling law.
 */
public final class FieldPresenceEvaluator {

    public static final double EARTH_SURFACE_FIELD_UT = 31.0;
    public static final double EARTH_MASS_KG = 5.972e24;
    public static final double EARTH_ROTATION_PERIOD_S = 86164.0;
    public static final double DEFAULT_ROTATION_PERIOD_S = 86400.0;

    private static final double LOG_M_MIN = 20.0; // log10(10^20 kg)
    private static final double LOG_M_MAX = 27.0; // log10(10^27 kg)

    private FieldPresenceEvaluator() {}

    /**
     * Computes the probability of dynamo presence given body mass.
     * P(field) = clamp((log10(M) - 20) / (27 - 20), 0.05, 0.85)
     */
    public static double probabilityFromMass(double massKg) {
        if (massKg <= 0.0) return 0.05;
        double logM = Math.log10(massKg);
        double raw = (logM - LOG_M_MIN) / (LOG_M_MAX - LOG_M_MIN);
        return Math.max(0.05, Math.min(0.85, raw));
    }

    /**
     * Evaluates magnetic properties for a celestial body based on its catalog status or
     * mass-dependent epistemic dynamo scaling law.
     */
    public static CelestialMagnetismRegistry.MagneticProperties evaluateField(
            String name, double massKg, double radiusMeters, double rotationPeriodSeconds) {

        CelestialMagnetismRegistry.MagneticProperties catalog = CelestialMagnetismRegistry.getProperties(name);
        if (catalog.status() == CelestialMagnetismRegistry.EpistemicStatus.KNOWN_YES ||
            catalog.status() == CelestialMagnetismRegistry.EpistemicStatus.KNOWN_NO) {
            return catalog;
        }

        // Consult session cache
        PresetFieldRollCache cache = PresetFieldRollCache.getInstance();
        if (cache.contains(name)) {
            return cache.get(name);
        }

        // Perform deterministic roll
        double pField = probabilityFromMass(massKg);
        long seed = (long) (name != null ? name.toLowerCase(Locale.ROOT).hashCode() : 0) ^ Double.doubleToLongBits(massKg);
        Random rng = new Random(seed);

        boolean hasDynamo = rng.nextDouble() < pField;
        CelestialMagnetismRegistry.MagneticProperties result;

        if (hasDynamo) {
            double tRot = (rotationPeriodSeconds > 0.0) ? rotationPeriodSeconds : DEFAULT_ROTATION_PERIOD_S;
            double jitter = 0.5 + 1.5 * rng.nextDouble(); // [0.5, 2.0]
            double massRatio = Math.max(1.0e-10, massKg / EARTH_MASS_KG);
            double rotRatio = Math.max(1.0e-10, EARTH_ROTATION_PERIOD_S / tRot);

            double b0 = EARTH_SURFACE_FIELD_UT * Math.pow(massRatio, 0.7) * Math.pow(rotRatio, 0.5) * jitter;
            double tilt = rng.nextDouble() * 90.0;
            double offset = rng.nextDouble() * 0.25;

            result = new CelestialMagnetismRegistry.MagneticProperties(
                    b0, tilt, offset,
                    CelestialMagnetismRegistry.EpistemicStatus.UNKNOWN_PROBABLE,
                    "Predicted Dynamo (Scaling Law)"
            );
        } else {
            result = new CelestialMagnetismRegistry.MagneticProperties(
                    0.0, 0.0, 0.0,
                    CelestialMagnetismRegistry.EpistemicStatus.UNKNOWN_PROBABLE,
                    "Inactive Dynamo (Roll Failed)"
            );
        }

        cache.put(name, result);
        return result;
    }
}
