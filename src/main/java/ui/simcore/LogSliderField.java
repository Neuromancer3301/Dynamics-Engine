package ui.simcore;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * Reusable JavaFX component coupling a logarithmic {@link Slider}
 * mapping [log10(min), log10(max)] linearly with a synchronized
 * {@link TextField} formatted in scientific notation (%.3e).
 */
public class LogSliderField extends VBox {

    private final double minVal;
    private final double maxVal;
    private final String unitLabel;

    private final Slider slider;
    private final TextField textField;
    private final DoubleProperty value = new SimpleDoubleProperty();

    private boolean updating = false;

    public LogSliderField(double minVal, double maxVal, double initialVal) {
        this(minVal, maxVal, initialVal, "");
    }

    public LogSliderField(double minVal, double maxVal, double initialVal, String unitLabel) {
        super(4);
        if (minVal <= 0 || maxVal <= minVal) {
            throw new IllegalArgumentException("minVal must be > 0 and maxVal must be > minVal");
        }

        this.minVal = minVal;
        this.maxVal = maxVal;
        this.unitLabel = unitLabel;

        double clampedInit = Math.max(minVal, Math.min(maxVal, initialVal));
        this.value.set(clampedInit);

        double logMin = Math.log10(minVal);
        double logMax = Math.log10(maxVal);
        double logInit = Math.log10(clampedInit);

        slider = new Slider(logMin, logMax, logInit);
        slider.setMaxWidth(Double.MAX_VALUE);

        textField = new TextField(String.format(Locale.US, "%.3e", clampedInit));
        textField.getStyleClass().add("dialog-field");
        HBox.setHgrow(textField, Priority.ALWAYS);

        HBox topRow = new HBox(6, textField);
        topRow.setAlignment(Pos.CENTER_LEFT);

        if (unitLabel != null && !unitLabel.isBlank()) {
            Label unit = new Label(unitLabel);
            unit.getStyleClass().add("dialog-unit-label");
            topRow.getChildren().add(unit);
        }

        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            updating = true;
            try {
                double realVal = Math.pow(10.0, newVal.doubleValue());
                value.set(realVal);
                textField.setText(String.format(Locale.US, "%.3e", realVal));
                textField.setStyle("");
            } finally {
                updating = false;
            }
        });

        textField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            try {
                double parsed = Double.parseDouble(newVal.trim());
                if (Double.isFinite(parsed) && parsed >= minVal && parsed <= maxVal) {
                    updating = true;
                    try {
                        value.set(parsed);
                        slider.setValue(Math.log10(parsed));
                        textField.setStyle("");
                    } finally {
                        updating = false;
                    }
                } else {
                    textField.setStyle("-fx-border-color: -danger;");
                }
            } catch (Exception ex) {
                textField.setStyle("-fx-border-color: -danger;");
            }
        });

        value.addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            double v = newVal.doubleValue();
            if (Double.isFinite(v) && v >= minVal && v <= maxVal) {
                updating = true;
                try {
                    slider.setValue(Math.log10(v));
                    textField.setText(String.format(Locale.US, "%.3e", v));
                    textField.setStyle("");
                } finally {
                    updating = false;
                }
            }
        });

        getChildren().addAll(topRow, slider);
        getStyleClass().add("log-slider-field");
    }

    public DoubleProperty valueProperty() {
        return value;
    }

    public double getValue() {
        return value.get();
    }

    public void setValue(double val) {
        if (!Double.isFinite(val)) return;
        double clamped = Math.max(minVal, Math.min(maxVal, val));
        updating = true;
        try {
            value.set(clamped);
            slider.setValue(Math.log10(clamped));
            textField.setText(String.format(Locale.US, "%.3e", clamped));
            textField.setStyle("");
        } finally {
            updating = false;
        }
    }

    public double getMinVal() { return minVal; }
    public double getMaxVal() { return maxVal; }
    public String getUnitLabel() { return unitLabel; }
    public Slider getSlider() { return slider; }
    public TextField getTextField() { return textField; }
}
