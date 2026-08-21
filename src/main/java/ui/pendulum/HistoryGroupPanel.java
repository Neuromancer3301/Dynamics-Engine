package ui.pendulum;

import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * The sidebar's "History" group: the time-travel scrubbing slider. Moved
 * out of {@code ui.ControlPanel} — see round 1 §10 of the UI restructuring
 * plan.
 */
public final class HistoryGroupPanel extends VBox {

    // Kept as a field so the render loop can move it (auto-tracking "now")
    // without disturbing an in-progress user drag — see setHistoryPositionLive.
    private final Slider historySlider = new Slider(0, 0, 0);
    private final Label historyLabel = SidebarControlFactory.historyValueLabel();

    private Runnable onScrubStart;
    private Consumer<Integer> onScrubTo;
    private Runnable onScrubEnd;

    public HistoryGroupPanel() {
        super(10);

        Label lHistory = SidebarControlFactory.sectionLabel("History");
        historySlider.setMaxWidth(Double.MAX_VALUE);
        historySlider.getStyleClass().add("sidebar-slider");
        historySlider.setAccessibleText("Scrub through the last 30 seconds of simulation history");
        lHistory.setLabelFor(historySlider);
        historySlider.setOnMousePressed(e -> { if (onScrubStart != null) onScrubStart.run(); });
        historySlider.setOnMouseReleased(e -> { if (onScrubEnd != null) onScrubEnd.run(); });
        historySlider.valueProperty().addListener((o, ov, nv) -> {
            if (onScrubTo != null) onScrubTo.accept(nv.intValue());
        });
        Label historyHint = SidebarControlFactory.hintLabel("Drag to replay the last ~30s. The simulation keeps running live underneath.");

        getChildren().setAll(lHistory, SidebarControlFactory.hRow(historySlider, historyLabel), historyHint);
    }

    /** Called when the user presses down on the history slider — the start of a scrub gesture. */
    public void setOnScrubStart(Runnable callback) { this.onScrubStart = callback; }
    /** Called with the target history index on every value change while scrubbing. */
    public void setOnScrubTo(Consumer<Integer> callback) { this.onScrubTo = callback; }
    /** Called when the user releases the history slider — the end of a scrub gesture. */
    public void setOnScrubEnd(Runnable callback) { this.onScrubEnd = callback; }

    /** Keeps the slider's range in sync as history accumulates. Safe to call every frame, including mid-drag. */
    public void updateHistoryRange(int maxIndex) {
        historySlider.setMax(Math.max(0, maxIndex));
    }

    /** Moves the slider to track "now" — only call while the user isn't actively scrubbing, or this fights their drag. */
    public void setHistoryPositionLive(int index) {
        historySlider.setValue(index);
        historyLabel.setText("LIVE");
    }

    /** Updates only the label, deliberately leaving the slider's value alone so an in-progress drag isn't disturbed. */
    public void setHistoryPositionScrubbed(double secondsAgo) {
        historyLabel.setText(String.format("-%.1fs", secondsAgo));
    }
}
