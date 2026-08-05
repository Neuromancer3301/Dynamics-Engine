package controller;

import config.AppConfig;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import navigation.Navigable;
import navigation.SceneRouter;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Second stub screen — exists mainly as the copy-pasteable template for
 * future simple screens: an FXML file, a controller implementing {@link
 * Navigable}, a "← Menu" button, one {@link navigation.Route} constant.
 */
public final class AboutController implements Initializable, Navigable {

    @FXML private Label versionLabel;

    private SceneRouter router;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        versionLabel.setText(AppConfig.APP_NAME + " · v" + AppConfig.APP_VERSION);
    }

    @FXML
    private void handleBack() {
        router.back();
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }
}
