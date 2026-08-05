package ui;

import physics.SimState;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

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

    // Default per-bob color palette.
    private static final Color[] BOB_COLORS_DEFAULT = {
        Color.web("#FF6B6B"),   // coral red
        Color.web("#4ECDC4"),   // teal
        Color.web("#A29BFE"),   // lavender
        Color.web("#FDCB6E"),   // gold
        Color.web("#55EFC4"),   // mint
        Color.web("#FD79A8"),   // pink
        Color.web("#74B9FF"),   // sky blue
        Color.web("#E17055"),   // orange
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

    // Not final: a structural edit (per-link editor, runtime N) changes the
    // arm's total length after construction. Without a way to update this,
    // the render scale computed in render() would silently go stale — the
    // exact "extreme rod lengths not handled visually" gap flagged earlier.
    private double totalLength;

    // One trail deque per link, sized lazily to the current N in render()
    // (ensureTrailCapacity) since PendulumCanvas isn't otherwise notified
    // when a structural edit changes link count.
    private final List<Deque<double[]>> trails = new ArrayList<>(); // {screenX, screenY} per link
    private TrailMode trailMode = TrailMode.TIP_ONLY;

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

    public PendulumCanvas(double width, double height, double totalLength) {
        super(width, height);
        this.totalLength = totalLength;
        wireInteraction();
    }

    /** Registers the listener notified of grab/drag/release. {@code null} disables interaction. */
    public void setDragListener(DragListener listener) {
        this.dragListener = listener;
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

    public TrailMode getTrailMode() { return trailMode; }

    /** Swaps the per-bob color palette. See {@link #BOB_COLORS_COLORBLIND_SAFE}'s javadoc for why this replaces the whole set. */
    public void setColorBlindSafe(boolean colorBlindSafe) {
        this.bobColors = colorBlindSafe ? BOB_COLORS_COLORBLIND_SAFE : BOB_COLORS_DEFAULT;
    }

    /** Disables trail recording and the glow/highlight flourishes in drawChain. See the field's javadoc. */
    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        if (reducedMotion) clearTrail();
    }

    /** Full render call with no ensemble — invoked every frame from the AnimationTimer when the butterfly effect is off. */
    public void render(SimState state) {
        render(state, null);
    }

    /**
     * Full render call, optionally drawing a butterfly-effect ensemble as
     * faint ghost chains behind the primary. {@code ensembleStates} may be
     * {@code null} or empty when the ensemble isn't active.
     */
    public void render(SimState state, List<SimState> ensembleStates) {
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
        // scale: fit full arm length in ~45% of canvas width (allows full swing)
        this.scale  = Math.min(W * 0.44, H * 0.80) / Math.max(totalLength, 0.1);
        this.pivotX = W / 2.0;
        this.pivotY = H * 0.14;   // pivot near top-centre
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

        drawPivot(gc, pivotX, pivotY);
        drawChain(gc, state, scale, pivotX, pivotY);
        drawInfoOverlay(gc, state, W);

        // Whichever link is currently grabbed takes priority over whatever
        // was last hovered — mouseMoved doesn't fire mid-drag in JavaFX, so
        // hoveredLink alone would show stale data while dragging.
        int inspected = (draggedLink >= 0) ? draggedLink : hoveredLink;
        drawBobInspector(gc, state, inspected, scale, pivotX, pivotY, W);
    }

    public void clearTrail() { trails.forEach(Deque::clear); }

    /** Grows {@link #trails} to match N — never shrinks, so re-adding a link after removing one doesn't need special-casing. */
    private void ensureTrailCapacity(int n) {
        while (trails.size() < n) trails.add(new ArrayDeque<>());
    }

    private void recordTrailPoints(SimState state, double scale, double pivotX, double pivotY) {
        if (reducedMotion || trailMode == TrailMode.OFF || state.getN() == 0) return;

        if (trailMode == TrailMode.TIP_ONLY) {
            recordTrailPoint(state, state.getN() - 1, scale, pivotX, pivotY);
        } else { // ALL_LINKS
            for (int i = 0; i < state.getN(); i++) recordTrailPoint(state, i, scale, pivotX, pivotY);
        }
    }

    private void recordTrailPoint(SimState state, int link, double scale, double pivotX, double pivotY) {
        double tx = pivotX + state.bobX[link] * scale;
        double ty = pivotY - state.bobY[link] * scale; // flip Y (screen Y down)
        Deque<double[]> t = trails.get(link);
        t.addLast(new double[]{tx, ty});
        while (t.size() > TRAIL_MAX) t.removeFirst();
    }

    // -------------------------------------------------------------------------
    // Interaction — hit-testing, coordinate transform, drag/fling
    // -------------------------------------------------------------------------

    private void wireInteraction() {
        setOnMouseMoved(e -> {
            if (dragListener == null) return;
            hoveredLink = hitTestBob(e.getX(), e.getY());
            setCursor(hoveredLink >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });

        setOnMouseExited(e -> {
            hoveredLink = -1;
            setCursor(Cursor.DEFAULT);
        });

        setOnMousePressed(e -> {
            if (dragListener == null) return;
            int hit = hitTestBob(e.getX(), e.getY());
            if (hit < 0) return;
            if (dragListener.onGrab(hit)) {
                draggedLink = hit;
                dragSamples.clear();
                recordDragSample(angleFromScreen(hit, e.getX(), e.getY()));
            }
        });

        setOnMouseDragged(e -> {
            if (draggedLink < 0) return;
            double angle = angleFromScreen(draggedLink, e.getX(), e.getY());
            recordDragSample(angle);
            dragListener.onDrag(draggedLink, angle);
        });

        setOnMouseReleased(e -> {
            if (draggedLink < 0) return;
            double angle = angleFromScreen(draggedLink, e.getX(), e.getY());
            double angularVelocity = estimateAngularVelocity();
            dragListener.onRelease(draggedLink, angle, angularVelocity);
            draggedLink = -1;
            dragSamples.clear();
            hoveredLink = hitTestBob(e.getX(), e.getY());
            setCursor(hoveredLink >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });
    }

    /** Returns the topmost bob under the given screen point, or -1. Later-drawn (higher-index) bobs win on overlap. */
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
     * Converts a screen point into the angle {@code linkIndex} would need to
     * point its bob there, measured from that link's parent joint (the
     * previous bob, or the pivot for link 0) — the inverse of the forward
     * mapping in {@link physics.PhysicsEngine#getState()}
     * ({@code x = L sin(theta), y = -L cos(theta)}).
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

    private static double wrapDelta(double delta) {
        while (delta >  Math.PI) delta -= 2 * Math.PI;
        while (delta < -Math.PI) delta += 2 * Math.PI;
        return delta;
    }

    // -------------------------------------------------------------------------
    // Private drawing helpers
    // -------------------------------------------------------------------------

    private void drawBackground(GraphicsContext gc, double W, double H) {
        gc.setFill(Color.web("#0D0D1F"));
        gc.fillRect(0, 0, W, H);

        // Subtle grid
        gc.setStroke(Color.web("#1A1A3A", 0.6));
        gc.setLineWidth(0.5);
        for (double x = 0; x < W; x += 45) gc.strokeLine(x, 0, x, H);
        for (double y = 0; y < H; y += 45) gc.strokeLine(0, y, W, y);

        // Horizontal centre line (helps perceive swing amplitude)
        double cx = W / 2.0;
        gc.setStroke(Color.web("#2A2A5A", 0.5));
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
        double prevX = pivotX, prevY = pivotY;
        gc.setStroke(Color.web("#6C63FF", 0.14));
        gc.setLineWidth(1.0);

        for (int i = 0; i < state.getN(); i++) {
            double bx = pivotX + state.bobX[i] * scale;
            double by = pivotY - state.bobY[i] * scale;
            gc.strokeLine(prevX, prevY, bx, by);
            prevX = bx;
            prevY = by;
        }

        gc.setFill(Color.web("#6C63FF", 0.4));
        double r = 2.5;
        gc.fillOval(prevX - r, prevY - r, r * 2, r * 2);
    }

    private void drawTrails(GraphicsContext gc, int n) {
        if (trailMode == TrailMode.OFF) return;

        if (trailMode == TrailMode.TIP_ONLY) {
            if (n > 0) drawOneTrail(gc, trails.get(n - 1), Color.web("#FF6B6B"));
        } else { // ALL_LINKS — each in its own bob color, so overlapping trails stay distinguishable
            for (int i = 0; i < n; i++) drawOneTrail(gc, trails.get(i), bobColors[i % bobColors.length]);
        }
    }

    private void drawOneTrail(GraphicsContext gc, Deque<double[]> trail, Color color) {
        if (trail.size() < 2) return;

        int total = trail.size();
        int idx   = 0;
        double[] prev = null;

        for (double[] pt : trail) {
            if (prev != null) {
                double alpha = 0.05 + 0.65 * ((double) idx / total);
                double width = 0.5 + 2.0 * ((double) idx / total);
                gc.setStroke(color.deriveColor(0, 1, 1, alpha));
                gc.setLineWidth(width);
                gc.strokeLine(prev[0], prev[1], pt[0], pt[1]);
            }
            prev = pt;
            idx++;
        }
    }

    private void drawPivot(GraphicsContext gc, double px, double py) {
        // Shadow
        gc.setFill(Color.web("#000000", 0.4));
        gc.fillOval(px - PIVOT_RADIUS + 2, py - PIVOT_RADIUS + 2,
                    PIVOT_RADIUS * 2, PIVOT_RADIUS * 2);
        // Body
        gc.setFill(Color.web("#CCCCDD"));
        gc.fillOval(px - PIVOT_RADIUS, py - PIVOT_RADIUS, PIVOT_RADIUS * 2, PIVOT_RADIUS * 2);
        // Cross mark
        gc.setStroke(Color.web("#888899"));
        gc.setLineWidth(1.5);
        gc.strokeLine(px - 4, py, px + 4, py);
        gc.strokeLine(px, py - 4, px, py + 4);
    }

    private void drawChain(GraphicsContext gc, SimState state,
                           double scale, double pivotX, double pivotY) {
        double prevX = pivotX, prevY = pivotY;
        boolean simple = reducedMotion || state.getN() > GRADIENT_THRESHOLD;

        for (int i = 0; i < state.getN(); i++) {
            double bx = pivotX + state.bobX[i] * scale;
            double by = pivotY - state.bobY[i] * scale;

            // Rod shadow
            gc.setStroke(Color.web("#000000", 0.35));
            gc.setLineWidth(ROD_WIDTH + 2);
            gc.strokeLine(prevX + 1.5, prevY + 1.5, bx + 1.5, by + 1.5);

            // Rod
            gc.setStroke(Color.web("#9A9ABB"));
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

        gc.setFont(Font.font("Monospaced", 10));
        double boxW = 176, boxH = 48;
        double boxX = Math.min(bx + 14, canvasW - boxW - 4);
        double boxY = Math.max(by - boxH - 14, 4);

        gc.setFill(Color.web("#000000", 0.6));
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 6, 6);
        gc.setStroke(Color.web("#6C63FF", 0.7));
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(boxX, boxY, boxW, boxH, 6, 6);

        gc.setFill(Color.web("#FFFFFF"));
        gc.fillText("Link #" + (link + 1), boxX + 8, boxY + 15);
        gc.setFill(Color.web("#CCCCEE"));
        gc.fillText(String.format("θ=%+.3f rad  ω=%+.3f rad/s", state.angles[link], state.angularVelocities[link]),
                boxX + 8, boxY + 30);
        gc.fillText(String.format("m=%.3f kg   L=%.3f m", mass, rodLength), boxX + 8, boxY + 44);
    }

    private void drawInfoOverlay(GraphicsContext gc, SimState state, double W) {
        gc.setFont(Font.font("Monospaced", FontWeight.NORMAL, 11));

        // Semi-transparent pill background
        gc.setFill(Color.web("#000000", 0.45));
        gc.fillRoundRect(8, 8, 190, 46, 8, 8);

        gc.setFill(Color.web("#A0A0CC"));
        gc.fillText(String.format("t  = %7.2f s",   state.time),         14, 23);
        gc.fillText(String.format("E  = %7.3f J",   state.totalEnergy),  14, 37);
        gc.fillText(String.format("KE = %6.3f  PE = %7.3f",
                                   state.kineticEnergy, state.potentialEnergy), 14, 51);
    }

    private void drawWaitingMessage(GraphicsContext gc, double W, double H) {
        gc.setFill(Color.web("#4A4A8A", 0.7));
        gc.setFont(Font.font("System", FontWeight.NORMAL, 14));
        gc.fillText("Initialising physics engine...", W / 2 - 100, H / 2);
    }
}
