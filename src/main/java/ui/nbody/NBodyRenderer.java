package ui.nbody;

import physics.nbody.NBodyState;
import ui.simcore.Camera;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Draws the n-body scene and its HUD overlays — the n-body analogue of
 * {@code ui.pendulum.PendulumChainRenderer}, composed into {@link
 * NBodyCanvas} the same way that class is composed into {@code
 * PendulumCanvas} (round 1 §5 of the UI restructuring plan's pattern,
 * carried over to a second simulation type).
 *
 * <p><b>Flat-colored circles, unconditionally</b> — direct reuse of {@code
 * PendulumChainRenderer}'s own simple-branch fallback (its {@code simple}
 * flag already draws exactly this, just conditionally on N/reduced-motion;
 * here it's the only mode, since there's no chaos-trail visualization story
 * for n-body — see the n-body implementation spec §6.3/§11).
 *
 * <p><b>Render radius is not world-scaled</b> — same answer the pendulum
 * already settled on, carried forward: real body radii are hopelessly tiny
 * next to real orbital distances (the Sun's own radius is ~0.5% of Earth's
 * orbit), so true-to-scale rendering would make every body an invisible
 * dot. {@link #radiusForBody} derives a pixel size from mass <em>relative
 * to the most massive body currently in the scene</em> (cbrt-scaled,
 * clamped — same shape as {@code radiusForBob}'s factor). Positions/orbits
 * are true-to-scale via the camera; body size is presentational only.
 *
 * <p><b>Round 1.1: that mass-based size is then capped against the nearest
 * other body's actual screen distance</b> (see {@link #computeSafeRadii}).
 * Without this, a purely screen-constant Sun radius comfortably exceeds
 * Mercury's entire orbit at the scene's own default fitted view (the whole
 * system, out to its outermost body, has to fit on screen at once, which
 * puts the inner planets only a few pixels from the Sun) — the Sun's circle
 * simply swallows them, at every zoom level tight enough for that pair's
 * on-screen separation to still be smaller than the Sun's own radius. The
 * cap recomputes each body's nearest-neighbor screen distance every frame
 * and never lets its radius exceed a safe fraction of that, so any two
 * bodies' circles stay visibly distinct at whatever zoom the camera is
 * currently at, and the Sun visibly shrinks back down once the view is
 * crowded, then grows back to its full size once zoomed in enough to give
 * it room. This is why {@link #draw} must run {@link #computeSafeRadii}
 * before either hit-testing or drawing can call {@link #radiusForBody}.
 *
 * <p><b>HUD numbers are scientific notation, not the pendulum's {@code
 * %.3f}/{@code %+.1f°} formats</b> — those are fine at pendulum magnitudes
 * (masses of a few kg, angles in degrees) but useless at n-body's SI
 * magnitudes (a mass of 5972000000000000000000000.000 kg is unreadable).
 * Every numeric HUD field here uses {@code %.3e}.
 */
final class NBodyRenderer {

    private static final double MAX_BODY_RADIUS = 15.0;
    // Floor as a FRACTION of MAX_BODY_RADIUS, not an absolute pixel count —
    // see radiusForBody's javadoc for why a pure cbrt(relative-mass) ratio
    // collapses almost every body in a real solar-system-scale roster
    // toward this floor (the Sun outweighs everything else by many orders
    // of magnitude), and why that's an accepted, flagged limitation rather
    // than a bug: hit-radius forgiveness (see NBodyInteraction) is what
    // keeps a floor-sized body clickable, not a bigger floor.
    private static final double MIN_RADIUS_FACTOR = 0.18;

    // A body's radius never exceeds this fraction of its nearest neighbor's
    // CURRENT screen distance (see computeSafeRadii) — two bodies each
    // capped at 0.45 of their mutual separation sum to 0.9 of it, leaving a
    // visible gap between their circles rather than them merging at 1.0.
    private static final double NEIGHBOR_RADIUS_FRACTION = 0.45;
    // Absolute floor in pixels regardless of how crowded the neighborhood
    // is — an isolated close pair still needs to render as two visible
    // dots, not two mathematically-non-overlapping but invisible ones.
    private static final double MIN_ABSOLUTE_RADIUS = 1.5;

    private static final Color[] BODY_COLORS_DEFAULT = {
        Color.web("#EA3F8C"),   // magenta (accent)
        Color.web("#3DDCC7"),   // cyan
        Color.web("#E8D34A"),   // yellow
        Color.web("#5FE87A"),   // green
        Color.web("#7B8CFF"),   // periwinkle
        Color.web("#FF8A3D"),   // orange
        Color.web("#C77DFF"),   // violet
        Color.web("#9AA0A6"),   // cool grey
    };

    private static final Color[] BODY_COLORS_COLORBLIND_SAFE = {
        Color.web("#E69F00"),   // orange
        Color.web("#56B4E9"),   // sky blue
        Color.web("#009E73"),   // bluish green
        Color.web("#F0E442"),   // yellow
        Color.web("#0072B2"),   // blue
        Color.web("#D55E00"),   // vermillion
        Color.web("#CC79A7"),   // reddish purple
        Color.web("#999999"),   // grey
    };

    // Cycles per second for the selection halo's pulse — matches
    // PendulumChainRenderer's own constant exactly, for a consistent feel.
    private static final double HALO_PULSE_HZ = 1.2;

    private static final Color ACCENT      = Color.web("#EA3F8C");
    private static final Color ACCENT_A70  = Color.web("#EA3F8C", 0.7);
    private static final Color BLACK_A35   = Color.web("#000000", 0.35);
    private static final Color BLACK_A60   = Color.web("#000000", 0.6);
    private static final Color BLACK_A70   = Color.web("#000000", 0.7);
    private static final Color WHITE       = Color.web("#FFFFFF");
    private static final Color TEXT_SECONDARY = Color.web("#D6D6DC");

    private static final Font FONT_HUD = Font.font("Monospaced", 14);

    private static final double HUD_TEXT_PADDING = 9;
    private static final double HUD_MIN_WIDTH = 190;

    // Round 1.1: motion trails, per body, toggled independently rather than
    // a single global mode — see ui.nbody.DisplayGroupPanel's checkbox
    // list. Matches PendulumChainRenderer's own TRAIL_MAX exactly.
    private static final int TRAIL_MAX = 600;

    private final Camera camera;
    private Color[] bodyColors = BODY_COLORS_DEFAULT;
    private boolean reducedMotion = false;

    // Cached once per draw() (see radiusForBody's javadoc), reused by
    // NBodyInteraction's hit-testing between frames without a full O(N)
    // rescan on every mouse-move event.
    private double cachedMaxMass = 1.0;
    private double[] cachedSafeRadius = null; // index-aligned with the last-drawn state; see computeSafeRadii

    // Trail state — self-healing against N changing (see ensureTrailCapacity):
    // both arrays/lists are rebuilt from scratch (defaulting to OFF) the
    // moment their length stops matching the live state's body count, since
    // an Add/Delete/preset-load renumbers what index i even refers to.
    // Editing a body's own parameters never changes N, so this preserves
    // trailEnabled/history across that kind of edit for free.
    private boolean[] trailEnabled = new boolean[0];
    private final List<Deque<double[]>> trails = new ArrayList<>(); // world-space {x, y} per body

    // Reused scratch node for text-width measurement, same trick
    // PendulumChainRenderer uses to size a HUD box to its actual content.
    private final javafx.scene.text.Text metricsProbe = new javafx.scene.text.Text();

    NBodyRenderer(Camera camera) {
        this.camera = camera;
    }

    void setColorBlindSafe(boolean colorBlindSafe) {
        this.bodyColors = colorBlindSafe ? BODY_COLORS_COLORBLIND_SAFE : BODY_COLORS_DEFAULT;
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        if (reducedMotion) clearTrailHistory();
    }

    /** Number of bodies the trail state is currently sized for — what {@code ui.nbody.DisplayGroupPanel}'s checkbox list should show. */
    int trailBodyCount() { return trailEnabled.length; }

    /** Whether body {@code i} currently leaves a trail. */
    boolean isTrailEnabled(int i) { return i >= 0 && i < trailEnabled.length && trailEnabled[i]; }

    /** Toggles body {@code i}'s trail. Out-of-range indices are silently ignored (defensive against a stale UI reference across a structural edit). */
    void setTrailEnabled(int i, boolean on) {
        if (i >= 0 && i < trailEnabled.length) trailEnabled[i] = on;
    }

    /** Enables or disables every body's trail at once — the Display tab's "All"/"None" buttons. */
    void setAllTrailsEnabled(boolean on) {
        Arrays.fill(trailEnabled, on);
    }

    /**
     * Erases recorded trail history without touching which bodies are
     * enabled — called on Reset (a body jumping back to its initial
     * position should not draw a line through where it used to be), never
     * on an ordinary structural edit, where {@link #ensureTrailCapacity}'s
     * own self-healing already handles the case that actually needs a
     * reset (N changed).
     */
    void clearTrailHistory() {
        for (Deque<double[]> t : trails) t.clear();
    }

    /**
     * Draws one full frame's worth of n-body content: every body, the
     * selection halo, the status overlay, and the hovered/selected body's
     * inspector HUD. Background/waiting-message/scale-bar are already
     * handled by {@code SimCanvas} by the time this is called.
     */
    void draw(GraphicsContext gc, NBodyState state, double w, double h, int hoveredBody, int selectedBody) {
        double scale = camera.getScale();
        double originX = camera.originX(w);
        double originY = camera.originY(h);
        this.cachedMaxMass = maxMass(state);
        this.cachedSafeRadius = computeSafeRadii(state, scale);

        ensureTrailCapacity(state.getN());
        recordTrailPoints(state);
        drawTrails(gc, state, scale, originX, originY);

        drawBodies(gc, state, scale, originX, originY);
        drawSelectionHalo(gc, state, scale, originX, originY, selectedBody);
        drawStatusOverlay(gc, state);
        drawBodyHud(gc, state, hoveredBody, scale, originX, originY, w, "");
        if (selectedBody != hoveredBody) drawBodyHud(gc, state, selectedBody, scale, originX, originY, w, "Selected: ");
    }

    /**
     * This body's screen-constant render radius: mass relative to the most
     * massive body <em>currently in the scene</em> (not a hardcoded
     * assumption about which body that is — a user could in principle add
     * one heavier than the roster's Sun), cbrt-scaled and clamped. Also
     * used by {@link NBodyInteraction} for hit-testing.
     */
    double radiusForBody(NBodyState state, int i) {
        double relative = state.mass[i] / cachedMaxMass;
        double factor = Math.max(MIN_RADIUS_FACTOR, Math.min(1.0, Math.cbrt(relative)));
        double desired = MAX_BODY_RADIUS * factor;

        double safe = (cachedSafeRadius != null && i < cachedSafeRadius.length) ? cachedSafeRadius[i] : desired;
        return Math.max(MIN_ABSOLUTE_RADIUS, Math.min(desired, safe));
    }

    private static double maxMass(NBodyState state) {
        double max = 1.0e-300; // never zero — avoids a divide-by-zero if every mass were somehow ~0
        for (double m : state.mass) max = Math.max(max, m);
        return max;
    }

    /**
     * For each body, {@link #NEIGHBOR_RADIUS_FRACTION} of its distance (in
     * CURRENT screen pixels, i.e. already multiplied by {@code scale}) to
     * the nearest other body — see this class's javadoc for why. O(N²), the
     * same complexity {@code physics.nbody.NBodyEngine}'s own derivative
     * already accepts at this N; negligible next to that at every N this
     * app actually renders.
     */
    private static double[] computeSafeRadii(NBodyState state, double scale) {
        int n = state.getN();
        double[] safe = new double[n];
        java.util.Arrays.fill(safe, Double.MAX_VALUE);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = (state.positionX[i] - state.positionX[j]) * scale;
                double dy = (state.positionY[i] - state.positionY[j]) * scale;
                double cap = Math.hypot(dx, dy) * NEIGHBOR_RADIUS_FRACTION;
                if (cap < safe[i]) safe[i] = cap;
                if (cap < safe[j]) safe[j] = cap;
            }
        }
        return safe;
    }

    /** Rebuilds trail state from scratch (all OFF, no history) the moment N stops matching — see the field javadoc for why this is safe/desired. */
    private void ensureTrailCapacity(int n) {
        if (trailEnabled.length == n) return;
        trailEnabled = new boolean[n];
        trails.clear();
        for (int i = 0; i < n; i++) trails.add(new ArrayDeque<>());
    }

    private void recordTrailPoints(NBodyState state) {
        if (reducedMotion) return;
        for (int i = 0; i < state.getN(); i++) {
            if (!trailEnabled[i]) continue;
            Deque<double[]> t = trails.get(i);
            t.addLast(new double[]{state.positionX[i], state.positionY[i]});
            while (t.size() > TRAIL_MAX) t.removeFirst();
        }
    }

    private void drawTrails(GraphicsContext gc, NBodyState state, double scale, double originX, double originY) {
        if (reducedMotion) return;
        for (int i = 0; i < state.getN(); i++) {
            if (!trailEnabled[i]) continue;
            drawOneTrail(gc, trails.get(i), bodyColors[i % bodyColors.length], scale, originX, originY);
        }
    }

    /**
     * Re-projects each recorded world-space point through the CURRENT
     * camera every call, rather than trusting a screen position baked in
     * when the point was recorded — same reasoning as {@code
     * PendulumChainRenderer#drawOneTrail}: a pan/zoom (or a follow-COM
     * frame) between two recordings would otherwise tear old segments away
     * from the body instead of moving with it.
     */
    private void drawOneTrail(GraphicsContext gc, Deque<double[]> trail, Color color, double scale, double originX, double originY) {
        if (trail.size() < 2) return;

        int total = trail.size();
        int idx = 0;
        double prevX = 0, prevY = 0;
        boolean havePrev = false;

        for (double[] pt : trail) {
            double x = originX + pt[0] * scale;
            double y = originY - pt[1] * scale;
            if (havePrev) {
                double alpha = 0.05 + 0.65 * ((double) idx / total);
                double width = 0.5 + 2.0 * ((double) idx / total);
                gc.setStroke(color.deriveColor(0, 1, 1, alpha));
                gc.setLineWidth(width);
                gc.strokeLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
            havePrev = true;
            idx++;
        }
    }

    private void drawBodies(GraphicsContext gc, NBodyState state, double scale, double originX, double originY) {
        for (int i = 0; i < state.getN(); i++) {
            double bx = originX + state.positionX[i] * scale;
            double by = originY - state.positionY[i] * scale;
            double r = radiusForBody(state, i);

            gc.setFill(BLACK_A35);
            gc.fillOval(bx - r + 1.5, by - r + 1.5, r * 2, r * 2);

            gc.setFill(bodyColors[i % bodyColors.length]);
            gc.fillOval(bx - r, by - r, r * 2, r * 2);
        }
    }

    private void drawSelectionHalo(GraphicsContext gc, NBodyState state, double scale, double originX, double originY, int selectedBody) {
        if (selectedBody < 0 || selectedBody >= state.getN()) return;

        double bx = originX + state.positionX[selectedBody] * scale;
        double by = originY - state.positionY[selectedBody] * scale;
        double r = radiusForBody(state, selectedBody) * 1.9;

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

    /** Top-left "is this thing alive" overlay — N/t/E, always visible even while the sidebar (which shows the same numbers plus drift%) is collapsed. */
    private void drawStatusOverlay(GraphicsContext gc, NBodyState state) {
        gc.setFont(FONT_HUD);

        String line1 = String.format("N  = %d", state.getN());
        String line2 = String.format("t  = %.3e s", state.time);
        String line3 = String.format("E  = %.3e J", state.totalEnergy);

        double boxX = 8, boxY = 8, boxH = 58;
        double boxW = hudBoxWidth(FONT_HUD, line1, line2, line3);

        gc.setFill(BLACK_A60);
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

        gc.setFill(ACCENT);
        gc.fillText(line1, boxX + HUD_TEXT_PADDING, boxY + 19);
        gc.fillText(line2, boxX + HUD_TEXT_PADDING, boxY + 35);
        gc.fillText(line3, boxX + HUD_TEXT_PADDING, boxY + 51);
    }

    /**
     * The hovered- or selected-body inspector: name, mass/radius,
     * position, velocity — every numeric field in {@code %.3e}, per this
     * class's javadoc. Shared by the hover and selection cases (see {@link
     * #draw}), distinguished only by the anchor point (beside the body
     * versus a fixed top-right box) and a "Selected: " label prefix.
     */
    private void drawBodyHud(GraphicsContext gc, NBodyState state, int body,
                              double scale, double originX, double originY, double canvasW, String labelPrefix) {
        if (body < 0 || body >= state.getN()) return;

        double bx = originX + state.positionX[body] * scale;
        double by = originY - state.positionY[body] * scale;

        Font font = FONT_HUD;
        gc.setFont(font);

        String line1 = labelPrefix + state.name[body];
        String line2 = String.format("m=%.3e kg   r=%.3e m", state.mass[body], state.radius[body]);
        String line3 = String.format("x=%+.3e  y=%+.3e m", state.positionX[body], state.positionY[body]);
        String line4 = String.format("vx=%+.3e  vy=%+.3e m/s", state.velocityX[body], state.velocityY[body]);

        double boxW = hudBoxWidth(font, line1, line2, line3, line4);
        double boxH = 76;
        // Selected-body HUD (labelPrefix non-empty) anchors top-right,
        // clear of the always-present status overlay; hover HUD anchors
        // beside the body itself, same as the pendulum's bob inspector.
        double boxX, boxY;
        if (labelPrefix.isEmpty()) {
            boxX = Math.min(bx + 14, canvasW - boxW - 4);
            boxY = Math.max(by - boxH - 14, 4);
        } else {
            boxX = canvasW - boxW - 8;
            boxY = 8;
        }

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
        gc.fillText(line4, boxX + HUD_TEXT_PADDING, boxY + 70);
    }
}
