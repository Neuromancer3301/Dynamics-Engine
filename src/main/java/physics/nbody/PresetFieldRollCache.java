package physics.nbody;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic once-and-cache session persistence for uncharacterized preset body rolls.
 */
public final class PresetFieldRollCache {

    private static final PresetFieldRollCache INSTANCE = new PresetFieldRollCache();

    private final ConcurrentHashMap<String, CelestialMagnetismRegistry.MagneticProperties> cache = new ConcurrentHashMap<>();

    private PresetFieldRollCache() {}

    public static PresetFieldRollCache getInstance() {
        return INSTANCE;
    }

    private String normalize(String key) {
        if (key == null) return "";
        return key.trim().toLowerCase(Locale.ROOT);
    }

    public CelestialMagnetismRegistry.MagneticProperties get(String key) {
        return cache.get(normalize(key));
    }

    public void put(String key, CelestialMagnetismRegistry.MagneticProperties properties) {
        if (properties != null) {
            cache.put(normalize(key), properties);
        }
    }

    public boolean contains(String key) {
        return cache.containsKey(normalize(key));
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }
}
