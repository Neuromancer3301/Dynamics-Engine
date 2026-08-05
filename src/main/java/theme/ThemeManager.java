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
 */
public final class ThemeManager {

    private static final ThemeManager INSTANCE = new ThemeManager();
    private static final String PREF_KEY_THEME           = "theme";
    private static final String PREF_KEY_REDUCED_MOTION  = "reducedMotion";
    private static final String PREF_KEY_COLORBLIND_SAFE = "colorBlindSafePalette";

    private final Preferences prefs = Preferences.userRoot().node(AppConfig.PREFS_NODE);
    private final List<Runnable> listeners = new ArrayList<>();

    private Theme current;
    private Parent activeRoot;
    private boolean reducedMotion;
    private boolean colorBlindSafePalette;

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
    }

    public static ThemeManager getInstance() {
        return INSTANCE;
    }

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

    public void setTheme(Theme theme) {
        if (theme == current) return;
        this.current = theme;
        prefs.put(PREF_KEY_THEME, theme.name());
        if (activeRoot != null) applyTo(activeRoot);
        listeners.forEach(Runnable::run);
    }

    public void toggle() {
        setTheme(current == Theme.DARK ? Theme.LIGHT : Theme.DARK);
    }

    public boolean isReducedMotion() { return reducedMotion; }

    public void setReducedMotion(boolean reducedMotion) {
        if (reducedMotion == this.reducedMotion) return;
        this.reducedMotion = reducedMotion;
        prefs.putBoolean(PREF_KEY_REDUCED_MOTION, reducedMotion);
        listeners.forEach(Runnable::run);
    }

    public boolean isColorBlindSafePalette() { return colorBlindSafePalette; }

    public void setColorBlindSafePalette(boolean colorBlindSafe) {
        if (colorBlindSafe == this.colorBlindSafePalette) return;
        this.colorBlindSafePalette = colorBlindSafe;
        prefs.putBoolean(PREF_KEY_COLORBLIND_SAFE, colorBlindSafe);
        listeners.forEach(Runnable::run);
    }

    private void applyTo(Parent root) {
        for (Theme t : Theme.values()) root.getStyleClass().remove(t.styleClass());
        root.getStyleClass().add(current.styleClass());
    }
}
