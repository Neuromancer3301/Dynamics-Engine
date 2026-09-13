package ui.nbody;

import javafx.scene.image.Image;

import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of real 3D models for every celestial body across all simulation presets
 * (Home Solar System, TRAPPIST-1, Alpha Centauri) and user-added bodies.
 */
public final class CelestialBody3DRegistry {

    private static final Map<String, CelestialBody3DModel> MODEL_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Double> AXIAL_TILTS = new HashMap<>();

    static {
        // Astronomical axial tilts (obliquities) in degrees
        AXIAL_TILTS.put("mercury", 0.03);
        AXIAL_TILTS.put("venus", 177.36);
        AXIAL_TILTS.put("earth", 23.44);
        AXIAL_TILTS.put("moon", 1.54);
        AXIAL_TILTS.put("mars", 25.19);
        AXIAL_TILTS.put("phobos", 0.0);
        AXIAL_TILTS.put("deimos", 0.0);
        AXIAL_TILTS.put("jupiter", 3.13);
        AXIAL_TILTS.put("io", 0.0);
        AXIAL_TILTS.put("europa", 0.1);
        AXIAL_TILTS.put("ganymede", 0.2);
        AXIAL_TILTS.put("callisto", 0.4);
        AXIAL_TILTS.put("saturn", 26.73);
        AXIAL_TILTS.put("titan", 0.3);
        AXIAL_TILTS.put("enceladus", 0.0);
        AXIAL_TILTS.put("mimas", 0.0);
        AXIAL_TILTS.put("iapetus", 15.47);
        AXIAL_TILTS.put("rhea", 0.0);
        AXIAL_TILTS.put("dione", 0.0);
        AXIAL_TILTS.put("tethys", 0.0);
        AXIAL_TILTS.put("uranus", 97.77);
        AXIAL_TILTS.put("titania", 0.0);
        AXIAL_TILTS.put("oberon", 0.0);
        AXIAL_TILTS.put("ariel", 0.0);
        AXIAL_TILTS.put("umbriel", 0.0);
        AXIAL_TILTS.put("miranda", 0.0);
        AXIAL_TILTS.put("neptune", 28.32);
        AXIAL_TILTS.put("triton", 129.8);
        AXIAL_TILTS.put("ceres", 4.0);
        AXIAL_TILTS.put("vesta", 29.0);
        AXIAL_TILTS.put("pluto", 122.53);
        AXIAL_TILTS.put("charon", 0.0);
        AXIAL_TILTS.put("halley_s_comet", 18.0);
        AXIAL_TILTS.put("sun", 7.25);
        AXIAL_TILTS.put("trappist_1", 0.0);
        AXIAL_TILTS.put("alpha_centauri_a", 7.0);
        AXIAL_TILTS.put("alpha_centauri_b", 8.0);
        AXIAL_TILTS.put("proxima_centauri", 0.0);
    }

    private CelestialBody3DRegistry() {}

    /**
     * Retrieves or instantiates the real 3D model for the given celestial body.
     */
    public static CelestialBody3DModel getModel(String bodyName, boolean isStar, boolean isCompact, boolean isComet) {
        return getModel(bodyName, null, isStar, isCompact, isComet);
    }

    /**
     * Retrieves or instantiates the real 3D model with fallback color for user-created bodies.
     */
    public static CelestialBody3DModel getModel(String bodyName, Color baseColor, boolean isStar, boolean isCompact, boolean isComet) {
        String key = normalize(bodyName);
        return MODEL_CACHE.computeIfAbsent(key, k -> createModel(bodyName, k, baseColor, isStar, isCompact, isComet));
    }

    /** Clears all cached 3D models and snapshots. */
    public static void clearCache() {
        MODEL_CACHE.clear();
    }

    private static CelestialBody3DModel createModel(String rawName, String normalizedKey, Color baseColor,
                                                   boolean isStar, boolean isCompact, boolean isComet) {
        Image texture = CelestialBodyTextureManager.getTexture(rawName);

        // Ring texture for Saturn
        Image ringTexture = null;
        if (normalizedKey.contains("saturn")) {
            ringTexture = CelestialBodyTextureManager.getTexture("saturn_ring");
        }

        double tilt = AXIAL_TILTS.getOrDefault(normalizedKey, 15.0);

        boolean star = isStar || normalizedKey.contains("sun") || normalizedKey.equals("trappist_1")
                || normalizedKey.contains("alpha_centauri") || normalizedKey.contains("proxima_centauri");

        boolean compact = isCompact || normalizedKey.contains("black_hole") || normalizedKey.contains("singularity");

        boolean comet = isComet || normalizedKey.contains("halley") || normalizedKey.contains("comet");

        return new CelestialBody3DModel(rawName, texture, ringTexture, tilt, star, compact, comet, baseColor);
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) return "body";
        return name.trim().toLowerCase().replaceAll("[^a-z0-9]", "_");
    }
}
