package ui.simcore;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * Reusable JavaFX component coupling numeric input across three configurable
 * scale modes:
 * <ul>
 *     <li>{@link ScaleMode#LOGARITHMIC}: Single slider mapping [log10(min), log10(max)] linearly</li>
 *     <li>{@link ScaleMode#LINEAR}: Single linear slider dynamically scoped to the current decade [0, 10ⁿ⁺¹] for uniform precision</li>
 *     <li>{@link ScaleMode#EXPONENT}: Dual sliders — one for the mantissa [1.0, 10.0) and one for integer powers of 10</li>
 * </ul>
 * Synchronized with an editable {@link TextField} formatted in scientific notation (%.3e).
 */
public class LogSliderField extends VBox {

    public enum ScaleMode {
        LOGARITHMIC("Log Scale"),
        LINEAR("Normal Scale"),
        EXPONENT("Constant \u00D7 10\u207F");

        private final String label;
        ScaleMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final double minVal;
    private final double maxVal;
    private final int minExp;
    private final int maxExp;
    private final String unitLabel;

    private final Slider slider;         // Logarithmic slider (preserved for backwards-compatibility & getSlider())
    private final Slider linearSlider;   // Linear normal scale slider
    private final Label linearInfoLabel;
    private final VBox linearBox;

    private final Slider mantissaSlider; // Mantissa slider [1.0, 10.0)
    private final Slider exponentSlider; // Exponent slider [minExp, maxExp]
    private final Label mantissaLabel;
    private final Label exponentLabel;
    private final VBox exponentBox;
    private final VBox sliderContainer;
    private final ComboBox<ScaleMode> modeSelector;

    private final TextField textField;
    private final DoubleProperty value = new SimpleDoubleProperty();

    private boolean updating = false;

    public LogSliderField(double minVal, double maxVal, double initialVal) {
        this(minVal, maxVal, initialVal, "", ScaleMode.LOGARITHMIC);
    }

    public LogSliderField(double minVal, double maxVal, double initialVal, String unitLabel) {
        this(minVal, maxVal, initialVal, unitLabel, ScaleMode.LOGARITHMIC);
    }

    public LogSliderField(double minVal, double maxVal, double initialVal, String unitLabel, ScaleMode defaultMode) {
        super(4);
        if (minVal <= 0 || maxVal <= minVal) {
            throw new IllegalArgumentException("minVal must be > 0 and maxVal must be > minVal");
        }

        this.minVal = minVal;
        this.maxVal = maxVal;
        this.minExp = (int) Math.floor(Math.log10(minVal));
        this.maxExp = (int) Math.ceil(Math.log10(maxVal));
        this.unitLabel = unitLabel;

        double clampedInit = Math.max(minVal, Math.min(maxVal, initialVal));
        this.value.set(clampedInit);

        double logMin = Math.log10(minVal);
        double logMax = Math.log10(maxVal);
        double logInit = Math.log10(clampedInit);

        // 1. Log slider
        slider = new Slider(logMin, logMax, logInit);
        slider.setMaxWidth(Double.MAX_VALUE);

        // 2. Linear slider (dynamically bounded to current decade)
        linearInfoLabel = new Label();
        linearInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");
        linearSlider = new Slider(0.0, maxVal, clampedInit);
        linearSlider.setMaxWidth(Double.MAX_VALUE);
        linearBox = new VBox(2, linearInfoLabel, linearSlider);
        updateLinearBounds(clampedInit);

        // 3. Dual sliders: Mantissa [1.0, 10.0) and Exponent [minExp, maxExp]
        int initExp = (int) Math.floor(Math.log10(clampedInit));
        initExp = Math.max(minExp, Math.min(maxExp, initExp));
        double initMantissa = clampedInit / Math.pow(10.0, initExp);
        if (initMantissa >= 10.0 && initExp < maxExp) {
            initMantissa = 1.0;
            initExp++;
        }

        mantissaSlider = new Slider(1.0, 10.0, initMantissa);
        mantissaSlider.setMaxWidth(Double.MAX_VALUE);

        exponentSlider = new Slider(minExp, maxExp, initExp);
        exponentSlider.setMaxWidth(Double.MAX_VALUE);
        exponentSlider.setSnapToTicks(true);
        exponentSlider.setMajorTickUnit(1.0);
        exponentSlider.setMinorTickCount(0);

        mantissaLabel = new Label(String.format(Locale.US, "Constant (c): %.2f", initMantissa));
        mantissaLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");

        exponentLabel = new Label("Power: " + formatExponent(initExp));
        exponentLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-fg-muted;");

        VBox mantissaBox = new VBox(2, mantissaLabel, mantissaSlider);
        VBox expRowBox = new VBox(2, exponentLabel, exponentSlider);
        exponentBox = new VBox(4, mantissaBox, expRowBox);

        // Slider container switching visible controls
        sliderContainer = new VBox(slider);

        // Text field & Unit
        textField = new TextField(String.format(Locale.US, "%.3e", clampedInit));
        textField.getStyleClass().add("dialog-field");
        HBox.setHgrow(textField, Priority.ALWAYS);

        // Scale mode selector
        modeSelector = new ComboBox<>();
        modeSelector.getItems().addAll(ScaleMode.values());
        modeSelector.setValue(defaultMode != null ? defaultMode : ScaleMode.LOGARITHMIC);
        modeSelector.getStyleClass().add("dialog-field");
        modeSelector.setMinWidth(125);
        modeSelector.setPrefWidth(130);

        HBox topRow = new HBox(6, textField);
        topRow.setAlignment(Pos.CENTER_LEFT);

        if (unitLabel != null && !unitLabel.isBlank()) {
            Label unit = new Label(unitLabel);
            unit.getStyleClass().add("dialog-unit-label");
            topRow.getChildren().add(unit);
        }
        topRow.getChildren().add(modeSelector);

        // Switch visible slider view on mode change
        modeSelector.valueProperty().addListener((obs, oldMode, newMode) -> {
            applyScaleMode(newMode);
        });

        // 1. Log slider listener
        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            updating = true;
            try {
                double realVal = Math.max(minVal, Math.min(maxVal, Math.pow(10.0, newVal.doubleValue())));
                value.set(realVal);
                textField.setText(String.format(Locale.US, "%.3e", realVal));
                textField.setStyle("");
                syncOtherSliders(ScaleMode.LOGARITHMIC, realVal);
            } finally {
                updating = false;
            }
        });

        // 2. Linear slider listener
        linearSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            updating = true;
            try {
                double v = newVal.doubleValue();
                // Dynamic decade expansion / contraction if dragging at bounds
                if (v >= linearSlider.getMax() * 0.999 && linearSlider.getMax() < maxVal) {
                    double newMax = Math.min(maxVal, linearSlider.getMax() * 10.0);
                    linearSlider.setMax(newMax);
                    linearInfoLabel.setText(String.format(Locale.US, "Linear Range: 0 to %.2e %s", newMax, unitLabel));
                } else if (v <= linearSlider.getMax() * 0.08 && linearSlider.getMax() > minVal * 10.0 && v > 0) {
                    double newMax = Math.max(minVal * 10.0, linearSlider.getMax() / 10.0);
                    linearSlider.setMax(newMax);
                    linearInfoLabel.setText(String.format(Locale.US, "Linear Range: 0 to %.2e %s", newMax, unitLabel));
                }

                double realVal = Math.max(minVal, Math.min(maxVal, v));
                value.set(realVal);
                textField.setText(String.format(Locale.US, "%.3e", realVal));
                textField.setStyle("");
                syncOtherSliders(ScaleMode.LINEAR, realVal);
            } finally {
                updating = false;
            }
        });

        // 3. Mantissa slider listener
        mantissaSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            updating = true;
            try {
                double c = newVal.doubleValue();
                int p = (int) Math.round(exponentSlider.getValue());
                double realVal = Math.max(minVal, Math.min(maxVal, c * Math.pow(10.0, p)));
                value.set(realVal);
                textField.setText(String.format(Locale.US, "%.3e", realVal));
                textField.setStyle("");
                mantissaLabel.setText(String.format(Locale.US, "Constant (c): %.2f", c));
                syncOtherSliders(ScaleMode.EXPONENT, realVal);
            } finally {
                updating = false;
            }
        });

        // 4. Exponent slider listener
        exponentSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            updating = true;
            try {
                double c = mantissaSlider.getValue();
                int p = (int) Math.round(newVal.doubleValue());
                double realVal = Math.max(minVal, Math.min(maxVal, c * Math.pow(10.0, p)));
                value.set(realVal);
                textField.setText(String.format(Locale.US, "%.3e", realVal));
                textField.setStyle("");
                exponentLabel.setText("Power: " + formatExponent(p));
                syncOtherSliders(ScaleMode.EXPONENT, realVal);
            } finally {
                updating = false;
            }
        });

        // Text field listener
        textField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            try {
                double parsed = Double.parseDouble(newVal.trim());
                if (Double.isFinite(parsed) && parsed >= minVal && parsed <= maxVal) {
                    updating = true;
                    try {
                        value.set(parsed);
                        syncAllSliders(parsed);
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

        // Programmatic value property listener
        value.addListener((obs, oldVal, newVal) -> {
            if (updating) return;
            double v = newVal.doubleValue();
            if (Double.isFinite(v) && v >= minVal && v <= maxVal) {
                updating = true;
                try {
                    syncAllSliders(v);
                    textField.setText(String.format(Locale.US, "%.3e", v));
                    textField.setStyle("");
                } finally {
                    updating = false;
                }
            }
        });

        applyScaleMode(modeSelector.getValue());
        getChildren().addAll(topRow, sliderContainer);
        getStyleClass().add("log-slider-field");
    }

    private void applyScaleMode(ScaleMode mode) {
        if (mode == null) mode = ScaleMode.LOGARITHMIC;
        sliderContainer.getChildren().clear();
        switch (mode) {
            case LOGARITHMIC -> sliderContainer.getChildren().add(slider);
            case LINEAR -> sliderContainer.getChildren().add(linearBox);
            case EXPONENT -> sliderContainer.getChildren().add(exponentBox);
        }
        syncAllSliders(value.get());

        // Automatically resize the dialog stage window to fit content without hiding the ButtonBar
        Platform.runLater(() -> {
            if (getScene() != null && getScene().getWindow() != null) {
                getScene().getWindow().sizeToScene();
            }
        });
    }

    private void updateLinearBounds(double realVal) {
        if (realVal <= 0 || !Double.isFinite(realVal)) return;
        int exp = (int) Math.floor(Math.log10(realVal));
        double decadeMax = Math.min(maxVal, Math.pow(10.0, exp + 1));
        double decadeMin = 0.0;
        if (decadeMax <= minVal) {
            decadeMax = Math.min(maxVal, minVal * 10.0);
        }
        linearSlider.setMin(decadeMin);
        linearSlider.setMax(decadeMax);
        linearSlider.setValue(realVal);
        linearInfoLabel.setText(String.format(Locale.US, "Linear Range: 0 to %.2e %s", decadeMax, unitLabel));
    }

    private void syncAllSliders(double realVal) {
        slider.setValue(Math.log10(realVal));
        updateLinearBounds(realVal);

        int exp = (int) Math.floor(Math.log10(realVal));
        exp = Math.max(minExp, Math.min(maxExp, exp));
        double mantissa = realVal / Math.pow(10.0, exp);
        if (mantissa >= 10.0 && exp < maxExp) {
            mantissa = 1.0;
            exp++;
        }
        mantissaSlider.setValue(mantissa);
        exponentSlider.setValue(exp);
        mantissaLabel.setText(String.format(Locale.US, "Constant (c): %.2f", mantissa));
        exponentLabel.setText("Power: " + formatExponent(exp));
    }

    private void syncOtherSliders(ScaleMode activeMode, double realVal) {
        if (activeMode != ScaleMode.LOGARITHMIC) {
            slider.setValue(Math.log10(realVal));
        }
        if (activeMode != ScaleMode.LINEAR) {
            updateLinearBounds(realVal);
        }
        if (activeMode != ScaleMode.EXPONENT) {
            int exp = (int) Math.floor(Math.log10(realVal));
            exp = Math.max(minExp, Math.min(maxExp, exp));
            double mantissa = realVal / Math.pow(10.0, exp);
            if (mantissa >= 10.0 && exp < maxExp) {
                mantissa = 1.0;
                exp++;
            }
            mantissaSlider.setValue(mantissa);
            exponentSlider.setValue(exp);
            mantissaLabel.setText(String.format(Locale.US, "Constant (c): %.2f", mantissa));
            exponentLabel.setText("Power: " + formatExponent(exp));
        }
    }

    public static String formatExponent(int exp) {
        String s = Integer.toString(exp);
        StringBuilder sb = new StringBuilder("10");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '-' -> sb.append('\u207B');
                case '0' -> sb.append('\u2070');
                case '1' -> sb.append('\u00B9');
                case '2' -> sb.append('\u00B2');
                case '3' -> sb.append('\u00B3');
                case '4' -> sb.append('\u2074');
                case '5' -> sb.append('\u2075');
                case '6' -> sb.append('\u2076');
                case '7' -> sb.append('\u2077');
                case '8' -> sb.append('\u2078');
                case '9' -> sb.append('\u2079');
                default -> sb.append(ch);
            }
        }
        return sb.toString();
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
            syncAllSliders(clamped);
            textField.setText(String.format(Locale.US, "%.3e", clamped));
            textField.setStyle("");
        } finally {
            updating = false;
        }
    }

    public ScaleMode getScaleMode() {
        return modeSelector.getValue();
    }

    public void setScaleMode(ScaleMode mode) {
        if (mode != null) {
            modeSelector.setValue(mode);
        }
    }

    public ComboBox<ScaleMode> getModeSelector() { return modeSelector; }
    public double getMinVal() { return minVal; }
    public double getMaxVal() { return maxVal; }
    public String getUnitLabel() { return unitLabel; }
    public Slider getSlider() { return slider; }
    public Slider getLinearSlider() { return linearSlider; }
    public Slider getMantissaSlider() { return mantissaSlider; }
    public Slider getExponentSlider() { return exponentSlider; }
    public TextField getTextField() { return textField; }
}
