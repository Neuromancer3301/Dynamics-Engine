package ui.nbody;

import ui.simcore.Camera;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Owns direct-manipulation of an {@link NBodyCanvas}'s bodies: hit-testing,
 * screen&harr;world position math, drag/fling, and drag-to-pan on empty
 * space. The n-body analogue of {@code ui.pendulum.PendulumInteraction} —
 * same composed-not-inherited shape (round 1 §4 of the UI restructuring
 * plan), same reason panning is wired here rather than in {@code
 * ui.simcore.SimCanvas} (a {@code Canvas} can only usefully have one owner
 * of its mouse handlers, and pan-on-empty-space needs this class's own
 * hit-testing to know what "empty space" even means).
 *
 * <p>Two deliberate simplifications versus the pendulum, both because a
 * flat body set has no ordering the way a chain does (n-body implementation
 * spec §6.2):
 * <ul>
 *   <li><b>Right-click opens the delete-confirmation dialog directly</b>, in
 *       either tool — there's no length-drag gesture to reserve it for one
 *       tool the way the pendulum's right-click-drag/right-click-release
 *       split does.</li>
 *   <li><b>A clean click (not a drag) on empty space</b> fires {@link
 *       NBodyCanvas.EmptySpaceClickListener} with the clicked world
 *       position — the controller opens the Add dialog there only when the
 *       Add tool is active, mirroring how the double-click listener below
 *       is routed to either the parameter or Add dialog by the controller,
 *       not by this class. A press-drag-release that moved more than a few
 *       pixels is treated as an ordinary pan instead (see {@link
 *       #EMPTY_CLICK_MAX_DRAG_PX}) — panning while the Add tool is active
 *       still needs to work.</li>
 * </ul>
 *
 * <p>No gravity-handle equivalent — n-body gravity is emergent from mutual
 * mass, not a paintable uniform field; nothing to drag for it.
 */
final class NBodyInteraction {

    // How far back to look when estimating a fling velocity on release —
    // matches PendulumInteraction's own window exactly.
    private static final long FLING_WINDOW_NANOS = 150_000_000L;

    // Round 1.4: converts the pointer's on-SCREEN speed (px/s) at release
    // into a world-space fling velocity (m/s) — deliberately NOT derived
    // from how far the drag moved in world space (screenToWorldX/Y),
    // unlike PendulumInteraction's identical-looking formula, which this
    // was originally copied from. That formula works for the pendulum only
    // because its world scale happens to sit within an order of magnitude
    // of screen pixels (a few metres, a few dozen px/metre); at n-body
    // scale, 1 screen pixel is routinely BILLIONS of metres, so the same
    // formula turned an ordinary 100px drag into a released velocity of
    // roughly 47,000x the speed of light — the body's very next physics
    // step would place it so far away it reads as having simply vanished.
    // Measuring the swipe in screen space instead and converting through
    // this fixed constant makes a fling's felt strength depend on how fast
    // you actually moved the mouse, not on how zoomed in the camera
    // happens to be — a deliberate, very fast flick (~2000px/s) lands
    // around 30km/s, comparable to Earth's own orbital speed; an ordinary
    // slow drag-release lands at a gentle few km/s or less.
    private static final double FLING_WORLD_MPS_PER_SCREEN_PXPS = 15.0;

    // Defensive backstop on top of the conversion above, not a substitute
    // for it — real mouse input rarely reports screen speeds that would
    // exceed this once converted, but a dropped-frame environment
    // reporting one large jump between two mouse-move events shouldn't be
    // able to launch a body past every real planetary orbital speed in
    // this app's presets (Mercury's, the fastest, is ~47km/s) regardless.
    private static final double FLING_MAX_WORLD_MPS = 5.0e5;

    // A body is "hit" within its visible radius plus a few px of
    // forgiveness — this is what keeps a floor-sized (visually tiny) body
    // clickable regardless of its render size (see NBodyRenderer's own
    // MIN_VISIBLE_RADIUS floor).
    private static final double HIT_RADIUS_PAD = 6.0;

    // A press-release on empty space that moved less than this many screen
    // pixels counts as a click (fires EmptySpaceClickListener), not a pan.
    private static final double EMPTY_CLICK_MAX_DRAG_PX = 5.0;

    private final NBodyCanvas canvas;
    private final Camera camera;
    private final NBodyRenderer renderer;

    private NBodyCanvas.DragListener dragListener;
    private NBodyCanvas.SelectionListener selectionListener;
    private NBodyCanvas.DoubleClickListener doubleClickListener;
    private NBodyCanvas.RightClickListener rightClickListener;
    private NBodyCanvas.EmptySpaceClickListener emptySpaceClickListener;

    private int draggedBody = -1;
    private int hoveredBody = -1; // -1 when the pointer isn't over a body

    private final Deque<double[]> dragSamples = new ArrayDeque<>(); // {nanos, worldX, worldY}, most recent FLING_WINDOW_NANOS only

    private boolean panning = false;
    private double lastPanScreenX, lastPanScreenY;
    private double totalPanDragPx; // accumulated since the current press — see EMPTY_CLICK_MAX_DRAG_PX

    // Round 1.1: gated by the Add tool, same mechanism/reasoning as
    // PendulumInteraction's own field — see NBodyActionRailBuilder's
    // class javadoc for why this was added. Selection and double-click
    // stay tool-agnostic; only starting a new drag is gated.
    private boolean dragEditingEnabled = true;

    NBodyInteraction(NBodyCanvas canvas, Camera camera, NBodyRenderer renderer) {
        this.canvas = canvas;
        this.camera = camera;
        this.renderer = renderer;
        wireInteraction();
    }

    /** Read by {@code NBodyCanvas.drawContent()} to feed the renderer's hover HUD. */
    int hoveredBody() { return hoveredBody; }

    void setDragListener(NBodyCanvas.DragListener listener) { this.dragListener = listener; }
    void setSelectionListener(NBodyCanvas.SelectionListener listener) { this.selectionListener = listener; }
    void setDoubleClickListener(NBodyCanvas.DoubleClickListener listener) { this.doubleClickListener = listener; }
    void setRightClickListener(NBodyCanvas.RightClickListener listener) { this.rightClickListener = listener; }
    void setEmptySpaceClickListener(NBodyCanvas.EmptySpaceClickListener listener) { this.emptySpaceClickListener = listener; }

    void setDragEditingEnabled(boolean dragEditingEnabled) { this.dragEditingEnabled = dragEditingEnabled; }

    private void wireInteraction() {
        canvas.setOnMouseMoved(e -> {
            hoveredBody = hitTestBody(e.getX(), e.getY());
            canvas.setCursor(hoveredBody >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });

        canvas.setOnMouseExited(e -> {
            hoveredBody = -1;
            canvas.setCursor(Cursor.DEFAULT);
        });

        canvas.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                int hit = hitTestBody(e.getX(), e.getY());
                if (hit >= 0) {
                    if (selectionListener != null) selectionListener.onBodySelected(hit);
                    if (rightClickListener != null) rightClickListener.onRightClick(hit);
                }
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) return;

            int hit = hitTestBody(e.getX(), e.getY());
            if (hit >= 0) {
                if (selectionListener != null) selectionListener.onBodySelected(hit);
                if (e.getClickCount() == 2) {
                    if (doubleClickListener != null) doubleClickListener.onDoubleClick(hit);
                    return;
                }
                if (!dragEditingEnabled || dragListener == null) return;
                // Round 1.4: the body FollowMode.SELECTED_BODY currently
                // has the camera locked onto can't be grabbed — see
                // NBodyCanvas#isFollowLocked's own javadoc for why.
                // Selection (above) and double-click/right-click are
                // unaffected; only starting a NEW drag is refused.
                if (canvas.isFollowLocked(hit)) return;
                if (dragListener.onGrab(hit)) {
                    draggedBody = hit;
                    dragSamples.clear();
                    recordDragSample(e.getX(), e.getY());
                }
                return;
            }

            // Empty space: start a pan. A release with little enough total
            // movement is reinterpreted as a click (see onMouseReleased).
            panning = true;
            totalPanDragPx = 0;
            lastPanScreenX = e.getX();
            lastPanScreenY = e.getY();
        });

        canvas.setOnMouseDragged(e -> {
            if (panning) {
                double dx = e.getX() - lastPanScreenX, dy = e.getY() - lastPanScreenY;
                camera.pan(dx, dy);
                totalPanDragPx += Math.hypot(dx, dy);
                lastPanScreenX = e.getX();
                lastPanScreenY = e.getY();
                return;
            }
            if (draggedBody < 0) return;
            double wx = screenToWorldX(e.getX()), wy = screenToWorldY(e.getY());
            recordDragSample(e.getX(), e.getY());
            dragListener.onDrag(draggedBody, wx, wy);
        });

        canvas.setOnMouseReleased(e -> {
            if (panning) {
                panning = false;
                if (totalPanDragPx < EMPTY_CLICK_MAX_DRAG_PX && emptySpaceClickListener != null) {
                    emptySpaceClickListener.onEmptySpaceClick(screenToWorldX(e.getX()), screenToWorldY(e.getY()));
                }
                return;
            }
            if (draggedBody < 0) return;
            double wx = screenToWorldX(e.getX()), wy = screenToWorldY(e.getY());
            double[] velocity = estimateVelocity();
            dragListener.onRelease(draggedBody, wx, wy, velocity[0], velocity[1]);
            draggedBody = -1;
            dragSamples.clear();
            hoveredBody = hitTestBody(e.getX(), e.getY());
            canvas.setCursor(hoveredBody >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });
    }

    private double screenToWorldX(double screenX) { return camera.screenToWorldX(screenX, canvas.getWidth()); }
    private double screenToWorldY(double screenY) { return camera.screenToWorldY(screenY, canvas.getHeight()); }

    /**
     * Returns the index of the body under the given screen point, or −1 for
     * empty space. Iterates backwards (later-indexed bodies are drawn on
     * top, matching {@link NBodyRenderer#draw}'s own iteration order). Uses
     * squared distance to avoid a square root per body per event.
     */
    private int hitTestBody(double screenX, double screenY) {
        var state = canvas.lastState();
        if (state == null) return -1;
        double scale = camera.getScale();
        double originX = camera.originX(canvas.getWidth());
        double originY = camera.originY(canvas.getHeight());
        for (int i = state.getN() - 1; i >= 0; i--) {
            double bx = originX + state.positionX[i] * scale;
            double by = originY - state.positionY[i] * scale;
            double dx = screenX - bx, dy = screenY - by;
            double hitRadius = renderer.radiusForBody(state, i) + HIT_RADIUS_PAD;
            if (dx * dx + dy * dy <= hitRadius * hitRadius) return i;
        }
        return -1;
    }

    /** Records a SCREEN-space sample ({@code screenX}/{@code screenY} in pixels, not world coordinates) — see {@link #FLING_WORLD_MPS_PER_SCREEN_PXPS}'s javadoc for why. */
    private void recordDragSample(double screenX, double screenY) {
        long now = System.nanoTime();
        dragSamples.addLast(new double[]{now, screenX, screenY});
        while (!dragSamples.isEmpty() && now - dragSamples.peekFirst()[0] > FLING_WINDOW_NANOS) {
            dragSamples.removeFirst();
        }
    }

    /**
     * {vx, vy} in world m/s, estimated from the last ~150ms of drag samples
     * — the "fling" this canvas's release hands to {@link
     * NBodyCanvas.DragListener#onRelease}. Computed from the pointer's
     * SCREEN-space speed converted through a fixed calibration, then capped
     * — see {@link #FLING_WORLD_MPS_PER_SCREEN_PXPS} and {@link
     * #FLING_MAX_WORLD_MPS}'s javadoc for why, in detail.
     */
    private double[] estimateVelocity() {
        if (dragSamples.size() < 2) return new double[]{0.0, 0.0};
        double[] first = dragSamples.peekFirst();
        double[] last = dragSamples.peekLast();
        double dt = (last[0] - first[0]) / 1_000_000_000.0;
        if (dt <= 1.0e-4) return new double[]{0.0, 0.0};

        double screenVx = (last[1] - first[1]) / dt;
        double screenVy = (last[2] - first[2]) / dt;
        // Y flips sign here exactly like screenToWorldY does for position —
        // moving the pointer DOWN on screen (increasing screenY) is a
        // decrease in world Y, since screen Y grows downward and world Y
        // grows upward.
        double worldVx = screenVx * FLING_WORLD_MPS_PER_SCREEN_PXPS;
        double worldVy = -screenVy * FLING_WORLD_MPS_PER_SCREEN_PXPS;

        double speed = Math.hypot(worldVx, worldVy);
        if (speed > FLING_MAX_WORLD_MPS) {
            double k = FLING_MAX_WORLD_MPS / speed;
            worldVx *= k;
            worldVy *= k;
        }
        return new double[]{worldVx, worldVy};
    }
}
