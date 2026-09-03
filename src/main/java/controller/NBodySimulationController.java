package controller;

import javafx.animation.AnimationTimer;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import navigation.Navigable;
import navigation.SceneRouter;
import physics.integrator.IntegratorType;
import physics.nbody.NBodyConfig;
import physics.nbody.NBodyEngine;
import physics.nbody.NBodyState;
import physics.nbody.Presets;
import simulation.SimulationLoop;
import simulation.StateBuffer;
import theme.ThemeManager;
import ui.icon.Icons;
import ui.nbody.ControlPanel;
import ui.nbody.NBodyActionRailBuilder;
import ui.nbody.NBodyCanvas;
import ui.nbody.NBodyDialogFactory;
import ui.simcore.LayoutShell;
import ui.simcore.SidebarTabs;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * Hosts the n-body gravity simulation inside the FXML shell — the n-body
 * analogue of {@code controller.SimulationController}. Same assembly order
 * as that class's {@link #initialize}: engine+loop, the canvas, this
 * screen's collaborators (dialog factory, action rail, layout shell), the
 * canvas's interaction listeners, the sidebar panels, the render timer.
 * Nothing is started until {@link #onShow()}. See the n-body implementation
 * spec §9.
 *
 * <p>Smaller than {@code SimulationController} throughout, for reasons
 * documented at each cut: no graph panel/{@code GraphsGroupPanel} (no
 * Graphs tab — see the spec §8), no {@code HistoryBuffer}/scrubbing (no
 * History tab), no chaos/ensemble/compare/sonify orchestration (no
 * pendulum-chaos equivalent for n-body — the spec's own §11 non-goals).
 * {@code graphHost}/{@code canvasGraphStack} still exist on this screen's
 * FXML (a mechanical copy of {@code Simulation.fxml}, which {@link
 * LayoutShell}'s constructor requires) but {@code graphHost} is never
 * toggled open, since nothing here ever calls {@code
 * LayoutShell#setGraphVisible}.
 */
public final class NBodySimulationController implements Initializable, Navigable, NBodyDialogFactory.Host {

    @FXML private Button btnBack;
    @FXML private Label titleLabel;
    @FXML private VBox actionRail;
    @FXML private StackPane canvasGraphStack;
    @FXML private StackPane canvasHost;
    @FXML private StackPane graphHost;
    @FXML private ScrollPane sidebarScroll;
    @FXML private VBox controlHost;

    private SceneRouter router;
    private SimulationLoop<NBodyEngine, NBodyState> simLoop;
    private StateBuffer<NBodyState> stateBuffer;
    private ControlPanel controlPanel;
    private SidebarTabs sidebarTabs;
    private NBodyCanvas nbodyCanvas;
    private AnimationTimer renderTimer;

    private NBodyActionRailBuilder actionRailBuilder;
    private NBodyDialogFactory dialogFactory;
    private LayoutShell layoutShell;

    // The structural shape currently applied. Mirrors
    // SimulationController's currentConfig/originalConfig split exactly:
    // currentConfig always reflects whatever's live; originalConfig is
    // where Reset returns to (moved only by a deliberate "start fresh from
    // here" — a preset pick or the initial load).
    private NBodyConfig currentConfig;
    private NBodyConfig originalConfig;

    // NBodyEngine's constructor always starts with a fresh RK4 sized for
    // its own N — see IntegratorType's javadoc for why the previous
    // selection can't just carry over a structural rebuild. Kept here so
    // applyStructuralEdit can re-apply it at the new size (4N, not 2N —
    // see the n-body implementation spec §2.3).
    private IntegratorType selectedIntegratorType = IntegratorType.RK4;

    // Baseline values for the sidebar's three drift% readouts — captured
    // once from the first post-reset/post-rebuild frame, nulled whenever
    // the engine is rebuilt or reset. Mirrors SimulationController's own
    // initialEnergy field, extended to the two n-body-only conserved
    // quantities (see physics.nbody.NBodyState's javadoc).
    private Double initialEnergy;
    private Double initialMomentumX;
    private Double initialMomentumY;
    private Double initialAngularMomentum;

    private long frameCount;

    // Added on onShow() / removed on onHide() — see SimulationController's
    // identical field for why this must be paired, not just added once.
    private EventHandler<KeyEvent> keyHandler;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        NBodyConfig config = Presets.homeSolarSystem();
        currentConfig = config;
        originalConfig = config;
        stateBuffer = new StateBuffer<>();
        // §3/§9: fixedDt=3600s (1 hour) — orbital-timescale, not the
        // pendulum's default 0.002s. See simulation.SimulationLoop's
        // 4-arg constructor and the spec's own worked example for why.
        simLoop = new SimulationLoop<>(new NBodyEngine(config), stateBuffer, config.getSpeedMultiplier(), 3600.0);

        nbodyCanvas = new NBodyCanvas(500, 580);

        // Read once at construction, matching SimulationController's own
        // reasoning: a change to either setting takes effect next time this
        // screen opens, not mid-simulation.
        nbodyCanvas.setReducedMotion(ThemeManager.getInstance().isReducedMotion());
        nbodyCanvas.setColorBlindSafe(ThemeManager.getInstance().isColorBlindSafePalette());

        // Unmanaged for the same reason PendulumCanvas is — see
        // SimulationController's identical comment: a managed Canvas's
        // self-reported preferred size would otherwise inflate its host's
        // layout and push later siblings off-window.
        nbodyCanvas.setManaged(false);
        nbodyCanvas.widthProperty().bind(canvasHost.widthProperty());
        nbodyCanvas.heightProperty().bind(canvasHost.heightProperty());
        canvasHost.getChildren().add(nbodyCanvas);

        dialogFactory = new NBodyDialogFactory(this);

        actionRailBuilder = new NBodyActionRailBuilder(actionRail, nbodyCanvas);
        actionRailBuilder.build();

        layoutShell = new LayoutShell(sidebarScroll, graphHost, canvasGraphStack);
        layoutShell.buildSidebarToggle();

        nbodyCanvas.setDragListener(new NBodyCanvas.DragListener() {
            /** Every rendered body is grabbable. */
            @Override
            public boolean onGrab(int bodyIndex) { return true; }

            /** While held: pin the body to the pointer's world position with ZERO velocity, so it follows the mouse instead of fighting it. */
            @Override
            public void onDrag(int bodyIndex, double worldX, double worldY) {
                simLoop.submit(e -> e.setBodyState(bodyIndex, worldX, worldY, 0.0, 0.0));
            }

            /** On release: hand back the estimated fling velocity. */
            @Override
            public void onRelease(int bodyIndex, double worldX, double worldY, double vx, double vy) {
                simLoop.submit(e -> e.setBodyState(bodyIndex, worldX, worldY, vx, vy));
            }
        });

        // Selecting a body pauses the simulation, same rule as the
        // pendulum's own selection (see #setPaused).
        nbodyCanvas.setSelectionListener(bodyIndex -> {
            nbodyCanvas.setSelectedBody(bodyIndex);
            setPaused(true);
        });

        // Double-click always opens the parameter dialog — even with the
        // Add tool active, per the n-body implementation spec §6.2 (there's
        // no "insert after body k" for a double-click to mean instead).
        nbodyCanvas.setDoubleClickListener(dialogFactory::showBodyParameterDialog);

        // Right-click opens the delete dialog directly, in either tool —
        // spec §6.2's first simplification versus the pendulum.
        nbodyCanvas.setRightClickListener(dialogFactory::showDeleteBodyDialog);

        // A clean click on empty space only does something while the Add
        // tool is active — spec §6.2's second simplification. While Edit is
        // active this fires too, but there is nothing to do with it.
        nbodyCanvas.setEmptySpaceClickListener((worldX, worldY) -> {
            if (actionRailBuilder.getActiveTool() == NBodyActionRailBuilder.Tool.ADD) {
                dialogFactory.showAddBodyDialog(worldX, worldY);
            }
        });

        controlPanel = new ControlPanel();
        controlPanel.setOnResetCallback(() -> {
            if (originalConfig != null) {
                applyStructuralEdit(originalConfig, false);
                nbodyCanvas.fitToContent();
            }
        });
        controlPanel.setOnIntegratorChange(this::setIntegratorType);
        // Resuming always clears selection — mirrors SimulationController's
        // identical rule; the Pause button and the Space shortcut are the
        // two paths that can resume, so both enforce it.
        controlPanel.setOnPauseChange(paused -> {
            if (!paused) nbodyCanvas.setSelectedBody(-1);
        });
        // Picking a preset is a deliberate "start fresh from here" — moves
        // the Reset baseline, same reasoning as LinkEditorPanel's preset
        // picker / SimulationController's establishesNewBaseline flag. The
        // two-arg applyStructuralEdit already re-fits the camera whenever
        // establishesNewBaseline is true, so no separate call is needed
        // here — see that method's own javadoc.
        controlPanel.setOnPresetApply(preset -> applyStructuralEdit(preset.config(), true));
        // Round 1.2: a single click in the list neither pauses nor selects
        // (round 1.1 had it mirror the canvas's press-pauses-and-selects
        // rule, but that defeats the actual point of a quick click-to-peek —
        // you want to see a body's numbers changing while the sim keeps
        // running, not freeze it). It only pins the "Watching: " live HUD
        // (see NBodyCanvas#setInfoBody); double-click is still the only
        // path that selects/pauses/opens anything, and is unchanged below.
        controlPanel.setOnBodyInfo(nbodyCanvas::setInfoBody);
        // Pausing first matters here exactly as much as it does for the
        // canvas's own selection-then-pause rule: the parameter dialog
        // snapshots live state once and reuses it for every OTHER body's
        // position/velocity on Apply — leaving the sim running while this
        // (potentially long-open) modal sits there would let it silently
        // discard everyone else's intervening motion the moment Apply is
        // pressed. Already paused by the single-click above by the time a
        // double-click's second click arrives; setPaused(true) again here
        // is a harmless no-op in that case, and the only path that matters
        // if some other caller ever invokes this without a preceding select.
        controlPanel.setOnBodyOpen(index -> {
            setPaused(true);
            nbodyCanvas.setSelectedBody(index);
            dialogFactory.showBodyParameterDialog(index);
        });
        controlPanel.build(simLoop, nbodyCanvas, config);

        // The tabbed sidebar shell: Live Status always visible, one group's
        // controls shown at a time — same SidebarTabs class the pendulum
        // uses, assembled here with this screen's own (smaller) tab set.
        sidebarTabs = new SidebarTabs();
        sidebarTabs.build(
                controlPanel.getStatusBlock(),
                new SidebarTabs.Tab("Motion", Icons.Glyph.MOTION, controlPanel.getMotionGroup()),
                new SidebarTabs.Tab("Bodies", Icons.Glyph.BODIES, controlPanel.getBodiesGroup()),
                new SidebarTabs.Tab("Display", Icons.Glyph.DISPLAY, controlPanel.getDisplayGroup()));
        VBox.setVgrow(sidebarTabs, Priority.ALWAYS);
        controlHost.getChildren().setAll(sidebarTabs);
        controlHost.minHeightProperty().bind(sidebarScroll.heightProperty());

        titleLabel.setText("N-Body Gravity Simulator   ·   N = " + config.getN()
                + "   ·   RK4 / Newtonian Gravity");

        renderTimer = buildRenderTimer();
    }

    /**
     * The single place pause state changes from code (the Space shortcut
     * and body selection both call this) — mirrors {@code
     * SimulationController#setPaused} exactly.
     */
    private void setPaused(boolean paused) {
        simLoop.setPaused(paused);
        controlPanel.setPausedVisual(paused);
        if (!paused) nbodyCanvas.setSelectedBody(-1);
    }

    /**
     * Handles a validated {@link NBodyConfig} from a preset pick or any
     * structural-edit dialog: swaps the engine via {@link
     * SimulationLoop#submitRebuild}, then refreshes everything downstream
     * that was sized or labeled for the old N.
     *
     * @param establishesNewBaseline whether this edit moves where Reset
     *        returns to (a preset pick) — individual Add/Edit/Delete via
     *        the canvas dialogs do not, mirroring {@code
     *        SimulationController#applyStructuralEdit}'s identical
     *        distinction. Also the sole trigger for re-fitting the camera
     *        here, same as that method: length-drag-equivalent/dialog/Add/
     *        Delete edits leave pan/zoom exactly where the user left them.
     *        Reset itself passes {@code false} (rebuilding back to an
     *        already-current baseline doesn't "move" it) and re-fits the
     *        camera itself at its own call site instead — see {@link
     *        #initialize}'s Reset callback.
     */
    private void applyStructuralEdit(NBodyConfig newConfig, boolean establishesNewBaseline) {
        if (establishesNewBaseline) originalConfig = newConfig;

        int selectedBefore = nbodyCanvas.getSelectedBody();
        int infoBefore = nbodyCanvas.getInfoBody();

        currentConfig = newConfig;
        simLoop.submitRebuild(old -> new NBodyEngine(newConfig));
        // The fresh engine defaults to RK4 — re-apply whatever the user
        // actually had selected, sized for the new N (4N, not 2N — see
        // NBodyEngine's own javadoc on this exact point).
        simLoop.submit(e -> e.setIntegrator(selectedIntegratorType.create(4 * newConfig.getN())));

        // The selected index is still meaningful if still in range;
        // otherwise there's nothing sensible left to point the halo/HUD at.
        nbodyCanvas.setSelectedBody(selectedBefore >= 0 && selectedBefore < newConfig.getN() ? selectedBefore : -1);
        // Same reasoning, independently, for the round 1.2 "Watching: " pin —
        // it has nothing to do with selectedBody, so a structural edit has
        // to clamp/clear it separately rather than get this for free.
        nbodyCanvas.setInfoBody(infoBefore >= 0 && infoBefore < newConfig.getN() ? infoBefore : -1);

        if (establishesNewBaseline) nbodyCanvas.fitToContent();

        // Recorded trail history describes bodies that may no longer exist
        // at those indices post-edit — cleared unconditionally on every
        // structural edit, same as PendulumCanvas#clearTrail's identical
        // call in SimulationController#applyStructuralEdit. Which bodies
        // are CHECKED survives this (see NBodyRenderer#clearTrailHistory);
        // only the drawn path itself resets.
        nbodyCanvas.clearTrails();

        controlPanel.updateBodyCount(newConfig.getN());
        controlPanel.refreshBodies(newConfig);

        // Baseline drift values, captured directly from newConfig's own
        // initial state rather than left for buildRenderTimer to pick up
        // opportunistically from stateBuffer. That would race the physics
        // thread: submitRebuild above is only *queued*, drained at the top
        // of that thread's own next loop iteration — the very next
        // rendered frame can still read stateBuffer.read() as the OLD
        // (pre-rebuild) engine's last-published state, silently adopting
        // it as the "new" baseline even though it describes a scene with a
        // different N/masses entirely. Constructing a throwaway engine here
        // is cheap (array copies only, no stepping) and makes the baseline
        // depend on nothing but newConfig itself.
        NBodyState freshBaseline = new NBodyEngine(newConfig).getState();
        initialEnergy = freshBaseline.totalEnergy;
        initialMomentumX = freshBaseline.totalMomentumX;
        initialMomentumY = freshBaseline.totalMomentumY;
        initialAngularMomentum = freshBaseline.totalAngularMomentum;

        titleLabel.setText("N-Body Gravity Simulator   ·   N = " + newConfig.getN()
                + "   ·   RK4 / Newtonian Gravity");
    }

    /** Switches the integration strategy on the live engine, and remembers the choice for the next structural rebuild. */
    private void setIntegratorType(IntegratorType type) {
        selectedIntegratorType = type;
        simLoop.submit(e -> e.setIntegrator(type.create(4 * currentConfig.getN())));
    }

    // -------------------------------------------------------------------------
    // NBodyDialogFactory.Host
    // -------------------------------------------------------------------------

    @Override
    public NBodyConfig currentConfig() { return currentConfig; }

    @Override
    public NBodyState liveState() { return stateBuffer.read(); }

    @Override
    public double liveGravitationalConstant() { return simLoop.currentEngine().getGravitationalConstant(); }

    @Override
    public void applyStructuralEdit(NBodyConfig edited) { applyStructuralEdit(edited, false); }

    @Override
    public void selectBody(int body) { nbodyCanvas.setSelectedBody(body); }

    @Override
    public Window ownerWindow() { return btnBack.getScene() != null ? btnBack.getScene().getWindow() : null; }

    /**
     * Creates the 60fps render loop. Simpler than {@code
     * SimulationController}'s: no graph/history/chaos bookkeeping to spread
     * across frames, just the canvas (every frame) and the sidebar's status
     * block (every 4th, matching the pendulum's own status-update cadence).
     */
    private AnimationTimer buildRenderTimer() {
        return new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                NBodyState liveState = stateBuffer.read();

                if (liveState != null && initialEnergy == null) {
                    initialEnergy = liveState.totalEnergy;
                    initialMomentumX = liveState.totalMomentumX;
                    initialMomentumY = liveState.totalMomentumY;
                    initialAngularMomentum = liveState.totalAngularMomentum;
                }

                nbodyCanvas.render(liveState);

                if (frameCount % 4 == 0) {
                    controlPanel.updateStatus(liveState, initialEnergy, initialMomentumX, initialMomentumY, initialAngularMomentum);
                }
                frameCount++;
            }
        };
    }

    /** Wired to the "← Menu" button in {@code NBody.fxml}. */
    @FXML
    private void handleBack() {
        router.back();
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }

    /** Starts the physics thread, the render loop, and the keyboard shortcuts. Paired exactly with {@link #onHide}. */
    @Override
    public void onShow() {
        simLoop.start();
        renderTimer.start();

        keyHandler = this::handleKeyPress;
        Scene scene = btnBack.getScene();
        if (scene != null) scene.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
    }

    /** Stops everything {@link #onShow} started — see {@code SimulationController#onHide}'s identical reasoning for why every line here matters. */
    @Override
    public void onHide() {
        renderTimer.stop();
        simLoop.stop();

        Scene scene = btnBack.getScene();
        if (scene != null && keyHandler != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        keyHandler = null;
    }

    /**
     * Space, R, and → — the same three shortcuts the pendulum screen
     * offers, for the same reasons (see {@code
     * SimulationController#handleKeyPress}'s javadoc, including why → backs
     * off when a {@link Control} has focus). R here calls {@link
     * SimulationLoop#reset()} (return to the CURRENT structural config's
     * initial state) — deliberately not a full {@link #applyStructuralEdit}
     * back to {@link #originalConfig}, which is what the sidebar's own
     * Reset button does; this is the same "restart the clock" versus
     * "undo every edit" distinction the pendulum screen already draws
     * between its R shortcut and its Reset button.
     */
    private void handleKeyPress(KeyEvent e) {
        switch (e.getCode()) {
            case SPACE -> {
                setPaused(!simLoop.isPaused());
                e.consume();
            }
            case R -> {
                simLoop.reset();
                nbodyCanvas.fitToContent();
                nbodyCanvas.clearTrails();
                initialEnergy = null;
                initialMomentumX = null;
                initialMomentumY = null;
                initialAngularMomentum = null;
                e.consume();
            }
            case RIGHT -> {
                Scene scene = btnBack.getScene();
                if (scene != null && scene.getFocusOwner() instanceof Control) return;
                simLoop.stepOnce();
                e.consume();
            }
            default -> { /* not a shortcut this screen handles */ }
        }
    }
}
