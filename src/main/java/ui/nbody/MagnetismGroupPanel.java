package ui.nbody;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Dedicated sidebar panel for planetary magnetism, solar wind plasma dynamics,
 * and magnetopause standoff visualization (Phase 3 "Lines of Force").
 */
public final class MagnetismGroupPanel extends VBox {

    private final NBodyCanvas canvas;

    public MagnetismGroupPanel(NBodyCanvas canvas) {
        super(10);
        this.canvas = canvas;

        // ---- Vector Field Tracing Mode ----
        Label modeHeader = sectionLabel("Tracing Mode");
        ComboBox<NBodyRenderer.TracingMode> modeBox =
                new ComboBox<>(FXCollections.observableArrayList(NBodyRenderer.TracingMode.values()));
        modeBox.setMaxWidth(Double.MAX_VALUE);
        modeBox.setValue(canvas.getTracingMode());
        modeBox.setCellFactory(lv -> tracingModeCell());
        modeBox.setButtonCell(tracingModeCell());
        modeBox.setOnAction(e -> canvas.setTracingMode(modeBox.getValue()));

        Label modeHint = hintLabel("Closed-Form Parametric computes analytic dipole loops deformed by Chapman-Ferraro/Shue "
                + "standoff (<0.1 ms/body). Numerical RK4 integrates multi-body vector streamlines through ∑ Bᵢ.");

        // ---- Display Toggles ----
        Label toggleHeader = sectionLabel("Field & Wind Visualization");

        CheckBox cbFields = new CheckBox("Show Magnetic Fields");
        cbFields.getStyleClass().add("sidebar-checkbox");
        cbFields.setTextFill(javafx.scene.paint.Color.web("#D6D6DC"));
        cbFields.setSelected(canvas.isShowMagneticFields());
        cbFields.setOnAction(e -> canvas.setShowMagneticFields(cbFields.isSelected()));

        CheckBox cbWind = new CheckBox("Show Solar Wind Plasma");
        cbWind.getStyleClass().add("sidebar-checkbox");
        cbWind.setTextFill(javafx.scene.paint.Color.web("#D6D6DC"));
        cbWind.setSelected(canvas.isShowSolarWind());
        cbWind.setOnAction(e -> canvas.setShowSolarWind(cbWind.isSelected()));

        CheckBox cbBowShock = new CheckBox("Show Magnetopause Bow Shock");
        cbBowShock.getStyleClass().add("sidebar-checkbox");
        cbBowShock.setTextFill(javafx.scene.paint.Color.web("#D6D6DC"));
        cbBowShock.setSelected(canvas.isShowBowShock());
        cbBowShock.setOnAction(e -> canvas.setShowBowShock(cbBowShock.isSelected()));

        VBox togglesBox = new VBox(6, cbFields, cbWind, cbBowShock);

        // ---- Field Line Density Slider ----
        Label densityHeader = sectionLabel("Field Line Density");
        Slider densitySlider = new Slider(4, 24, canvas.getFieldLineDensity());
        densitySlider.setBlockIncrement(1);
        densitySlider.setMajorTickUnit(4);
        densitySlider.setSnapToTicks(true);
        densitySlider.getStyleClass().add("sidebar-slider");

        Label densityValue = new Label(String.valueOf(canvas.getFieldLineDensity()));
        densityValue.getStyleClass().add("sidebar-mono-readout");
        densityValue.setTextFill(javafx.scene.paint.Color.web("#3DDCC7"));

        densitySlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int val = newVal.intValue();
            densityValue.setText(String.valueOf(val));
            canvas.setFieldLineDensity(val);
        });

        HBox densityRow = new HBox(8, densitySlider, densityValue);
        densityRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(densitySlider, Priority.ALWAYS);

        // ---- Auroral Luminescence Slider ----
        Label lumHeader = sectionLabel("Auroral Luminescence");
        Slider lumSlider = new Slider(0.1, 1.0, canvas.getAuroralLuminescence());
        lumSlider.getStyleClass().add("sidebar-slider");

        Label lumValue = new Label(String.format("%.0f%%", canvas.getAuroralLuminescence() * 100));
        lumValue.getStyleClass().add("sidebar-mono-readout");
        lumValue.setTextFill(javafx.scene.paint.Color.web("#3DDCC7"));

        lumSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            double val = newVal.doubleValue();
            lumValue.setText(String.format("%.0f%%", val * 100));
            canvas.setAuroralLuminescence(val);
        });

        HBox lumRow = new HBox(8, lumSlider, lumValue);
        lumRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(lumSlider, Priority.ALWAYS);

        Label lumHint = hintLabel("Controls field line and auroral plasma emission intensity.");

        getChildren().setAll(
                modeHeader, modeBox, modeHint,
                sep(),
                toggleHeader, togglesBox,
                sep(),
                densityHeader, densityRow,
                sep(),
                lumHeader, lumRow, lumHint
        );
    }

    private static ListCell<NBodyRenderer.TracingMode> tracingModeCell() {
        return new ListCell<>() {
            @Override protected void updateItem(NBodyRenderer.TracingMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        };
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        return l;
    }

    private static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-hint");
        l.setWrapText(true);
        return l;
    }

    private static Separator sep() {
        Separator s = new Separator();
        s.getStyleClass().add("sidebar-separator");
        return s;
    }
}
