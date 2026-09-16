package ui.nbody;

import javafx.scene.image.Image;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages texture assets and custom user models for celestial bodies in the N-Body simulation.
 * <p>
 * Texture lookup order for a body named {@code "Jupiter"}:
 * <ol>
 *     <li>User custom directory: {@code ~/.dynamics-engine/textures/bodies/jupiter.png} (or .jpg)</li>
 *     <li>Local folder: {@code textures/bodies/jupiter.png} (or .jpg)</li>
 *     <li>Classpath resource: {@code /textures/bodies/jupiter.png} (or .jpg)</li>
 * </ol>
 * If no texture file is found, the renderer seamlessly falls back to authentic,
 * high-fidelity procedural astronomical shaders tailored to that specific body.
 */
public final class CelestialBodyTextureManager {

    private static final Logger LOGGER = Logger.getLogger(CelestialBodyTextureManager.class.getName());
    private static final Map<String, Image> TEXTURE_CACHE = new HashMap<>();
    private static final Map<String, Boolean> SEARCH_ATTEMPTED = new HashMap<>();

    private static final String USER_HOME = System.getProperty("user.home", ".");
    private static final Path USER_TEXTURES_DIR = Path.of(USER_HOME, ".dynamics-engine", "textures", "bodies");

    static {
        ensureUserDirectoryExists();
    }

    private CelestialBodyTextureManager() {}

    /** Ensures the user directory {@code ~/.dynamics-engine/textures/bodies/} exists with an explanatory README. */
    public static void ensureUserDirectoryExists() {
        try {
            if (!Files.exists(USER_TEXTURES_DIR)) {
                Files.createDirectories(USER_TEXTURES_DIR);
                Path readme = USER_TEXTURES_DIR.resolve("README.txt");
                if (!Files.exists(readme)) {
                    String instructions = """
                            ===================================================================
                            Dynamics Engine - Custom Celestial Body Textures & Models Directory
                            ===================================================================
                            You can customize the appearance of any celestial body by placing an
                            image file in this folder:

                            Supported Formats: PNG, JPG, JPEG
                            Naming Convention: <body_name_lowercase>.png (spaces replaced with underscores)

                            Examples:
                            - earth.png
                            - jupiter.png
                            - mars.png
                            - moon.png
                            - saturn.png
                            - sun.png
                            - trappist_1.png
                            - cygnus_x_1.png

                            Where to get free high-resolution planetary textures:
                            - NASA Solar System Exploration: https://solarsystem.nasa.gov
                            - USGS Astrogeology Science Center: https://astrogeology.usgs.gov
                            - Solar System Scope Textures: https://www.solarsystemscope.com/textures/

                            Any image placed here will automatically be loaded and wrapped onto the
                            body's 3D rotating billboard at runtime!
                            """;
                    Files.writeString(readme, instructions);
                }
            }
        } catch (Exception e) {
            LOGGER.fine("Could not create user texture directory: " + e.getMessage());
        }
    }

    /** Returns the path to the user's custom textures directory. */
    public static Path getUserTexturesDirectory() {
        return USER_TEXTURES_DIR;
    }

    /**
     * Attempts to find and load a custom image texture for the given body name.
     * Returns {@code null} if no custom texture is provided.
     */
    public static Image getTexture(String bodyName) {
        if (bodyName == null || bodyName.isBlank()) return null;

        String key = normalizeName(bodyName);
        if (TEXTURE_CACHE.containsKey(key)) {
            return TEXTURE_CACHE.get(key);
        }

        if (SEARCH_ATTEMPTED.containsKey(key)) {
            return null; // Already tried and not found
        }

        Image loaded = loadTextureImage(key);
        SEARCH_ATTEMPTED.put(key, true);
        if (loaded != null) {
            TEXTURE_CACHE.put(key, loaded);
        }
        return loaded;
    }

    /** Clears the cache so newly added textures in the folder are reloaded immediately. */
    public static void clearCache() {
        TEXTURE_CACHE.clear();
        SEARCH_ATTEMPTED.clear();
    }

    private static String normalizeName(String name) {
        return name.trim().toLowerCase().replaceAll("[^a-z0-9]", "_");
    }

    private static Image loadTextureImage(String normalizedName) {
        String[] extensions = { ".png", ".jpg", ".jpeg" };

        // 1. Check ~/.dynamics-engine/textures/bodies/<name>.<ext>
        for (String ext : extensions) {
            File file = USER_TEXTURES_DIR.resolve(normalizedName + ext).toFile();
            if (file.exists() && file.isFile() && file.length() > 0) {
                try (InputStream is = new FileInputStream(file)) {
                    return new Image(is);
                } catch (Exception e) {
                    LOGGER.warning("Failed to load user texture from " + file + ": " + e.getMessage());
                }
            }
        }

        // 2. Check local working directory textures/bodies/<name>.<ext>
        for (String ext : extensions) {
            File file = new File("textures/bodies/" + normalizedName + ext);
            if (file.exists() && file.isFile() && file.length() > 0) {
                try (InputStream is = new FileInputStream(file)) {
                    return new Image(is);
                } catch (Exception e) {
                    LOGGER.warning("Failed to load local texture from " + file + ": " + e.getMessage());
                }
            }
        }

        // 3. Check classpath /textures/bodies/<name>.<ext>
        for (String ext : extensions) {
            String resPath = "/textures/bodies/" + normalizedName + ext;
            InputStream is = CelestialBodyTextureManager.class.getResourceAsStream(resPath);
            if (is != null) {
                try (is) {
                    return new Image(is);
                } catch (Exception e) {
                    LOGGER.warning("Failed to load classpath texture from " + resPath + ": " + e.getMessage());
                }
            }
        }

        return null;
    }
}
