package physics.nbody;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Planetary magnetism catalog defining authored physical parameters, magnetic tilts,
 * center offsets, and epistemic classification for all known and preset celestial bodies.
 */
public final class CelestialMagnetismRegistry {

    private CelestialMagnetismRegistry() {}

    public record MagneticProperties(
        double surfaceFieldMicroTesla, // B_0 in microteslas
        double tiltDegrees,            // Magnetic tilt relative to rotational axis
        double offsetRadiusFraction,   // Center offset in body radii
        EpistemicStatus status,
        String dynamoMechanism
    ) {}

    public enum EpistemicStatus {
        KNOWN_YES,
        KNOWN_NO,
        UNKNOWN_PROBABLE
    }

    private static final Map<String, MagneticProperties> CATALOG = new HashMap<>();

    static {
        // Active dynamos (KNOWN_YES)
        CATALOG.put("earth",    new MagneticProperties(31.0, 11.3, 0.08, EpistemicStatus.KNOWN_YES, "Active Geodynamo"));
        CATALOG.put("jupiter",  new MagneticProperties(428.0, 9.6, 0.13, EpistemicStatus.KNOWN_YES, "Metallic Hydrogen Dynamo"));
        CATALOG.put("saturn",   new MagneticProperties(21.0, 0.0, 0.04, EpistemicStatus.KNOWN_YES, "Axisymmetric Liquid Metallic"));
        CATALOG.put("uranus",   new MagneticProperties(23.0, 58.6, 0.31, EpistemicStatus.KNOWN_YES, "Convective Ionic Ocean"));
        CATALOG.put("neptune",  new MagneticProperties(14.0, 46.8, 0.55, EpistemicStatus.KNOWN_YES, "Convective Ionic Ocean"));
        CATALOG.put("mercury",  new MagneticProperties(0.3, 2.0, 0.20, EpistemicStatus.KNOWN_YES, "Partially Molten Core"));
        CATALOG.put("ganymede", new MagneticProperties(1.2, 176.0, 0.05, EpistemicStatus.KNOWN_YES, "Liquid Iron Core"));
        CATALOG.put("sun",      new MagneticProperties(150.0, 7.25, 0.0, EpistemicStatus.KNOWN_YES, "Solar Dynamo & Wind Emitter"));

        // Inactive / crustal only (KNOWN_NO)
        MagneticProperties inactive = new MagneticProperties(0.0, 0.0, 0.0, EpistemicStatus.KNOWN_NO, "Inactive / Crustal Only");
        CATALOG.put("venus", inactive);
        CATALOG.put("mars", inactive);
        CATALOG.put("moon", inactive);
        CATALOG.put("earth's moon", inactive);
        CATALOG.put("luna", inactive);
        CATALOG.put("ceres", inactive);
        CATALOG.put("vesta", inactive);
        CATALOG.put("phobos", inactive);
        CATALOG.put("deimos", inactive);
    }

    private static String normalize(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Looks up magnetic properties for a named body. If unlisted, returns UNKNOWN_PROBABLE.
     */
    public static MagneticProperties getProperties(String name) {
        String key = normalize(name);
        MagneticProperties props = CATALOG.get(key);
        if (props != null) {
            return props;
        }
        return new MagneticProperties(0.0, 0.0, 0.0, EpistemicStatus.UNKNOWN_PROBABLE, "Uncharacterized Body");
    }

    /**
     * Computes magnetic dipole moment m (in A·m²) from surface equatorial field B_0 (in µT)
     * and physical radius R (in meters):
     * m = 10 * B_0[µT] * R^3
     */
    public static double momentFromField(double surfaceFieldMicroTesla, double radiusMeters) {
        if (surfaceFieldMicroTesla <= 0 || radiusMeters <= 0) return 0.0;
        return 10.0 * surfaceFieldMicroTesla * radiusMeters * radiusMeters * radiusMeters;
    }

    /**
     * Computes surface equatorial field B_0 (in µT) from magnetic dipole moment m (in A·m²)
     * and physical radius R (in meters):
     * B_0[µT] = 0.1 * m / R^3
     */
    public static double fieldFromMoment(double magneticMoment, double radiusMeters) {
        if (magneticMoment <= 0 || radiusMeters <= 0) return 0.0;
        return (0.1 * magneticMoment) / (radiusMeters * radiusMeters * radiusMeters);
    }
}
