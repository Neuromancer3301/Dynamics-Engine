package ui.nbody;

import physics.nbody.NBodyState;
import theme.ThemeManager;
import ui.simcore.Camera;
import ui.simcore.SimCanvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

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

    private static final int STAR_COUNT = 250;
    private static final double[] STAR_X = new double[STAR_COUNT];
    private static final double[] STAR_Y = new double[STAR_COUNT];
    private static final double[] STAR_RADIUS = new double[STAR_COUNT];
    private static final double[] STAR_ALPHA = new double[STAR_COUNT];
    private static final double[] STAR_PARALLAX = new double[STAR_COUNT];

    static {
        java.util.Random rng = new java.util.Random(0x4E424F4459L); // deterministic seed
        for (int i = 0; i < STAR_COUNT; i++) {
            STAR_X[i] = rng.nextDouble();
            STAR_Y[i] = rng.nextDouble();
            STAR_RADIUS[i] = 0.75 + rng.nextDouble() * 0.75; // 0.75 - 1.5 px
            STAR_ALPHA[i] = 0.20 + rng.nextDouble() * 0.65;  // 0.20 - 0.85
            STAR_PARALLAX[i] = 0.02 + rng.nextDouble() * 0.06; // subtle parallax
        }
    }

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

    /**
     * Round 1.4: what the camera's follow-point tracks, replacing the old
     * plain on/off "follow center of mass" toggle now that there's a second
     * thing worth following. A dropdown in the Display tab picks one; see
     * {@link #setFollowMode}.
     */
    public enum FollowMode {
        /** Camera stays exactly where the user last panned/zoomed it — the world origin, unless panned away from it. */
        OFF,
        /** Tracks the scene's mass-weighted average position every frame — useful once bodies have drifted far from the world origin. Does not itself change zoom. */
        CENTER_OF_MASS,
        /**
         * Tracks {@link #selectedBody} every frame and, the moment a NEW
         * body becomes the target, zooms in once to frame it at roughly
         * {@link #FOLLOWED_BODY_TARGET_PIXEL_DIAMETER} screen pixels wide —
         * see {@link #render}'s own javadoc for why that's a one-time
         * snap rather than a continuous per-frame re-lock (the user's own
         * subsequent zoom/pan must still work normally, exactly like
         * {@link #CENTER_OF_MASS} already promises). The followed body
         * cannot be drag-edited while this mode holds it — see {@link
         * #isFollowLocked}.
         */
        SELECTED_BODY
    }

    private final NBodyRenderer renderer;
    private final NBodyInteraction interaction;

    private NBodyState lastState;

    // Set externally (by controller.NBodySimulationController), read by
    // rendering — outlives any single gesture, same reasoning as
    // PendulumCanvas#selectedLink.
    private int selectedBody = -1;

    // Round 1.2: a body pinned from the Bodies tab's single click, shown
    // with a live inspector HUD but deliberately NOT routed through
    // selectedBody — it must not pause the sim, engage the selection halo,
    // or become drag-eligible the way an actual selection does. See
    // #setInfoBody and NBodyRenderer#draw's own javadoc on the "Watching: "
    // HUD this drives.
    private int infoBody = -1;

    // §7: a pure view concern, lives on the canvas rather than the engine —
    // picked by the sidebar's Display tab dropdown.
    private FollowMode followMode = FollowMode.OFF;

    // Round 1.4: which body SELECTED_BODY's one-time zoom-in has already
    // been applied for, so switching modes away and back (or selecting a
    // NEW body while already in SELECTED_BODY mode) re-triggers it rather
    // than silently reusing a stale scale from a previous target. -1 means
    // "not yet applied to anything" — see #render.
    private int lastZoomedFollowBody = -1;

    // How wide, in screen pixels, SELECTED_BODY's dynamic zoom frames
    // the followed body's true diameter (round 2.5.1: dynamic zoom to 22px).
    private static final double FOLLOWED_BODY_TARGET_PIXEL_DIAMETER = 22.0;
    private static final double TRANSITION_DURATION_SECONDS = 1.3;

    // Smooth follow transition animation state (round 2.5)
    private boolean followTransitionActive = false;
    private long transitionStartNanos = -1;
    private double transitionStartCenterX = 0.0;
    private double transitionStartCenterY = 0.0;
    private double transitionStartScale = 1.0;
    private double transitionStartPanX = 0.0;
    private double transitionStartPanY = 0.0;
    private double transitionTargetScale = 1.0;
    private int transitionTargetBody = -1;

    // Round 1.1: the pendulum-tuned default zoom range (50x beyond the
    // fitted view) leaves the Moon fused into Earth even at maximum zoom —
    // the default view fits the whole system out to its outermost body
    // (billions of km), while resolving Earth from its Moon (a few hundred
    // thousand km apart) needs on the order of 10^5-10^6x more zoom than
    // that, not 50x. See Camera#setZoomRange's own javadoc for the math.
    private static final double MIN_ZOOM = 0.025;   // unchanged — no need to zoom out further than the pendulum ever did
    private static final double MAX_ZOOM = 1.0e8;

    public NBodyCanvas(double width, double height) {
        super(width, height);
        this.renderer = new NBodyRenderer(camera);
        this.interaction = new NBodyInteraction(this, camera, renderer);
        camera.setZoomRange(MIN_ZOOM, MAX_ZOOM);
        camera.setOriginFraction(0.5, 0.5);

        // Cancel follow transition if user scrolls
        addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> cancelFollowTransition());
    }

    @Override
    protected double contentExtent() {
        if (lastState == null || lastState.getN() == 0) return 1.0;
        double originX = 0, originY = 0;
        if (followMode == FollowMode.CENTER_OF_MASS) {
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
    protected void drawBackground(GraphicsContext gc, double w, double h) {
        gc.setFill(Color.web("#08080B"));
        gc.fillRect(0, 0, w, h);

        double panOffsetX = camera.originX(w) - w * 0.5;
        double panOffsetY = camera.originY(h) - h * 0.5;

        for (int i = 0; i < STAR_COUNT; i++) {
            double sx = (STAR_X[i] * w + panOffsetX * STAR_PARALLAX[i]) % w;
            if (sx < 0) sx += w;
            double sy = (STAR_Y[i] * h + panOffsetY * STAR_PARALLAX[i]) % h;
            if (sy < 0) sy += h;
            double r = STAR_RADIUS[i];
            gc.setFill(Color.color(1.0, 1.0, 1.0, STAR_ALPHA[i]));
            gc.fillOval(sx - r, sy - r, r * 2, r * 2);
        }

        drawOriginAxes(gc, w, h);
    }

    // Round 1.1: powers of ten from 10km to 10 billion km — the pendulum's
    // own "nice" lengths (0.05m to 500m) top out at a fraction of a single
    // pixel next to this scene's actual scale, so the picker was always
    // just returning its largest entry regardless of zoom (see the n-body
    // implementation spec round 1.1 issue #1 and SimCanvas#scaleBarNiceLengths).
    private static final double[] SCALE_BAR_NICE_LENGTHS = {
            1.0e4, 1.0e5, 1.0e6, 1.0e7, 1.0e8, 1.0e9, 1.0e10, 1.0e11, 1.0e12, 1.0e13
    };

    @Override
    protected double[] scaleBarNiceLengths() { return SCALE_BAR_NICE_LENGTHS; }

    @Override
    protected String formatScaleBarLabel(double referenceLength) {
        return String.format("%.0e m", referenceLength);
    }

    @Override
    protected boolean hasContent() { return lastState != null; }

    @Override
    protected void drawContent(GraphicsContext gc, double w, double h) {
        renderer.draw(gc, lastState, w, h, interaction.hoveredBody(), selectedBody, infoBody);
    }

    /**
     * Full render call — invoked every frame from the controller's
     * AnimationTimer. The follow-point is updated HERE, before {@link
     * #renderFrame()} runs, not inside {@link #drawContent} — {@code
     * SimCanvas#renderFrame} draws the background (including the
     * world-origin axes, which read {@code camera.originX}/{@code originY}
     * directly) before ever calling {@code drawContent}, so setting the
     * follow-point only once {@code drawContent} runs would draw those
     * axes one frame stale relative to where the bodies themselves get
     * projected. Updating it up front means every draw this frame —
     * background included — sees the same, current follow-point.
     */
    public void render(NBodyState state) {
        this.lastState = state;
        // §7 / round 1.4: recomputed fresh every frame — cheap (O(N)) at
        // this scale, and the camera itself is what actually keeps the
        // followed point centered; this canvas doesn't need to remember
        // the COM (or the followed body's position) between frames.
        if (lastState != null) {
            if (followMode != FollowMode.SELECTED_BODY) {
                lastZoomedFollowBody = -1;
                followTransitionActive = false;
            }

            if (followTransitionActive) {
                if (transitionTargetBody >= 0 && transitionTargetBody < lastState.getN() && selectedBody == transitionTargetBody) {
                    long now = System.nanoTime();
                    double elapsed = (now - transitionStartNanos) / 1.0e9;
                    boolean reduced = ThemeManager.getInstance().isReducedMotion();
                    double u = (reduced || TRANSITION_DURATION_SECONDS <= 0) ? 1.0 : Math.min(1.0, elapsed / TRANSITION_DURATION_SECONDS);

                    // Smooth cubic smoothstep easing: zero velocity at start and end
                    double alpha = u * u * (3.0 - 2.0 * u);

                    // Dynamically interpolate world center towards live moving body position
                    double targetX = lastState.positionX[transitionTargetBody];
                    double targetY = lastState.positionY[transitionTargetBody];
                    double curCenterX = (1.0 - alpha) * transitionStartCenterX + alpha * targetX;
                    double curCenterY = (1.0 - alpha) * transitionStartCenterY + alpha * targetY;

                    // Logarithmic scale interpolation: smoothly zooms in or zooms out
                    double lnStart = Math.log(Math.max(transitionStartScale, 1.0e-30));
                    double lnTarget = Math.log(Math.max(transitionTargetScale, 1.0e-30));
                    double curScale = Math.exp((1.0 - alpha) * lnStart + alpha * lnTarget);

                    // Pan offset smoothly decays to 0 so target is centered at (0.5 * W, 0.5 * H)
                    double curPanX = (1.0 - alpha) * transitionStartPanX;
                    double curPanY = (1.0 - alpha) * transitionStartPanY;

                    camera.setPan(curPanX, curPanY);
                    camera.setFollowPoint(curCenterX, curCenterY);
                    camera.setScale(curScale);

                    if (u >= 1.0) {
                        camera.setPan(0.0, 0.0);
                        camera.setFollowPoint(targetX, targetY);
                        camera.setScale(transitionTargetScale);
                        followTransitionActive = false;
                        lastZoomedFollowBody = transitionTargetBody;
                    }
                } else {
                    followTransitionActive = false;
                }
            } else {
                switch (followMode) {
                    case OFF -> camera.clearFollowPoint();
                    case CENTER_OF_MASS -> {
                        if (lastState.getN() > 0) {
                            double[] com = centerOfMass(lastState);
                            camera.setFollowPoint(com[0], com[1]);
                        } else {
                            camera.clearFollowPoint();
                        }
                    }
                    case SELECTED_BODY -> {
                        if (selectedBody >= 0 && selectedBody < lastState.getN()) {
                            camera.setFollowPoint(lastState.positionX[selectedBody], lastState.positionY[selectedBody]);
                            if (selectedBody != lastZoomedFollowBody) {
                                startFollowTransition(selectedBody);
                            }
                        } else {
                            camera.clearFollowPoint();
                        }
                    }
                }
            }
        }
        renderFrame();
    }

    /**
     * Starts a smooth 1-2s transition animation centering and dynamically zooming
     * to the selected body (apparent diameter ~22px). Zooms in if smaller, zooms out
     * if larger, and handles offscreen bodies smoothly.
     */
    public void startFollowTransition(int bodyIndex) {
        if (lastState == null || bodyIndex < 0 || bodyIndex >= lastState.getN()) return;

        double w = getWidth() > 0 ? getWidth() : 500;
        double h = getHeight() > 0 ? getHeight() : 580;

        // Current world position at viewport center
        transitionStartCenterX = camera.screenToWorldX(w * 0.5, w);
        transitionStartCenterY = camera.screenToWorldY(h * 0.5, h);
        transitionStartScale = camera.getScale();
        transitionStartPanX = camera.getPanX();
        transitionStartPanY = camera.getPanY();

        double radius = Math.max(lastState.radius[bodyIndex], 1.0e-6);
        transitionTargetScale = FOLLOWED_BODY_TARGET_PIXEL_DIAMETER / (2.0 * radius);
        double minScale = camera.getBaseScale() * MIN_ZOOM;
        double maxScale = camera.getBaseScale() * MAX_ZOOM;
        transitionTargetScale = Math.max(minScale, Math.min(maxScale, transitionTargetScale));

        transitionTargetBody = bodyIndex;
        transitionStartNanos = System.nanoTime();
        followTransitionActive = true;

        if (ThemeManager.getInstance().isReducedMotion()) {
            finishFollowTransition();
        }
    }

    /** Instantly completes the follow transition without waiting for duration. */
    public void finishFollowTransition() {
        if (followTransitionActive && lastState != null && transitionTargetBody >= 0 && transitionTargetBody < lastState.getN()) {
            camera.setPan(0.0, 0.0);
            camera.setFollowPoint(lastState.positionX[transitionTargetBody], lastState.positionY[transitionTargetBody]);
            camera.setScale(transitionTargetScale);
            followTransitionActive = false;
            lastZoomedFollowBody = transitionTargetBody;
        }
    }

    /** Cancels the follow transition (e.g. if the user manually pans or scrolls). */
    public void cancelFollowTransition() {
        followTransitionActive = false;
    }

    public boolean isFollowTransitionActive() {
        return followTransitionActive;
    }

    @Override
    protected void onViewportResized(double oldWidth, double oldHeight, double newWidth, double newHeight) {
        if (followMode == FollowMode.SELECTED_BODY) {
            // Keep followed body centered in viewport
            camera.rescaleForViewport(oldWidth, oldHeight, newWidth, newHeight);
        } else if (camera.getZoom() == 1.0 && camera.getPanX() == 0.0 && camera.getPanY() == 0.0) {
            // In overview mode: re-fit so content stays framed in the smaller area (no offscreen shifting)
            fitToContent();
        } else {
            camera.rescaleForViewport(oldWidth, oldHeight, newWidth, newHeight);
        }
    }

    /**
     * Sets what the camera follows — see {@link FollowMode}.
     */
    public void setFollowMode(FollowMode mode) {
        this.followMode = mode;
        if (mode == FollowMode.SELECTED_BODY) {
            if (selectedBody >= 0 && lastState != null && selectedBody < lastState.getN()) {
                startFollowTransition(selectedBody);
            }
        } else {
            cancelFollowTransition();
            lastZoomedFollowBody = -1;
        }
    }

    /** What the camera currently follows. */
    public FollowMode getFollowMode() { return followMode; }

    /** Returns the underlying camera controlling pan and zoom. */
    public Camera getCamera() { return camera; }

    /**
     * Round 1.4: whether {@code bodyIndex} is the body {@link
     * FollowMode#SELECTED_BODY} currently has locked the camera onto — if
     * so, {@link NBodyInteraction} refuses to start a new drag on it. A
     * live, camera-following, physics-driven body being simultaneously
     * grabbable as a drag handle is confusing at best (which position wins,
     * the physics or the pointer?) and directly undercuts the reason its
     * selection was allowed to survive a resume in the first place — see
     * {@code controller.NBodySimulationController#setPaused}'s own note on
     * that carve-out. Double-click (open the parameter dialog) and
     * right-click (delete) are untouched — both are deliberate, explicit
     * actions with their own established safety behavior, not the
     * "accidentally dragged it" case this specifically guards against.
     */
    public boolean isFollowLocked(int bodyIndex) {
        return followMode == FollowMode.SELECTED_BODY && bodyIndex == selectedBody && bodyIndex >= 0;
    }

    /** Registers the listener notified of grab/drag/release. {@code null} disables interaction. */
    public void setDragListener(DragListener listener) { interaction.setDragListener(listener); }

    /** Enables or disables drag-to-reposition of bodies, independent of selection — gated by the Add tool (round 1.1; see {@code NBodyActionRailBuilder}). On by default. */
    public void setDragEditingEnabled(boolean dragEditingEnabled) { interaction.setDragEditingEnabled(dragEditingEnabled); }

    /** Cancels any active drag or pan gesture, clearing drag history. */
    public void cancelDrag() { interaction.cancelDrag(); }

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
    public void setSelectedBody(int body) {
        int old = this.selectedBody;
        this.selectedBody = body;
        if (followMode == FollowMode.SELECTED_BODY) {
            if (body >= 0 && lastState != null && body < lastState.getN() && body != old) {
                startFollowTransition(body);
            }
        } else if (body < 0) {
            cancelFollowTransition();
        }
    }

    public int getSelectedBody() { return selectedBody; }

    /**
     * Round 1.2: pins a body to a live "Watching: " inspector HUD, drawn
     * beside it exactly like hover, WITHOUT pausing the simulation or
     * touching {@link #selectedBody} — its numbers keep updating frame to
     * frame while the sim keeps running, which is the entire point (see the
     * Bodies tab's single-click wiring in {@code
     * controller.NBodySimulationController}). {@code -1} clears the pin.
     */
    public void setInfoBody(int body) { this.infoBody = body; }

    public int getInfoBody() { return infoBody; }

    /** Swaps the per-body color palette for a colour-blind-safe one. */
    public void setColorBlindSafe(boolean colorBlindSafe) { renderer.setColorBlindSafe(colorBlindSafe); }

    private boolean reducedMotion = false;

    /** Disables the selection halo's pulse animation and motion trails (a global accessibility preference, read once at screen construction — see {@code theme.ThemeManager#isReducedMotion}). */
    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        renderer.setReducedMotion(reducedMotion);
    }

    // ---- Round 1.1: motion trails, per body — see ui.nbody.DisplayGroupPanel ----

    /** Number of bodies the trail state is currently sized for. */
    public int trailBodyCount() { return renderer.trailBodyCount(); }

    /** Whether body {@code i} currently leaves a trail. */
    public boolean isTrailEnabled(int i) { return renderer.isTrailEnabled(i); }

    /** Toggles body {@code i}'s trail on or off. */
    public void setTrailEnabled(int i, boolean on) { renderer.setTrailEnabled(i, on); }

    /** Enables or disables every body's trail at once. */
    public void setAllTrailsEnabled(boolean on) { renderer.setAllTrailsEnabled(on); }

    /** Erases recorded trail history (not which bodies are enabled) — call on Reset, where a body jumping back to its initial position shouldn't draw a line through where it used to be. */
    public void clearTrails() { renderer.clearTrailHistory(); }

    /**
     * Mass-weighted average position — the scene's center of mass. Computed
     * fresh on demand rather than stored on {@code NBodyState} itself: it's
     * O(N) and cheap at this scale, and has no physics meaning of its own —
     * purely a render-side convenience (n-body implementation spec §7).
     */
    private static double[] centerOfMass(NBodyState state) {
        if (state == null || state.getN() == 0) return new double[]{0, 0};
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
