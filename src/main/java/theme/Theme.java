package theme;

import javafx.scene.paint.Color;

/**
 * The set of visual themes the shell supports.
 *
 * <p><b>Extension point:</b> to add a theme, add a constant here and a
 * matching {@code .theme-xxx} token block in {@code theme.css}. No other
 * class references a theme name directly — {@link ThemeManager} and every
 * stylesheet key off the enum constant and its {@link #styleClass()}.
 */
public enum Theme {
    // The literal Color below duplicates theme.css's -bg-base hex for this
    // theme. JavaFX can't read a looked-up CSS custom property back into
    // Java (the same reasoning component.NavCardController documents for
    // its BASE_HEIGHT/EXPANDED_GROWTH constants), so navigation.SceneRouter
    // uses this to keep Scene.fill in sync with the active theme instead of
    // defaulting to white — see backgroundColor().
    DARK("theme-dark", Color.web("#08080B")),
    LIGHT("theme-light", Color.web("#F5F5F7"));

    private final String styleClass;
    private final Color backgroundColor;

    Theme(String styleClass, Color backgroundColor) {
        this.styleClass = styleClass;
        this.backgroundColor = backgroundColor;
    }

    public String styleClass() {
        return styleClass;
    }

    /** The theme's -bg-base color, for Scene#setFill — see SceneRouter. */
    public Color backgroundColor() {
        return backgroundColor;
    }
}
