package ui.nbody;

import physics.integrator.IntegratorType;
import physics.nbody.NBodyConfig;
import physics.nbody.NBodyEngine;
import physics.nbody.NBodyState;
import physics.nbody.Presets;
import simulation.SimulationLoop;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Thin coordinator for the n-body sidebar's controls — the n-body analogue
 * of {@code ui.pendulum.ControlPanel}: constructs the four grouped panels
 * ({@link StatusPanel}, {@link MotionGroupPanel}, {@link BodiesGroupPanel},
 * {@link DisplayGroupPanel}) once in {@link #build}, wires whichever
 * callbacks were set beforehand into the sub-panel that actually owns them,
 * and exposes a passthrough accessor/{@code setOnXxx}/{@code updateXxx}
 * surface. See the n-body implementation spec §8.
 */
public final class ControlPanel extends VBox {

    private StatusPanel statusPanel;
    private MotionGroupPanel motionGroup;
    private BodiesGroupPanel bodiesGroup;
    private DisplayGroupPanel displayGroup;

    // Callbacks, set via setOnXxx before build() and threaded into whichever
    // sub-panel owns them once it's constructed — same pattern as
    // ui.pendulum.ControlPanel.
    private Runnable onReset;
    private Consumer<IntegratorType> onIntegratorChange;
    private Consumer<Boolean> onPauseChange;
    private Consumer<Presets.Preset> onPresetApply;
    private IntConsumer onBodyClick;

    public ControlPanel() {
        super(10);
    }

    /** Constructs the four grouped panels and wires every callback set so far into whichever one owns it. Call after setting all callbacks. */
    public void build(SimulationLoop<NBodyEngine, NBodyState> simLoop, NBodyCanvas canvas, NBodyConfig initialConfig) {
        statusPanel = new StatusPanel(initialConfig.getN());

        motionGroup = new MotionGroupPanel(simLoop, initialConfig.getGravitationalConstant(), initialConfig.getSpeedMultiplier());
        motionGroup.setOnReset(onReset);
        motionGroup.setOnIntegratorChange(onIntegratorChange);
        motionGroup.setOnPauseChange(onPauseChange);

        bodiesGroup = new BodiesGroupPanel(initialConfig);
        bodiesGroup.setOnPresetApply(onPresetApply);
        bodiesGroup.setOnBodyClick(onBodyClick);

        displayGroup = new DisplayGroupPanel(canvas);
    }

    // ---- Group/status accessors ----

    public VBox getStatusBlock()  { return statusPanel; }
    public VBox getMotionGroup()  { return motionGroup; }
    public VBox getBodiesGroup()  { return bodiesGroup; }
    public VBox getDisplayGroup() { return displayGroup; }

    // ---- Called from controller.NBodySimulationController before build() ----

    public void setOnResetCallback(Runnable r) { this.onReset = r; }
    public void setOnIntegratorChange(Consumer<IntegratorType> callback) { this.onIntegratorChange = callback; }
    public void setOnPauseChange(Consumer<Boolean> callback) { this.onPauseChange = callback; }
    public void setOnPresetApply(Consumer<Presets.Preset> callback) { this.onPresetApply = callback; }
    public void setOnBodyClick(IntConsumer callback) { this.onBodyClick = callback; }

    // ---- Called from controller.NBodySimulationController every render tick, or on state changes ----

    public void setPausedVisual(boolean paused) { motionGroup.setPausedVisual(paused); }
    public void updateBodyCount(int n) { statusPanel.updateBodyCount(n); }
    public void refreshBodies(NBodyConfig config) { bodiesGroup.refreshBodies(config); }

    public void updateStatus(NBodyState state, Double initialEnergy, Double initialMomentumX,
                              Double initialMomentumY, Double initialAngularMomentum) {
        statusPanel.updateStatus(state, initialEnergy, initialMomentumX, initialMomentumY, initialAngularMomentum);
    }
}
