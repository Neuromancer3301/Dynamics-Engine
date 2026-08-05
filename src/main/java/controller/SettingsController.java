package controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import navigation.Navigable;
import navigation.SceneRouter;
import theme.Theme;
import theme.ThemeManager;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Preferences screen: theme, plus the two accessibility toggles
 * ({@link ThemeManager#isReducedMotion} and {@link
 * ThemeManager#isColorBlindSafePalette}) that live in {@code
 * ThemeManager} for the reasons documented there. Both apply immediately
 * to chrome shared across screens (menu transitions, hover effects); the
 * simulation canvas specifically reads them once at its own {@code
 * initialize()}, so a change here takes effect the next time that screen
 * opens rather than live, mid-simulation.
 */
public final class SettingsController implements Initializable, Navigable {

    @FXML private Label currentThemeLabel;
    @FXML private Button toggleThemeButton;
    @FXML private CheckBox reducedMotionCheck;
    @FXML private CheckBox colorBlindCheck;

    private SceneRouter router;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        refreshThemeLabel();
        reducedMotionCheck.setSelected(ThemeManager.getInstance().isReducedMotion());
        colorBlindCheck.setSelected(ThemeManager.getInstance().isColorBlindSafePalette());
    }

    @FXML
    private void handleToggleTheme() {
        ThemeManager.getInstance().toggle();
        refreshThemeLabel();
    }

    @FXML
    private void handleToggleReducedMotion() {
        ThemeManager.getInstance().setReducedMotion(reducedMotionCheck.isSelected());
    }

    @FXML
    private void handleToggleColorBlindSafe() {
        ThemeManager.getInstance().setColorBlindSafePalette(colorBlindCheck.isSelected());
    }

    @FXML
    private void handleBack() {
        router.back();
    }

    private void refreshThemeLabel() {
        Theme current = ThemeManager.getInstance().getCurrent();
        currentThemeLabel.setText("Current theme: " + current.name().toLowerCase());
        toggleThemeButton.setText(current == Theme.DARK ? "Switch to Light" : "Switch to Dark");
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }
}
