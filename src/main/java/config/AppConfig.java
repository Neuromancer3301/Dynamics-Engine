package config;

/**
 * Central, immutable application constants.
 *
 * <p>Anything that would otherwise be a magic number or string literal
 * scattered across controllers belongs here instead. Bumping the version,
 * renaming the app, or changing the default window size should never touch
 * more than this file plus the one place that reads it.
 */
public final class AppConfig {

    private AppConfig() {}

    public static final String APP_NAME    = "Dynamics Engine";
    public static final String APP_TAGLINE = "N-Pendulum Chain Simulator";
    public static final String APP_VERSION = "1.0.4";

    public static final double MIN_WIDTH  = 960;
    public static final double MIN_HEIGHT = 640;
    public static final double DEFAULT_WIDTH  = 1280;
    public static final double DEFAULT_HEIGHT = 800;

    /** java.util.prefs node used by {@link theme.ThemeManager} to persist the user's theme choice. */
    public static final String PREFS_NODE = "engine.dynamics.app";
}
