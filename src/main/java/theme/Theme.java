package theme;

/**
 * The set of visual themes the shell supports.
 *
 * <p><b>Extension point:</b> to add a theme, add a constant here and a
 * matching {@code .theme-xxx} token block in {@code theme.css}. No other
 * class references a theme name directly — {@link ThemeManager} and every
 * stylesheet key off the enum constant and its {@link #styleClass()}.
 */
public enum Theme {
    DARK("theme-dark"),
    LIGHT("theme-light");

    private final String styleClass;

    Theme(String styleClass) {
        this.styleClass = styleClass;
    }

    /** The CSS class this theme maps to in {@code theme.css}, e.g. {@code theme-dark}. */
    public String styleClass() {
        return styleClass;
    }
}
