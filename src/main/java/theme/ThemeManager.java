package theme;

import config.AppConfig;
import javafx.scene.Parent;
import javafx.scene.transform.Scale;

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
 * preference, for the same underlying reason: JavaFX has no bridge to the
 * OS's own text-scale accessibility setting, and — unlike a browser —
 * its CSS engine supports no relative font unit (no {@code em}/{@code rem}/
 * {@code %}, only {@code px}/{@code pt}), and {@code theme.css} hardcodes a
 * {@code px} size on essentially every text-bearing class for layout
 * density reasons. That combination means there is no clean way to make
 * "100 more DPI-independent px" ripple through every class from one
 * variable. The honest option taken here is a uniform {@link Scale}
 * transform on the active root — the same technique a browser's Ctrl+/Ctrl-
 * zoom uses — applied on top of everything, including hardcoded sizes.
 * Deliberately capped modestly (see {@link #MAX_FONT_SCALE}): a transform
 * zoom doesn't reflow layout or grow the window, so a large factor risks
 * clipping sidebar content against the window edge.
 */
public final class ThemeManager {

    private static final ThemeManager INSTANCE = new ThemeManager();
    private static final String PREF_KEY_THEME           = "theme";
    private static final String PREF_KEY_REDUCED_MOTION  = "reducedMotion";
    private static final String PREF_KEY_COLORBLIND_SAFE = "colorBlindSafePalette";
    private static final String PREF_KEY_FONT_SCALE       = "fontScale";

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

    public double getFontScale() { return fontScale; }

    public void setFontScale(double scale) {
        double clamped = clampFontScale(scale);
        if (clamped == this.fontScale) return;
        this.fontScale = clamped;
        prefs.putDouble(PREF_KEY_FONT_SCALE, clamped);
        if (activeRoot != null) applyFontScale(activeRoot);
        listeners.forEach(Runnable::run);
    }

    private static double clampFontScale(double scale) {
        return Math.max(MIN_FONT_SCALE, Math.min(MAX_FONT_SCALE, scale));
    }

    private void applyTo(Parent root) {
        for (Theme t : Theme.values()) root.getStyleClass().remove(t.styleClass());
        root.getStyleClass().add(current.styleClass());
        applyFontScale(root);
    }

    private void applyFontScale(Parent root) {
        root.getTransforms().removeIf(t -> t instanceof Scale);
        if (fontScale != MIN_FONT_SCALE) {
            // Pivot at the origin (top-left): the scene's own top-left
            // corner is the one edge every screen in this app keeps clear
            // (padding, not controls), so that's the direction least likely
            // to clip something under the modest cap above.
            root.getTransforms().add(new Scale(fontScale, fontScale, 0, 0));
        }
    }
}
