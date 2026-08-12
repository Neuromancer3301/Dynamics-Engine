package navigation;

/**
 * Enumerates every screen the shell can navigate to.
 *
 * <p><b>Extension point:</b> adding a screen to the app is — add a constant
 * here pointing at its FXML file, add the FXML + controller pair it names,
 * done. {@link SceneRouter} needs no changes to support it.
 */
public enum Route {
    MAIN_MENU ("/fxml/MainMenu.fxml",   "Home"),
    SIMULATION("/fxml/Simulation.fxml", "Simulation"),
    SETTINGS  ("/fxml/Settings.fxml",   "Settings"),
    ABOUT     ("/fxml/About.fxml",      "About");

    private final String fxmlPath;
    private final String title;

    Route(String fxmlPath, String title) {
        this.fxmlPath = fxmlPath;
        this.title = title;
    }

    /** Classpath location of this screen's FXML file. */
    public String fxmlPath() {
        return fxmlPath;
    }

    /** Human-readable name shown in the window title bar. */
    public String title() {
        return title;
    }
}
