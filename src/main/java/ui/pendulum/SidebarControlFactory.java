package ui.pendulum;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Small shared factories for the sidebar's grouped panels (round 1 §10 of
 * the UI restructuring plan) — pulled out of {@code ui.ControlPanel} so
 * {@code StatusPanel}/{@code MotionGroupPanel}/{@code ChaosGroupPanel}/
 * {@code GraphsGroupPanel}/{@code HistoryGroupPanel}/{@code
 * DisplayGroupPanel} share one copy instead of six.
 */
final class SidebarControlFactory {

    private SidebarControlFactory() {}

    static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        l.setPadding(new javafx.geometry.Insets(4, 0, 0, 0));
        return l;
    }

    /** A monospaced status line — monospace so digits stay column-aligned as values change and don't jitter. */
    static Label styledLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-status-label");
        return l;
    }

    /** The right-hand history readout ("LIVE" or "-3.4s"). Fixed minimum width and right alignment stop it shifting as the text length changes. */
    static Label historyValueLabel() {
        Label l = styledLabel("LIVE");
        l.setMinWidth(58);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
    }

    /** Small wrapped explanatory text under a control. */
    static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-hint");
        l.setWrapText(true);
        return l;
    }

    /** A themed, full-width slider. */
    static Slider slider(double min, double max, double val) {
        Slider s = new Slider(min, max, val);
        s.setMaxWidth(Double.MAX_VALUE);
        s.getStyleClass().add("sidebar-slider");
        return s;
    }

    /** The small right-aligned number box paired with a slider. */
    static TextField numericField(double initial) {
        TextField f = new TextField(String.format("%.2f", initial));
        f.getStyleClass().add("sidebar-numeric-field");
        f.setPrefWidth(60);
        f.setAlignment(Pos.CENTER_RIGHT);
        return f;
    }

    /** Enter commits a valid number (clamped to the slider's range); anything invalid reverts to the slider's current value. */
    static void wireNumericFieldToSlider(TextField field, Slider slider) {
        field.setOnAction(e -> {
            try {
                double value = Double.parseDouble(field.getText().trim());
                slider.setValue(Math.max(slider.getMin(), Math.min(slider.getMax(), value)));
            } catch (NumberFormatException ex) {
                field.setText(String.format("%.2f", slider.getValue()));
            }
        });
    }

    /** Lays a slider beside its value label, with the slider taking all spare width so the label stays a fixed size. */
    static HBox hRow(Slider slider, Node value) {
        HBox box = new HBox(6, slider, value);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        return box;
    }

    /** Applies the shared sidebar button styling. Takes {@code ButtonBase} so it serves both {@code Button} and {@code ToggleButton}. */
    static void styleButton(ButtonBase b) {
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("sidebar-button");
    }

    static ToggleButton graphModeButton(String text) {
        ToggleButton b = new ToggleButton(text);
        styleButton(b);
        return b;
    }

    /** A thin divider between sidebar sections. A new instance each call, since a JavaFX node can only occupy one place in the scene graph. */
    static Separator sep() {
        Separator s = new Separator();
        s.getStyleClass().add("sidebar-separator");
        return s;
    }
}
