package ui.pendulum;

import ui.simcore.Camera;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Owns direct-manipulation of a {@link PendulumCanvas}'s bobs and gravity
 * handle: hit-testing, the screen&harr;world angle math, drag/fling, the
 * right-click length-drag, and (new this round) drag-to-pan on empty space.
 *
 * <p>Composed into {@code PendulumCanvas}, not inherited — see round 1 §4 of
 * the UI restructuring plan. The six listener interfaces this class fires
 * ({@link PendulumCanvas.DragListener} etc.) stay declared on {@code
 * PendulumCanvas} itself, not here: they're part of that class's public API
 * (existing callers construct anonymous implementations as {@code
 * PendulumCanvas.DragListener}), even though the wiring/hit-testing logic
 * that fires them now lives in this package-private class instead.
 *
 * <p><b>Why panning is wired here, not in {@code SimCanvas}:</b> a {@code
 * Canvas} can only usefully have one owner of {@code setOnMousePressed
 * /Dragged/Released} — {@code SimCanvas}'s constructor runs before this
 * class exists, so a base-class pan handler would risk stealing presses
 * meant for a bob grab, or fighting over handler order. Scroll-to-zoom is
 * safe in {@code SimCanvas} (nothing else uses scroll); pan-on-empty-space
 * requires the same hit-testing this class already owns, so it's wired
 * here, entirely against the shared {@link Camera} API — which is what
 * actually makes panning reusable by a future simulation's own interaction
 * collaborator, without {@code SimCanvas} knowing anything about
 * hit-testing.
 */
final class PendulumInteraction {

    // How far back to look when estimating angular velocity for a "fling"
    // on release.
    private static final long FLING_WINDOW_NANOS = 150_000_000L;

    // A bob is "hit" within its visible radius plus a few px of forgiveness.
    private static final double HIT_RADIUS_PAD = 6.0;

    private static final double GRAVITY_HANDLE_HIT_RADIUS = 10;

    // ---- Snap-to-unit (§6.3) ----
    private static final double ANGLE_SNAP_INCREMENT  = Math.PI / 12.0; // 15 degrees
    private static final double LENGTH_SNAP_INCREMENT = 0.25;           // meters
    private boolean snapEnabled = false;

    // ---- Right-click ("length") drag (§7.3) ----
    private static final double MIN_DRAG_LENGTH = 0.05;

    private final PendulumCanvas canvas;
    private final Camera camera;

    private PendulumCanvas.DragListener dragListener;
    private PendulumCanvas.GravityListener gravityListener;
    private PendulumCanvas.SelectionListener selectionListener;
    private PendulumCanvas.LengthDragListener lengthDragListener;
    private PendulumCanvas.DoubleClickListener doubleClickListener;
    private PendulumCanvas.RightClickListener rightClickListener;

    private int draggedLink = -1;
    private int hoveredLink = -1; // -1 when the pointer isn't over a bob

    private final Deque<double[]> dragSamples = new ArrayDeque<>(); // {nanos, angle}, most recent FLING_WINDOW_NANOS only

    private double gravityAngle = 0.0;
    private boolean draggingGravity = false;

    private int lengthDragLink = -1;
    private int previewLink = -1;
    private double[] previewScreenX;
    private double[] previewScreenY;

    // Round 3 §4-e: while the Add tool is active, left/right-drag on a bob
    // are intentionally no-ops.
    private boolean dragEditingEnabled = true;

    // ---- Right-click delete (round 4 §2) ----
    private int pendingRightClickLink = -1;

    // ---- Pan-on-empty-space (new this round) ----
    private boolean panning = false;
    private double lastPanX, lastPanY;

    PendulumInteraction(PendulumCanvas canvas, Camera camera) {
        this.canvas = canvas;
        this.camera = camera;
        wireInteraction();
    }

    // ---- Accessors read by PendulumCanvas.drawContent() to feed the renderer ----
    int inspectedLink() { return draggedLink >= 0 ? draggedLink : hoveredLink; }
    int previewLink() { return previewLink; }
    double[] previewScreenX() { return previewScreenX; }
    double[] previewScreenY() { return previewScreenY; }
    double gravityAngle() { return gravityAngle; }
    boolean isDraggingGravity() { return draggingGravity; }

    void setGravityAngleVisual(double angle) { this.gravityAngle = angle; }
    void clearLengthPreview() { this.previewLink = -1; }

    void setDragListener(PendulumCanvas.DragListener listener) { this.dragListener = listener; }
    void setGravityListener(PendulumCanvas.GravityListener listener) { this.gravityListener = listener; }
    void setSelectionListener(PendulumCanvas.SelectionListener listener) { this.selectionListener = listener; }
    void setLengthDragListener(PendulumCanvas.LengthDragListener listener) { this.lengthDragListener = listener; }
    void setDoubleClickListener(PendulumCanvas.DoubleClickListener listener) { this.doubleClickListener = listener; }
    void setRightClickListener(PendulumCanvas.RightClickListener listener) { this.rightClickListener = listener; }

    void setDragEditingEnabled(boolean dragEditingEnabled) { this.dragEditingEnabled = dragEditingEnabled; }
    void setSnapEnabled(boolean snapEnabled) { this.snapEnabled = snapEnabled; }

    // -------------------------------------------------------------------------
    // Interaction — hit-testing, coordinate transform, drag/fling, pan
    // -------------------------------------------------------------------------

    private void wireInteraction() {
        canvas.setOnMouseMoved(e -> {
            if (dragListener == null) return;
            hoveredLink = hitTestBob(e.getX(), e.getY());
            boolean overGravityHandle = hoveredLink < 0 && hitTestGravityHandle(e.getX(), e.getY());
            canvas.setCursor((hoveredLink >= 0 || overGravityHandle) ? Cursor.HAND : Cursor.DEFAULT);
        });

        canvas.setOnMouseExited(e -> {
            hoveredLink = -1;
            canvas.setCursor(Cursor.DEFAULT);
        });

        canvas.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                int hit = hitTestBob(e.getX(), e.getY());
                if (hit >= 0) {
                    if (selectionListener != null) selectionListener.onLinkSelected(hit);
                    if (dragEditingEnabled && lengthDragListener != null) {
                        lengthDragLink = hit;
                    } else if (!dragEditingEnabled) {
                        pendingRightClickLink = hit;
                    }
                }
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) return;

            int hit = hitTestBob(e.getX(), e.getY());
            if (hit >= 0) {
                if (selectionListener != null) selectionListener.onLinkSelected(hit);
                if (e.getClickCount() == 2) {
                    if (doubleClickListener != null) doubleClickListener.onDoubleClick(hit);
                    return;
                }
                if (!dragEditingEnabled || dragListener == null) return;
                if (dragListener.onGrab(hit)) {
                    draggedLink = hit;
                    dragSamples.clear();
                    recordDragSample(angleFromScreen(hit, e.getX(), e.getY()));
                }
                return;
            }
            if (hitTestGravityHandle(e.getX(), e.getY())) {
                if (gravityListener != null) draggingGravity = true;
                return; // over the handle — not empty space, even without a listener wired
            }

            // Both bob and gravity-handle hit-tests missed: empty space, so
            // start a pan instead.
            panning = true;
            lastPanX = e.getX();
            lastPanY = e.getY();
        });

        canvas.setOnMouseDragged(e -> {
            if (panning) {
                camera.pan(e.getX() - lastPanX, e.getY() - lastPanY);
                lastPanX = e.getX();
                lastPanY = e.getY();
                return;
            }
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

        canvas.setOnMouseReleased(e -> {
            if (panning) {
                panning = false;
                return;
            }
            if (draggingGravity) {
                draggingGravity = false;
                return;
            }
            if (pendingRightClickLink >= 0) {
                if (rightClickListener != null) rightClickListener.onRightClick(pendingRightClickLink);
                pendingRightClickLink = -1;
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
            draggedLink = -1;
            dragSamples.clear();
            hoveredLink = hitTestBob(e.getX(), e.getY());
            canvas.setCursor(hoveredLink >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });
    }

    private double pivotX() { return camera.originX(canvas.getWidth()); }
    private double pivotY() { return camera.originY(canvas.getHeight()); }
    private double scale() { return camera.getScale(); }

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

    /** World-unit distance from {@code link}'s parent joint to a screen point. */
    private double computeDraggedLength(int link, double screenX, double screenY) {
        var lastState = canvas.lastState();
        double scale = scale(), pivotX = pivotX(), pivotY = pivotY();
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
     * {@code editedLink}, without touching the live state: the edited bob
     * moves to its new distance along its unchanged angle, and every
     * downstream bob shifts by that same screen-space delta.
     */
    private void computeLengthPreview(int editedLink, double newLength) {
        var lastState = canvas.lastState();
        if (lastState == null) return;
        int n = lastState.getN();
        if (editedLink < 0 || editedLink >= n) return;

        double scale = scale(), pivotX = pivotX(), pivotY = pivotY();
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

    private boolean hitTestGravityHandle(double screenX, double screenY) {
        double dx = screenX - PendulumChainRenderer.gravityHandleX(pivotX(), gravityAngle);
        double dy = screenY - PendulumChainRenderer.gravityHandleY(pivotY(), gravityAngle);
        double r = GRAVITY_HANDLE_HIT_RADIUS;
        return dx * dx + dy * dy <= r * r;
    }

    private double gravityAngleFromScreen(double screenX, double screenY) {
        return Math.atan2(screenX - pivotX(), screenY - pivotY());
    }

    /**
     * Returns the index of the bob under the given screen point, or −1 for
     * empty space. Iterates backwards from the tip (later bobs are drawn on
     * top). Uses squared distance to avoid a square root per bob per event.
     */
    private int hitTestBob(double screenX, double screenY) {
        var lastState = canvas.lastState();
        if (lastState == null) return -1;
        double scale = scale(), pivotX = pivotX(), pivotY = pivotY();
        for (int i = lastState.getN() - 1; i >= 0; i--) {
            double bx = pivotX + lastState.bobX[i] * scale;
            double by = pivotY - lastState.bobY[i] * scale;
            double dx = screenX - bx, dy = screenY - by;
            double hitRadius = canvas.renderer().radiusForBob(lastState, i) + HIT_RADIUS_PAD;
            if (dx * dx + dy * dy <= hitRadius * hitRadius) return i;
        }
        return -1;
    }

    /**
     * The inverse coordinate transform: given a screen position, works out
     * what angle would put a bob there. See {@code
     * physics.PhysicsEngine#getState()} for the forward mapping this
     * inverts.
     */
    private double angleFromScreen(int linkIndex, double screenX, double screenY) {
        var lastState = canvas.lastState();
        double scale = scale(), pivotX = pivotX(), pivotY = pivotY();
        double parentX, parentY;
        if (linkIndex <= 0) {
            parentX = pivotX;
            parentY = pivotY;
        } else {
            parentX = pivotX + lastState.bobX[linkIndex - 1] * scale;
            parentY = pivotY - lastState.bobY[linkIndex - 1] * scale;
        }
        double worldDX =  (screenX - parentX) / scale;
        double worldDY = -(screenY - parentY) / scale;
        return Math.atan2(worldDX, -worldDY);
    }

    private void recordDragSample(double angle) {
        long now = System.nanoTime();
        dragSamples.addLast(new double[]{now, angle});
        while (!dragSamples.isEmpty() && now - dragSamples.peekFirst()[0] > FLING_WINDOW_NANOS) {
            dragSamples.removeFirst();
        }
    }

    private double estimateAngularVelocity() {
        if (dragSamples.size() < 2) return 0.0;
        double[] first = dragSamples.peekFirst();
        double[] last  = dragSamples.peekLast();
        double dt = (last[0] - first[0]) / 1_000_000_000.0;
        if (dt <= 1.0e-4) return 0.0;
        return wrapDelta(last[1] - first[1]) / dt;
    }

    private static double wrapDelta(double delta) {
        while (delta >  Math.PI) delta -= 2 * Math.PI;
        while (delta < -Math.PI) delta += 2 * Math.PI;
        return delta;
    }
}
