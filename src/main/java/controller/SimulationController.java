package controller;

import audio.Sonifier;
import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import navigation.Navigable;
import navigation.SceneRouter;
import physics.BifurcationSweep;
import physics.PendulumConfig;
import physics.PhysicsEngine;
import physics.SimState;
import physics.integrator.Integrator;
import physics.integrator.IntegratorType;
import simulation.Ensemble;
import simulation.HistoryBuffer;
import simulation.SimulationLoop;
import simulation.StateBuffer;
import theme.Theme;
import theme.ThemeManager;
import ui.ControlPanel;
import ui.GraphPanel;
import ui.LinkEditorPanel;
import ui.PendulumCanvas;
import ui.SidebarTabs;
import ui.icon.Icons;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosts the existing Canvas-based simulation view inside the FXML shell.
 *
 * <p>The physics engine, the render loop, and the three {@code ui.*} Canvas
 * components are untouched from the original prototype — this controller's
 * job is purely to own their lifecycle ({@link #onShow} / {@link #onHide})
 * and to size them to FXML-declared containers instead of a hand-built
 * {@code Stage}. Binding each canvas's width/height to its host {@link
 * StackPane} is the entire change that makes the simulation resizable: both
 * {@code PendulumCanvas.render()} and {@code GraphPanel.render()} already
 * read {@code getWidth()}/{@code getHeight()} fresh every frame.
 *
 * <p><b>Interaction:</b> grabbing and dragging a bob (wired below via {@link
 * PendulumCanvas.DragListener}) doesn't itself pause the simulation — press
 * already did that via selection (see below) — and each drag event submits a
 * {@code SimCommand} that overwrites just the grabbed link's angle and
 * zeroes its velocity, while the physics thread keeps integrating every
 * link, grabbed one included, in between those events. Because the mass
 * matrix couples every link's derivative to every other link's angle, the
 * rest of the chain visibly reacts to the forced one in real time rather
 * than sitting frozen — a standard "kinematic forcing" technique for
 * interactive physics, not a special case bolted onto the integrator.
 * Releasing submits one final command with an angular velocity estimated
 * from the last ~150ms of motion, producing the "fling."
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
 * <p><b>Structural edits:</b> {@link ui.LinkEditorPanel} covers both the
 * per-link parameter editor and runtime N control in one place — adding or
 * removing a row changes N directly, and both go through the same "Apply"
 * action ({@link #applyStructuralEdit}), which submits an {@code
 * EngineRebuilder} via {@link SimulationLoop#rebuildWithConfig} rather than
 * a {@code SimCommand}, since changing length, mass, or N means replacing
 * the engine's internal arrays rather than mutating a field on them.
 *
 * <p><b>Butterfly effect:</b> the "Chaos" toggle ({@link
 * #setEnsembleActive}) spawns a {@link Ensemble} of near-identical copies
 * from the primary's exact current state, stepped alongside it on the
 * physics thread and rendered as faint ghost chains. A structural edit
 * invalidates any active ensemble (its N/lengths no longer match the
 * primary), so {@link #applyStructuralEdit} always clears it. While the
 * ensemble is active, {@link #estimateLyapunov} turns its divergence from
 * the primary into a live largest-Lyapunov-exponent estimate.
 *
 * <p><b>Integrator comparison:</b> {@link #compareIntegrators} runs every
 * {@code IntegratorType} from the primary's current state on temporary,
 * disposable engines (never touching the live simulation) and plots their
 * energy drift via {@code GraphPanel.Mode.COMPARISON}.
 *
 * <p><b>Time-travel scrubbing:</b> the render loop also feeds a {@link
 * HistoryBuffer}; dragging the sidebar's history slider shows a past frame
 * on the canvas while the live simulation keeps running underneath — see
 * that class's javadoc for the deliberate scope boundary (a view into the
 * past, not a rewind of the live engine).
 *
 * <p><b>Layout shell (§5, §9, §10):</b> the sidebar ({@link #sidebarScroll})
 * and the graph column ({@link #graphHost}) both start collapsed to zero
 * width — canvas-first — and animate open via {@link #setSidebarExpanded}/
 * {@link #setGraphVisible}, never {@code setVisible(false)}, so each stays a
 * genuine layout column that shrinks its neighbors rather than overlapping
 * them. The sidebar's contents are a {@link SidebarTabs} shell: an
 * always-visible "Live Status" block plus exactly one settings group's
 * controls at a time, built from {@link ControlPanel}'s grouped panels.
 */
public final class SimulationController implements Initializable, Navigable {

    private static final Logger LOG = Logger.getLogger(SimulationController.class.getName());

    // 50 members, 1e-7 rad apart: small enough to be an imperceptible
    // cluster at spawn, large enough to be well above floating-point noise
    // (~1e-16) so the eventual divergence is a real chaos signal, not an
    // artifact of it.
    private static final int    ENSEMBLE_SIZE    = 50;
    private static final double ENSEMBLE_EPSILON = 1.0e-7;

    // History: sampled every 3rd render-timer tick (~20Hz at 60fps) rather
    // than every physics publish (~500Hz) — scrubbing doesn't need physics
    // resolution, and 500Hz would make even a short window a very large
    // buffer. 600 entries at ~20Hz is ~30s of replayable history.
    private static final int HISTORY_CAPACITY          = 600;
    private static final int HISTORY_SAMPLE_EVERY_FRAMES = 3;
    private static final double HISTORY_SAMPLE_HZ = 60.0 / HISTORY_SAMPLE_EVERY_FRAMES;

    // How long each integrator runs for "Compare Integrators" — long enough
    // to show a real divergence trend, short enough to compute and plot
    // instantly given this engine's measured throughput.
    private static final double COMPARISON_DURATION_SECONDS = 10.0;
    private static final double COMPARISON_DT = 0.002;

    // Bifurcation sweep parameters — see physics.BifurcationSweep. Kept
    // modest (rather than the hundreds of columns/tens of settle-seconds a
    // textbook figure might use) specifically so this stays a "background
    // task that finishes in a reasonable time on typical hardware," not a
    // multi-minute wait — the tradeoff is a coarser-looking diagram, an
    // acceptable one for a demonstration feature.
    private static final double BIFURCATION_PARAM_MIN      = 0.1;
    private static final double BIFURCATION_PARAM_MAX      = Math.PI - 0.05;
    private static final int    BIFURCATION_COLUMNS        = 90;
    private static final double BIFURCATION_SETTLE_SECONDS = 6.0;
    private static final double BIFURCATION_SAMPLE_SECONDS = 5.0;

    // §5/§10 layout shell: expanded widths and the shared collapse/expand
    // animation duration. Chosen to comfortably fit the sidebar's larger
    // fonts/icon tab bar (§2, §9) and the graph panel's own axes/labels.
    private static final double SIDEBAR_EXPANDED_WIDTH = 340;
    private static final double GRAPH_EXPANDED_WIDTH    = 460;
    private static final Duration WIDTH_ANIM_DURATION   = Duration.millis(220);

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
    private GraphPanel graphPanel;
    private AnimationTimer renderTimer;

    // §5: sidebar/graph collapse state and their width-animation handles.
    private boolean sidebarExpanded = false;
    private Icons.IconView sidebarToggleIcon;
    private Timeline sidebarWidthAnim;
    private Timeline graphWidthAnim;

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

    // Round 3 §4-e: which rail tool governs press/double-click behavior on
    // the pendulum canvas. See buildActionRail/setActiveTool.
    private enum RailTool { EDIT, ADD }
    private RailTool activeTool = RailTool.EDIT;

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

    // Lyapunov estimate: set to the sim-time at which the current ensemble
    // was spawned, so each frame can measure elapsed time and estimate
    // lambda = ln(separation / epsilon) / elapsed. Null when no ensemble
    // is active. See buildRenderTimer for the actual computation.
    private Double ensembleStartSimTime;

    // Off by default — an unrequested tone playing on launch would be an
    // unpleasant surprise. See buildRenderTimer for the per-frame frequency
    // update and audio.Sonifier's javadoc for why this can never throw even
    // on a machine with no audio output.
    private final Sonifier sonifier = new Sonifier();
    private boolean sonifyActive = false;

    // Non-null only while a sweep is in flight — see generateBifurcationMap
    // and onHide (which cancels it if the user navigates away mid-sweep).
    private Task<BifurcationSweep.Result> bifurcationTask;

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
     * sidebar panels, then callback wiring, then the render timer. Nothing
     * is STARTED here — the physics thread and render loop only begin in
     * {@link #onShow()}, so the screen consumes no CPU until it is visible.
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        PendulumConfig config = PendulumConfig.defaultConfig();
        currentConfig = config;
        originalConfig = config;
        stateBuffer = new StateBuffer();
        simLoop = new SimulationLoop(config, stateBuffer);

        pendulumCanvas = new PendulumCanvas(500, 580, config.getTotalLength());
        graphPanel = new GraphPanel(490, 580);

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
            if (activeTool == RailTool.ADD) showAddLinkDialog(link);
            else showLinkParameterDialog(link);
        });

        // Round 4 §2 — right-click-and-release a link while Add is active
        // (i.e. drag-editing disabled, so no length-drag armed) opens the
        // delete-confirmation dialog.
        pendulumCanvas.setRightClickListener(this::showDeleteLinkDialog);

        controlPanel = new ControlPanel();
        // Round 3 §4-f: Reset rebuilds all the way back to the pre-stacking
        // baseline, not just wherever currentConfig currently sits — a full
        // rebuild, since Add may have changed N. applyStructuralEdit already
        // clears initialEnergy/history/ensemble/compare/trails/selection, so
        // nothing else needs repeating here.
        controlPanel.setOnResetCallback(() -> {
            if (originalConfig != null) applyStructuralEdit(originalConfig);
        });
        controlPanel.setOnEnsembleToggle(this::setEnsembleActive);
        controlPanel.setOnSonifyToggle(this::setSonifyActive);
        controlPanel.setOnGenerateBifurcation(this::generateBifurcationMap);
        controlPanel.setOnCompareToggle(this::setCompareActive);
        controlPanel.setOnResetGravityDirection(() -> {
            currentGravityAngle = 0.0;
            simLoop.setGravityAngle(0.0);
            pendulumCanvas.setGravityAngleVisual(0.0);
        });
        controlPanel.setOnIntegratorChange(this::setIntegratorType);
        controlPanel.setOnCompareIntegrators(this::compareIntegrators);
        controlPanel.setOnScrubStart(() -> scrubbing = true);
        controlPanel.setOnScrubTo(idx -> scrubIndex = idx);
        controlPanel.setOnScrubEnd(() -> scrubbing = false);
        controlPanel.setOnGraphVisibilityChange(this::setGraphVisible);
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
        // group's controls shown at a time.
        sidebarTabs = new SidebarTabs();
        sidebarTabs.build(
                controlPanel.getStatusBlock(),
                controlPanel.getMotionGroup(),
                controlPanel.getChaosGroup(),
                controlPanel.getGraphsGroup(),
                controlPanel.getHistoryGroup(),
                linkEditorPanel,
                controlPanel.getDisplayGroup());
        VBox.setVgrow(sidebarTabs, Priority.ALWAYS);
        controlHost.getChildren().setAll(sidebarTabs);

        // §5.4 — the sidebar's background must fill the whole right column
        // regardless of content length; binding controlHost's minHeight to
        // the ScrollPane's own (viewport) height, rather than leaving it to
        // "size to children," is what removes the uncovered strip at the
        // bottom when a group's content is short.
        controlHost.minHeightProperty().bind(sidebarScroll.heightProperty());

        buildActionRail();
        buildSidebarToggle();

        titleLabel.setText("N-Pendulum Chain Simulator   ·   N = " + config.getN()
                + "   ·   RK4 / Lagrangian Mechanics");

        renderTimer = buildRenderTimer();
    }

    // -------------------------------------------------------------------------
    // §5 layout shell — left action rail, sidebar/graph collapse
    // -------------------------------------------------------------------------

    /** Builds the always-present left action rail: Edit / Add (mutually exclusive, §4-e), Snap-to-Unit (§6). */
    private void buildActionRail() {
        Theme theme = ThemeManager.getInstance().getCurrent();

        Button editButton = new Button();
        editButton.getStyleClass().addAll("rail-button", "rail-button-locked-active"); // starts active
        Icons.IconView editIcon = Icons.create(Icons.Glyph.SELECT, 20, Icons.activeColor(theme));
        editButton.setGraphic(editIcon);
        Tooltip.install(editButton, new Tooltip("Edit — click a link to pose it; drag to pose live."));

        Button addButton = new Button();
        addButton.getStyleClass().add("rail-button");
        Icons.IconView addIcon = Icons.create(Icons.Glyph.ADD, 20, Icons.idleColor(theme));
        addButton.setGraphic(addIcon);
        Tooltip.install(addButton, new Tooltip("Add — double-click a link to insert a new one right after it."));

        editButton.setOnAction(e -> setActiveTool(RailTool.EDIT, editButton, editIcon, addButton, addIcon));
        addButton.setOnAction(e -> setActiveTool(RailTool.ADD, editButton, editIcon, addButton, addIcon));

        Separator sep = new Separator();
        sep.getStyleClass().add("rail-separator");

        ToggleButton snapButton = new ToggleButton();
        snapButton.getStyleClass().add("rail-button");
        Icons.IconView snapIcon = Icons.create(Icons.Glyph.SNAP, 20, Icons.idleColor(theme));
        snapButton.setGraphic(snapIcon);
        Tooltip.install(snapButton, new Tooltip("Snap to 15° angle / 0.25m length increments"));
        snapButton.setOnAction(e -> {
            boolean on = snapButton.isSelected();
            pendulumCanvas.setSnapEnabled(on);
            Theme t = ThemeManager.getInstance().getCurrent();
            snapIcon.setColor(on ? Icons.activeColor(t) : Icons.idleColor(t));
        });

        actionRail.getChildren().addAll(editButton, addButton, sep, snapButton);

        ThemeManager.getInstance().addListener(() -> {
            Theme t = ThemeManager.getInstance().getCurrent();
            editIcon.setColor(activeTool == RailTool.EDIT ? Icons.activeColor(t) : Icons.idleColor(t));
            addIcon.setColor(activeTool == RailTool.ADD ? Icons.activeColor(t) : Icons.idleColor(t));
            snapIcon.setColor(snapButton.isSelected() ? Icons.activeColor(t) : Icons.idleColor(t));
        });
    }

    /**
     * Switches the active rail tool (§4-e): moves the "locked-active" style
     * to whichever button is now current, recolors both icons, and gates
     * {@link PendulumCanvas}'s drag-posing accordingly — Add's only job is
     * inserting a link, not posing the chain, so left/right-drag are
     * intentionally no-ops while it's active.
     */
    private void setActiveTool(RailTool tool, Button editButton, Icons.IconView editIcon,
                                Button addButton, Icons.IconView addIcon) {
        activeTool = tool;
        boolean editActive = tool == RailTool.EDIT;

        editButton.getStyleClass().remove("rail-button-locked-active");
        addButton.getStyleClass().remove("rail-button-locked-active");
        (editActive ? editButton : addButton).getStyleClass().add("rail-button-locked-active");

        Theme t = ThemeManager.getInstance().getCurrent();
        editIcon.setColor(editActive ? Icons.activeColor(t) : Icons.idleColor(t));
        addIcon.setColor(editActive ? Icons.idleColor(t) : Icons.activeColor(t));

        pendulumCanvas.setDragEditingEnabled(editActive);
    }

    /** Builds the overlay chevron button (top-right of the canvas/graph area) that toggles the sidebar. */
    private void buildSidebarToggle() {
        Button toggle = new Button();
        toggle.getStyleClass().add("canvas-overlay-button");
        // Round 3 §2: a fixed, theme-independent tint, not Icons.hoverColor
        // (near-black in light theme) — this button's chip is always dark
        // regardless of app theme (see .canvas-overlay-button's own CSS
        // comment), so the icon has to ignore the app theme the same way,
        // or it goes invisible in light mode. No ThemeManager listener
        // needed anymore since the color is now genuinely constant.
        sidebarToggleIcon = Icons.create(Icons.Glyph.CHEVRON, 18, Icons.onDarkOverlayColor());
        sidebarToggleIcon.setRotate(sidebarExpanded ? 0 : 180);
        toggle.setGraphic(sidebarToggleIcon);
        Tooltip.install(toggle, new Tooltip("Toggle sidebar"));
        toggle.setOnAction(e -> setSidebarExpanded(!sidebarExpanded));

        StackPane.setAlignment(toggle, Pos.TOP_RIGHT);
        StackPane.setMargin(toggle, new Insets(10));
        canvasGraphStack.getChildren().add(toggle);
    }

    /**
     * Animates the sidebar's width between 0 (collapsed) and {@link
     * #SIDEBAR_EXPANDED_WIDTH}. Deliberately a min/pref/max width tween on a
     * real layout column, not {@code setVisible(false)} — the canvas/graph
     * genuinely shrink to make room rather than being overlaid.
     */
    private void setSidebarExpanded(boolean expanded) {
        this.sidebarExpanded = expanded;
        double target = expanded ? SIDEBAR_EXPANDED_WIDTH : 0.0;

        if (sidebarWidthAnim != null) sidebarWidthAnim.stop();

        if (ThemeManager.getInstance().isReducedMotion()) {
            sidebarScroll.setMinWidth(target);
            sidebarScroll.setPrefWidth(target);
            sidebarScroll.setMaxWidth(target);
        } else {
            sidebarWidthAnim = new Timeline(new KeyFrame(WIDTH_ANIM_DURATION,
                    new KeyValue(sidebarScroll.minWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(sidebarScroll.prefWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(sidebarScroll.maxWidthProperty(), target, Interpolator.EASE_BOTH)));
            sidebarWidthAnim.play();
        }

        if (sidebarToggleIcon != null) sidebarToggleIcon.setRotate(expanded ? 0 : 180);
    }

    /**
     * Animates the graph column's width between 0 (hidden) and {@link
     * #GRAPH_EXPANDED_WIDTH} — see §10. Entirely independent of the
     * sidebar's own collapse state; both just happen to use the same
     * min/pref/max-width technique.
     */
    private void setGraphVisible(boolean show) {
        double target = show ? GRAPH_EXPANDED_WIDTH : 0.0;

        if (graphWidthAnim != null) graphWidthAnim.stop();

        if (ThemeManager.getInstance().isReducedMotion()) {
            graphHost.setMinWidth(target);
            graphHost.setPrefWidth(target);
            graphHost.setMaxWidth(target);
        } else {
            graphWidthAnim = new Timeline(new KeyFrame(WIDTH_ANIM_DURATION,
                    new KeyValue(graphHost.minWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(graphHost.prefWidthProperty(), target, Interpolator.EASE_BOTH),
                    new KeyValue(graphHost.maxWidthProperty(), target, Interpolator.EASE_BOTH)));
            graphWidthAnim.play();
        }
    }

    // -------------------------------------------------------------------------
    // §7.1 — pause/resume + selection
    // -------------------------------------------------------------------------

    /**
     * The single place pause state changes from code (the Space shortcut
     * and bob selection both call this): keeps {@link SimulationLoop} and
     * {@link ControlPanel}'s Pause button in sync, and — per §7.1 — clears
     * any current selection whenever the simulation resumes, regardless of
     * what caused the pause in the first place. The Pause button's own
     * click handler (in {@link ControlPanel}) doesn't call this directly —
     * it mirrors the same two effects via {@link
     * ControlPanel#setOnPauseChange} instead, so there's no risk of a
     * double {@code simLoop.setPaused} call.
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
     * rebuild only changes the one link actually being edited, instead of
     * silently reverting every other link's live drift/prior drag back to
     * whatever currentConfig last held. Falls back to currentConfig only if
     * no state has been published yet.
     */
    private double[] liveAngles() {
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
     * Opens the double-click parameter dialog (§7.4): angle/length/mass for
     * one link, validated the same way {@link LinkEditorPanel} validates its
     * own rows, committing through {@link #applyStructuralEdit} on "Apply."
     * Selection/pause is already handled by the press that preceded this
     * double-click (§4-a) — nothing to do here for that.
     */
    private void showLinkParameterDialog(int link) {
        if (currentConfig == null || link < 0 || link >= currentConfig.getN()) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit Link #" + (link + 1));
        themeDialog(dialog.getDialogPane());

        ButtonType applyButtonType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButtonType, ButtonType.CANCEL);

        // Round 4 §1b: degrees, matching LinkEditorPanel's new default —
        // internal state stays radians; this dialog is display/input only.
        TextField angleField  = dialogField(String.format("%.4f", Math.toDegrees(currentConfig.getInitAngle(link))));
        TextField lengthField = dialogField(String.format("%.4f", currentConfig.getLength(link)));
        TextField massField   = dialogField(String.format("%.4f", currentConfig.getMass(link)));

        Label error = new Label();
        error.getStyleClass().add("sidebar-error-label");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("dialog-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Angle (°)"), angleField);
        grid.addRow(1, new Label("Length (m)"), lengthField);
        grid.addRow(2, new Label("Mass (kg)"), massField);
        grid.add(error, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(grid);

        Node applyButtonNode = dialog.getDialogPane().lookupButton(applyButtonType);
        applyButtonNode.addEventFilter(ActionEvent.ACTION, evt -> {
            Double angleDegrees = parseFinite(angleField.getText());
            Double length       = parsePositive(lengthField.getText());
            Double mass         = parsePositive(massField.getText());
            if (angleDegrees == null) { showDialogError(error, "Angle must be a finite number."); evt.consume(); return; }
            if (length == null)       { showDialogError(error, "Length must be a positive, finite number."); evt.consume(); return; }
            if (mass == null)         { showDialogError(error, "Mass must be a positive, finite number."); evt.consume(); return; }

            double[] lengths = currentConfig.getLengths();
            double[] masses  = currentConfig.getMasses();
            double[] angles  = liveAngles(); // every other link's live pose, not currentConfig's stale one (§4-f)
            lengths[link] = length;
            masses[link]  = mass;
            angles[link]  = Math.toRadians(angleDegrees);
            try {
                PendulumConfig edited = new PendulumConfig(currentConfig.getN(), lengths, masses, angles,
                        currentConfig.getGravity(), currentConfig.getSpeedMultiplier());
                applyStructuralEdit(edited);
            } catch (IllegalArgumentException ex) {
                showDialogError(error, ex.getMessage());
                evt.consume();
            }
        });

        if (btnBack.getScene() != null) dialog.initOwner(btnBack.getScene().getWindow());
        dialog.showAndWait();
    }

    /**
     * Opens the Add-link dialog (§4-e): double-clicking link {@code k} while
     * the Add tool is active. Splices a new link in as index {@code k+1};
     * every surviving link keeps its live pose (§4-f's {@link #liveAngles},
     * not currentConfig's stale one). The new link's angle is either the
     * entered value taken as-is (global-frame, matching every other link's
     * convention — see {@link physics.PhysicsEngine#getState()}) or, if the
     * "relative" checkbox is checked, {@code k}'s current live angle plus
     * the entered offset. Selects the newly-added link on confirm, and
     * leaves the Add tool active so more links can be added in a row.
     */
    private void showAddLinkDialog(int k) {
        if (currentConfig == null || k < 0 || k >= currentConfig.getN()) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Link After #" + (k + 1));
        themeDialog(dialog.getDialogPane());

        ButtonType addButtonType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        TextField lengthField = dialogField(String.format("%.4f", currentConfig.getLength(k)));
        TextField massField   = dialogField(String.format("%.4f", currentConfig.getMass(k)));
        TextField angleField  = dialogField("0.0000"); // zero is zero in either unit — no conversion needed here
        CheckBox relativeCheck = new CheckBox("Relative angle (offset from Link #" + (k + 1) + ")");
        relativeCheck.getStyleClass().add("sidebar-checkbox");

        Label error = new Label();
        error.getStyleClass().add("sidebar-error-label");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("dialog-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Length (m)"), lengthField);
        grid.addRow(1, new Label("Mass (kg)"), massField);
        grid.addRow(2, new Label("Angle (°)"), angleField);
        grid.add(relativeCheck, 0, 3, 2, 1);
        grid.add(error, 0, 4, 2, 1);
        dialog.getDialogPane().setContent(grid);

        Node addButtonNode = dialog.getDialogPane().lookupButton(addButtonType);
        addButtonNode.addEventFilter(ActionEvent.ACTION, evt -> {
            Double length     = parsePositive(lengthField.getText());
            Double mass       = parsePositive(massField.getText());
            Double angleInput = parseFinite(angleField.getText());
            if (length == null)     { showDialogError(error, "Length must be a positive, finite number."); evt.consume(); return; }
            if (mass == null)       { showDialogError(error, "Mass must be a positive, finite number."); evt.consume(); return; }
            if (angleInput == null) { showDialogError(error, "Angle must be a finite number."); evt.consume(); return; }

            double[] liveAngles = liveAngles();
            // §1b: angleInput is degrees (display unit) — convert before
            // combining with liveAngles[k], which is radians either way.
            double angleOffsetOrAbsoluteRadians = Math.toRadians(angleInput);
            double newAngle = relativeCheck.isSelected()
                    ? liveAngles[k] + angleOffsetOrAbsoluteRadians
                    : angleOffsetOrAbsoluteRadians;

            int n = currentConfig.getN();
            int newN = n + 1;
            double[] oldLengths = currentConfig.getLengths();
            double[] oldMasses  = currentConfig.getMasses();

            double[] lengths = new double[newN];
            double[] masses  = new double[newN];
            double[] angles  = new double[newN];

            for (int i = 0; i <= k; i++) {
                lengths[i] = oldLengths[i];
                masses[i]  = oldMasses[i];
                angles[i]  = liveAngles[i];
            }
            lengths[k + 1] = length;
            masses[k + 1]  = mass;
            angles[k + 1]  = newAngle;
            for (int i = k + 1; i < n; i++) {
                lengths[i + 1] = oldLengths[i];
                masses[i + 1]  = oldMasses[i];
                angles[i + 1]  = liveAngles[i];
            }

            try {
                PendulumConfig edited = new PendulumConfig(newN, lengths, masses, angles,
                        currentConfig.getGravity(), currentConfig.getSpeedMultiplier());
                applyStructuralEdit(edited);
                pendulumCanvas.setSelectedLink(k + 1); // override applyStructuralEdit's default (re-selects k)
            } catch (IllegalArgumentException ex) {
                showDialogError(error, ex.getMessage());
                evt.consume();
            }
        });

        if (btnBack.getScene() != null) dialog.initOwner(btnBack.getScene().getWindow());
        dialog.showAndWait();
    }

    /**
     * Opens the delete-confirmation dialog (round 4 §2): right-clicking link
     * {@code link} while the Add tool is active (see {@link
     * PendulumCanvas.RightClickListener}). Refuses on the sole remaining
     * link — {@link PendulumConfig} requires N &ge; 1 — with a plain info
     * alert rather than silently doing nothing. When a next link survives
     * the deletion, offers to keep its current swing direction (recomputed
     * from its new parent) instead of leaving its raw stored angle as-is;
     * this only ever touches that one surviving link's angle, never its
     * length, so "relative" means "preserve pose," not "preserve exact
     * world position" (the latter would require silently resizing a rod
     * nobody asked to resize).
     */
    private void showDeleteLinkDialog(int link) {
        if (currentConfig == null || link < 0 || link >= currentConfig.getN()) return;
        int n = currentConfig.getN();
        if (n <= 1) {
            Alert info = new Alert(Alert.AlertType.INFORMATION, "Can't delete the only remaining link.");
            themeDialog(info.getDialogPane());
            info.showAndWait();
            return;
        }

        boolean hasNext = link < n - 1;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Link #" + (link + 1));
        confirm.setHeaderText("Delete Link #" + (link + 1) + "?");
        themeDialog(confirm.getDialogPane());

        CheckBox relativeCheck = new CheckBox(
                "Keep Link #" + (link + 2) + "'s current pose (relative to its new parent)");
        relativeCheck.setSelected(true); // avoids a visual snap by default — judgment call
        if (hasNext) confirm.getDialogPane().setContent(relativeCheck);

        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            double[] liveAngles = liveAngles();
            double[] oldLengths = currentConfig.getLengths();
            double[] oldMasses  = currentConfig.getMasses();
            SimState state = stateBuffer.read();

            int newN = n - 1;
            double[] lengths = new double[newN];
            double[] masses  = new double[newN];
            double[] angles  = new double[newN];

            for (int i = 0; i < link; i++) {
                lengths[i] = oldLengths[i]; masses[i] = oldMasses[i]; angles[i] = liveAngles[i];
            }
            for (int i = link + 1; i < n; i++) {
                int dst = i - 1;
                lengths[dst] = oldLengths[i];
                masses[dst]  = oldMasses[i];
                angles[dst]  = liveAngles[i]; // "global" default — the raw stored angle, unchanged
            }

            if (hasNext && relativeCheck.isSelected() && state != null) {
                // Recompute the surviving next link's angle so it points the
                // same *direction* it did a moment ago, now measured from its
                // new parent (whatever the deleted link's own parent was) —
                // its length is left exactly as stored, so this preserves
                // pose, not necessarily the exact same (x,y): reattaching at
                // a different anchor distance can't hit the old point
                // without also changing the rod's length, which nothing
                // here asked for.
                double oldBx = state.bobX[link + 1], oldBy = state.bobY[link + 1];
                double parentX = (link == 0) ? 0.0 : state.bobX[link - 1];
                double parentY = (link == 0) ? 0.0 : state.bobY[link - 1];
                double dx = oldBx - parentX, dy = oldBy - parentY;
                // Same convention as PhysicsEngine.getState(): cx += L*sin(theta), cy -= L*cos(theta)
                angles[link] = Math.atan2(dx, -dy);
            }

            try {
                PendulumConfig edited = new PendulumConfig(newN, lengths, masses, angles,
                        currentConfig.getGravity(), currentConfig.getSpeedMultiplier());
                applyStructuralEdit(edited);
                pendulumCanvas.setSelectedLink(Math.min(link, newN - 1));
            } catch (IllegalArgumentException ex) {
                LOG.log(Level.WARNING, "Delete-link commit rejected", ex);
            }
        });
    }

    /** Themes a Dialog/Alert's pane the same way every themed dialog in this class does — pulled out to avoid a third copy-paste. */
    private static void themeDialog(DialogPane pane) {
        pane.getStylesheets().add(SimulationController.class.getResource("/css/theme.css").toExternalForm());
        pane.getStyleClass().addAll("themed-dialog", ThemeManager.getInstance().getCurrent().styleClass());
    }

    private static TextField dialogField(String initial) {
        TextField f = new TextField(initial);
        f.getStyleClass().add("sidebar-numeric-field");
        f.setPrefWidth(120);
        return f;
    }

    private static Double parseFinite(String text) {
        if (text == null) return null;
        try {
            double v = Double.parseDouble(text.trim());
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parsePositive(String text) {
        Double v = parseFinite(text);
        return (v != null && v > 0) ? v : null;
    }

    private static void showDialogError(Label error, String message) {
        error.setText(message);
        error.setVisible(true);
        error.setManaged(true);
    }

    /**
     * Handles a validated {@link PendulumConfig} from the link editor, the
     * length-drag commit, the parameter dialog, or the add-link dialog:
     * swaps the engine via {@link SimulationLoop#rebuildWithConfig}, then
     * refreshes everything downstream that was sized or labeled for the old
     * N/length — the canvas's render scale, its stale trail, the graph's
     * history, and the two sidebar headers that display N. The action-bar
     * tools (length drag, parameter dialog, Add) call this one-arg overload,
     * which never moves the Reset baseline — see the two-arg overload below.
     */
    private void applyStructuralEdit(PendulumConfig newConfig) {
        applyStructuralEdit(newConfig, false);
    }

    /**
     * @param establishesNewBaseline whether this edit is a deliberate
     *        "start fresh from here" (a preset pick, a file load, the
     *        sidebar's own "Apply Changes") that should move where the
     *        Reset button returns to — see {@link #originalConfig}.
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

    /**
     * Turns the butterfly-effect ensemble on or off. Spawning captures
     * whatever the primary engine's live state is <em>right now</em> — see
     * {@link Ensemble}'s javadoc for why that's deliberate.
     */
    private void setEnsembleActive(boolean active) {
        if (!active) {
            simLoop.setEnsemble(null);
            ensembleStartSimTime = null;
            return;
        }
        SimState current = stateBuffer.read();
        if (current == null) return; // physics thread hasn't published a first state yet
        simLoop.setEnsemble(new Ensemble(
                currentConfig, current.angles, current.angularVelocities,
                ENSEMBLE_SIZE, ENSEMBLE_EPSILON));
        ensembleStartSimTime = current.time;
    }

    /**
     * Activates or deactivates A/B compare: a single "B" engine, structurally
     * identical to the primary but with link 0's initial angle offset by
     * {@code deltaTheta1Radians}, spawned from the primary's exact current
     * state (mirroring {@link #setEnsembleActive}'s own "from right now, not
     * from a reset" reasoning) and stepped alongside it thereafter. Unlike
     * the ensemble, this is one deliberately, visibly different scenario —
     * see {@code ui.PendulumCanvas#drawCompareChain}.
     */
    private void setCompareActive(boolean active, double deltaTheta1Radians) {
        if (!active) {
            simLoop.setCompareEngine(null);
            return;
        }
        SimState current = stateBuffer.read();
        if (current == null) return; // physics thread hasn't published a first state yet

        PhysicsEngine compareEngine = new PhysicsEngine(currentConfig);
        for (int i = 0; i < current.getN(); i++) {
            double offset = (i == 0) ? deltaTheta1Radians : 0.0;
            compareEngine.setLinkState(i, current.angles[i] + offset, current.angularVelocities[i]);
        }
        simLoop.setCompareEngine(compareEngine);
    }

    /** Starts or stops the audio tone. The render loop feeds it a frequency each frame while active. */
    private void setSonifyActive(boolean active) {
        sonifyActive = active;
        if (active) sonifier.start();
        else        sonifier.stop();
    }

    /**
     * Runs {@link BifurcationSweep} on a background thread — see its own
     * javadoc for what it computes and why this can't run on the JavaFX
     * thread directly. {@code currentConfig} is captured once up front
     * (deliberately not read live from mid-sweep): the sweep already takes
     * a while, and racing it against the user editing links while it runs
     * would make "what does this diagram describe" ambiguous.
     */
    private void generateBifurcationMap() {
        if (bifurcationTask != null && bifurcationTask.isRunning()) return;

        PendulumConfig base = currentConfig;
        controlPanel.setBifurcationRunning(true);

        Task<BifurcationSweep.Result> task = new Task<>() {
            @Override
            protected BifurcationSweep.Result call() {
                return BifurcationSweep.sweep(base,
                        BIFURCATION_PARAM_MIN, BIFURCATION_PARAM_MAX, BIFURCATION_COLUMNS,
                        BIFURCATION_SETTLE_SECONDS, BIFURCATION_SAMPLE_SECONDS,
                        frac -> updateProgress(frac, 1.0), this::isCancelled);
            }
        };
        task.progressProperty().addListener((obs, oldVal, newVal) ->
                controlPanel.setBifurcationProgress(newVal.doubleValue()));
        task.setOnSucceeded(e -> {
            BifurcationSweep.Result result = task.getValue();
            graphPanel.setBifurcationData(result.paramValues(), result.samples());
            graphPanel.setMode(GraphPanel.Mode.BIFURCATION);
            controlPanel.selectBifurcationMode();
            controlPanel.setBifurcationRunning(false);
            bifurcationTask = null;
        });
        task.setOnFailed(e -> {
            LOG.log(Level.WARNING, "Bifurcation sweep failed", task.getException());
            controlPanel.setBifurcationRunning(false);
            bifurcationTask = null;
        });
        task.setOnCancelled(e -> {
            controlPanel.setBifurcationRunning(false);
            bifurcationTask = null;
        });

        bifurcationTask = task;
        Thread thread = new Thread(task, "BifurcationSweep");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Runs each {@link IntegratorType} from the primary's exact current
     * state for {@link #COMPARISON_DURATION_SECONDS}, using temporary
     * engine instances that never touch the live simulation, and plots the
     * resulting energy drift as {@link GraphPanel.ComparisonSeries}.
     */
    private void compareIntegrators() {
        SimState current = stateBuffer.read();
        if (current == null) return;

        // Same scope-channel triad as everywhere else in this palette
        // (magenta/cyan/yellow) — see ui.GraphPanel's own color comment.
        Color[] colors = { Color.web("#EA3F8C"), Color.web("#3DDCC7"), Color.web("#E8D34A") };
        List<GraphPanel.ComparisonSeries> series = new ArrayList<>();
        int steps = (int) Math.round(COMPARISON_DURATION_SECONDS / COMPARISON_DT);

        int colorIdx = 0;
        for (IntegratorType type : IntegratorType.values()) {
            PhysicsEngine engine = new PhysicsEngine(currentConfig);
            for (int i = 0; i < current.getN(); i++) {
                engine.setLinkState(i, current.angles[i], current.angularVelocities[i]);
            }
            Integrator integrator = type.create(2 * currentConfig.getN());
            engine.setIntegrator(integrator);

            double e0 = engine.getState().totalEnergy;
            double[] times = new double[steps + 1];
            double[] drift = new double[steps + 1];
            times[0] = 0;
            drift[0] = 0;

            for (int i = 1; i <= steps; i++) {
                engine.step(COMPARISON_DT);
                times[i] = i * COMPARISON_DT;
                drift[i] = Math.abs(engine.getState().totalEnergy - e0);
            }

            series.add(new GraphPanel.ComparisonSeries(type.toString(), times, drift, colors[colorIdx++ % colors.length]));
        }

        graphPanel.setComparisonData(series);
        graphPanel.setMode(GraphPanel.Mode.COMPARISON);
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
     * a viewer would notice stuttering. This staggering is a deliberate
     * budget: full-rate updates of all of it would waste time re-rendering
     * text and charts far faster than anyone can read them.
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

                if (sonifyActive && liveState != null && liveState.getN() > 0) {
                    // Tip bob (last link): the one whose speed already
                    // drives the fastest visual motion, so ear and eye track
                    // the same thing. Mapped linearly into an audible range
                    // rather than 1:1 to rad/s, which would mostly sit below
                    // or above what's pleasant to listen to.
                    double tipOmega = Math.abs(liveState.angularVelocities[liveState.getN() - 1]);
                    double hz = 220.0 + Math.min(tipOmega, 10.0) / 10.0 * 660.0;
                    sonifier.setFrequency(hz);
                }

                if (graphFrameCount % 2 == 0) {
                    graphPanel.addDataPoint(liveState);
                    graphPanel.render();
                }
                graphFrameCount++;

                if (graphFrameCount % 4 == 0) {
                    controlPanel.updateStatus(displayState, initialEnergy);
                    controlPanel.updateLyapunov(estimateLyapunov(liveState, ghosts));
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

    /**
     * Simplified two-trajectory Lyapunov estimate: lambda = ln(separation /
     * epsilon) / elapsed, using link 0's angular separation between the
     * primary and one ensemble member. This is the standard method's
     * single-measurement form, without Benettin's periodic renormalization
     * — accurate for the short initial-divergence window before saturation,
     * which is exactly the regime the ensemble is already used in. Returns
     * null whenever there's nothing to measure yet (no ensemble, too little
     * elapsed time, or separation not yet above floating-point noise).
     */
    private Double estimateLyapunov(SimState liveState, List<SimState> ghosts) {
        if (liveState == null || ghosts == null || ghosts.isEmpty() || ensembleStartSimTime == null) return null;

        double elapsed = liveState.time - ensembleStartSimTime;
        if (elapsed < 0.25) return null; // too little separation yet to measure meaningfully

        double separation = Math.abs(wrapAngleDelta(liveState.angles[0] - ghosts.get(0).angles[0]));
        if (separation < 1.0e-9) return null;

        return Math.log(separation / ENSEMBLE_EPSILON) / elapsed;
    }

    /** Normalises an angle difference into (−π, π], so a separation measured across the wrap boundary isn't reported as nearly a full turn. */
    private static double wrapAngleDelta(double delta) {
        while (delta >  Math.PI) delta -= 2 * Math.PI;
        while (delta < -Math.PI) delta += 2 * Math.PI;
        return delta;
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
        sonifier.stop();
        sonifyActive = false;
        controlPanel.setSonifyVisual(false);

        // A sweep left running after navigating away would keep a
        // background thread alive computing a diagram nobody can see.
        if (bifurcationTask != null) bifurcationTask.cancel();

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
