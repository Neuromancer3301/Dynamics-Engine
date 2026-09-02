package ui.nbody;

import physics.nbody.NBodyState;
import ui.simcore.SimCanvas;
import javafx.scene.canvas.GraphicsContext;

/**
 * JavaFX Canvas that renders the n-body scene and owns direct-manipulation
 * (grab/drag/fling) of its bodies. The n-body analogue of {@code
 * ui.pendulum.PendulumCanvas} — a thin composition shell over {@code
 * ui.simcore.SimCanvas} (background/waiting-message/scale-bar/pan-zoom
 * camera), {@link NBodyInteraction} (hit-testing, drag/fling, pan), and
 * {@link NBodyRenderer} (everything actually drawn). See the n-body
 * implementation spec §6.1.
 *
 * <p>Interaction: register a {@link DragListener} via {@link
 * #setDragListener} to be notified as the user grabs, drags, and releases a
 * body — unlike the pendulum's angle-based drag, a body's drag position is
 * a plain world (x, y) point, since bodies have no parent joint to measure
 * an angle from. Hovering (or dragging) a body shows a live inspector
 * overlay — see {@link NBodyRenderer}.
 */
public final class NBodyCanvas extends SimCanvas {

    /** Notified as the user grabs, drags, and releases a body. See the class javadoc. */
    public interface DragListener {
        /** A body was pressed. Return {@code true} to begin dragging it, {@code false} to ignore the press. */
        boolean onGrab(int bodyIndex);

        /** The pointer moved while {@code bodyIndex} was grabbed; {@code worldX}/{@code worldY} is its new world-space position. */
        void onDrag(int bodyIndex, double worldX, double worldY);

        /** The pointer was released; {@code vx}/{@code vy} (m/s) is estimated from the motion just before release — the "fling." */
        void onRelease(int bodyIndex, double worldX, double worldY, double vx, double vy);
    }

    /** Notified when a body becomes selected — a completed click or click-drag-release. Selection outlives the gesture; see {@link #setSelectedBody}. */
    public interface SelectionListener {
        void onBodySelected(int bodyIndex);
    }

    /** Notified when a body is double-clicked (not part of a drag) — opens the per-body parameter dialog (or, if the Add tool is active, the same dialog — see the n-body implementation spec §6.2). */
    public interface DoubleClickListener {
        void onDoubleClick(int bodyIndex);
    }

    /** Notified on a right-click on a body — opens the delete-confirmation dialog directly, in either tool (§6.2's first simplification versus the pendulum). */
    public interface RightClickListener {
        void onRightClick(int bodyIndex);
    }

    /** Notified on a clean click (not a drag-to-pan) on empty space — the controller opens the Add dialog here only when the Add tool is active (§6.2's second simplification). */
    public interface EmptySpaceClickListener {
        void onEmptySpaceClick(double worldX, double worldY);
    }

    private final NBodyRenderer renderer;
    private final NBodyInteraction interaction;

    private NBodyState lastState;

    // Set externally (by controller.NBodySimulationController), read by
    // rendering — outlives any single gesture, same reasoning as
    // PendulumCanvas#selectedLink.
    private int selectedBody = -1;

    // §7: a pure view concern, lives on the canvas rather than the engine —
    // toggled by the sidebar's Display tab.
    private boolean followCenterOfMass = false;

    public NBodyCanvas(double width, double height) {
        super(width, height);
        this.renderer = new NBodyRenderer(camera);
        this.interaction = new NBodyInteraction(this, camera, renderer);
    }

    @Override
    protected double contentExtent() {
        if (lastState == null) return 1.0;
        double originX = 0, originY = 0;
        if (followCenterOfMass) {
            double[] com = centerOfMass(lastState);
            originX = com[0];
            originY = com[1];
        }
        double max = 0.1; // matches Camera.fitToContent's own floor, avoiding a degenerate fit for a single body at the origin
        for (int i = 0; i < lastState.getN(); i++) {
            double dx = lastState.positionX[i] - originX;
            double dy = lastState.positionY[i] - originY;
            max = Math.max(max, Math.hypot(dx, dy));
        }
        return max;
    }

    @Override
    protected boolean hasContent() { return lastState != null; }

    @Override
    protected void drawContent(GraphicsContext gc, double w, double h) {
        // §7: recomputed fresh every frame, before the renderer projects
        // anything world->screen — cheap (O(N)) at this scale, and the
        // camera itself is what actually keeps the followed point centered;
        // this canvas doesn't need to remember the COM between frames.
        if (followCenterOfMass) {
            double[] com = centerOfMass(lastState);
            camera.setFollowPoint(com[0], com[1]);
        } else {
            camera.clearFollowPoint();
        }
        renderer.draw(gc, lastState, w, h, interaction.hoveredBody(), selectedBody);
    }

    /** Full render call — invoked every frame from the controller's AnimationTimer. */
    public void render(NBodyState state) {
        this.lastState = state;
        renderFrame();
    }

    /**
     * Toggles whether the camera follows the scene's center of mass rather
     * than the fixed world origin (see {@code ui.simcore.Camera}'s
     * follow-point support). Does not itself re-fit the camera — toggling
     * on/off starts following from wherever the camera currently sits, so
     * the transition is smooth rather than a snap (n-body implementation
     * spec §7).
     */
    public void setFollowCenterOfMass(boolean on) { this.followCenterOfMass = on; }

    /** Whether the camera is currently following the center of mass. */
    public boolean isFollowingCenterOfMass() { return followCenterOfMass; }

    /** Registers the listener notified of grab/drag/release. {@code null} disables interaction. */
    public void setDragListener(DragListener listener) { interaction.setDragListener(listener); }

    /** Registers the listener notified when a body becomes selected. {@code null} disables that notification (selection can still be set programmatically). */
    public void setSelectionListener(SelectionListener listener) { interaction.setSelectionListener(listener); }

    /** Registers the listener notified on a double-click. {@code null} disables that interaction. */
    public void setDoubleClickListener(DoubleClickListener listener) { interaction.setDoubleClickListener(listener); }

    /** Registers the listener notified on a right-click on a body. {@code null} disables that notification. */
    public void setRightClickListener(RightClickListener listener) { interaction.setRightClickListener(listener); }

    /** Registers the listener notified on a clean click on empty space. {@code null} disables that notification. */
    public void setEmptySpaceClickListener(EmptySpaceClickListener listener) { interaction.setEmptySpaceClickListener(listener); }

    /**
     * Sets the currently-selected body, drawn with a pulsing halo and shown
     * in the top-right HUD. {@code -1} clears the selection. Single source
     * of truth for selection state, same as {@code
     * PendulumCanvas#setSelectedLink} — the controller owns pause/resume
     * policy and calls this to reflect it visually.
     */
    public void setSelectedBody(int body) { this.selectedBody = body; }

    public int getSelectedBody() { return selectedBody; }

    /** Swaps the per-body color palette for a colour-blind-safe one. */
    public void setColorBlindSafe(boolean colorBlindSafe) { renderer.setColorBlindSafe(colorBlindSafe); }

    /** Disables the selection halo's pulse animation (a global accessibility preference, read once at screen construction — see {@code theme.ThemeManager#isReducedMotion}). */
    public void setReducedMotion(boolean reducedMotion) { renderer.setReducedMotion(reducedMotion); }

    /**
     * Mass-weighted average position — the scene's center of mass. Computed
     * fresh on demand rather than stored on {@code NBodyState} itself: it's
     * O(N) and cheap at this scale, and has no physics meaning of its own —
     * purely a render-side convenience (n-body implementation spec §7).
     */
    private static double[] centerOfMass(NBodyState state) {
        double totalMass = 0, cx = 0, cy = 0;
        for (int i = 0; i < state.getN(); i++) {
            totalMass += state.mass[i];
            cx += state.mass[i] * state.positionX[i];
            cy += state.mass[i] * state.positionY[i];
        }
        return totalMass > 0 ? new double[]{cx / totalMass, cy / totalMass} : new double[]{0, 0};
    }

    /** The most recently rendered state, or {@code null} before the first frame — package-private, read by {@link NBodyInteraction}. */
    NBodyState lastState() { return lastState; }
}
