package ui.nbody;

import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;

/**
 * The sidebar's "Display" group: just the Follow-COM toggle. Lives here
 * (rather than, say, Motion) since it's a pure view concern with zero
 * physics effect — the same reasoning {@code ui.pendulum.DisplayGroupPanel}
 * already applies to trail mode/velocity tint. See the n-body
 * implementation spec §8.
 */
public final class DisplayGroupPanel extends VBox {

    public DisplayGroupPanel(NBodyCanvas canvas) {
        super(10);

        ToggleButton btnFollowCom = new ToggleButton("🎯  Follow Center of Mass: Off");
        btnFollowCom.setMaxWidth(Double.MAX_VALUE);
        btnFollowCom.getStyleClass().add("sidebar-button");
        btnFollowCom.setSelected(canvas.isFollowingCenterOfMass());
        btnFollowCom.setOnAction(e -> {
            boolean on = btnFollowCom.isSelected();
            canvas.setFollowCenterOfMass(on);
            btnFollowCom.setText(on ? "🎯  Follow Center of Mass: On" : "🎯  Follow Center of Mass: Off");
        });

        Label hint = new Label("Keeps the scene's center of mass centered on screen — useful once bodies "
                + "have drifted far from the world origin. Pan/zoom still work normally while following.");
        hint.getStyleClass().add("sidebar-hint");
        hint.setWrapText(true);

        getChildren().setAll(btnFollowCom, hint);
    }
}
