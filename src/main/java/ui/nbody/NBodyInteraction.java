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
                if (dragListener.onGrab(hit)) {
                    draggedBody = hit;
                    dragSamples.clear();
                    recordDragSample(screenToWorldX(e.getX()), screenToWorldY(e.getY()));
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
            recordDragSample(wx, wy);
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

    private void recordDragSample(double worldX, double worldY) {
        long now = System.nanoTime();
        dragSamples.addLast(new double[]{now, worldX, worldY});
        while (!dragSamples.isEmpty() && now - dragSamples.peekFirst()[0] > FLING_WINDOW_NANOS) {
            dragSamples.removeFirst();
        }
    }

    /** {vx, vy} estimated from the last ~150ms of drag samples — the "fling" this canvas's release hands to {@link NBodyCanvas.DragListener#onRelease}. */
    private double[] estimateVelocity() {
        if (dragSamples.size() < 2) return new double[]{0.0, 0.0};
        double[] first = dragSamples.peekFirst();
        double[] last = dragSamples.peekLast();
        double dt = (last[0] - first[0]) / 1_000_000_000.0;
        if (dt <= 1.0e-4) return new double[]{0.0, 0.0};
        return new double[]{(last[1] - first[1]) / dt, (last[2] - first[2]) / dt};
    }
}
