package ui.pendulum;

import physics.SimState;
import physics.integrator.IntegratorType;
import simulation.SimulationLoop;
import javafx.scene.layout.VBox;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Thin coordinator for the simulation sidebar's controls.
 *
 * <p>As of round 1 §10 of the UI restructuring plan, this class no longer
 * builds any controls directly — it constructs the six grouped panels
 * ({@link StatusPanel}, {@link MotionGroupPanel}, {@link ChaosGroupPanel},
 * {@link GraphsGroupPanel}, {@link HistoryGroupPanel}, {@link
 * DisplayGroupPanel}, all in {@code ui.pendulum}) once in {@link #build},
 * wires whichever callbacks were set beforehand into the sub-panel that
 * actually owns them, and exposes the exact same public accessor/{@code
 * setOnXxx}/{@code updateXxx}/{@code setXxxVisual} surface as before — every
 * existing {@code controller.SimulationController} call site is therefore
 * unchanged.
 */
public final class ControlPanel extends VBox {

    private StatusPanel statusPanel;
    private MotionGroupPanel motionGroup;
    private ChaosGroupPanel chaosGroup;
    private GraphsGroupPanel graphsGroup;
    private HistoryGroupPanel historyGroup;
    private DisplayGroupPanel displayGroup;

    // Callbacks, set via setOnXxx before build() and threaded into whichever
    // sub-panel owns them once it's constructed.
    private Runnable onReset;
    private Consumer<Boolean> onEnsembleToggle;
    private Consumer<Boolean> onSonifyToggle;
    private Runnable onGenerateBifurcation;
    private Runnable onResetGravityDirection;
    private BiConsumer<Boolean, Double> onCompareToggle;
    private Consumer<IntegratorType> onIntegratorChange;
    private Runnable onCompareIntegrators;
    private Runnable onScrubStart;
    private Consumer<Integer> onScrubTo;
    private Runnable onScrubEnd;
    private Consumer<Boolean> onGraphVisibilityChange;
    private Consumer<Boolean> onPauseChange;

    public ControlPanel() {
        super(10);
    }

    /**
     * Constructs the six grouped panels and wires every callback set so far
     * into whichever one owns it. Call after setting all callbacks.
     */
    public void build(SimulationLoop simLoop,
                      PendulumGraphPanel graphPanel,
                      PendulumCanvas pendulumCanvas,
                      int n) {

        statusPanel = new StatusPanel(n);

        motionGroup = new MotionGroupPanel(simLoop, graphPanel, pendulumCanvas);
        motionGroup.setOnResetGravityDirection(onResetGravityDirection);
        motionGroup.setOnReset(onReset);
        motionGroup.setOnIntegratorChange(onIntegratorChange);
        motionGroup.setOnCompareIntegrators(onCompareIntegrators);
        motionGroup.setOnPauseChange(onPauseChange);

        chaosGroup = new ChaosGroupPanel(simLoop);
        chaosGroup.setOnEnsembleToggle(onEnsembleToggle);
        chaosGroup.setOnSonifyToggle(onSonifyToggle);
        chaosGroup.setOnCompareToggle(onCompareToggle);

        graphsGroup = new GraphsGroupPanel(graphPanel);
        graphsGroup.setOnGenerateBifurcation(onGenerateBifurcation);
        graphsGroup.setOnGraphVisibilityChange(onGraphVisibilityChange);

        historyGroup = new HistoryGroupPanel();
        historyGroup.setOnScrubStart(onScrubStart);
        historyGroup.setOnScrubTo(onScrubTo);
        historyGroup.setOnScrubEnd(onScrubEnd);

        displayGroup = new DisplayGroupPanel(pendulumCanvas);
    }

    // ---- Group/status accessors ----

    public VBox getStatusBlock()  { return statusPanel; }
    public VBox getMotionGroup()  { return motionGroup; }
    public VBox getChaosGroup()   { return chaosGroup; }
    public VBox getGraphsGroup()  { return graphsGroup; }
    public VBox getHistoryGroup() { return historyGroup; }
    public VBox getDisplayGroup() { return displayGroup; }

    // ---- Called from controller.SimulationController before build() ----

    public void setOnResetCallback(Runnable r) { this.onReset = r; }
    public void setOnEnsembleToggle(Consumer<Boolean> callback) { this.onEnsembleToggle = callback; }
    public void setOnSonifyToggle(Consumer<Boolean> callback)   { this.onSonifyToggle = callback; }
    public void setOnGenerateBifurcation(Runnable callback)     { this.onGenerateBifurcation = callback; }
    public void setOnResetGravityDirection(Runnable callback)   { this.onResetGravityDirection = callback; }
    public void setOnCompareToggle(BiConsumer<Boolean, Double> callback) { this.onCompareToggle = callback; }
    public void setOnGraphVisibilityChange(Consumer<Boolean> callback) { this.onGraphVisibilityChange = callback; }
    public void setOnPauseChange(Consumer<Boolean> callback) { this.onPauseChange = callback; }
    public void setOnIntegratorChange(Consumer<IntegratorType> callback) { this.onIntegratorChange = callback; }
    public void setOnCompareIntegrators(Runnable callback) { this.onCompareIntegrators = callback; }
    public void setOnScrubStart(Runnable callback) { this.onScrubStart = callback; }
    public void setOnScrubTo(Consumer<Integer> callback) { this.onScrubTo = callback; }
    public void setOnScrubEnd(Runnable callback) { this.onScrubEnd = callback; }

    // ---- Called from controller.SimulationController every render tick, or on state changes ----

    public void setCompareVisual(boolean active) { chaosGroup.setCompareVisual(active); }
    public void setBifurcationProgress(double fraction) { graphsGroup.setBifurcationProgress(fraction); }
    public void setBifurcationRunning(boolean running) { graphsGroup.setBifurcationRunning(running); }
    public void selectBifurcationMode() { graphsGroup.selectBifurcationMode(); }
    public void updateHistoryRange(int maxIndex) { historyGroup.updateHistoryRange(maxIndex); }
    public void setHistoryPositionLive(int index) { historyGroup.setHistoryPositionLive(index); }
    public void setHistoryPositionScrubbed(double secondsAgo) { historyGroup.setHistoryPositionScrubbed(secondsAgo); }
    public void updateLyapunov(Double lambda) { statusPanel.updateLyapunov(lambda); }
    public void setEnsembleVisual(boolean active) { chaosGroup.setEnsembleVisual(active); }
    public void updateLinkCount(int n) { statusPanel.updateLinkCount(n); }
    public void setPausedVisual(boolean paused) { motionGroup.setPausedVisual(paused); }
    public void setSonifyVisual(boolean on) { chaosGroup.setSonifyVisual(on); }
    public void updateStatus(SimState state, Double initialEnergy) { statusPanel.updateStatus(state, initialEnergy); }
}
