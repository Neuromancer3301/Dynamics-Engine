package ui;

import physics.SimState;
import javafx.scene.Cursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * JavaFX Canvas that renders the N-pendulum chain and owns direct-manipulation
 * (grab / drag / fling) of its bobs.
 *
 * Rendering features:
 *  - Dark-space gradient background with subtle grid
 *  - Glowing trail for the tip bob (last link)
 *  - Individually colored bobs with radial gradient highlights
 *  - Rod rendering with depth shadow
 *  - Info overlay: simulation time and total energy
 *
 * Interaction: register a {@link DragListener} via {@link #setDragListener}
 * to be notified as the user grabs, drags, and releases a bob. This class
 * owns the screen&harr;world coordinate transform (it's the only place that
 * knows the current {@code scale}/pivot) and does the hit-testing and
 * angle math; the listener only ever sees link indices and angles, so it
 * can stay a thin translation to {@code simulation.command.SimCommand}s
 * with no coordinate-geometry of its own.
 *
 * <p>Hovering (or dragging) a bob also shows a live inspector overlay —
 * that link's angle, angular velocity, mass, and rod length — entirely
 * internal to this class; nothing outside it needs to know a bob is being
 * inspected.
 */
public final class PendulumCanvas extends Canvas {

    // ---- Visual constants ----
    private static final int    TRAIL_MAX    = 600;
    private static final double ROD_WIDTH    = 2.5;
    private static final double PIVOT_RADIUS = 6.0;

    // Canvas-drawn text. Named rather than inlined because the overlay
    // boxes' width/height and per-line baselines are derived from them (see
    // drawBobInspector / drawInfoOverlay) — changing a size in one place
    // without the other is exactly how text ends up clipped by its own
    // background. These deliberately track theme.css's .sidebar-* sizes so
    // Canvas text and styled chrome read at the same scale, even though
    // JavaFX CSS can't reach a Canvas to enforce it.
    private static final double LABEL_FONT_SIZE     = 11;  // scale bar, gravity handle, compare marker
    private static final double INSPECTOR_FONT_SIZE = 12;
    private static final double INSPECTOR_LINE_H    = 17;
    private static final double OVERLAY_FONT_SIZE   = 13;
    private static final double OVERLAY_LINE_H      = 18;

    // Bob radius is no longer a constant: it shrinks with N (so a wide chain
    // doesn't render as a solid caterpillar — the runtime N control makes
    // this a real, reachable case, not a hypothetical one) and varies per
    // bob with mass (cube root, since a bob's visual "volume" should track
    // mass), so editing mass in the link editor is something you can see.
    private static final double MAX_BOB_RADIUS = 11.0;
    private static final double MIN_BOB_RADIUS = 3.5;

    // A bob is "hit" within its visible radius plus a few px of forgiveness —
    // the radius alone makes for an unpleasantly precise target, especially
    // once MIN_BOB_RADIUS shrinks it on a long chain.
    private static final double HIT_RADIUS_PAD = 6.0;

    // How far back to look when estimating angular velocity for a "fling"
    // on release. Long enough to smooth out per-frame jitter, short enough
    // that a fling reflects the motion right before release, not the whole drag.
    private static final long FLING_WINDOW_NANOS = 150_000_000L;

    // Above this N, the per-bob glow/gradient/highlight treatment in
    // drawChain is skipped in favour of a flat fill: at high N the bobs are
    // small and numerous enough that the extra draw calls cost real frame
    // time without being individually visible anyway.
    private static final int GRADIENT_THRESHOLD = 20;

    // Default per-bob color palette — the "Signal" identity's multi-channel
    // set: real oscilloscopes distinguish simultaneous channels with a small
    // set of saturated hues on a dark screen (yellow/cyan/green alongside
    // magenta), rather than an arbitrary rainbow. Link 0 (the accent
    // magenta) doubles as the app's one signature hue elsewhere — the
    // gravity handle, a single-series graph's trace — so a chain's first
    // link visually reads as "the primary signal" even in ALL_LINKS trail
    // mode.
    private static final Color[] BOB_COLORS_DEFAULT = {
        Color.web("#EA3F8C"),   // magenta (accent)
        Color.web("#3DDCC7"),   // cyan
        Color.web("#E8D34A"),   // yellow
        Color.web("#5FE87A"),   // green
        Color.web("#7B8CFF"),   // periwinkle
        Color.web("#FF8A3D"),   // orange
        Color.web("#C77DFF"),   // violet
        Color.web("#9AA0A6"),   // cool grey
    };

    // Okabe-Ito palette: the standard colour-blind-safe qualitative set,
    // distinguishable under all three common types of dichromacy. The
    // default palette's coral/mint pairing in particular is hard to tell
    // apart under deuteranopia (~8% of men) — this replaces the whole set,
    // not just that one pair, so no two adjacent link colors collide under
    // any common dichromacy.
    private static final Color[] BOB_COLORS_COLORBLIND_SAFE = {
        Color.web("#E69F00"),   // orange
        Color.web("#56B4E9"),   // sky blue
        Color.web("#009E73"),   // bluish green
        Color.web("#F0E442"),   // yellow
        Color.web("#0072B2"),   // blue
        Color.web("#D55E00"),   // vermillion
        Color.web("#CC79A7"),   // reddish purple
        Color.web("#999999"),   // grey
    };

    /** Per-link trail: which links (if any) leave a fading path. Cycled via {@link #cycleTrailMode}. */
    public enum TrailMode { OFF, TIP_ONLY, ALL_LINKS }

    /** Notified as the user grabs, drags, and releases a bob. See the class javadoc. */
    public interface DragListener {
        /** A bob was pressed. Return {@code true} to begin dragging it, {@code false} to ignore the press. */
        boolean onGrab(int linkIndex);

        /** The pointer moved while {@code linkIndex} was grabbed; {@code angle} is its new value in radians. */
        void onDrag(int linkIndex, double angle);

        /** The pointer was released; {@code angularVelocity} (rad/s) is estimated from the motion just before release. */
        void onRelease(int linkIndex, double angle, double angularVelocity);
    }

    /** Notified as the user drags the gravity-direction handle — "painting" the field. See {@link #drawGravityHandle}. */
    public interface GravityListener {
        /** The handle was dragged to a new direction; {@code angle} follows the same convention as a link's {@code theta} (0 = straight down). */
        void onGravityAngleChanged(double angle);
    }

    /**
     * Notified when a bob becomes selected — a completed left-button click
     * or click-drag-release (§7.1 of the UI overhaul spec). Selection is a
     * persistent state that outlives the gesture, unlike {@link
     * #hoveredLink}/{@link #draggedLink}; this class only reports the
     * event, it doesn't own the state itself — see {@link #setSelectedLink}.
     */
    public interface SelectionListener {
        void onLinkSelected(int linkIndex);
    }

    /**
     * Notified during a right-button ("length") drag on a bob (§7.3). Length
     * is a structural parameter — {@link #onLengthPreview} fires
     * continuously as a visual-only preview (no engine mutation), and
     * {@link #onLengthCommit} fires once on release, meant to be routed
     * through the same structural-edit path {@code ui.LinkEditorPanel}'s
     * "Apply Changes" already uses. {@code newLength} is already positive
     * and finite (clamped) by the time either method is called.
     */
    public interface LengthDragListener {
        void onLengthPreview(int linkIndex, double newLength);
        void onLengthCommit(int linkIndex, double newLength);
    }

    /** Notified when a bob is double-clicked (not part of a drag) — opens the per-link parameter dialog (§7.4). */
    public interface DoubleClickListener {
        void onDoubleClick(int linkIndex);
    }

    // Not final: a structural edit (per-link editor, runtime N) changes the
    // arm's total length after construction. Without a way to update this,
    // the render scale computed in render() would silently go stale — the
    // exact "extreme rod lengths not handled visually" gap flagged earlier.
    private double totalLength;

    // One trail deque per link, sized lazily to the current N in render()
    // (ensureTrailCapacity) since PendulumCanvas isn't otherwise notified
    // when a structural edit changes link count.
    private final List<Deque<double[]>> trails = new ArrayList<>(); // {screenX, screenY, |angularVelocity|} per link
    private TrailMode trailMode = TrailMode.TIP_ONLY;

    // Colours each trail segment by the bob's speed at that instant (blue =
    // slow, red = fast) instead of a fixed colour — off by default since it
    // trades away the ALL_LINKS mode's per-link colour coding, which is the
    // more informative default when N>1.
    private boolean velocityTint = false;

    // Angular speed (rad/s) that saturates the tint at "fast" (red). Not
    // measured from data — a fixed reference so the same colour always
    // means the same physical speed across different runs/scenarios,
    // otherwise comparing two sessions' trails by eye would be meaningless.
    private static final double VELOCITY_TINT_MAX = 8.0;

    private final Deque<double[]> dragSamples = new ArrayDeque<>(); // {nanos, angle}, most recent FLING_WINDOW_NANOS only

    private Color[] bobColors = BOB_COLORS_DEFAULT;

    // When true: no trail recording, and drawChain skips the glow halo and
    // specular highlight (kept: the base gradient body fill itself, which
    // is a static per-frame render rather than an animated flourish). See
    // ui.SimulationController for where this flag is populated from the
    // Settings screen's preference.
    private boolean reducedMotion = false;

    // Updated every render() call; read by mouse handlers to hit-test and
    // convert screen coordinates back to world angles between frames.
    private double scale = 1.0, pivotX, pivotY;
    private double bobBaseRadius = MAX_BOB_RADIUS;
    private SimState lastState;

    private DragListener dragListener;
    private int draggedLink = -1;
    private int hoveredLink = -1; // -1 when the pointer isn't over a bob; drawn as the inspector overlay in render()

    // Gravity-direction handle — a small draggable marker at a fixed pixel
    // radius from the pivot (not scaled with the chain, unlike everything
    // else drawn relative to it), independent of any link/bob. 0 = straight
    // down, matching physics.PhysicsEngine's convention exactly.
    private static final double GRAVITY_HANDLE_RADIUS = 46;
    private static final double GRAVITY_HANDLE_HIT_RADIUS = 10;
    private GravityListener gravityListener;
    private double gravityAngle = 0.0;
    private boolean draggingGravity = false;

    // ---- Selection (§7.1) ----
    private SelectionListener selectionListener;
    private int selectedLink = -1;

    // ---- Snap-to-unit (§6.3) ----
    private static final double ANGLE_SNAP_INCREMENT  = Math.PI / 12.0; // 15 degrees
    private static final double LENGTH_SNAP_INCREMENT = 0.25;           // meters
    private boolean snapEnabled = false;

    // ---- Right-click ("length") drag (§7.3) ----
    // Length is a structural parameter — dragging only produces a local
    // visual preview (previewLink/previewScreenX/Y below); the engine is
    // never touched until release, when lengthDragListener.onLengthCommit
    // fires exactly once.
    private static final double MIN_DRAG_LENGTH = 0.05;
    private LengthDragListener lengthDragListener;
    private int lengthDragLink = -1;
    private int previewLink = -1;
    private double[] previewScreenX;
    private double[] previewScreenY;

    // ---- Double-click (§7.4) ----
    private DoubleClickListener doubleClickListener;

    // Round 3 §4-e: while the Add tool is active, left/right-drag on a bob
    // are intentionally no-ops (Add's only job is inserting a link, not
    // posing the chain) — gated here rather than in the listener interfaces
    // themselves, since selection/pause (§4-a) must still fire either way.
    // See controller.SimulationController#setActiveTool.
    private boolean dragEditingEnabled = true;

    public PendulumCanvas(double width, double height, double totalLength) {
        super(width, height);
        this.totalLength = totalLength;
        wireInteraction(); // attach the mouse handlers once, at construction
    }

    /** Registers the listener notified of grab/drag/release. {@code null} disables interaction. */
    public void setDragListener(DragListener listener) {
        this.dragListener = listener;
    }

    /** Registers the listener notified when the gravity handle is dragged. {@code null} disables that interaction. */
    public void setGravityListener(GravityListener listener) {
        this.gravityListener = listener;
    }

    /** Registers the listener notified when a bob becomes selected. {@code null} disables that notification (selection can still be set programmatically). */
    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }

    /** Registers the listener notified during/after a right-click length drag. {@code null} disables that interaction. */
    public void setLengthDragListener(LengthDragListener listener) {
        this.lengthDragListener = listener;
    }

    /** Registers the listener notified on a double-click. {@code null} disables that interaction. */
    public void setDoubleClickListener(DoubleClickListener listener) {
        this.doubleClickListener = listener;
    }

    /**
     * Enables or disables left/right-drag posing of bobs (§4-e's Add-tool
     * no-op) without touching selection, which stays tool-agnostic — see
     * {@link #wireInteraction}. On by default (today's existing behavior).
     */
    public void setDragEditingEnabled(boolean dragEditingEnabled) {
        this.dragEditingEnabled = dragEditingEnabled;
    }

    /**
     * Sets the currently-selected link, drawn with a pulsing halo (see
     * {@link #drawSelectionHalo}) and reflected in the top-left HUD (see
     * {@link #drawSelectedLinkHud}). {@code -1} clears the selection. This
     * is the single source of truth for selection state — {@code
     * controller.SimulationController} owns the pause/resume policy (§7.1)
     * and calls this to reflect it visually; this class never pauses
     * anything itself.
     */
    public void setSelectedLink(int link) {
        this.selectedLink = link;
    }

    public int getSelectedLink() { return selectedLink; }

    /** Toggles 15°-angle / 0.25m-length drag snapping (§6.3). Off by default (continuous drag, today's existing behavior). */
    public void setSnapEnabled(boolean snapEnabled) {
        this.snapEnabled = snapEnabled;
    }

    /**
     * Updates where the handle is drawn without implying a drag happened —
     * for keeping the visual in sync after an external change (a structural
     * rebuild re-applying the previous angle; see
     * controller.SimulationController#applyStructuralEdit).
     */
    public void setGravityAngleVisual(double angle) {
        this.gravityAngle = angle;
    }

    /** Updates the arm length used to compute the render scale. Call after any structural rebuild. */
    public void setTotalLength(double totalLength) {
        this.totalLength = totalLength;
    }

    /** Cycles TIP_ONLY -> ALL_LINKS -> OFF -> TIP_ONLY. Clears existing trails on entering OFF, so nothing lingers. */
    public void cycleTrailMode() {
        trailMode = switch (trailMode) {
            case TIP_ONLY  -> TrailMode.ALL_LINKS;
            case ALL_LINKS -> TrailMode.OFF;
            case OFF       -> TrailMode.TIP_ONLY;
        };
        if (trailMode == TrailMode.OFF) clearTrail();
    }

    /** Current trail mode — read by the sidebar to keep its button label in sync. */
    public TrailMode getTrailMode() { return trailMode; }

    /** Enables colouring trail segments by speed instead of by link. See {@link #velocityColor}. */
    public void setVelocityTint(boolean on) { this.velocityTint = on; }

    /** Whether velocity tinting is active. */
    public boolean isVelocityTint() { return velocityTint; }

    /** Swaps the per-bob color palette. See {@link #BOB_COLORS_COLORBLIND_SAFE}'s javadoc for why this replaces the whole set. */
    public void setColorBlindSafe(boolean colorBlindSafe) {
        this.bobColors = colorBlindSafe ? BOB_COLORS_COLORBLIND_SAFE : BOB_COLORS_DEFAULT;
    }

    /** Disables trail recording and the glow/highlight flourishes in drawChain. See the field's javadoc. */
    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        if (reducedMotion) clearTrail();
    }

    /** Full render call with no ensemble or A/B compare — invoked every frame from the AnimationTimer when neither is active. */
    public void render(SimState state) {
        render(state, null, null);
    }

    /** Full render call, optionally drawing a butterfly-effect ensemble, with no A/B compare chain. */
    public void render(SimState state, List<SimState> ensembleStates) {
        render(state, ensembleStates, null);
    }

    /**
     * Full render call, optionally drawing a butterfly-effect ensemble as
     * faint ghost chains behind the primary, and/or a single bold "B"
     * chain for A/B compare (see {@link #drawCompareChain}).
     * {@code ensembleStates} may be {@code null} or empty when the ensemble
     * isn't active; {@code compareState} is {@code null} when A/B compare
     * isn't active.
     */
    public void render(SimState state, List<SimState> ensembleStates, SimState compareState) {
        GraphicsContext gc = getGraphicsContext2D();
        double W = getWidth();
        double H = getHeight();

        drawBackground(gc, W, H);

        this.lastState = state;
        if (state == null) {
            drawWaitingMessage(gc, W, H);
            return;
        }

        // ---- Coordinate mapping ----
        // Round 3 §4-c: pivot centred (was H*0.14, near the top) so a
        // near-180° swing has real clearance above the pivot, not just
        // below — the old near-top pivot clipped the apex of exactly the
        // near-inverted case this app ships a preset for (see Presets
        // .nearVerticalKnifeEdge()). scale zoomed out to match: bounded by
        // the smaller of two now roughly-symmetric clearances around the
        // centred pivot, not the old split's generous 80%-below/nothing-above.
        this.scale  = Math.min(W * 0.40, H * 0.40) / Math.max(totalLength, 0.1);
        this.pivotX = W / 2.0;
        this.pivotY = H * 0.46;
        this.bobBaseRadius = baseRadiusForN(state.getN());

        ensureTrailCapacity(state.getN());
        recordTrailPoints(state, scale, pivotX, pivotY);
        drawTrails(gc, state.getN());

        // Ghosts drawn before the primary chain, and with flat, ungradiented
        // strokes — cheap enough to repeat 50 times a frame, unlike the
        // primary bob's RadialGradient treatment.
        if (ensembleStates != null) {
            for (SimState ghost : ensembleStates) drawGhostChain(gc, ghost, scale, pivotX, pivotY);
        }

        // Drawn after the ghosts but before the primary chain, so a
        // deliberately-different B chain reads as "alongside" the primary
        // rather than buried under either it or the faint ensemble ghosts.
        if (compareState != null) drawCompareChain(gc, compareState, scale, pivotX, pivotY);

        drawPivot(gc, pivotX, pivotY);
        drawScaleIndicator(gc, pivotX, pivotY, scale);
        drawGravityHandle(gc, pivotX, pivotY);
        drawChain(gc, state, scale, pivotX, pivotY);
        drawSelectionHalo(gc, state, scale, pivotX, pivotY);
        drawInfoOverlay(gc, state, W);
        drawSelectedLinkHud(gc, state);

        // Whichever link is currently grabbed takes priority over whatever
        // was last hovered — mouseMoved doesn't fire mid-drag in JavaFX, so
        // hoveredLink alone would show stale data while dragging. Hover
        // inspection is tool-agnostic (§7.2): it fires whenever a bob is
        // hovered, regardless of selection or which rail tool is "active."
        int inspected = (draggedLink >= 0) ? draggedLink : hoveredLink;
        drawBobInspector(gc, state, inspected, scale, pivotX, pivotY, W);
    }

    /** Erases every link's trail history. Called on reset and after a structural rebuild, where old points describe a chain that no longer exists. */
    public void clearTrail() { trails.forEach(Deque::clear); }

    /**
     * Saves the current frame — pendulum, trails, ghosts, everything already
     * drawn on this canvas — as a PNG. "Trace art": running with a long
     * trail (see {@link #cycleTrailMode}) for a while and exporting captures
     * exactly the kind of chaotic-attractor-shaped image this class already
     * renders live, with no separate rendering path to keep in sync.
     *
     * <p>Converts pixel-by-pixel through {@link PixelReader} rather than
     * pulling in the {@code javafx.swing} module's {@code SwingFXUtils} —
     * one extra module and Maven dependency this project doesn't otherwise
     * need, just to avoid what's a handful of lines here (and {@code
     * java.desktop}, needed for {@link ImageIO} either way, is already in
     * the jpackage module list).
     */
    public void exportSnapshot(Path path) throws IOException {
        WritableImage image = this.snapshot(new SnapshotParameters(), null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", path.toFile());
    }

    /** Grows {@link #trails} to match N — never shrinks, so re-adding a link after removing one doesn't need special-casing. */
    private void ensureTrailCapacity(int n) {
        while (trails.size() < n) trails.add(new ArrayDeque<>());
    }

    /** Appends this frame's trail point(s) — one for the tip, or one per link, depending on {@link #trailMode}. */
    private void recordTrailPoints(SimState state, double scale, double pivotX, double pivotY) {
        if (reducedMotion || trailMode == TrailMode.OFF || state.getN() == 0) return;

        if (trailMode == TrailMode.TIP_ONLY) {
            recordTrailPoint(state, state.getN() - 1, scale, pivotX, pivotY);
        } else { // ALL_LINKS
            for (int i = 0; i < state.getN(); i++) recordTrailPoint(state, i, scale, pivotX, pivotY);
        }
    }

    /**
     * Stores one trail point as {@code {screenX, screenY, |angularVelocity|}}.
     *
     * <p>Note the points are stored in SCREEN coordinates, already
     * transformed — so drawing a trail is a straight line-to-line walk with
     * no per-point maths. The trade-off is that a resize leaves older points
     * at their old scale until they age out, which is imperceptible at 600
     * points and far cheaper than re-transforming every point every frame.
     * The speed is carried along so velocity tinting needs no second lookup.
     */
    private void recordTrailPoint(SimState state, int link, double scale, double pivotX, double pivotY) {
        double tx = pivotX + state.bobX[link] * scale;
        double ty = pivotY - state.bobY[link] * scale; // flip Y (screen Y down)
        Deque<double[]> t = trails.get(link);
        t.addLast(new double[]{tx, ty, Math.abs(state.angularVelocities[link])});
        while (t.size() > TRAIL_MAX) t.removeFirst();
    }

    // -------------------------------------------------------------------------
    // Interaction — hit-testing, coordinate transform, drag/fling
    // -------------------------------------------------------------------------

    private void wireInteraction() {
        setOnMouseMoved(e -> {
            if (dragListener == null) return;
            hoveredLink = hitTestBob(e.getX(), e.getY());
            boolean overGravityHandle = hoveredLink < 0 && hitTestGravityHandle(e.getX(), e.getY());
            setCursor((hoveredLink >= 0 || overGravityHandle) ? Cursor.HAND : Cursor.DEFAULT);
        });

        setOnMouseExited(e -> {
            hoveredLink = -1;
            setCursor(Cursor.DEFAULT);
        });

        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                // Right-click-drag = length (§7.3) — an entirely separate
                // gesture from the left-button angle drag below; grabbing a
                // bob here never affects the primary drag state. Selection
                // (and the resulting pause, via SimulationController) fires
                // on press regardless of button — see §4-a/§4-f.
                int hit = hitTestBob(e.getX(), e.getY());
                if (hit >= 0) {
                    if (selectionListener != null) selectionListener.onLinkSelected(hit);
                    if (dragEditingEnabled && lengthDragListener != null) lengthDragLink = hit;
                }
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) return;

            // Bob grabs take priority: the gravity handle sits
            // GRAVITY_HANDLE_RADIUS (46px) from the pivot, close enough to a
            // small/low-N chain's first bob that treating the handle as a
            // fallback rather than checking it first avoids ever stealing a
            // legitimate bob grab.
            int hit = hitTestBob(e.getX(), e.getY());
            if (hit >= 0) {
                // §4-a/§4-f: select (and pause) the instant the bob is
                // pressed, before any drag/double-click branching — one
                // code path, tool-agnostic, for angle-editing, length-
                // editing, and Add-tool clicks alike.
                if (selectionListener != null) selectionListener.onLinkSelected(hit);
                if (e.getClickCount() == 2) {
                    // Double-click (§7.4) — not a drag; the parameter/add
                    // dialog handles anything else it needs, so nothing more
                    // here.
                    if (doubleClickListener != null) doubleClickListener.onDoubleClick(hit);
                    return;
                }
                // §4-e: Add tool poses nothing — dragging a bob is a no-op
                // while it's active.
                if (!dragEditingEnabled || dragListener == null) return;
                if (dragListener.onGrab(hit)) {
                    draggedLink = hit;
                    dragSamples.clear();
                    recordDragSample(angleFromScreen(hit, e.getX(), e.getY()));
                }
                return;
            }
            if (gravityListener != null && hitTestGravityHandle(e.getX(), e.getY())) {
                draggingGravity = true;
            }
        });

        setOnMouseDragged(e -> {
            if (draggingGravity) {
                gravityAngle = gravityAngleFromScreen(e.getX(), e.getY());
                gravityListener.onGravityAngleChanged(gravityAngle);
                return;
            }
            if (lengthDragLink >= 0) {
                double newLength = clampLength(maybeSnapLength(computeDraggedLength(lengthDragLink, e.getX(), e.getY())));
                computeLengthPreview(lengthDragLink, newLength);
                if (lengthDragListener != null) lengthDragListener.onLengthPreview(lengthDragLink, newLength);
                return;
            }
            if (draggedLink < 0) return;
            double angle = maybeSnapAngle(angleFromScreen(draggedLink, e.getX(), e.getY()));
            recordDragSample(angle);
            dragListener.onDrag(draggedLink, angle);
        });

        setOnMouseReleased(e -> {
            if (draggingGravity) {
                draggingGravity = false;
                return;
            }
            if (lengthDragLink >= 0) {
                double newLength = clampLength(maybeSnapLength(computeDraggedLength(lengthDragLink, e.getX(), e.getY())));
                if (lengthDragListener != null) lengthDragListener.onLengthCommit(lengthDragLink, newLength);
                lengthDragLink = -1;
                clearLengthPreview();
                return;
            }
            if (draggedLink < 0) return;
            double angle = maybeSnapAngle(angleFromScreen(draggedLink, e.getX(), e.getY()));
            double angularVelocity = estimateAngularVelocity();
            dragListener.onRelease(draggedLink, angle, angularVelocity);
            // Selection (§7.1) now happens on press (§4-a), not here.
            draggedLink = -1;
            dragSamples.clear();
            hoveredLink = hitTestBob(e.getX(), e.getY());
            setCursor(hoveredLink >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });
    }

    private double maybeSnapAngle(double angle) {
        return snapEnabled ? snapToIncrement(angle, ANGLE_SNAP_INCREMENT) : angle;
    }

    private double maybeSnapLength(double length) {
        return snapEnabled ? snapToIncrement(length, LENGTH_SNAP_INCREMENT) : length;
    }

    private static double snapToIncrement(double value, double increment) {
        return Math.round(value / increment) * increment;
    }

    private static double clampLength(double length) {
        return Math.max(MIN_DRAG_LENGTH, length);
    }

    /** World-unit distance from {@code link}'s parent joint to a screen point — the same quantity {@link #angleFromScreen} measures the direction of. */
    private double computeDraggedLength(int link, double screenX, double screenY) {
        double parentX, parentY;
        if (link <= 0) {
            parentX = pivotX;
            parentY = pivotY;
        } else {
            parentX = pivotX + lastState.bobX[link - 1] * scale;
            parentY = pivotY - lastState.bobY[link - 1] * scale;
        }
        return Math.hypot(screenX - parentX, screenY - parentY) / scale;
    }

    /**
     * Recomputes screen-space bob positions for a proposed length change on
     * {@code editedLink}, without touching {@link #lastState}: the edited
     * bob moves to its new distance along its unchanged angle, and every
     * downstream bob (which only depends on its own angle/length, not the
     * edited one's) shifts by that same screen-space delta — the chain is
     * otherwise rigid. Read by {@link #drawChain} whenever {@link
     * #previewLink} is non-negative; cleared by {@link #clearLengthPreview}.
     */
    private void computeLengthPreview(int editedLink, double newLength) {
        if (lastState == null) return;
        int n = lastState.getN();
        if (editedLink < 0 || editedLink >= n) return;

        double[] xs = new double[n];
        double[] ys = new double[n];
        double dx = 0, dy = 0;

        for (int i = 0; i < n; i++) {
            double bx = pivotX + lastState.bobX[i] * scale;
            double by = pivotY - lastState.bobY[i] * scale;

            if (i == editedLink) {
                double parentX = (i == 0) ? pivotX : pivotX + lastState.bobX[i - 1] * scale;
                double parentY = (i == 0) ? pivotY : pivotY - lastState.bobY[i - 1] * scale;
                double theta = lastState.angles[i];
                double newBx = parentX + newLength * scale * Math.sin(theta);
                // + not - : matches this class's own screen-Y convention
                // everywhere else (render(): by = pivotY - bobY*scale, where
                // PhysicsEngine.getState() composes bobY = L*cos(theta)); the
                // old "-" mirrored the preview vertically around the parent
                // joint (a 30° angle previewing near 150°) — see round-3 §4-b.
                double newBy = parentY + newLength * scale * Math.cos(theta);
                dx = newBx - bx;
                dy = newBy - by;
                xs[i] = newBx;
                ys[i] = newBy;
            } else if (i > editedLink) {
                xs[i] = bx + dx;
                ys[i] = by + dy;
            } else {
                xs[i] = bx;
                ys[i] = by;
            }
        }

        this.previewScreenX = xs;
        this.previewScreenY = ys;
        this.previewLink = editedLink;
    }

    /** Ends a right-click length-drag preview — the next render() draws the chain from real state again. */
    public void clearLengthPreview() {
        this.previewLink = -1;
    }

    private boolean hitTestGravityHandle(double screenX, double screenY) {
        double dx = screenX - gravityHandleX(pivotX), dy = screenY - gravityHandleY(pivotY);
        double r = GRAVITY_HANDLE_HIT_RADIUS;
        return dx * dx + dy * dy <= r * r;
    }

    /** Same (sin, cos) convention as {@link #gravityHandleX}/{@link #gravityHandleY}, inverted: screen point relative to the pivot -> angle. */
    private double gravityAngleFromScreen(double screenX, double screenY) {
        return Math.atan2(screenX - pivotX, screenY - pivotY);
    }

    /**
     * Returns the index of the bob under the given screen point, or −1 for
     * empty space.
     *
     * <p>Iterates <b>backwards</b> from the tip on purpose: bobs are drawn
     * in ascending order, so later ones appear on top. Searching in reverse
     * means the visually topmost bob is found first, matching what the user
     * sees when two overlap. The target is padded by {@link #HIT_RADIUS_PAD}
     * because hitting an exact 4-pixel circle with a mouse is unpleasant.
     * Uses squared distance to avoid a square root per bob per event.
     */
    private int hitTestBob(double screenX, double screenY) {
        if (lastState == null) return -1;
        for (int i = lastState.getN() - 1; i >= 0; i--) {
            double bx = pivotX + lastState.bobX[i] * scale;
            double by = pivotY - lastState.bobY[i] * scale;
            double dx = screenX - bx, dy = screenY - by;
            double hitRadius = radiusForBob(lastState, i) + HIT_RADIUS_PAD;
            if (dx * dx + dy * dy <= hitRadius * hitRadius) return i;
        }
        return -1;
    }

    /** N-scaled base radius, floored/ceilinged so bobs stay visible at both N=1 and the editor's 60-link cap. */
    private static double baseRadiusForN(int n) {
        double r = MAX_BOB_RADIUS / Math.sqrt(Math.max(n, 1));
        return Math.max(MIN_BOB_RADIUS, Math.min(MAX_BOB_RADIUS, r));
    }

    /** This bob's radius: the current N-scaled base, adjusted by mass (cube root, so it tracks perceived volume). */
    private double radiusForBob(SimState state, int i) {
        double mass = (state.masses != null && i < state.masses.length) ? state.masses[i] : 1.0;
        double factor = Math.max(0.6, Math.min(1.6, Math.cbrt(Math.max(mass, 1.0e-6))));
        return bobBaseRadius * factor;
    }

    /**
     * <b>The inverse coordinate transform — this is what makes dragging
     * possible.</b>
     *
     * <p>Rendering goes one way: angle → screen position. Dragging needs the
     * other: the user gives a screen position, and we must work out what
     * angle would put the bob there. This method is that inverse.
     *
     * <p><b>Three steps:</b>
     * <ol>
     *   <li><b>Find the parent joint.</b> A link's angle is measured from
     *       where it is attached — the previous bob, or the pivot for link 0
     *       — not from the canvas origin.</li>
     *   <li><b>Convert to world units.</b> Subtract the parent's position to
     *       get a relative offset, divide by {@code scale} to undo the
     *       zoom, and negate y to undo the screen flip (screen y grows
     *       downward, world y grows upward).</li>
     *   <li><b>Take the arctangent.</b> {@code atan2(dx, -dy)} — note the
     *       deliberately unusual argument order and sign. It is exactly the
     *       inverse of the forward mapping in {@link
     *       physics.PhysicsEngine#getState()} ({@code x = L·sin(theta)},
     *       {@code y = −L·cos(theta)}), which is why the convention
     *       "theta = 0 means straight down" survives the round trip.</li>
     * </ol>
     */
    private double angleFromScreen(int linkIndex, double screenX, double screenY) {
        double parentX, parentY;
        if (linkIndex <= 0) {
            parentX = pivotX;
            parentY = pivotY;
        } else {
            parentX = pivotX + lastState.bobX[linkIndex - 1] * scale;
            parentY = pivotY - lastState.bobY[linkIndex - 1] * scale;
        }
        double worldDX =  (screenX - parentX) / scale;
        double worldDY = -(screenY - parentY) / scale; // undo the screen Y-flip used everywhere else in this class
        return Math.atan2(worldDX, -worldDY);
    }

    /**
     * Records a timestamped angle sample during a drag, discarding anything
     * older than {@link #FLING_WINDOW_NANOS}. This rolling window is what
     * {@link #estimateAngularVelocity} differentiates to produce a fling —
     * keeping it short means the throw reflects the motion just before
     * release, not the whole drag.
     */
    private void recordDragSample(double angle) {
        long now = System.nanoTime();
        dragSamples.addLast(new double[]{now, angle});
        while (!dragSamples.isEmpty() && now - dragSamples.peekFirst()[0] > FLING_WINDOW_NANOS) {
            dragSamples.removeFirst();
        }
    }

    /** Finite-difference angular velocity over the recent drag window, wrap-aware. Zero if too little motion was recorded. */
    private double estimateAngularVelocity() {
        if (dragSamples.size() < 2) return 0.0;
        double[] first = dragSamples.peekFirst();
        double[] last  = dragSamples.peekLast();
        double dt = (last[0] - first[0]) / 1_000_000_000.0;
        if (dt <= 1.0e-4) return 0.0;
        return wrapDelta(last[1] - first[1]) / dt;
    }

    /**
     * Normalises an angle DIFFERENCE into (−π, π].
     *
     * <p>Essential for the fling calculation: if a drag crosses the ±π
     * wrap-around boundary, the raw difference is about 2π (a full turn)
     * even though the bob barely moved. Without this, releasing near that
     * boundary would launch the bob at an enormous fictitious speed.
     */
    private static double wrapDelta(double delta) {
        while (delta >  Math.PI) delta -= 2 * Math.PI;
        while (delta < -Math.PI) delta += 2 * Math.PI;
        return delta;
    }

    // -------------------------------------------------------------------------
    // Private drawing helpers
    // -------------------------------------------------------------------------

    // Round 3 §4-d: shared text measurement for the HUD/inspector boxes
    // below, so their width tracks the font actually rendered instead of a
    // hardcoded pixel guess that quietly drifts out of sync with it (as
    // happened once already, when the earlier font-size pass landed).
    private static final double HUD_TEXT_PADDING = 9;
    private static final double HUD_MIN_WIDTH = 150;

    // Reused scratch node — cheap to query, no need to recreate per call.
    private final javafx.scene.text.Text metricsProbe = new javafx.scene.text.Text();

    private double measureTextWidth(Font font, String text) {
        metricsProbe.setFont(font);
        metricsProbe.setText(text);
        return metricsProbe.getLayoutBounds().getWidth();
    }

    private double hudBoxWidth(Font font, String... lines) {
        double max = 0;
        for (String line : lines) max = Math.max(max, measureTextWidth(font, line));
        return Math.max(HUD_MIN_WIDTH, HUD_TEXT_PADDING * 2 + max);
    }

    private void drawBackground(GraphicsContext gc, double W, double H) {
        // Near-void, neutral grey grid — no blue/violet tint, so the one
        // accent hue (magenta) never has to compete with a second hue baked
        // into the chrome itself. See theme.css's "Signal" note.
        gc.setFill(Color.web("#08080B"));
        gc.fillRect(0, 0, W, H);

        // Subtle grid
        gc.setStroke(Color.web("#1C1C22", 0.7));
        gc.setLineWidth(0.5);
        for (double x = 0; x < W; x += 45) gc.strokeLine(x, 0, x, H);
        for (double y = 0; y < H; y += 45) gc.strokeLine(0, y, W, y);

        // Horizontal centre line (helps perceive swing amplitude)
        double cx = W / 2.0;
        gc.setStroke(Color.web("#2E2E36", 0.6));
        gc.setLineWidth(1.0);
        gc.strokeLine(cx, 0, cx, H);
    }

    /**
     * One butterfly-effect ensemble member: a faint, flat-shaded chain with
     * no glow, gradient, or shadow — the ~50-instance-per-frame cost this
     * feature adds is kept cheap specifically by skipping everything {@link
     * #drawChain} spends on making the single primary chain look good.
     */
    private void drawGhostChain(GraphicsContext gc, SimState state, double scale, double pivotX, double pivotY) {
        // Faint copies of the same signal, not a second color story: each
        // ghost is the accent magenta at very low alpha, so 50 of them read
        // as "the live trace, echoed" rather than introducing a competing hue.
        double prevX = pivotX, prevY = pivotY;
        gc.setStroke(Color.web("#EA3F8C", 0.12));
        gc.setLineWidth(1.0);

        for (int i = 0; i < state.getN(); i++) {
            double bx = pivotX + state.bobX[i] * scale;
            double by = pivotY - state.bobY[i] * scale;
            gc.strokeLine(prevX, prevY, bx, by);
            prevX = bx;
            prevY = by;
        }

        gc.setFill(Color.web("#EA3F8C", 0.35));
        double r = 2.5;
        gc.fillOval(prevX - r, prevY - r, r * 2, r * 2);
    }

    /**
     * A/B compare's "B" chain — one deliberately-different scenario run
     * alongside the primary. Bold and clearly distinguished by color (not
     * a partial-alpha ghost the way the ensemble's 50 near-identical copies
     * are): this is meant to be read at a glance as "the other one," not
     * discovered by squinting.
     */
    private void drawCompareChain(GraphicsContext gc, SimState state, double scale, double pivotX, double pivotY) {
        double prevX = pivotX, prevY = pivotY;
        // Cyan — the "second channel" color everywhere else in this palette
        // (bob #2, the energy graph's KE trace): a two-channel scope reads
        // as "CH1 vs CH2," and this is deliberately that same pairing
        // against the magenta primary chain, not an arbitrary third hue.
        Color compareColor = Color.web("#3DDCC7");
        gc.setStroke(compareColor.deriveColor(0, 1, 1, 0.8));
        gc.setLineWidth(2.0);

        for (int i = 0; i < state.getN(); i++) {
            double bx = pivotX + state.bobX[i] * scale;
            double by = pivotY - state.bobY[i] * scale;
            gc.strokeLine(prevX, prevY, bx, by);
            prevX = bx;
            prevY = by;
        }

        gc.setFill(compareColor);
        double r = bobBaseRadius * 0.7;
        gc.fillOval(prevX - r, prevY - r, r * 2, r * 2);
        gc.setStroke(Color.web("#1B6E63"));
        gc.setLineWidth(1.0);
        gc.strokeOval(prevX - r, prevY - r, r * 2, r * 2);

        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 10));
        gc.setFill(compareColor);
        gc.fillText("B", prevX + r + 3, prevY + 3);
    }

    /** Draws whichever trails the current mode calls for — the tip only, or every link in its own colour. */
    private void drawTrails(GraphicsContext gc, int n) {
        if (trailMode == TrailMode.OFF) return;

        if (trailMode == TrailMode.TIP_ONLY) {
            // The tip trail is the single clearest "live signal" reading on
            // this whole canvas — it gets the accent, same as the gravity
            // handle and every other primary/live element.
            if (n > 0) drawOneTrail(gc, trails.get(n - 1), Color.web("#EA3F8C"));
        } else { // ALL_LINKS — each in its own bob color, so overlapping trails stay distinguishable
            for (int i = 0; i < n; i++) drawOneTrail(gc, trails.get(i), bobColors[i % bobColors.length]);
        }
    }

    /**
     * Draws one trail as a series of short segments whose alpha and width
     * both ramp up with age index — so the path fades out behind the bob
     * and thickens toward it, reading as motion rather than as a static
     * line. Per-segment styling is why this is a loop of {@code strokeLine}
     * calls rather than one {@code strokePolyline}.
     */
    private void drawOneTrail(GraphicsContext gc, Deque<double[]> trail, Color color) {
        if (trail.size() < 2) return;

        int total = trail.size();
        int idx   = 0;
        double[] prev = null;

        for (double[] pt : trail) {
            if (prev != null) {
                double alpha = 0.05 + 0.65 * ((double) idx / total);
                double width = 0.5 + 2.0 * ((double) idx / total);
                Color segmentColor = velocityTint ? velocityColor(pt[2]) : color;
                gc.setStroke(segmentColor.deriveColor(0, 1, 1, alpha));
                gc.setLineWidth(width);
                gc.strokeLine(prev[0], prev[1], pt[0], pt[1]);
            }
            prev = pt;
            idx++;
        }
    }

    /**
     * Maps an angular speed to a blue (slow) → magenta (fast, at/above
     * {@link #VELOCITY_TINT_MAX}) hue ramp — a short sweep through the cool
     * side of the wheel into the app's own accent hue, rather than the full
     * rainbow a blue-to-red ramp would cross. "Fast" lands on the same
     * magenta used for the accent/gravity handle/primary trace everywhere
     * else, so the tint's hottest color always means the same thing this
     * whole palette already uses it for.
     */
    private static Color velocityColor(double angularSpeed) {
        double t = Math.max(0.0, Math.min(1.0, angularSpeed / VELOCITY_TINT_MAX));
        double hue = 220.0 + t * 110.0; // 220=blue at t=0, up to 330=magenta at t=1
        return Color.hsb(hue, 0.85, 1.0);
    }

    // Candidate reference lengths for the scale bar, in the same world
    // units as PendulumConfig's lengths (meters, per the g=9.81 default).
    // "Nice" values, map-legend style, so the bar reads as a round number
    // rather than something like "0.73 m".
    private static final double[] SCALE_BAR_NICE_LENGTHS =
            {0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500};
    private static final double SCALE_BAR_TARGET_PX = 70;

    /**
     * A pivot-anchored ruler showing what one world-length unit looks like
     * at the current auto-fit {@code scale}. Necessary because {@code scale}
     * itself changes with N/link lengths (see {@link #render}'s coordinate
     * mapping comment) specifically so the whole chain always fits on
     * screen — without this, a longer chain silently redraws at a smaller
     * scale with no visual cue that "smaller" happened, and a viewer has no
     * way to compare apparent sizes across two different configurations.
     */
    private void drawScaleIndicator(GraphicsContext gc, double pivotX, double pivotY, double scale) {
        double referenceLength = pickScaleBarLength(scale);
        double barPx = referenceLength * scale;
        double barX  = pivotX + PIVOT_RADIUS + 24;
        double barY  = pivotY;

        gc.setStroke(Color.web("#8A8A94", 0.85));
        gc.setLineWidth(1.5);
        gc.strokeLine(barX, barY, barX + barPx, barY);
        gc.strokeLine(barX, barY - 4, barX, barY + 4);
        gc.strokeLine(barX + barPx, barY - 4, barX + barPx, barY + 4);

        gc.setFill(Color.web("#8A8A94"));
        gc.setFont(Font.font("Monospaced", 10));
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        String label = (referenceLength == Math.floor(referenceLength))
                ? String.format("%d m", (int) referenceLength)
                : String.format("%s m", referenceLength);
        gc.fillText(label, barX + barPx / 2.0, barY - 8);
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT); // restore default for every other fillText in this class
    }

    /** Handle position for the current {@link #gravityAngle} — same (sin, cos) convention {@code render()} uses for every bob, at a fixed pixel radius instead of a scaled world length. */
    private double gravityHandleX(double pivotX) { return pivotX + GRAVITY_HANDLE_RADIUS * Math.sin(gravityAngle); }
    // Note: +cos here, whereas bobs use -cos. The handle points ALONG gravity
    // (downward at angle 0); a bob at angle 0 hangs down but is drawn from a
    // pivot using screen coordinates where +y is down. Both are consistent
    // with their own reference point.
    private double gravityHandleY(double pivotY) { return pivotY + GRAVITY_HANDLE_RADIUS * Math.cos(gravityAngle); }

    /**
     * Draggable indicator for {@link PhysicsEngine#getGravityAngle}
     * ("painting" the gravity field, per the project's nice-to-have review):
     * a short line from the pivot to a small handle in the current gravity
     * direction. Grabbing and dragging the handle rotates it — see {@link
     * #wireInteraction}. Always drawn (not gated by trail mode or reduced
     * motion): it's a static control, not an animated flourish.
     */
    private void drawGravityHandle(GraphicsContext gc, double pivotX, double pivotY) {
        double hx = gravityHandleX(pivotX), hy = gravityHandleY(pivotY);

        // The accent, not a fourth color: this is the one thing on the
        // whole canvas the user directly manipulates, and "what's
        // interactive looks interactive" here means it shares the same
        // magenta as every other live/active element rather than
        // introducing its own hue.
        gc.setStroke(Color.web("#EA3F8C", 0.75));
        gc.setLineWidth(1.5);
        gc.strokeLine(pivotX, pivotY, hx, hy);

        gc.setFill(draggingGravity ? Color.web("#EA3F8C") : Color.web("#EA3F8C", 0.85));
        gc.fillOval(hx - 5, hy - 5, 10, 10);
        gc.setStroke(Color.web("#7A2148"));
        gc.setLineWidth(1.0);
        gc.strokeOval(hx - 5, hy - 5, 10, 10);

        gc.setFont(Font.font("Monospaced", 10));
        gc.setFill(Color.web("#EA3F8C"));
        gc.fillText("g", hx + 8, hy + 3);
    }

    /** Picks the nice reference length whose pixel length lands closest to {@link #SCALE_BAR_TARGET_PX}. */
    private static double pickScaleBarLength(double scale) {
        double best = SCALE_BAR_NICE_LENGTHS[0];
        double bestDiff = Double.MAX_VALUE;
        for (double candidate : SCALE_BAR_NICE_LENGTHS) {
            double diff = Math.abs(candidate * scale - SCALE_BAR_TARGET_PX);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        return best;
    }

    /** Draws the fixed anchor the chain hangs from: a shadowed disc with a crosshair, so the origin of the coordinate system is visually unambiguous. */
    private void drawPivot(GraphicsContext gc, double px, double py) {
        // Neutral, not accent: the pivot is structural (it never moves,
        // never carries live data), so it stays plain grey — the accent is
        // reserved for what's actually live or interactive on this canvas.
        // Shadow
        gc.setFill(Color.web("#000000", 0.4));
        gc.fillOval(px - PIVOT_RADIUS + 2, py - PIVOT_RADIUS + 2,
                    PIVOT_RADIUS * 2, PIVOT_RADIUS * 2);
        // Body
        gc.setFill(Color.web("#D6D6DC"));
        gc.fillOval(px - PIVOT_RADIUS, py - PIVOT_RADIUS, PIVOT_RADIUS * 2, PIVOT_RADIUS * 2);
        // Cross mark
        gc.setStroke(Color.web("#6B6B74"));
        gc.setLineWidth(1.5);
        gc.strokeLine(px - 4, py, px + 4, py);
        gc.strokeLine(px, py - 4, px, py + 4);
    }

    private void drawChain(GraphicsContext gc, SimState state,
                           double scale, double pivotX, double pivotY) {
        double prevX = pivotX, prevY = pivotY;
        boolean simple = reducedMotion || state.getN() > GRADIENT_THRESHOLD;

        // A right-click length drag in progress (§7.3): draw from the
        // locally-computed preview positions instead of live state — the
        // engine itself is untouched until release, so this is the only
        // place the proposed length is visible.
        boolean previewing = previewLink >= 0 && previewScreenX != null && previewScreenX.length == state.getN();

        for (int i = 0; i < state.getN(); i++) {
            double bx = previewing ? previewScreenX[i] : pivotX + state.bobX[i] * scale;
            double by = previewing ? previewScreenY[i] : pivotY - state.bobY[i] * scale;

            // Rod shadow
            gc.setStroke(Color.web("#000000", 0.35));
            gc.setLineWidth(ROD_WIDTH + 2);
            gc.strokeLine(prevX + 1.5, prevY + 1.5, bx + 1.5, by + 1.5);

            // Rod
            gc.setStroke(Color.web("#C7C7D1"));
            gc.setLineWidth(ROD_WIDTH);
            gc.strokeLine(prevX, prevY, bx, by);

            double bobRadius = radiusForBob(state, i);
            Color bobColor = bobColors[i % bobColors.length];

            if (simple) {
                // Flat fill only: no glow, no gradient, no shadow, no
                // highlight. At N > GRADIENT_THRESHOLD the bobs are small
                // and numerous enough that none of that is individually
                // visible anyway, so it's pure frame-time cost; under
                // reduced motion it's the "gradient highlights" the
                // preference is specifically about disabling.
                gc.setFill(bobColor);
                gc.fillOval(bx - bobRadius, by - bobRadius, bobRadius * 2, bobRadius * 2);
            } else {
                // Bob glow (larger semi-transparent halo)
                gc.setFill(bobColor.deriveColor(0, 1, 1, 0.18));
                double glowR = bobRadius * 1.8;
                gc.fillOval(bx - glowR, by - glowR, glowR * 2, glowR * 2);

                // Bob shadow
                gc.setFill(Color.web("#000000", 0.35));
                gc.fillOval(bx - bobRadius + 2, by - bobRadius + 2,
                            bobRadius * 2, bobRadius * 2);

                // Bob body — radial gradient
                RadialGradient gradient = new RadialGradient(
                        0, 0,
                        bx - bobRadius * 0.35, by - bobRadius * 0.35,
                        bobRadius * 1.2,
                        false, CycleMethod.NO_CYCLE,
                        new Stop(0, bobColor.brighter()),
                        new Stop(1, bobColor.darker().darker())
                );
                gc.setFill(gradient);
                gc.fillOval(bx - bobRadius, by - bobRadius, bobRadius * 2, bobRadius * 2);

                // Bob specular highlight
                gc.setFill(Color.web("#FFFFFF", 0.35));
                double hl = bobRadius * 0.55;
                gc.fillOval(bx - hl * 0.9, by - hl * 0.9, hl, hl);
            }

            prevX = bx;
            prevY = by;
        }
    }

    // Cycles per second for the selection halo's pulse — fast enough to
    // read as "alive," slow enough not to be distracting next to the
    // pendulum's own motion.
    private static final double HALO_PULSE_HZ = 1.2;

    /**
     * A glowing accent ring around the selected bob (§7.1), redrawn every
     * frame. Pulses via a simple sine-based alpha animation; under {@link
     * #reducedMotion} it's a static ring at a fixed alpha instead — the
     * same "skip the tween, keep the state visible" rule as everywhere else
     * in this app that checks {@code isReducedMotion()}.
     */
    private void drawSelectionHalo(GraphicsContext gc, SimState state, double scale, double pivotX, double pivotY) {
        if (selectedLink < 0 || selectedLink >= state.getN()) return;

        double bx = pivotX + state.bobX[selectedLink] * scale;
        double by = pivotY - state.bobY[selectedLink] * scale;
        double r = radiusForBob(state, selectedLink) * 1.9;

        double alpha;
        if (reducedMotion) {
            alpha = 0.6;
        } else {
            double phase = (System.nanoTime() / 1.0e9) * HALO_PULSE_HZ * 2 * Math.PI;
            alpha = 0.35 + 0.35 * (0.5 + 0.5 * Math.sin(phase));
        }

        gc.setStroke(Color.web("#EA3F8C", alpha));
        gc.setLineWidth(2.5);
        gc.strokeOval(bx - r, by - r, r * 2, r * 2);
    }

    /**
     * The hover/drag inspector: live angle, angular velocity, mass, and
     * rod length for whichever link is currently hovered or grabbed. Rod
     * length isn't in {@link SimState} — it's recovered here as the
     * distance between this link's bob and its parent joint, which works
     * regardless of the current angle since {@code bobX}/{@code bobY} are
     * cumulative positions and consecutive ones isolate exactly one rod.
     */
    private void drawBobInspector(GraphicsContext gc, SimState state, int link,
                                  double scale, double pivotX, double pivotY, double canvasW) {
        if (link < 0 || link >= state.getN()) return;

        double bx = pivotX + state.bobX[link] * scale;
        double by = pivotY - state.bobY[link] * scale;
        double parentX = (link == 0) ? pivotX : pivotX + state.bobX[link - 1] * scale;
        double parentY = (link == 0) ? pivotY : pivotY - state.bobY[link - 1] * scale;
        double rodLength = Math.hypot(bx - parentX, by - parentY) / scale;
        double mass = (state.masses != null && link < state.masses.length) ? state.masses[link] : Double.NaN;

        Font font = Font.font("Monospaced", 14);
        gc.setFont(font);

        String line1 = "Link #" + (link + 1);
        String line2 = String.format("θ=%+.3f rad  ω=%+.3f rad/s", state.angles[link], state.angularVelocities[link]);
        String line3 = String.format("m=%.3f kg   L=%.3f m", mass, rodLength);

        double boxW = hudBoxWidth(font, line1, line2, line3);
        double boxH = 58;
        double boxX = Math.min(bx + 14, canvasW - boxW - 4);
        double boxY = Math.max(by - boxH - 14, 4);

        // Opacity raised from 0.6 (see the class-level note on §2's audit):
        // at the larger font sizes this pass introduces, the old value let
        // a bright swinging bob or a light-colored trail show through
        // enough to hurt legibility.
        gc.setFill(Color.web("#000000", 0.7));
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);
        gc.setStroke(Color.web("#EA3F8C", 0.7));
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(boxX, boxY, boxW, boxH, 4, 4);

        double textY = boxY + 19;
        gc.setFill(Color.web("#FFFFFF"));
        gc.fillText(line1, boxX + HUD_TEXT_PADDING, boxY + 18);
        gc.setFill(Color.web("#D6D6DC"));
        gc.fillText(line2, boxX + HUD_TEXT_PADDING, boxY + 36);
        gc.fillText(line3, boxX + HUD_TEXT_PADDING, boxY + 53);
    }

    // Round 3 §4-d: drawSelectedLinkHud sits immediately right of this pill,
    // so it needs this call's actual box width, not a hardcoded guess —
    // recorded here each frame since render() always draws this first.
    private double infoOverlayBoxW = 216;

    private void drawInfoOverlay(GraphicsContext gc, SimState state, double W) {
        Font font = Font.font("Monospaced", FontWeight.NORMAL, 14);
        gc.setFont(font);

        String line1 = String.format("t  = %7.2f s",   state.time);
        String line2 = String.format("E  = %7.3f J",   state.totalEnergy);
        String line3 = String.format("KE = %6.3f  PE = %7.3f", state.kineticEnergy, state.potentialEnergy);

        double boxX = 8, boxY = 8, boxH = 58;
        double boxW = hudBoxWidth(font, line1, line2, line3);
        this.infoOverlayBoxW = boxW;

        // Semi-transparent pill background — opacity raised from 0.45 (see
        // §2's audit note) so the larger text stays legible over a bright
        // swinging bob or a light-colored trail passing behind it.
        gc.setFill(Color.web("#000000", 0.6));
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

        // Live telemetry gets the accent — the same "one hue for anything
        // actively reporting a current value" rule as the tip trail and the
        // gravity handle.
        double textY = 27;
        gc.setFill(Color.web("#EA3F8C"));
        gc.fillText(line1, boxX + HUD_TEXT_PADDING, boxY + 19);
        gc.fillText(line2, boxX + HUD_TEXT_PADDING, boxY + 35);
        gc.fillText(line3, boxX + HUD_TEXT_PADDING, boxY + 51);
    }

    /**
     * A second, fixed-position HUD pill beside the time/energy overlay,
     * shown only while a link is selected (§8) — the same fields {@link
     * #drawBobInspector} already computes, just anchored top-left instead
     * of floating near the bob, and driven by selection rather than
     * hover/drag. This is in addition to, not a replacement for, the
     * floating inspector, which keeps following whichever bob is
     * hovered/dragged regardless of selection.
     */
    private void drawSelectedLinkHud(GraphicsContext gc, SimState state) {
        if (selectedLink < 0 || selectedLink >= state.getN()) return;

        double bx = pivotX + state.bobX[selectedLink] * scale;
        double by = pivotY - state.bobY[selectedLink] * scale;
        double parentX = (selectedLink == 0) ? pivotX : pivotX + state.bobX[selectedLink - 1] * scale;
        double parentY = (selectedLink == 0) ? pivotY : pivotY - state.bobY[selectedLink - 1] * scale;
        double rodLength = Math.hypot(bx - parentX, by - parentY) / scale;
        double mass = (state.masses != null && selectedLink < state.masses.length) ? state.masses[selectedLink] : Double.NaN;

        Font font = Font.font("Monospaced", 14);
        gc.setFont(font);

        String line1 = "Selected: Link #" + (selectedLink + 1);
        String line2 = String.format("θ=%+.3f rad  ω=%+.3f rad/s", state.angles[selectedLink], state.angularVelocities[selectedLink]);
        String line3 = String.format("m=%.3f kg   L=%.3f m", mass, rodLength);

        double boxX = 8 + infoOverlayBoxW + 8; // immediately right of drawInfoOverlay's pill
        double boxY = 8;
        double boxW = hudBoxWidth(font, line1, line2, line3);
        double boxH = 58;

        gc.setFill(Color.web("#000000", 0.6));
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);
        gc.setStroke(Color.web("#EA3F8C", 0.7));
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(boxX, boxY, boxW, boxH, 4, 4);

        gc.setFill(Color.web("#EA3F8C"));
        gc.fillText(line1, boxX + HUD_TEXT_PADDING, boxY + 18);
        gc.setFill(Color.web("#D6D6DC"));
        gc.fillText(line2, boxX + HUD_TEXT_PADDING, boxY + 36);
        gc.fillText(line3, boxX + HUD_TEXT_PADDING, boxY + 53);
    }

    /** Shown for the brief window between the screen opening and the physics thread publishing its first state, instead of an empty canvas. */
    private void drawWaitingMessage(GraphicsContext gc, double W, double H) {
        gc.setFill(Color.web("#7C7C88", 0.9));
        gc.setFont(Font.font("System", FontWeight.NORMAL, 17));
        gc.fillText("Initialising physics engine...", W / 2 - 105, H / 2);
    }
}
