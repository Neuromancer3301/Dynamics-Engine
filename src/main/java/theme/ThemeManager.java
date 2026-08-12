package theme;

import config.AppConfig;
import javafx.scene.Parent;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Single source of truth for the active {@link Theme}.
 *
 * <p>JavaFX has no equivalent of the web's {@code prefers-color-scheme} —
 * theming is done by toggling a style class on the visible scene root and
 * letting {@code theme.css} branch on it via looked-up colors (custom
 * properties such as {@code -bg-base} defined per theme class and consumed
 * everywhere else). This class owns that toggle, persists the user's choice
 * across launches, and lets any number of interested parties react to a
 * change without being wired to each other directly.
 *
 * <p>Deliberately a plain singleton rather than something DI-framework-based:
 * the app has one theme, shared by every screen, and a framework would add
 * more ceremony than the problem warrants at this scale.
 *
 * <p>Also the central home for two accessibility preferences — {@link
 * #isReducedMotion} and {@link #isColorBlindSafePalette} — that aren't
 * "theme" in the light/dark sense but belong in the same place for the same
 * reason: they're global display preferences, persisted the same way, that
 * several unrelated classes (the simulation canvas, the menu's nav cards,
 * the scene router's transitions) all need to read without being wired to
 * each other. Neither is detected from the OS automatically — JavaFX has no
 * accessible bridge to {@code prefers-reduced-motion} the way web CSS
 * does — so both are explicit Settings-screen toggles instead.
 *
 * <p>{@link #getFontScale}/{@link #setFontScale} is a third such
 * preference, for the same underlying reason: JavaFX exposes no bridge to
 * the OS's own text-scale accessibility setting, so it has to be an
 * explicit in-app control.
 *
 * <p><b>This class only stores the value.</b> Actually applying it is
 * {@code navigation.SceneRouter}'s job, because scaling is a layout
 * concern and the router owns the layout root — see {@code
 * SceneRouter#applyScaling} for how the zoom is made to reflow instead of
 * clipping. Changing the value here notifies listeners; the router is one
 * of them.
 */
public final class ThemeManager {

    private static final ThemeManager INSTANCE = new ThemeManager();
    private static final String PREF_KEY_THEME           = "theme";
    private static final String PREF_KEY_REDUCED_MOTION  = "reducedMotion";
    private static final String PREF_KEY_COLORBLIND_SAFE = "colorBlindSafePalette";
    private static final String PREF_KEY_FONT_SCALE       = "fontScale";

    // Bounds for the interface scale. The ceiling is a readability/usable-area
    // trade-off, NOT a clipping guard: navigation.SceneRouter reflows the UI
    // into a smaller logical area before magnifying, so a larger factor loses
    // working space rather than pushing controls off-screen. It can be raised
    // safely if a bigger option is wanted.
    public static final double MIN_FONT_SCALE = 1.0;
    public static final double MAX_FONT_SCALE = 1.2;

    private final Preferences prefs = Preferences.userRoot().node(AppConfig.PREFS_NODE);
    private final List<Runnable> listeners = new ArrayList<>();

    private Theme current;
    private Parent activeRoot;
    private boolean reducedMotion;
    private boolean colorBlindSafePalette;
    private double fontScale;

    private ThemeManager() {
        String saved = prefs.get(PREF_KEY_THEME, Theme.DARK.name());
        Theme parsed;
        try {
            parsed = Theme.valueOf(saved);
        } catch (IllegalArgumentException ex) {
            parsed = Theme.DARK;
        }
        this.current = parsed;
        this.reducedMotion         = prefs.getBoolean(PREF_KEY_REDUCED_MOTION, false);
        this.colorBlindSafePalette = prefs.getBoolean(PREF_KEY_COLORBLIND_SAFE, false);
        this.fontScale             = clampFontScale(prefs.getDouble(PREF_KEY_FONT_SCALE, MIN_FONT_SCALE));
    }

    /** The single shared instance. One theme is a genuinely global, app-wide property, which is what justifies a singleton here. */
    public static ThemeManager getInstance() {
        return INSTANCE;
    }

    /** The active theme. */
    public Theme getCurrent() {
        return current;
    }

    /**
     * Registers the currently visible scene root so it reflects the active
     * theme. {@link navigation.SceneRouter} calls this on every navigation —
     * only one root is ever "active" at a time, so no leak accumulates as
     * screens come and go.
     */
    public void register(Parent root) {
        this.activeRoot = root;
        applyTo(root);
    }

    /** Notified whenever the theme changes — used by screens that display the current theme name. */
    public void addListener(Runnable onChange) {
        listeners.add(onChange);
    }

    /** Switches theme, persists the choice, restyles the visible screen, and notifies listeners. No-ops if already active, so listeners don't fire spuriously. */
    public void setTheme(Theme theme) {
        if (theme == current) return;
        this.current = theme;
        prefs.put(PREF_KEY_THEME, theme.name());
        if (activeRoot != null) applyTo(activeRoot);
        listeners.forEach(Runnable::run);
    }

    /** Flips between the two themes — what the Settings button calls. */
    public void toggle() {
        setTheme(current == Theme.DARK ? Theme.LIGHT : Theme.DARK);
    }

    /** Whether animations should be suppressed. Read by the router, nav cards, and the pendulum canvas. */
    public boolean isReducedMotion() { return reducedMotion; }

    /** Sets and persists the reduced-motion preference. */
    public void setReducedMotion(boolean reducedMotion) {
        if (reducedMotion == this.reducedMotion) return;
        this.reducedMotion = reducedMotion;
        prefs.putBoolean(PREF_KEY_REDUCED_MOTION, reducedMotion);
        listeners.forEach(Runnable::run);
    }

    /** Whether the Okabe-Ito colour-blind-safe bob palette should be used. */
    public boolean isColorBlindSafePalette() { return colorBlindSafePalette; }

    /** Sets and persists the colour-blind palette preference. */
    public void setColorBlindSafePalette(boolean colorBlindSafe) {
        if (colorBlindSafe == this.colorBlindSafePalette) return;
        this.colorBlindSafePalette = colorBlindSafe;
        prefs.putBoolean(PREF_KEY_COLORBLIND_SAFE, colorBlindSafe);
        listeners.forEach(Runnable::run);
    }

    /** Current UI zoom factor, between {@link #MIN_FONT_SCALE} and {@link #MAX_FONT_SCALE}. */
    public double getFontScale() { return fontScale; }

    /** Sets and persists the UI zoom, clamped to the supported range, and re-applies it to the visible screen immediately. */
    public void setFontScale(double scale) {
        double clamped = clampFontScale(scale);
        if (clamped == this.fontScale) return;
        this.fontScale = clamped;
        prefs.putDouble(PREF_KEY_FONT_SCALE, clamped);
        // Deliberately does NOT touch the scene graph: navigation.SceneRouter
        // owns layout and listens for this notification. Applying a transform
        // from here as well would compound with the router's and double-scale.
        listeners.forEach(Runnable::run);
    }

    /** Forces any value — including a corrupted stored preference — into the supported range. */
    private static double clampFontScale(double scale) {
        return Math.max(MIN_FONT_SCALE, Math.min(MAX_FONT_SCALE, scale));
    }

    /** Swaps the theme style class on a screen root: remove every theme class, add the active one. This single class swap is what repaints the whole app. */
    private void applyTo(Parent root) {
        for (Theme t : Theme.values()) root.getStyleClass().remove(t.styleClass());
        root.getStyleClass().add(current.styleClass());
    }

}
