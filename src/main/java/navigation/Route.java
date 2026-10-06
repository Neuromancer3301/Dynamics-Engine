package navigation;

/*
  Key responsibilities:
    1. Centrel screening catalog
    2. FXLM mapping
    3. Window title definition
    4. Type-safety and Typo prevention
    5. Easy extensibility
  */
/**
  Enumerates every screen the shell can navigate to.
  
  Extension point: adding a screen to the app is — add a constant
  here pointing at its FXML file, add the FXML + controller pair it names,
  done. {@link SceneRouter} needs no changes to support it.
 */
public enum Route {                                                                                                                                                                                                                 
  MAIN_MENU ("/fxml/MainMenu.fxml", "Home"),                                                                                                                                                                                    
  SIMULATION ("/fxml/Simulation.fxml", "Simulation"),                                                                                                                                                                              
  NBODY ("/fxml/NBody.fxml", "N-Body Gravity"),                                                                                                                                                                          
  BOIDS ("/fxml/Boids.fxml", "Boids"),                                                                                                                                                                                   
  MANUAL ("/fxml/Manual.fxml", "Manual"),                                                                                                                                                                                  
  SETTINGS ("/fxml/Settings.fxml", "Settings"),                                                                                                                                                                                
  ABOUT ("/fxml/About.fxml", "About");

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
