package ui.nbody;

import physics.nbody.NBodyState;
import ui.simcore.Camera;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

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
 * dot. {@link #radiusForBody} derives a screen-constant pixel size from
 * mass <em>relative to the most massive body currently in the scene</em>
 * (cbrt-scaled, clamped — same shape as {@code radiusForBob}'s factor),
 * completely independent of {@link Camera#getScale()}. Positions/orbits are
 * true-to-scale via the camera; body size is presentational only.
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

    private final Camera camera;
    private Color[] bodyColors = BODY_COLORS_DEFAULT;
    private boolean reducedMotion = false;

    // Cached once per draw() (see radiusForBody's javadoc), reused by
    // NBodyInteraction's hit-testing between frames without a full O(N)
    // rescan on every mouse-move event.
    private double cachedMaxMass = 1.0;

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
        return MAX_BODY_RADIUS * factor;
    }

    private static double maxMass(NBodyState state) {
        double max = 1.0e-300; // never zero — avoids a divide-by-zero if every mass were somehow ~0
        for (double m : state.mass) max = Math.max(max, m);
        return max;
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
