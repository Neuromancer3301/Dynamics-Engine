package ui.simcore;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LogSliderFieldTest {

    @BeforeAll
    static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {
            // JavaFX toolkit already initialized
        }
    }

    @Test
    void testInitialValuesAndClamping() {
        LogSliderField field = new LogSliderField(1.0e14, 1.0e32, 1.989e30);
        assertEquals(1.989e30, field.getValue(), 1.0e20);
        assertEquals("1.989e+30", field.getTextField().getText());

        // Clamping below min
        field.setValue(1.0e10);
        assertEquals(1.0e14, field.getValue(), 1.0e10);

        // Clamping above max
        field.setValue(1.0e35);
        assertEquals(1.0e32, field.getValue(), 1.0e25);
    }

    @Test
    void testBidirectionalSynchronization() {
        LogSliderField field = new LogSliderField(1.0e14, 1.0e32, 1.0e20);

        // Update via setValue
        field.setValue(5.972e24);
        assertEquals(5.972e24, field.getValue(), 1.0e18);
        assertEquals("5.972e+24", field.getTextField().getText());
        double expectedSlider = Math.log10(5.972e24);
        assertEquals(expectedSlider, field.getSlider().getValue(), 1.0e-3);

        // Update via Slider
        field.getSlider().setValue(Math.log10(1.0e28));
        assertEquals(1.0e28, field.getValue(), 1.0e24);
        assertEquals("1.000e+28", field.getTextField().getText());

        // Update via TextField
        field.getTextField().setText("7.348e+22");
        field.getTextField().fireEvent(new javafx.event.ActionEvent());
        assertEquals(7.348e22, field.getValue(), 1.0e18);
    }

    @Test
    void testInvalidTextTriggersErrorBorder() {
        LogSliderField field = new LogSliderField(1.0e14, 1.0e32, 1.0e20);

        field.getTextField().setText("not_a_number");
        field.getTextField().fireEvent(new javafx.event.ActionEvent());

        assertTrue(field.getTextField().getStyle().contains("-danger"),
                "Invalid text input should apply danger border styling");

        // Value should remain unchanged
        assertEquals(1.0e20, field.getValue(), 1.0e15);

        // Entering valid text should clear error border
        field.getTextField().setText("2.000e+25");
        field.getTextField().fireEvent(new javafx.event.ActionEvent());

        assertFalse(field.getTextField().getStyle().contains("-danger"),
                "Valid text input should clear danger border styling");
        assertEquals(2.0e25, field.getValue(), 1.0e20);
    }

    @Test
    void testValuePropertyListener() {
        LogSliderField field = new LogSliderField(1.0e14, 1.0e32, 1.0e20);
        AtomicBoolean listenerFired = new AtomicBoolean(false);

        field.valueProperty().addListener((obs, oldVal, newVal) -> {
            listenerFired.set(true);
            assertEquals(3.5e21, newVal.doubleValue(), 1.0e16);
        });

        field.setValue(3.5e21);
        assertTrue(listenerFired.get(), "DoubleProperty listener should fire on value change");
    }

    @Test
    void testScaleModesAndSwitching() {
        LogSliderField field = new LogSliderField(1.0e14, 1.0e32, 1.0e20);
        assertEquals(LogSliderField.ScaleMode.LOGARITHMIC, field.getScaleMode());

        field.setScaleMode(LogSliderField.ScaleMode.LINEAR);
        assertEquals(LogSliderField.ScaleMode.LINEAR, field.getScaleMode());
        assertEquals(1.0e20, field.getValue(), 1.0e15);

        field.setScaleMode(LogSliderField.ScaleMode.EXPONENT);
        assertEquals(LogSliderField.ScaleMode.EXPONENT, field.getScaleMode());
        assertEquals(1.0e20, field.getValue(), 1.0e15);

        // Switching back to LOGARITHMIC preserves value
        field.setScaleMode(LogSliderField.ScaleMode.LOGARITHMIC);
        assertEquals(1.0e20, field.getValue(), 1.0e15);
    }

    @Test
    void testLinearSliderSync() {
        LogSliderField field = new LogSliderField(1.0e14, 1.0e32, 1.0e20);
        field.setScaleMode(LogSliderField.ScaleMode.LINEAR);

        field.getLinearSlider().setValue(5.0e25);
        assertEquals(5.0e25, field.getValue(), 1.0e20);
        assertEquals("5.000e+25", field.getTextField().getText());
    }

    @Test
    void testMantissaAndExponentSliderSync() {
        LogSliderField field = new LogSliderField(1.0e14, 1.0e32, 1.0e20);
        field.setScaleMode(LogSliderField.ScaleMode.EXPONENT);

        // Move mantissa to 2.5 and exponent to 26 -> 2.5e26
        field.getMantissaSlider().setValue(2.5);
        field.getExponentSlider().setValue(26);

        assertEquals(2.5e26, field.getValue(), 1.0e21);
        assertEquals("2.500e+26", field.getTextField().getText());

        // Value update syncs back to mantissa and exponent
        field.setValue(3.8e29);
        assertEquals(3.8, field.getMantissaSlider().getValue(), 1.0e-2);
        assertEquals(29, (int) Math.round(field.getExponentSlider().getValue()));
    }

    @Test
    void testFormatExponent() {
        assertEquals("10³⁰", LogSliderField.formatExponent(30));
        assertEquals("10⁻¹¹", LogSliderField.formatExponent(-11));
        assertEquals("10⁰", LogSliderField.formatExponent(0));
    }
}
