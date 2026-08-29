package controller;

import javafx.animation.AnimationTimer;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import navigation.Navigable;
import navigation.SceneRouter;
import physics.PendulumConfig;
import physics.PhysicsEngine;
import physics.SimState;
import physics.integrator.IntegratorType;
import simulation.Ensemble;
import simulation.HistoryBuffer;
import simulation.SimulationLoop;
import simulation.StateBuffer;
import theme.ThemeManager;
import ui.icon.Icons;
import ui.pendulum.ControlPanel;
import ui.pendulum.LinkEditorPanel;
import ui.pendulum.PendulumActionRailBuilder;
import ui.pendulum.PendulumCanvas;
import ui.pendulum.PendulumChaosFeatures;
import ui.pendulum.PendulumDialogFactory;
import ui.pendulum.PendulumGraphPanel;
import ui.simcore.LayoutShell;
import ui.simcore.SidebarTabs;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosts the existing Canvas-based simulation view inside the FXML shell.
 *
 * <p>The physics engine and the render loop are untouched from the original
 * prototype — this controller's job is purely to own their lifecycle
 * ({@link #onShow} / {@link #onHide}), size the two canvases to
 * FXML-declared containers, and wire together the collaborators that
 * actually build/own the UI: {@link PendulumCanvas} (the pendulum, via
 * {@code ui.simcore.SimCanvas}), {@link PendulumGraphPanel} (the analytics
 * graph, via {@code ui.simcore.ChartCanvas}), {@link ControlPanel} (the
 * sidebar's grouped controls), {@link PendulumActionRailBuilder} (the
 * Edit/Add/Snap rail), {@link PendulumDialogFactory} (the three structural-
 * edit dialogs), {@link PendulumChaosFeatures} (ensemble/compare/sonify/
 * bifurcation orchestration), and {@link LayoutShell} (the collapsible
 * sidebar/graph columns). See round 1 of the UI restructuring plan for how
 * this controller shrank from one 1300+-line file into this coordination
 * layer plus those collaborators.
 *
 * <p><b>Interaction:</b> grabbing and dragging a bob (wired below via {@link
 * PendulumCanvas.DragListener}) doesn't itself pause the simulation — press
 * already did that via selection (see below) — and each drag event submits a
 * {@code SimCommand} that overwrites just the grabbed link's angle and
 * zeroes its velocity, while the physics thread keeps integrating every
 * link, grabbed one included, in between those events. Releasing submits
 * one final command with an angular velocity estimated from the last
 * ~150ms of motion, producing the "fling."
 *
 * <p><b>Selection (§7.1 of the UI overhaul spec, press-timing per round 3
 * §4-a):</b> pressing a bob — left or right button, click or the start of a
 * drag — selects it and pauses the simulation immediately, before any
 * drag/double-click branching (see {@link PendulumCanvas.SelectionListener}
 * and {@link #setPaused}); picking a different bob switches the selection
 * without an intermediate resume; resuming — from any cause, this button or
 * the Space shortcut — always clears the current selection.
 *
 * <p><b>Right-click length editing and double-click parameters (§7.3,
 * §7.4):</b> both are structural edits and are routed through the exact
 * same {@link #applyStructuralEdit} path as {@link LinkEditorPanel}'s
 * "Apply Changes" — no second way to mutate the engine's structure exists.
 *
 * <p><b>Structural edits:</b> {@link LinkEditorPanel} covers both the
 * per-link parameter editor and runtime N control in one place — adding or
 * removing a row changes N directly, and both go through the same "Apply"
 * action ({@link #applyStructuralEdit}), which submits an {@code
 * EngineRebuilder} via {@link SimulationLoop#rebuildWithConfig} rather than
 * a {@code SimCommand}, since changing length, mass, or N means replacing
 * the engine's internal arrays rather than mutating a field on them.
 *
 * <p><b>Camera (round 1 §8):</b> a structural edit only re-fits {@link
 * PendulumCanvas}'s pan/zoom when it also moves the Reset baseline (a
 * preset pick, a file load, the sidebar's own "Apply Changes") or is Reset
 * itself — see {@link #applyStructuralEdit} and the Reset callback in
 * {@link #initialize}. Every other edit (length drag, parameter dialog,
 * Add, delete) leaves pan/zoom exactly where the user left it.
 *
 * <p><b>Butterfly effect, A/B compare, sonify, bifurcation, integrator
 * comparison:</b> see {@link PendulumChaosFeatures}.
 *
 * <p><b>Time-travel scrubbing:</b> the render loop also feeds a {@link
 * HistoryBuffer}; dragging the sidebar's history slider shows a past frame
 * on the canvas while the live simulation keeps running underneath — see
 * that class's javadoc for the deliberate scope boundary (a view into the
 * past, not a rewind of the live engine).
 *
 * <p><b>Layout shell (§5, §9, §10):</b> see {@link LayoutShell}. The
 * sidebar's contents are a {@link SidebarTabs} shell: an always-visible
 * "Live Status" block plus exactly one settings group's controls at a time,
 * built from {@link ControlPanel}'s grouped panels.
 */
public final class SimulationController implements Initializable, Navigable,
        PendulumDialogFactory.Host, PendulumChaosFeatures.Host {

    private static final Logger LOG = Logger.getLogger(SimulationController.class.getName());

    // History: sampled every 3rd render-timer tick (~20Hz at 60fps) rather
    // than every physics publish (~500Hz) — scrubbing doesn't need physics
    // resolution, and 500Hz would make even a short window a very large
    // buffer. 600 entries at ~20Hz is ~30s of replayable history.
    private static final int HISTORY_CAPACITY          = 600;
    private static final int HISTORY_SAMPLE_EVERY_FRAMES = 3;
    private static final double HISTORY_SAMPLE_HZ = 60.0 / HISTORY_SAMPLE_EVERY_FRAMES;

    @FXML private Button btnBack;
    @FXML private Label titleLabel;
    @FXML private VBox actionRail;
    @FXML private StackPane canvasGraphStack;
    @FXML private StackPane canvasHost;
    @FXML private StackPane graphHost;
    @FXML private ScrollPane sidebarScroll;
    @FXML private VBox controlHost;

    private SceneRouter router;
    private SimulationLoop simLoop;
    private StateBuffer stateBuffer;
    private ControlPanel controlPanel;
    private LinkEditorPanel linkEditorPanel;
    private SidebarTabs sidebarTabs;
    private PendulumCanvas pendulumCanvas;
    private PendulumGraphPanel graphPanel;
    private AnimationTimer renderTimer;

    private PendulumActionRailBuilder actionRailBuilder;
    private PendulumDialogFactory dialogFactory;
    private PendulumChaosFeatures chaosFeatures;
    private LayoutShell layoutShell;

    // The structural shape (N, lengths, masses, gravity, speed) currently
    // applied — kept so the butterfly-effect toggle can build ensemble
    // members with the right structure without re-deriving it from the UI.
    private PendulumConfig currentConfig;

    // Round 3 §4-f: the shape Reset returns to — a preset pick, a file load,
    // or the sidebar's own "Apply Changes" are deliberate "start fresh from
    // here"s and move this baseline; the action-bar tools (length drag,
    // parameter dialog, Add) stack on top of it without moving it. See
    // applyStructuralEdit's two-arg overload.
    private PendulumConfig originalConfig;

    // physics.PhysicsEngine's constructor always starts with a fresh RK4
    // sized for its own N — an Integrator's scratch buffers are fixed-size,
    // so the previous selection can't just carry over a rebuild. Kept here
    // so applyStructuralEdit can re-apply it at the new size; see
    // IntegratorType's javadoc for the full reasoning.
    private IntegratorType selectedIntegratorType = IntegratorType.RK4;

    // Mirrors PhysicsEngine's own gravityAngle — kept here for the same
    // reason selectedIntegratorType is: a structural rebuild (see
    // applyStructuralEdit) replaces the engine with a fresh one that
    // defaults back to 0 (straight down), so whatever the user last painted
    // needs re-applying rather than silently reverting.
    private double currentGravityAngle = 0.0;

    private Double initialEnergy;
    private long graphFrameCount;

    // Time-travel scrubbing — see HistoryBuffer's javadoc for the scope
    // boundary (a view into the past, not a rewind of the live simulation).
    private final HistoryBuffer history = new HistoryBuffer(HISTORY_CAPACITY);
    private boolean scrubbing = false;
    private int scrubIndex = 0;

    // Added on onShow() / removed on onHide() — see those methods. The
    // Scene object is reused across navigations (SceneRouter only swaps its
    // root), so an event FILTER added here without being removed would
    // silently accumulate one extra copy every time this screen is revisited.
    private EventHandler<KeyEvent> keyHandler;

    /**
     * Builds the entire simulation screen. Called once by {@code FXMLLoader}
     * after the FXML is parsed and {@code @FXML} fields are injected.
     *
     * <p>Assembly order: engine and loop, then the two canvases, then the
     * screen's collaborators (dialog factory, chaos features, action rail,
     * layout shell), then the canvas's interaction listeners (which
     * reference those collaborators), then the sidebar panels and their
     * callback wiring, then the render timer. Nothing is STARTED here — the
     * physics thread and render loop only begin in {@link #onShow()}, so
     * the screen consumes no CPU until it is visible.
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        PendulumConfig config = PendulumConfig.defaultConfig();
        currentConfig = config;
        originalConfig = config;
        stateBuffer = new StateBuffer();
        simLoop = new SimulationLoop(config, stateBuffer);

        pendulumCanvas = new PendulumCanvas(500, 580, config.getTotalLength());
        graphPanel = new PendulumGraphPanel(490, 580);

        // Read once at construction, not live — see SettingsController's
        // javadoc for why a change there takes effect next time this
        // screen opens rather than mid-simulation.
        pendulumCanvas.setReducedMotion(ThemeManager.getInstance().isReducedMotion());
        pendulumCanvas.setColorBlindSafe(ThemeManager.getInstance().isColorBlindSafePalette());

        // A Canvas reports its own current size as its "preferred size" — if
        // it stays managed, binding its width/height to its own StackPane
        // host makes the host's preferred size self-referential, which
        // inflates on layout and pushes later HBox/BorderPane siblings
        // (the graph, then the whole sidebar) off the visible window.
        // Unmanaging the canvas excludes it from that computation entirely;
        // it still fills its host exactly, since the binding is unaffected.
        pendulumCanvas.setManaged(false);
        graphPanel.setManaged(false);

        pendulumCanvas.widthProperty().bind(canvasHost.widthProperty());
        pendulumCanvas.heightProperty().bind(canvasHost.heightProperty());
        graphPanel.widthProperty().bind(graphHost.widthProperty());
        graphPanel.heightProperty().bind(graphHost.heightProperty());

        canvasHost.getChildren().add(pendulumCanvas);
        graphHost.getChildren().add(graphPanel);

        dialogFactory = new PendulumDialogFactory(this);
        chaosFeatures = new PendulumChaosFeatures(this);

        actionRailBuilder = new PendulumActionRailBuilder(actionRail, pendulumCanvas);
        actionRailBuilder.build();

        layoutShell = new LayoutShell(sidebarScroll, graphHost, canvasGraphStack);
        layoutShell.buildSidebarToggle();

        pendulumCanvas.setDragListener(new PendulumCanvas.DragListener() {
            /** Every bob is grabbable, so this always accepts. Returning false would let the canvas veto a drag. */
            @Override
            public boolean onGrab(int linkIndex) {
                return true; // every rendered bob accepts a grab
            }

            /** While held: pin the link to the pointer's angle with ZERO velocity, so it follows the mouse instead of fighting it. */
            @Override
            public void onDrag(int linkIndex, double angle) {
                simLoop.submit(e -> e.setLinkState(linkIndex, angle, 0.0));
            }

            /** On release: hand back the estimated fling velocity, so letting go mid-swing throws the bob rather than dropping it. */
            @Override
            public void onRelease(int linkIndex, double angle, double angularVelocity) {
                simLoop.submit(e -> e.setLinkState(linkIndex, angle, angularVelocity));
            }
        });

        pendulumCanvas.setGravityListener(angle -> {
            currentGravityAngle = angle;
            simLoop.setGravityAngle(angle);
        });

        // §7.1 — a completed click/click-drag-release selects the bob and
        // pauses the simulation; see setPaused for the resume-always-clears
        // half of this rule.
        pendulumCanvas.setSelectionListener(linkIndex -> {
            pendulumCanvas.setSelectedLink(linkIndex);
            setPaused(true);
        });

        // §7.3 — right-click-drag previews a length change visually (handled
        // entirely inside PendulumCanvas); only the commit-on-release needs
        // to reach the engine, through the same structural-edit path as
        // everything else that changes a link's length.
        pendulumCanvas.setLengthDragListener(new PendulumCanvas.LengthDragListener() {
            @Override
            public void onLengthPreview(int linkIndex, double newLength) {
                // No-op: the canvas already redraws its own local preview.
            }

            @Override
            public void onLengthCommit(int linkIndex, double newLength) {
                commitLinkLength(linkIndex, newLength);
            }
        });

        // §7.4 / round 3 §4-e — which dialog opens depends on the active
        // rail tool (Edit poses one link's parameters, Add inserts a new one).
        pendulumCanvas.setDoubleClickListener(link -> {
            if (actionRailBuilder.getActiveTool() == PendulumActionRailBuilder.Tool.ADD) dialogFactory.showAddLinkDialog(link);
            else dialogFactory.showLinkParameterDialog(link);
        });

        // Round 4 §2 — right-click-and-release a link while Add is active
        // (i.e. drag-editing disabled, so no length-drag armed) opens the
        // delete-confirmation dialog.
        pendulumCanvas.setRightClickListener(dialogFactory::showDeleteLinkDialog);

        controlPanel = new ControlPanel();
        // Round 3 §4-f: Reset rebuilds all the way back to the pre-stacking
        // baseline, not just wherever currentConfig currently sits — a full
        // rebuild, since Add may have changed N. applyStructuralEdit already
        // clears initialEnergy/history/ensemble/compare/trails/selection, so
        // nothing else needs repeating here. §8: Reset's one-arg
        // applyStructuralEdit call never moves the baseline, so it doesn't
        // auto-refit the camera — this explicit call is Reset's own.
        controlPanel.setOnResetCallback(() -> {
            if (originalConfig != null) {
                applyStructuralEdit(originalConfig);
                pendulumCanvas.fitToContent();
            }
        });
        controlPanel.setOnEnsembleToggle(chaosFeatures::setEnsembleActive);
        controlPanel.setOnSonifyToggle(chaosFeatures::setSonifyActive);
        controlPanel.setOnGenerateBifurcation(chaosFeatures::generateBifurcationMap);
        controlPanel.setOnCompareToggle(chaosFeatures::setCompareActive);
        controlPanel.setOnResetGravityDirection(() -> {
            currentGravityAngle = 0.0;
            simLoop.setGravityAngle(0.0);
            pendulumCanvas.setGravityAngleVisual(0.0);
        });
        controlPanel.setOnIntegratorChange(this::setIntegratorType);
        controlPanel.setOnCompareIntegrators(chaosFeatures::compareIntegrators);
        controlPanel.setOnScrubStart(() -> scrubbing = true);
        controlPanel.setOnScrubTo(idx -> scrubIndex = idx);
        controlPanel.setOnScrubEnd(() -> scrubbing = false);
        controlPanel.setOnGraphVisibilityChange(layoutShell::setGraphVisible);
        // Resuming always clears selection (§7.1) — the Pause button is one
        // of two paths that can resume (the Space shortcut, via setPaused,
        // is the other), so both need to enforce it.
        controlPanel.setOnPauseChange(paused -> {
            if (!paused) pendulumCanvas.setSelectedLink(-1);
        });
        controlPanel.build(simLoop, graphPanel, pendulumCanvas, config.getN());

        linkEditorPanel = new LinkEditorPanel();
        linkEditorPanel.loadFrom(config);
        linkEditorPanel.setLiveParameterSuppliers(simLoop::getGravity, simLoop::getSpeedMultiplier);
        // A preset/file load or the sidebar's own "Apply Changes" is a
        // deliberate "start fresh from here" — see originalConfig's javadoc.
        linkEditorPanel.setOnApply(cfg -> applyStructuralEdit(cfg, true));

        // §9 — the tabbed sidebar shell: Live Status always visible, one
        // group's controls shown at a time. The pendulum screen's own six
        // tabs are assembled here, at the call site, not inside SidebarTabs
        // itself — see that class's javadoc (round 1.4 §1) for why it only
        // knows about generic Tabs, never this specific set.
        sidebarTabs = new SidebarTabs();
        sidebarTabs.build(
                controlPanel.getStatusBlock(),
                new SidebarTabs.Tab("Motion", Icons.Glyph.MOTION, controlPanel.getMotionGroup()),
                new SidebarTabs.Tab("Chaos & Compare", Icons.Glyph.CHAOS, controlPanel.getChaosGroup()),
                new SidebarTabs.Tab("Graphs", Icons.Glyph.GRAPHS, controlPanel.getGraphsGroup()),
                new SidebarTabs.Tab("History", Icons.Glyph.HISTORY, controlPanel.getHistoryGroup()),
                new SidebarTabs.Tab("Links", Icons.Glyph.LINKS, linkEditorPanel),
                new SidebarTabs.Tab("Display", Icons.Glyph.DISPLAY, controlPanel.getDisplayGroup()));
        VBox.setVgrow(sidebarTabs, Priority.ALWAYS);
        controlHost.getChildren().setAll(sidebarTabs);

        // §5.4 — the sidebar's background must fill the whole right column
        // regardless of content length; binding controlHost's minHeight to
        // the ScrollPane's own (viewport) height, rather than leaving it to
        // "size to children," is what removes the uncovered strip at the
        // bottom when a group's content is short.
        controlHost.minHeightProperty().bind(sidebarScroll.heightProperty());

        titleLabel.setText("N-Pendulum Chain Simulator   ·   N = " + config.getN()
                + "   ·   RK4 / Lagrangian Mechanics");

        renderTimer = buildRenderTimer();
    }

    // -------------------------------------------------------------------------
    // §7.1 — pause/resume + selection
    // -------------------------------------------------------------------------

    /**
     * The single place pause state changes from code (the Space shortcut
     * and bob selection both call this): keeps {@link SimulationLoop} and
     * {@link ControlPanel}'s Pause button in sync, and — per §7.1 — clears
     * any current selection whenever the simulation resumes, regardless of
     * what caused the pause in the first place.
     */
    private void setPaused(boolean paused) {
        simLoop.setPaused(paused);
        controlPanel.setPausedVisual(paused);
        if (!paused) pendulumCanvas.setSelectedLink(-1);
    }

    /**
     * The live angle of every link right now, read from the physics thread's
     * latest published state — not {@code currentConfig}'s (possibly stale)
     * initAngles. Used by every action-bar edit path (§4-f) so a structural
     * rebuild only changes the one link actually being edited. Falls back to
     * currentConfig only if no state has been published yet.
     */
    @Override
    public double[] liveAngles() {
        SimState s = stateBuffer.read();
        return (s != null) ? s.angles.clone() : currentConfig.getInitAngles();
    }

    /** Commits a right-click length drag (§7.3) through the same structural-edit path as every other length change. */
    private void commitLinkLength(int linkIndex, double newLength) {
        if (currentConfig == null || linkIndex < 0 || linkIndex >= currentConfig.getN()) return;

        double[] lengths = currentConfig.getLengths();
        lengths[linkIndex] = newLength;
        try {
            PendulumConfig edited = new PendulumConfig(currentConfig.getN(), lengths, currentConfig.getMasses(),
                    liveAngles(), currentConfig.getGravity(), currentConfig.getSpeedMultiplier());
            applyStructuralEdit(edited);
        } catch (IllegalArgumentException ex) {
            // PendulumConfig rejected it — shouldn't happen given the canvas
            // already clamps to a positive, finite length before committing,
            // but fail quietly rather than crash, same as setLinkState's
            // existing non-finite-input handling elsewhere in this app.
            LOG.log(Level.WARNING, "Length-drag commit rejected", ex);
        }
    }

    /**
     * Handles a validated {@link PendulumConfig} from the link editor, the
     * length-drag commit, the parameter dialog, or the add-link dialog:
     * swaps the engine via {@link SimulationLoop#rebuildWithConfig}, then
     * refreshes everything downstream that was sized or labeled for the old
     * N/length. The action-bar tools (length drag, parameter dialog, Add)
     * call this one-arg overload, which never moves the Reset baseline —
     * see the two-arg overload below.
     */
    @Override
    public void applyStructuralEdit(PendulumConfig newConfig) {
        applyStructuralEdit(newConfig, false);
    }

    /**
     * @param establishesNewBaseline whether this edit is a deliberate
     *        "start fresh from here" (a preset pick, a file load, the
     *        sidebar's own "Apply Changes") that should move where the
     *        Reset button returns to — see {@link #originalConfig}. Round 1
     *        §8: also the sole trigger for re-fitting the camera here — see
     *        the class javadoc.
     */
    private void applyStructuralEdit(PendulumConfig newConfig, boolean establishesNewBaseline) {
        if (establishesNewBaseline) originalConfig = newConfig;

        int selectedBefore = pendulumCanvas.getSelectedLink();

        currentConfig = newConfig;
        simLoop.rebuildWithConfig(newConfig);
        // The fresh engine rebuildWithConfig just built defaults to RK4 —
        // re-apply whatever the user actually had selected, sized for the
        // new N. See selectedIntegratorType's javadoc.
        simLoop.setIntegrator(selectedIntegratorType.create(2 * newConfig.getN()));
        simLoop.setGravityAngle(currentGravityAngle);
        pendulumCanvas.setGravityAngleVisual(currentGravityAngle);

        // An active ensemble was built from the old N/lengths/masses — it's
        // not just stale, it no longer corresponds to what the primary chain
        // even looks like, so drop it rather than confuse the demo.
        simLoop.setEnsemble(null);
        controlPanel.setEnsembleVisual(false);

        // Same reasoning for an active A/B compare "B" engine.
        simLoop.setCompareEngine(null);
        controlPanel.setCompareVisual(false);

        pendulumCanvas.setTotalLength(newConfig.getTotalLength());
        // §8: only a baseline-moving edit (preset/file load/bulk apply)
        // snaps the camera back to a fitted view — length-drag/dialog/Add/
        // Delete leave pan/zoom exactly where the user left it.
        if (establishesNewBaseline) pendulumCanvas.fitToContent();
        pendulumCanvas.clearTrail();
        pendulumCanvas.clearLengthPreview();
        // The selected index is still meaningful if it's still in range (the
        // length-drag/dialog paths never change N); otherwise there's
        // nothing sensible left to point the halo/HUD at.
        pendulumCanvas.setSelectedLink(selectedBefore >= 0 && selectedBefore < newConfig.getN() ? selectedBefore : -1);
        graphPanel.clear();
        controlPanel.updateLinkCount(newConfig.getN());
        initialEnergy = null;

        // Historical samples describe a different N/length — showing them
        // post-rebuild would be replaying a chain that no longer exists.
        history.clear();
        scrubbing = false;

        titleLabel.setText("N-Pendulum Chain Simulator   ·   N = " + newConfig.getN()
                + "   ·   RK4 / Lagrangian Mechanics");
    }

    /** Switches the integration strategy on the live engine, and remembers the choice for the next structural rebuild. */
    private void setIntegratorType(IntegratorType type) {
        selectedIntegratorType = type;
        simLoop.setIntegrator(type.create(2 * currentConfig.getN()));
    }

    // -------------------------------------------------------------------------
    // PendulumDialogFactory.Host / PendulumChaosFeatures.Host
    // -------------------------------------------------------------------------

    @Override
    public PendulumConfig currentConfig() { return currentConfig; }

    @Override
    public SimState liveState() { return stateBuffer.read(); }

    @Override
    public SimulationLoop simLoop() { return simLoop; }

    @Override
    public PendulumGraphPanel graphPanel() { return graphPanel; }

    @Override
    public void selectLink(int link) { pendulumCanvas.setSelectedLink(link); }

    @Override
    public Window ownerWindow() { return btnBack.getScene() != null ? btnBack.getScene().getWindow() : null; }

    @Override
    public void setEnsembleVisual(boolean active) { controlPanel.setEnsembleVisual(active); }

    @Override
    public void setCompareVisual(boolean active) { controlPanel.setCompareVisual(active); }

    @Override
    public void setBifurcationRunning(boolean running) { controlPanel.setBifurcationRunning(running); }

    @Override
    public void setBifurcationProgress(double fraction) { controlPanel.setBifurcationProgress(fraction); }

    @Override
    public void onBifurcationComplete(double[] params, List<double[]> samples) {
        graphPanel.setBifurcationData(params, samples);
        graphPanel.setMode(PendulumGraphPanel.Mode.BIFURCATION);
        controlPanel.selectBifurcationMode();
    }

    /**
     * Creates the 60 fps render loop.
     *
     * <p>{@code AnimationTimer.handle()} is called by JavaFX once per screen
     * refresh, on the UI thread. It reads whatever the physics thread last
     * published — it never waits for it, which is what keeps rendering and
     * physics independent.
     *
     * <p><b>Not everything runs every frame.</b> Work is spread across
     * frames by counting them: the graph ingests and redraws every 2nd
     * frame, the status text updates every 4th, history samples every 3rd.
     * The pendulum itself redraws every frame, because that is the one thing
     * a viewer would notice stuttering.
     */
    private AnimationTimer buildRenderTimer() {
        return new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                SimState liveState = stateBuffer.read();

                if (liveState != null && initialEnergy == null) {
                    initialEnergy = liveState.totalEnergy;
                }

                if (graphFrameCount % HISTORY_SAMPLE_EVERY_FRAMES == 0) {
                    history.record(liveState);
                }

                // While scrubbing, the pendulum canvas shows a historical
                // frame; the graph (below) always reflects live data
                // regardless — it already accumulates its own history
                // (trails, Poincaré points), so freezing it while scrubbing
                // the canvas would be a second, inconsistent notion of
                // "the past" layered on top of the first.
                SimState displayState = liveState;
                if (scrubbing && history.size() > 0) {
                    int idx = Math.max(0, Math.min(scrubIndex, history.size() - 1));
                    SimState historical = history.get(idx);
                    if (historical != null) displayState = historical;
                }

                Ensemble ensemble = simLoop.getEnsemble();
                List<SimState> ghosts = (!scrubbing && ensemble != null) ? ensemble.snapshot() : null;

                PhysicsEngine compareEngine = simLoop.getCompareEngine();
                SimState compareState = (!scrubbing && compareEngine != null) ? compareEngine.getState() : null;

                pendulumCanvas.render(displayState, ghosts, compareState);

                chaosFeatures.updateSonifyFrequency(liveState);

                if (graphFrameCount % 2 == 0) {
                    graphPanel.addDataPoint(liveState);
                    graphPanel.render();
                }
                graphFrameCount++;

                if (graphFrameCount % 4 == 0) {
                    controlPanel.updateStatus(displayState, initialEnergy);
                    controlPanel.updateLyapunov(chaosFeatures.estimateLyapunov(liveState, ghosts));
                }

                controlPanel.updateHistoryRange(history.size() - 1);
                if (!scrubbing) {
                    controlPanel.setHistoryPositionLive(history.size() - 1);
                } else {
                    double secondsAgo = (history.size() - 1 - scrubIndex) / HISTORY_SAMPLE_HZ;
                    controlPanel.setHistoryPositionScrubbed(secondsAgo);
                }
            }
        };
    }

    /** Wired to the "← Menu" button in {@code Simulation.fxml}. */
    @FXML
    private void handleBack() {
        router.back();
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }

    /**
     * Starts everything this screen owns: the physics thread, the render
     * loop, and the keyboard shortcuts. Paired exactly with {@link #onHide}
     * — anything started here must be stopped there, or it keeps running
     * invisibly after the user navigates away.
     */
    @Override
    public void onShow() {
        simLoop.start();
        renderTimer.start();

        keyHandler = this::handleKeyPress;
        Scene scene = btnBack.getScene();
        if (scene != null) scene.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
    }

    /**
     * Stops everything {@link #onShow} started. Every line here exists to
     * prevent a specific leak: an orphaned physics thread, a render loop
     * drawing an invisible canvas, a tone still playing, a background sweep
     * computing a diagram nobody can see, and — because the {@code Scene}
     * outlives this screen — a key filter that would otherwise accumulate
     * one extra copy per visit.
     */
    @Override
    public void onHide() {
        renderTimer.stop();
        simLoop.stop();

        // Otherwise a live tone would keep playing after navigating away
        // from this screen; sonifyActive/the button are reset alongside it
        // so re-entering the screen doesn't show "Sonify: On" for a
        // sonifier that isn't actually running anymore.
        chaosFeatures.stopSonifier();
        controlPanel.setSonifyVisual(false);

        // A sweep left running after navigating away would keep a
        // background thread alive computing a diagram nobody can see.
        chaosFeatures.cancelBifurcationIfRunning();

        Scene scene = btnBack.getScene();
        if (scene != null && keyHandler != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
        keyHandler = null;
    }

    /**
     * Space, R, and → — the shortcuts you actually reach for mid-demo. An
     * event FILTER (capturing phase, registered on the Scene in onShow)
     * rather than a handler on a single node: Space on a focused Button
     * would otherwise trigger that button's own click before this ever saw
     * the key.
     */
    private void handleKeyPress(KeyEvent e) {
        switch (e.getCode()) {
            case SPACE -> {
                setPaused(!simLoop.isPaused());
                e.consume();
            }
            case R -> {
                simLoop.reset();
                pendulumCanvas.clearTrail();
                pendulumCanvas.fitToContent();
                graphPanel.clear();
                initialEnergy = null;
                history.clear();
                scrubbing = false;
                e.consume();
            }
            case RIGHT -> {
                simLoop.stepOnce();
                e.consume();
            }
            default -> { /* not a shortcut this screen handles */ }
        }
    }
}
