package ui.pendulum;

import physics.SimState;
import ui.simcore.Camera;
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
 * Draws the N-pendulum chain, its trails, ghosts, A/B-compare chain, and
 * every HUD overlay — everything {@code ui.PendulumCanvas} used to draw
 * itself, minus the background grid/waiting-message/scale-bar, which are
 * now {@code ui.simcore.SimCanvas}'s job since they're simulation-agnostic.
 *
 * <p>Composed into {@link PendulumCanvas}, not inherited — see round 1 §5 of
 * the UI restructuring plan. Reads camera state (pivot/scale) fresh every
 * {@link #draw} call; owns no interaction state of its own (that's {@link
 * PendulumInteraction}), only the per-frame visuals plus the {@link
 * #radiusForBob} formula {@code PendulumInteraction} needs for hit-testing.
 */
final class PendulumChainRenderer {

    // ---- Visual constants (moved verbatim from ui.PendulumCanvas) ----
    private static final int    TRAIL_MAX    = 600;
    private static final double ROD_WIDTH    = 2.5;
    private static final double PIVOT_RADIUS = 6.0;

    private static final double MAX_BOB_RADIUS = 11.0;
    private static final double MIN_BOB_RADIUS = 3.5;

    private static final int GRADIENT_THRESHOLD = 20;

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

    private static final double VELOCITY_TINT_MAX = 8.0;

    // Cycles per second for the selection halo's pulse.
    private static final double HALO_PULSE_HZ = 1.2;

    // Round 2.1: hoisted out of the per-frame draw methods below. Color.web
    // re-parses its hex-string argument on every call and Font.font does an
    // internal family/weight/size lookup on every call — neither is
    // expensive in isolation (a profiling pass found the whole draw() call
    // to already be sub-millisecond even without this), but every one of
    // these was previously a fresh call every single frame for a value that
    // never changes, which is pure avoidable garbage-collector pressure.
    // Anything whose alpha/hue genuinely varies frame-to-frame (the
    // selection halo's pulse, a trail segment's fade, velocity tinting)
    // stays computed inline — see drawSelectionHalo/drawOneTrail/velocityColor.
    private static final Color ACCENT          = Color.web("#EA3F8C");
    private static final Color ACCENT_A12      = Color.web("#EA3F8C", 0.12);
    private static final Color ACCENT_A35      = Color.web("#EA3F8C", 0.35);
    private static final Color ACCENT_A70      = Color.web("#EA3F8C", 0.7);
    private static final Color ACCENT_A75      = Color.web("#EA3F8C", 0.75);
    private static final Color ACCENT_A85      = Color.web("#EA3F8C", 0.85);
    private static final Color COMPARE_CYAN        = Color.web("#3DDCC7");
    private static final Color COMPARE_CYAN_STROKE = Color.web("#1B6E63");
    private static final Color BLACK_A35 = Color.web("#000000", 0.35);
    private static final Color BLACK_A40 = Color.web("#000000", 0.4);
    private static final Color BLACK_A60 = Color.web("#000000", 0.6);
    private static final Color BLACK_A70 = Color.web("#000000", 0.7);
    private static final Color WHITE     = Color.web("#FFFFFF");
    private static final Color WHITE_A35 = Color.web("#FFFFFF", 0.35);
    private static final Color ROD_COLOR       = Color.web("#C7C7D1");
    private static final Color TEXT_SECONDARY  = Color.web("#D6D6DC");
    private static final Color PIVOT_STROKE    = Color.web("#6B6B74");
    private static final Color GRAVITY_HANDLE_STROKE = Color.web("#7A2148");

    private static final Font FONT_HUD           = Font.font("Monospaced", 14);
    private static final Font FONT_COMPARE_LABEL = Font.font("Monospaced", FontWeight.BOLD, 10);
    private static final Font FONT_GRAVITY_LABEL = Font.font("Monospaced", 10);

    // Shared text measurement for the HUD/inspector boxes.
    private static final double HUD_TEXT_PADDING = 9;
    private static final double HUD_MIN_WIDTH = 150;

    // Gravity handle's fixed pixel radius from the pivot. Package-private
    // static so PendulumInteraction's hit-testing (hitTestGravityHandle)
    // can call the same gravityHandleX/Y formula this class draws with,
    // rather than keeping a second copy of the constant + formula in sync.
    private static final double GRAVITY_HANDLE_RADIUS = 46;

    private final Camera camera;

    private final List<Deque<double[]>> trails = new ArrayList<>(); // {screenX, screenY, |angularVelocity|} per link
    private PendulumCanvas.TrailMode trailMode = PendulumCanvas.TrailMode.TIP_ONLY;
    private boolean velocityTint = false;
    private Color[] bobColors = BOB_COLORS_DEFAULT;
    private boolean reducedMotion = false;
    private double bobBaseRadius = MAX_BOB_RADIUS;

    private List<SimState> ghostStates;
    private SimState compareState;

    // Round 3 §4-d: drawSelectedLinkHud sits immediately right of the
    // time/energy pill, so it needs that pill's actual box width, not a
    // hardcoded guess — recorded each frame since draw() always paints the
    // info overlay first.
    private double infoOverlayBoxW = 216;

    // Reused scratch node — cheap to query, no need to recreate per call.
    private final javafx.scene.text.Text metricsProbe = new javafx.scene.text.Text();

    PendulumChainRenderer(Camera camera) {
        this.camera = camera;
    }

    void setGhosts(List<SimState> ghostStates) { this.ghostStates = ghostStates; }
    void setCompare(SimState compareState) { this.compareState = compareState; }

    void setTrailMode(PendulumCanvas.TrailMode mode) {
        this.trailMode = mode;
        if (mode == PendulumCanvas.TrailMode.OFF) clearTrail();
    }
    PendulumCanvas.TrailMode getTrailMode() { return trailMode; }

    void setVelocityTint(boolean on) { this.velocityTint = on; }
    boolean isVelocityTint() { return velocityTint; }

    void setColorBlindSafe(boolean colorBlindSafe) {
        this.bobColors = colorBlindSafe ? BOB_COLORS_COLORBLIND_SAFE : BOB_COLORS_DEFAULT;
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        if (reducedMotion) clearTrail();
    }

    void clearTrail() { trails.forEach(Deque::clear); }

    /**
     * Draws one full frame's worth of pendulum content: trails, ghosts,
     * compare chain, pivot, gravity handle, the primary chain, selection
     * halo, and every HUD overlay. Background/waiting-message/scale-bar are
     * already handled by {@code SimCanvas} by the time this is called.
     */
    void draw(GraphicsContext gc, SimState state, double w, double h,
              int inspectedLink, int selectedLink,
              int previewLink, double[] previewScreenX, double[] previewScreenY,
              double gravityAngle, boolean draggingGravity) {
        double scale = camera.getScale();
        double pivotX = camera.originX(w);
        double pivotY = camera.originY(h);
        this.bobBaseRadius = baseRadiusForN(state.getN());

        ensureTrailCapacity(state.getN());
        recordTrailPoints(state);
        drawTrails(gc, state.getN(), scale, pivotX, pivotY);

        if (ghostStates != null) {
            for (SimState ghost : ghostStates) drawGhostChain(gc, ghost, scale, pivotX, pivotY);
        }
        if (compareState != null) drawCompareChain(gc, compareState, scale, pivotX, pivotY);

        drawPivot(gc, pivotX, pivotY);
        drawGravityHandle(gc, pivotX, pivotY, gravityAngle, draggingGravity);
        drawChain(gc, state, scale, pivotX, pivotY, previewLink, previewScreenX, previewScreenY);
        drawSelectionHalo(gc, state, scale, pivotX, pivotY, selectedLink);
        drawInfoOverlay(gc, state, w);
        drawSelectedLinkHud(gc, state, pivotX, pivotY, scale, selectedLink);
        drawBobInspector(gc, state, inspectedLink, scale, pivotX, pivotY, w);
    }

    /** This bob's radius: the current N-scaled base, adjusted by mass. Also used by {@code PendulumInteraction} for hit-testing. */
    double radiusForBob(SimState state, int i) {
        double mass = (state.masses != null && i < state.masses.length) ? state.masses[i] : 1.0;
        double factor = Math.max(0.6, Math.min(1.6, Math.cbrt(Math.max(mass, 1.0e-6))));
        return bobBaseRadius * factor;
    }

    /** N-scaled base radius, floored/ceilinged so bobs stay visible at both N=1 and the editor's 60-link cap. */
    private static double baseRadiusForN(int n) {
        double r = MAX_BOB_RADIUS / Math.sqrt(Math.max(n, 1));
        return Math.max(MIN_BOB_RADIUS, Math.min(MAX_BOB_RADIUS, r));
    }

    /** Grows {@link #trails} to match N — never shrinks. */
    private void ensureTrailCapacity(int n) {
        while (trails.size() < n) trails.add(new ArrayDeque<>());
    }

    /**
     * Records each link's current <em>world</em>-space position, not a
     * screen-space one — see {@link #drawOneTrail}'s javadoc for why: a
     * trail spans many frames, and the camera can pan/zoom between any two
     * of them, so baking a screen position in at record time would freeze
     * that point wherever the camera happened to be when it was recorded.
     */
    private void recordTrailPoints(SimState state) {
        if (reducedMotion || trailMode == PendulumCanvas.TrailMode.OFF || state.getN() == 0) return;

        if (trailMode == PendulumCanvas.TrailMode.TIP_ONLY) {
            recordTrailPoint(state, state.getN() - 1);
        } else { // ALL_LINKS
            for (int i = 0; i < state.getN(); i++) recordTrailPoint(state, i);
        }
    }

    private void recordTrailPoint(SimState state, int link) {
        Deque<double[]> t = trails.get(link);
        t.addLast(new double[]{state.bobX[link], state.bobY[link], Math.abs(state.angularVelocities[link])});
        while (t.size() > TRAIL_MAX) t.removeFirst();
    }

    private void drawGhostChain(GraphicsContext gc, SimState state, double scale, double pivotX, double pivotY) {
        double prevX = pivotX, prevY = pivotY;
        gc.setStroke(ACCENT_A12);
        gc.setLineWidth(1.0);

        for (int i = 0; i < state.getN(); i++) {
            double bx = pivotX + state.bobX[i] * scale;
            double by = pivotY - state.bobY[i] * scale;
            gc.strokeLine(prevX, prevY, bx, by);
            prevX = bx;
            prevY = by;
        }

        gc.setFill(ACCENT_A35);
        double r = 2.5;
        gc.fillOval(prevX - r, prevY - r, r * 2, r * 2);
    }

    private void drawCompareChain(GraphicsContext gc, SimState state, double scale, double pivotX, double pivotY) {
        double prevX = pivotX, prevY = pivotY;
        Color compareColor = COMPARE_CYAN;
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
        gc.setStroke(COMPARE_CYAN_STROKE);
        gc.setLineWidth(1.0);
        gc.strokeOval(prevX - r, prevY - r, r * 2, r * 2);

        gc.setFont(FONT_COMPARE_LABEL);
        gc.setFill(compareColor);
        gc.fillText("B", prevX + r + 3, prevY + 3);
    }

    private void drawTrails(GraphicsContext gc, int n, double scale, double pivotX, double pivotY) {
        if (trailMode == PendulumCanvas.TrailMode.OFF) return;

        if (trailMode == PendulumCanvas.TrailMode.TIP_ONLY) {
            if (n > 0) drawOneTrail(gc, trails.get(n - 1), ACCENT, scale, pivotX, pivotY);
        } else { // ALL_LINKS
            for (int i = 0; i < n; i++) drawOneTrail(gc, trails.get(i), bobColors[i % bobColors.length], scale, pivotX, pivotY);
        }
    }

    /**
     * Re-projects each recorded world-space point through the <em>current</em>
     * camera every call, rather than trusting a screen position baked in
     * back when the point was recorded — otherwise a pan or zoom mid-trail
     * would tear old segments away from the chain instead of moving with it,
     * since every other frame the pivot/scale used here moves right along
     * with dragging/scrolling but a stale baked-in screen point wouldn't.
     */
    private void drawOneTrail(GraphicsContext gc, Deque<double[]> trail, Color color, double scale, double pivotX, double pivotY) {
        if (trail.size() < 2) return;

        int total = trail.size();
        int idx   = 0;
        double prevX = 0, prevY = 0;
        boolean havePrev = false;

        for (double[] pt : trail) {
            double x = pivotX + pt[0] * scale;
            double y = pivotY - pt[1] * scale;
            if (havePrev) {
                double alpha = 0.05 + 0.65 * ((double) idx / total);
                double width = 0.5 + 2.0 * ((double) idx / total);
                Color segmentColor = velocityTint ? velocityColor(pt[2]) : color;
                gc.setStroke(segmentColor.deriveColor(0, 1, 1, alpha));
                gc.setLineWidth(width);
                gc.strokeLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
            havePrev = true;
            idx++;
        }
    }

    private static Color velocityColor(double angularSpeed) {
        double t = Math.max(0.0, Math.min(1.0, angularSpeed / VELOCITY_TINT_MAX));
        double hue = 220.0 + t * 110.0;
        return Color.hsb(hue, 0.85, 1.0);
    }

    private void drawChain(GraphicsContext gc, SimState state, double scale, double pivotX, double pivotY,
                            int previewLink, double[] previewScreenX, double[] previewScreenY) {
        double prevX = pivotX, prevY = pivotY;
        boolean simple = reducedMotion || state.getN() > GRADIENT_THRESHOLD;
        boolean previewing = previewLink >= 0 && previewScreenX != null && previewScreenX.length == state.getN();

        for (int i = 0; i < state.getN(); i++) {
            double bx = previewing ? previewScreenX[i] : pivotX + state.bobX[i] * scale;
            double by = previewing ? previewScreenY[i] : pivotY - state.bobY[i] * scale;

            gc.setStroke(BLACK_A35);
            gc.setLineWidth(ROD_WIDTH + 2);
            gc.strokeLine(prevX + 1.5, prevY + 1.5, bx + 1.5, by + 1.5);

            gc.setStroke(ROD_COLOR);
            gc.setLineWidth(ROD_WIDTH);
            gc.strokeLine(prevX, prevY, bx, by);

            double bobRadius = radiusForBob(state, i);
            Color bobColor = bobColors[i % bobColors.length];

            if (simple) {
                gc.setFill(bobColor);
                gc.fillOval(bx - bobRadius, by - bobRadius, bobRadius * 2, bobRadius * 2);
            } else {
                gc.setFill(bobColor.deriveColor(0, 1, 1, 0.18));
                double glowR = bobRadius * 1.8;
                gc.fillOval(bx - glowR, by - glowR, glowR * 2, glowR * 2);

                gc.setFill(BLACK_A35);
                gc.fillOval(bx - bobRadius + 2, by - bobRadius + 2, bobRadius * 2, bobRadius * 2);

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

                gc.setFill(WHITE_A35);
                double hl = bobRadius * 0.55;
                gc.fillOval(bx - hl * 0.9, by - hl * 0.9, hl, hl);
            }

            prevX = bx;
            prevY = by;
        }
    }

    private void drawSelectionHalo(GraphicsContext gc, SimState state, double scale, double pivotX, double pivotY, int selectedLink) {
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

    private void drawBobInspector(GraphicsContext gc, SimState state, int link,
                                   double scale, double pivotX, double pivotY, double canvasW) {
        if (link < 0 || link >= state.getN()) return;

        double bx = pivotX + state.bobX[link] * scale;
        double by = pivotY - state.bobY[link] * scale;
        double parentX = (link == 0) ? pivotX : pivotX + state.bobX[link - 1] * scale;
        double parentY = (link == 0) ? pivotY : pivotY - state.bobY[link - 1] * scale;
        double rodLength = Math.hypot(bx - parentX, by - parentY) / scale;
        double mass = (state.masses != null && link < state.masses.length) ? state.masses[link] : Double.NaN;

        Font font = FONT_HUD;
        gc.setFont(font);

        String line1 = "Link #" + (link + 1);
        String line2 = String.format("θ=%+.1f°  ω=%+.3f rad/s", Math.toDegrees(state.angles[link]), state.angularVelocities[link]);
        String line3 = String.format("m=%.3f kg   L=%.3f m", mass, rodLength);

        double boxW = hudBoxWidth(font, line1, line2, line3);
        double boxH = 58;
        double boxX = Math.min(bx + 14, canvasW - boxW - 4);
        double boxY = Math.max(by - boxH - 14, 4);

        gc.setFill(BLACK_A70);
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);
        gc.setStroke(ACCENT_A70);
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(boxX, boxY, boxW, boxH, 4, 4);

        gc.setFill(WHITE);
        gc.fillText(line1, boxX + HUD_TEXT_PADDING, boxY + 18);
        gc.setFill(TEXT_SECONDARY);
        gc.fillText(line2, boxX + HUD_TEXT_PADDING, boxY + 36);
        gc.fillText(line3, boxX + HUD_TEXT_PADDING, boxY + 53);
    }

    private void drawInfoOverlay(GraphicsContext gc, SimState state, double W) {
        // Same family/weight/size as FONT_HUD (FontWeight.NORMAL is Font.font's
        // own default), so this reuses that cached instance rather than
        // looking up an identical font fresh every frame.
        Font font = FONT_HUD;
        gc.setFont(font);

        String line1 = String.format("t  = %7.2f s",   state.time);
        String line2 = String.format("E  = %7.3f J",   state.totalEnergy);
        String line3 = String.format("KE = %6.3f  PE = %7.3f", state.kineticEnergy, state.potentialEnergy);

        double boxX = 8, boxY = 8, boxH = 58;
        double boxW = hudBoxWidth(font, line1, line2, line3);
        this.infoOverlayBoxW = boxW;

        gc.setFill(BLACK_A60);
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

        gc.setFill(ACCENT);
        gc.fillText(line1, boxX + HUD_TEXT_PADDING, boxY + 19);
        gc.fillText(line2, boxX + HUD_TEXT_PADDING, boxY + 35);
        gc.fillText(line3, boxX + HUD_TEXT_PADDING, boxY + 51);
    }

    private void drawSelectedLinkHud(GraphicsContext gc, SimState state, double pivotX, double pivotY, double scale, int selectedLink) {
        if (selectedLink < 0 || selectedLink >= state.getN()) return;

        double bx = pivotX + state.bobX[selectedLink] * scale;
        double by = pivotY - state.bobY[selectedLink] * scale;
        double parentX = (selectedLink == 0) ? pivotX : pivotX + state.bobX[selectedLink - 1] * scale;
        double parentY = (selectedLink == 0) ? pivotY : pivotY - state.bobY[selectedLink - 1] * scale;
        double rodLength = Math.hypot(bx - parentX, by - parentY) / scale;
        double mass = (state.masses != null && selectedLink < state.masses.length) ? state.masses[selectedLink] : Double.NaN;

        Font font = FONT_HUD;
        gc.setFont(font);

        String line1 = "Selected: Link #" + (selectedLink + 1);
        String line2 = String.format("θ=%+.1f°  ω=%+.3f rad/s", Math.toDegrees(state.angles[selectedLink]), state.angularVelocities[selectedLink]);
        String line3 = String.format("m=%.3f kg   L=%.3f m", mass, rodLength);

        double boxX = 8 + infoOverlayBoxW + 8;
        double boxY = 8;
        double boxW = hudBoxWidth(font, line1, line2, line3);
        double boxH = 58;

        gc.setFill(BLACK_A60);
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);
        gc.setStroke(ACCENT_A70);
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(boxX, boxY, boxW, boxH, 4, 4);

        gc.setFill(ACCENT);
        gc.fillText(line1, boxX + HUD_TEXT_PADDING, boxY + 18);
        gc.setFill(TEXT_SECONDARY);
        gc.fillText(line2, boxX + HUD_TEXT_PADDING, boxY + 36);
        gc.fillText(line3, boxX + HUD_TEXT_PADDING, boxY + 53);
    }

    private void drawPivot(GraphicsContext gc, double px, double py) {
        gc.setFill(BLACK_A40);
        gc.fillOval(px - PIVOT_RADIUS + 2, py - PIVOT_RADIUS + 2, PIVOT_RADIUS * 2, PIVOT_RADIUS * 2);
        gc.setFill(TEXT_SECONDARY);
        gc.fillOval(px - PIVOT_RADIUS, py - PIVOT_RADIUS, PIVOT_RADIUS * 2, PIVOT_RADIUS * 2);
        gc.setStroke(PIVOT_STROKE);
        gc.setLineWidth(1.5);
        gc.strokeLine(px - 4, py, px + 4, py);
        gc.strokeLine(px, py - 4, px, py + 4);
    }

    /** Handle position for the given gravity angle — same (sin, cos) convention every bob uses, at a fixed pixel radius instead of a scaled world length. */
    static double gravityHandleX(double pivotX, double gravityAngle) { return pivotX + GRAVITY_HANDLE_RADIUS * Math.sin(gravityAngle); }
    static double gravityHandleY(double pivotY, double gravityAngle) { return pivotY + GRAVITY_HANDLE_RADIUS * Math.cos(gravityAngle); }

    private void drawGravityHandle(GraphicsContext gc, double pivotX, double pivotY, double gravityAngle, boolean draggingGravity) {
        double hx = gravityHandleX(pivotX, gravityAngle), hy = gravityHandleY(pivotY, gravityAngle);

        gc.setStroke(ACCENT_A75);
        gc.setLineWidth(1.5);
        gc.strokeLine(pivotX, pivotY, hx, hy);

        gc.setFill(draggingGravity ? ACCENT : ACCENT_A85);
        gc.fillOval(hx - 5, hy - 5, 10, 10);
        gc.setStroke(GRAVITY_HANDLE_STROKE);
        gc.setLineWidth(1.0);
        gc.strokeOval(hx - 5, hy - 5, 10, 10);

        gc.setFont(FONT_GRAVITY_LABEL);
        gc.setFill(ACCENT);
        gc.fillText("g", hx + 8, hy + 3);
    }
}
