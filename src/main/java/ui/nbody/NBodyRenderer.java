package ui.nbody;

import physics.nbody.CelestialMagnetismRegistry;
import physics.nbody.DipoleFieldMath;
import physics.nbody.MagnetopauseCalculator;
import physics.nbody.NBodyConfig;
import physics.nbody.NBodyState;
import ui.simcore.Camera;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
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
 * <p><b>Round 1.2: render radius is true to the body's actual physical
 * size</b> ({@code state.radius[i]}, the same field the inspector HUD
 * already shows) — not a mass-derived presentational size. It goes through
 * exactly the camera scale positions do, so it shrinks and grows with zoom
 * the same honest way a position does: zoomed in enough, the Sun looks
 * exactly as much bigger than Earth as it actually is, instead of both
 * being squashed onto the same fixed size curve. {@link #radiusForBody}
 * only ever inflates a body's size <em>up</em> from that true value, and
 * only when it would otherwise be too small to see at all.
 *
 * <p><b>The one departure from true scale: a flat visibility floor</b> (see
 * {@link #MIN_VISIBLE_RADIUS}). Real body radii are hopelessly tiny next to
 * real orbital distances (the Sun's own radius is ~0.5% of Earth's orbit),
 * so at the scene's default fitted view — the whole system has to fit on
 * screen at once — every body's true radius rounds down to a fraction of a
 * pixel. A body below the floor is drawn at the floor instead of vanishing.
 *
 * <p><b>Round 1.3: that floor is flat, not neighbor-capped</b> — round 1.2
 * shrank it against the nearest other body's screen distance, on the theory
 * that inflating two close, below-floor bodies up to the same fixed size
 * could make them visually merge. In practice this made otherwise-similar
 * bodies render inconsistently for a reason that has nothing to do with
 * either body's own size: Venus (no close neighbor) rendered at the full
 * floor while Earth (the Moon a few px away) rendered visibly smaller,
 * despite the two being close to the same real size. Every body's floor is
 * now the same fixed {@link #MIN_VISIBLE_RADIUS} regardless of who's
 * nearby; a genuinely close pair (Earth/Moon at a wide zoom) can once again
 * visually overlap at the floor size, the same tradeoff round 1.1 first
 * fixed and round 1.2 kept — accepted here as the more honest answer:
 * zooming in (not a neighbor-dependent shrink) is how you resolve a close
 * pair, exactly like it already is for true-scale sizes above the floor.
 *
 * <p><b>HUD numbers are scientific notation, not the pendulum's {@code
 * %.3f}/{@code %+.1f°} formats</b> — those are fine at pendulum magnitudes
 * (masses of a few kg, angles in degrees) but useless at n-body's SI
 * magnitudes (a mass of 5972000000000000000000000.000 kg is unreadable).
 * Every numeric HUD field here uses {@code %.3e}.
 */
final class NBodyRenderer {

    // The floor a body's TRUE render radius (state.radius[i] * scale) gets
    // inflated up to when it would otherwise round below this many pixels —
    // flat and the same for every body (round 1.3 — see this class's
    // javadoc for why it's no longer neighbor-capped), not a mass-derived
    // presentational size. See radiusForBody.
    private static final double MIN_VISIBLE_RADIUS = 2.0;

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

    // Dynamic spacetime fabric & CAD/Blender infinite grid constants
    private static final double MESH_TARGET_SPACING_PX = 55.0;
    private static final double MESH_COUPLING_FACTOR = 2.5e-3;
    private static final double MESH_MAX_DISPLACEMENT_PX = 28.0;

    private final Camera camera;
    private Color[] bodyColors = BODY_COLORS_DEFAULT;
    private boolean reducedMotion = false;

    public enum TracingMode {
        PARAMETRIC("Closed-Form Parametric"),
        NUMERICAL_RK4("Numerical RK4 Streamlines");

        private final String label;
        TracingMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private TracingMode tracingMode = TracingMode.PARAMETRIC;
    private boolean showMagneticFields = true;
    private boolean showSolarWind = true;
    private boolean showBowShock = true;
    private int fieldLineDensity = 12;
    private double auroralLuminescence = 0.8;
    private final SolarWindRenderer solarWindRenderer = new SolarWindRenderer();

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

    public TracingMode getTracingMode() { return tracingMode; }
    public void setTracingMode(TracingMode tracingMode) { this.tracingMode = (tracingMode != null) ? tracingMode : TracingMode.PARAMETRIC; }

    public boolean isShowMagneticFields() { return showMagneticFields; }
    public void setShowMagneticFields(boolean show) { this.showMagneticFields = show; }

    public boolean isShowSolarWind() { return showSolarWind; }
    public void setShowSolarWind(boolean show) { this.showSolarWind = show; }

    public boolean isShowBowShock() { return showBowShock; }
    public void setShowBowShock(boolean show) { this.showBowShock = show; }

    public int getFieldLineDensity() { return fieldLineDensity; }
    public void setFieldLineDensity(int density) { this.fieldLineDensity = Math.max(4, Math.min(24, density)); }

    public double getAuroralLuminescence() { return auroralLuminescence; }
    public void setAuroralLuminescence(double lum) { this.auroralLuminescence = Math.max(0.1, Math.min(1.0, lum)); }

    public SolarWindRenderer getSolarWindRenderer() { return solarWindRenderer; }
    public void resetSolarWind() { solarWindRenderer.reset(); }

    private boolean isPaused = false;
    private double speedMultiplier = 100_000.0;

    public void setPaused(boolean paused) {
        this.isPaused = paused;
        this.solarWindRenderer.setPaused(paused);
    }

    public boolean isPaused() {
        return isPaused;
    }

    public void setSpeedMultiplier(double mult) {
        this.speedMultiplier = mult;
        this.solarWindRenderer.setSpeedMultiplier(mult);
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
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
     * Draws one full frame's worth of n-body content: spacetime mesh, every body,
     * the selection halo, the status overlay, and the hovered/selected/watched
     * body's inspector HUD. Background/waiting-message/scale-bar are
     * already handled by {@code SimCanvas} by the time this is called.
     *
     * @param infoBody round 1.2: a body pinned from the Bodies tab's single
     *        click — shown with the same beside-body inspector style as
     *        hover, labeled "Watching: ", <em>without</em> pausing the sim
     *        or engaging selection (see {@code NBodyCanvas#setInfoBody}), so
     *        its numbers keep changing live while the simulation runs.
     */
    void draw(GraphicsContext gc, NBodyState state, double w, double h, int hoveredBody, int selectedBody, int infoBody) {
        if (state == null) return;
        double scale = camera.getScale();
        double originX = camera.originX(w);
        double originY = camera.originY(h);

        // Layer 1: Spacetime fabric mesh & background stars
        drawSpacetimeMesh(gc, state, w, h, scale, originX, originY);

        if (state.getN() > 0) {
            ensureTrailCapacity(state.getN());
            recordTrailPoints(state);

            // Layer 2: Motion trails
            drawTrails(gc, state, scale, originX, originY);

            // Layer 3: Fields & Wind (solar wind plasma sparks, parametric / RK4 dipole lines #3DDCC7, bow shocks)
            drawMagneticFieldsAndWind(gc, state, w, h, scale, originX, originY);

            // Layer 4: Celestial bodies sorted hierarchically: satellites/moons first -> parent planets -> central stars/black holes
            drawBodies(gc, state, scale, originX, originY);

            // Layer 5: Overlays (interaction halo, velocity vectors, hover HUD)
            drawSelectionHalo(gc, state, scale, originX, originY, selectedBody);
            drawBodyHud(gc, state, hoveredBody, scale, originX, originY, w, "", false);
            if (selectedBody != hoveredBody) drawBodyHud(gc, state, selectedBody, scale, originX, originY, w, "Selected: ", true);
            if (infoBody != hoveredBody && infoBody != selectedBody) drawBodyHud(gc, state, infoBody, scale, originX, originY, w, "Watching: ", false);
        }

        drawStatusOverlay(gc, state);
    }

    /**
     * Categorizes a celestial body into a strict rendering & hit-test hierarchy tier:
     * Tier 0: Orbiting Satellites / Moons (drawn first, hit-tested last)
     * Tier 1: Parent Planets
     * Tier 2: Central Stars / Black Holes (drawn last on top, highest precedence)
     */
    public int getBodyHierarchyTier(NBodyState state, int i) {
        if (state == null || i < 0 || i >= state.getN()) return 0;
        double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;
        if (state.isStar(i) || state.isCompactObject(i, G) || state.mass[i] >= 1.0e29) {
            return 2; // Star / Compact Object
        }
        String name = (state.name != null && i < state.name.length && state.name[i] != null) ? state.name[i].toLowerCase() : "";
        if (name.contains("moon") || name.contains("io") || name.contains("europa") || name.contains("ganymede")
                || name.contains("callisto") || name.contains("titan") || name.contains("enceladus")
                || name.contains("mimas") || name.contains("iapetus") || name.contains("rhea")
                || name.contains("dione") || name.contains("tethys") || name.contains("titania")
                || name.contains("oberon") || name.contains("ariel") || name.contains("umbriel")
                || name.contains("miranda") || name.contains("triton") || name.contains("charon")
                || name.contains("phobos") || name.contains("deimos") || name.contains("satellite")) {
            return 0; // Satellite / Moon
        }
        if (state.mass[i] < 2.5e23) {
            for (int j = 0; j < state.getN(); j++) {
                if (j == i) continue;
                if (state.mass[j] > 1.0e24 && !state.isStar(j)) {
                    double dx = state.positionX[i] - state.positionX[j];
                    double dy = state.positionY[i] - state.positionY[j];
                    if (dx * dx + dy * dy < 5.0e9 * 5.0e9) {
                        return 0; // Orbiting satellite
                    }
                }
            }
        }
        return 1; // Parent planet
    }

    private void drawMagneticFieldsAndWind(GraphicsContext gc, NBodyState state, double w, double h, double scale, double originX, double originY) {
        if (state == null || state.getN() == 0) return;

        // 1. Solar Wind Plasma Advection
        if (showSolarWind) {
            solarWindRenderer.updateAndRender(gc, state, camera, w, h, state.time, 3600.0,
                    showSolarWind, showBowShock, auroralLuminescence, isPaused, speedMultiplier);
        }

        // 2. Magnetic Field Tracing & Bow Shocks
        if (showMagneticFields) {
            double alpha = Math.max(0.1, Math.min(1.0, 0.75 * auroralLuminescence));
            Color fieldColor = Color.web("#3DDCC7", alpha);

            int n = state.getN();
            for (int i = 0; i < n; i++) {
                if (!state.hasMagneticField(i)) continue;

                double bx = originX + state.positionX[i] * scale;
                double by = originY - state.positionY[i] * scale;
                double standoff = MagnetopauseCalculator.computeStandoff(i, state);
                double standoffScreen = standoff * scale;

                // Adaptive minimum magnetosphere indicator circle when zoomed out
                if (standoffScreen < 10.0) {
                    double indR = 10.0;
                    gc.setStroke(fieldColor.deriveColor(0, 1, 1, 0.45));
                    gc.setLineWidth(1.2);
                    gc.strokeOval(bx - indR, by - indR, 2 * indR, 2 * indR);
                }

                int starIdx = MagnetopauseCalculator.findNearestStar(i, state);
                double[] starPos = null;
                if (starIdx >= 0) {
                    starPos = new double[]{state.positionX[starIdx], state.positionY[starIdx]};
                }

                gc.setStroke(fieldColor);
                gc.setLineWidth(1.2);

                if (tracingMode == TracingMode.PARAMETRIC) {
                    List<double[]> loops = DipoleFieldMath.generateParametricLoops(
                            i, state, standoff, starPos, fieldLineDensity, 36);
                    int loopIdx = 0;
                    int numShells = Math.max(1, loops.size() / 2);
                    for (double[] poly : loops) {
                        int pts = poly.length / 2;
                        if (pts < 2) continue;
                        double[] xs = new double[pts];
                        double[] ys = new double[pts];
                        for (int p = 0; p < pts; p++) {
                            xs[p] = originX + poly[p * 2] * scale;
                            ys[p] = originY - poly[p * 2 + 1] * scale;
                        }
                        boolean isNightside = (loopIdx % 2 != 0);
                        double shellFrac = (double) (loopIdx / 2) / numShells;
                        double tailFrac = isNightside ? (0.35 + 0.65 * shellFrac) : (shellFrac * 0.20);
                        // Smooth transition from radiant cyan (#3DDCC7) to deep indigo/violet (#7C4DFF) in magnetotail
                        Color c = fieldColor.interpolate(Color.web("#7C4DFF", alpha * 0.92), tailFrac);
                        gc.setStroke(c);
                        gc.setLineWidth(1.3);
                        gc.strokePolyline(xs, ys, pts);
                        loopIdx++;
                    }
                } else {
                    // Numerical RK4 streamlines seeded bidirectionally along magnetic equator
                    double rBody = state.radius[i];
                    double tiltRad = Math.toRadians(state.magneticTiltDegrees[i]);
                    double uStarX = 1.0, uStarY = 0.0;
                    if (starPos != null) {
                        double sdx = starPos[0] - state.positionX[i];
                        double sdy = starPos[1] - state.positionY[i];
                        double sdist = Math.hypot(sdx, sdy);
                        if (sdist > 1.0e-3) {
                            uStarX = sdx / sdist;
                            uStarY = sdy / sdist;
                        }
                    }
                    double uPerpX = -uStarY;
                    double uPerpY = uStarX;
                    double mEqX, mEqY;
                    if (starPos != null) {
                        mEqX = -Math.sin(tiltRad) * uPerpX + Math.cos(tiltRad) * uStarX;
                        mEqY = -Math.sin(tiltRad) * uPerpY + Math.cos(tiltRad) * uStarY;
                    } else {
                        mEqX = -Math.sin(tiltRad);
                        mEqY = Math.cos(tiltRad);
                    }

                    int seeds = Math.max(8, fieldLineDensity);
                    double stepSize = Math.max(rBody * 0.05, standoff / 60.0);
                    int maxSteps = 200;

                    for (int s = 0; s < seeds; s++) {
                        double frac = (s + 0.5) / seeds;

                        for (int side : new int[]{1, -1}) {
                            double maxL = (side == 1) ? (0.85 * standoff) : (1.65 * standoff);
                            double lShell = rBody * (1.25 + (maxL / rBody - 1.25) * frac);

                            double sx0 = state.positionX[i] + side * lShell * mEqX;
                            double sy0 = state.positionY[i] + side * lShell * mEqY;
                            double[] line = DipoleFieldMath.integrateRK4Streamlines(state, sx0, sy0, stepSize, maxSteps);
                            int pts = line.length / 2;
                            if (pts < 2) continue;
                            double[] xs = new double[pts];
                            double[] ys = new double[pts];
                            for (int p = 0; p < pts; p++) {
                                xs[p] = originX + line[p * 2] * scale;
                                ys[p] = originY - line[p * 2 + 1] * scale;
                            }
                            double tailFrac = (side == -1) ? (0.35 + 0.65 * frac) : (frac * 0.20);
                            Color c = fieldColor.interpolate(Color.web("#7C4DFF", alpha * 0.92), tailFrac);
                            gc.setStroke(c);
                            gc.setLineWidth(1.3);
                            gc.strokePolyline(xs, ys, pts);
                        }
                    }
                }

                // Bow shock
                if (showBowShock && starPos != null) {
                    drawBowShock(gc, i, state, standoff, starPos, scale, originX, originY, auroralLuminescence);
                }
            }
        }
    }

    private void drawBowShock(GraphicsContext gc, int body, NBodyState state, double rMp,
                              double[] starPos, double scale, double originX, double originY, double luminescence) {
        double bx = state.positionX[body];
        double by = state.positionY[body];
        double sdx = starPos[0] - bx;
        double sdy = starPos[1] - by;
        double sdist = Math.hypot(sdx, sdy);
        if (sdist <= 1.0e-3) return;

        double ux = sdx / sdist;
        double uy = sdy / sdist;
        double perpX = -uy;
        double perpY = ux;

        double rBs = 1.28 * rMp;

        int pts = 51;
        double[] xs = new double[pts];
        double[] ys = new double[pts];
        double[] xMp = new double[pts];
        double[] yMp = new double[pts];

        for (int p = 0; p < pts; p++) {
            double alpha = Math.toRadians(-115.0 + (230.0 * p) / (pts - 1));
            double cosA = Math.cos(alpha);
            double sinA = Math.sin(alpha);
            double denom = Math.max(1.0e-4, 1.0 + cosA);
            double flaring = Math.pow(2.0 / denom, 0.60);

            double rBsAlpha = rBs * flaring;
            double rMpAlpha = rMp * flaring;

            double dirX = cosA * ux + sinA * perpX;
            double dirY = cosA * uy + sinA * perpY;

            xs[p] = originX + (bx + rBsAlpha * dirX) * scale;
            ys[p] = originY - (by + rBsAlpha * dirY) * scale;
            xMp[p] = originX + (bx + rMpAlpha * dirX) * scale;
            yMp[p] = originY - (by + rMpAlpha * dirY) * scale;
        }

        double lum = Math.max(0.1, Math.min(1.0, luminescence));

        gc.save();
        // 1. Magnetosheath cushion fill between bow shock and magnetopause (Item 5.3: Volumetric glowing cushion)
        double[] polyX = new double[pts * 2];
        double[] polyY = new double[pts * 2];
        for (int p = 0; p < pts; p++) {
            polyX[p] = xs[p];
            polyY[p] = ys[p];
            polyX[pts + p] = xMp[pts - 1 - p];
            polyY[pts + p] = yMp[pts - 1 - p];
        }
        // Base decelerating amber cushion fill (#FFA000, alpha ~ 0.35)
        gc.setFill(Color.color(1.0, 0.64, 0.0, 0.28 * lum));
        gc.fillPolygon(polyX, polyY, pts * 2);

        // Core compression subsolar layer fill (#FFD54F)
        gc.setFill(Color.color(1.0, 0.82, 0.20, 0.14 * lum));
        gc.fillPolygon(polyX, polyY, pts * 2);

        // 2. Magnetopause inner boundary line
        gc.setStroke(Color.color(0.88, 0.76, 0.38, 0.40 * lum));
        gc.setLineWidth(1.2);
        gc.strokePolyline(xMp, yMp, pts);

        // 3. Volumetric glowing bow shock cushion & radiant shock front
        // Outer soft amber/orange halo (#FF8F00, alpha ~ 0.15)
        gc.setStroke(Color.color(1.0, 0.56, 0.0, 0.16 * lum));
        gc.setLineWidth(16.0);
        gc.strokePolyline(xs, ys, pts);

        // Mid-tier compression glow
        gc.setStroke(Color.color(1.0, 0.72, 0.18, 0.36 * lum));
        gc.setLineWidth(8.0);
        gc.strokePolyline(xs, ys, pts);

        // Bright luminous gold shock front (#FFD54F, alpha ~ 0.70)
        gc.setStroke(Color.color(1.0, 0.84, 0.31, 0.70 * lum));
        gc.setLineWidth(3.6);
        gc.strokePolyline(xs, ys, pts);

        // Incandescent shock core
        gc.setStroke(Color.color(1.0, 0.98, 0.82, 0.92 * lum));
        gc.setLineWidth(1.4);
        gc.strokePolyline(xs, ys, pts);
        gc.restore();
    }

    private void drawBodies(GraphicsContext gc, NBodyState state, double scale, double originX, double originY) {
        int n = state.getN();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;

        // Hierarchical sort: satellites/moons first (tier 0) -> parent planets (tier 1) -> central stars/black holes (tier 2)
        Arrays.sort(order, (a, b) -> {
            int tA = getBodyHierarchyTier(state, a);
            int tB = getBodyHierarchyTier(state, b);
            if (tA != tB) return Integer.compare(tA, tB);
            return Double.compare(state.mass[a], state.mass[b]);
        });

        for (int idx = 0; idx < n; idx++) {
            int i = order[idx];
            drawSingleBody(gc, state, i, scale, originX, originY);
        }
    }

    private void drawSingleBody(GraphicsContext gc, NBodyState state, int i, double scale, double originX, double originY) {
        double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;
        double bx = originX + state.positionX[i] * scale;
        double by = originY - state.positionY[i] * scale;
        double r = radiusForBody(state, i);
        double dScreen = 2.0 * r;

        String bodyName = (state.name != null && i < state.name.length && state.name[i] != null) ? state.name[i] : "";
        String lower = bodyName.toLowerCase().trim();
        Color defaultColor = bodyColors[i % bodyColors.length];
        Color bodyColor = baseColorForBody(lower, defaultColor);
        double rotPeriod = (state.rotationPeriod != null && i < state.rotationPeriod.length) ? state.rotationPeriod[i] : 0.0;

        // Check if comet (Halley etc.): always render distinct coma and tail across cosmic distances
        boolean isComet = lower.contains("halley") || lower.contains("comet") || lower.contains("oumuamua");
        if (isComet) {
            drawCometModel(gc, state, i, bx, by, r, originX, originY);
            return;
        }

        // Check if compact object (Black Hole)
        boolean isCompact = state.isCompactObject(i, G) || lower.contains("black hole") || lower.contains("singularity");
        if (isCompact) {
            drawBlackHoleModel(gc, bx, by, r);
            return;
        }

        if (dScreen < 10.0) {
            // Glow clutter removal: strip generic photometric glow from non-magnetic inactive bodies (Venus, Mars, Moon, Ceres, etc.)
            if (state.hasMagneticField(i) || state.isStar(i)) {
                double rGlow = r + 3.0;
                gc.setFill(bodyColor.deriveColor(0, 1.0, 1.0, 0.25));
                gc.fillOval(bx - rGlow, by - rGlow, rGlow * 2, rGlow * 2);
            }

            gc.setFill(bodyColor);
            gc.fillOval(bx - r, by - r, r * 2, r * 2);

            // For Saturn at LOD 0, draw subtle miniature rings
            if (lower.contains("saturn")) {
                gc.setStroke(Color.web("#EAD3A2", 0.6));
                gc.setLineWidth(1.0);
                gc.strokeOval(bx - 1.8 * r, by - 0.7 * r, 3.6 * r, 1.4 * r);
            }
        } else {
            // LOD 1: dScreen >= 10 px -> 2D snapshot of the real 3D model for current rotation frame
            CelestialBody3DModel model = CelestialBody3DRegistry.getModel(bodyName, bodyColor, state.isStar(i), isCompact, isComet);

            if (model.isStar()) {
                // Coronal flare glow behind the 3D star sphere
                double rCorona = 1.9 * r;
                double pulse = reducedMotion ? 1.0 : (1.0 + 0.04 * Math.sin(state.time * 0.05));
                RadialGradient coronaGrad = new RadialGradient(
                        0, 0, bx, by, rCorona * pulse, false, CycleMethod.NO_CYCLE,
                        new Stop(0.0, lower.contains("trappist") || lower.contains("proxima") ? Color.web("#FF5722", 0.85) : Color.web("#FFFBE0", 0.85)),
                        new Stop(0.45, lower.contains("trappist") || lower.contains("proxima") ? Color.web("#C62828", 0.45) : Color.web("#FFA439", 0.45)),
                        new Stop(0.75, lower.contains("trappist") || lower.contains("proxima") ? Color.web("#4A0000", 0.15) : Color.web("#EA3F8C", 0.15)),
                        new Stop(1.0, Color.TRANSPARENT)
                );
                gc.setFill(coronaGrad);
                gc.fillOval(bx - rCorona * pulse, by - rCorona * pulse, 2 * rCorona * pulse, 2 * rCorona * pulse);
            }

            double phi = (rotPeriod > 0.0) ? ((state.time / rotPeriod) % 1.0) : 0.0;
            if (phi < 0) phi += 1.0;
            Image snapshot = model.getSnapshot(phi);

            if (snapshot != null) {
                double drawR = model.getDrawRadius(r);
                gc.drawImage(snapshot, bx - drawR, by - drawR, 2 * drawR, 2 * drawR);
            } else {
                drawGenericPlanetModel(gc, bx, by, r, bodyColor, state.time, rotPeriod);
            }
        }
    }

    /**
     * This body's screen-space render radius — true to {@code
     * state.radius[i]} through the same camera scale positions use (see
     * this class's javadoc), floored at a flat {@link #MIN_VISIBLE_RADIUS}
     * when it would otherwise be too small to see. Also used by {@link
     * NBodyInteraction} for hit-testing.
     */
    double radiusForBody(NBodyState state, int i) {
        double trueRadius = state.radius[i] * camera.getScale();
        return Math.max(trueRadius, MIN_VISIBLE_RADIUS);
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

    private Color baseColorForBody(String lower, Color fallback) {
        if (lower.contains("sun")) return Color.web("#FFD147");
        if (lower.contains("mercury")) return Color.web("#9E9287");
        if (lower.contains("venus")) return Color.web("#FFF2B8");
        if (lower.contains("earth")) return Color.web("#2B66B1");
        if (lower.contains("moon")) return Color.web("#B0B0B0");
        if (lower.contains("mars")) return Color.web("#C8572D");
        if (lower.contains("jupiter")) return Color.web("#E4C299");
        if (lower.contains("saturn")) return Color.web("#EAD3A2");
        if (lower.contains("uranus")) return Color.web("#80D4D4");
        if (lower.contains("neptune")) return Color.web("#2658C4");
        if (lower.contains("pluto")) return Color.web("#AC7451");
        if (lower.contains("titan")) return Color.web("#E69238");
        if (lower.contains("io")) return Color.web("#F6D842");
        if (lower.contains("europa")) return Color.web("#EDF4FA");
        if (lower.contains("halley") || lower.contains("comet")) return Color.web("#4DF0FF");
        if (lower.contains("trappist-1")) return Color.web("#FF4D26");
        if (lower.contains("proxima")) return Color.web("#FF5722");
        return fallback;
    }

    private void drawBlackHoleModel(GraphicsContext gc, double bx, double by, double r) {
        double rCore = Math.max(r, 3.0);
        double pulse = reducedMotion ? 0.7 : (0.7 + 0.15 * Math.sin((System.nanoTime() / 1.0e9) * 2.0 * Math.PI * 0.5));

        // Lensing halo from 1.5 r_core to 2.6 r_core
        RadialGradient lensingGrad = new RadialGradient(
                0, 0, bx, by, 2.6 * rCore, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.TRANSPARENT),
                new Stop(0.50, Color.web("#3DDCC7", pulse)),
                new Stop(0.80, Color.web("#EA3F8C", pulse * 0.7)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(lensingGrad);
        gc.fillOval(bx - 2.6 * rCore, by - 2.6 * rCore, 5.2 * rCore, 5.2 * rCore);

        // Glowing photon ring
        gc.setStroke(Color.web("#3DDCC7", Math.min(1.0, pulse * 1.1)));
        gc.setLineWidth(Math.max(1.0, 0.12 * rCore));
        gc.strokeOval(bx - 1.25 * rCore, by - 1.25 * rCore, 2.5 * rCore, 2.5 * rCore);

        // Event horizon (pitch black core)
        gc.setFill(Color.BLACK);
        gc.fillOval(bx - rCore, by - rCore, rCore * 2, rCore * 2);
    }

    private void drawTexturedBodyModel(GraphicsContext gc, Image texture, double bx, double by, double r, double time, double rotPeriod, boolean isSaturn) {
        if (isSaturn) {
            gc.save();
            gc.translate(bx, by);
            gc.rotate(-18.0);
            drawSaturnRings(gc, r, 0.42, 0, 180);
            gc.restore();
        }

        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        double phi = (rotPeriod > 0.0) ? ((time / rotPeriod) % 1.0) : 0.0;
        if (phi < 0) phi += 1.0;

        double mapW = 4.0 * r;
        double offsetX = (phi * mapW) % mapW;
        gc.drawImage(texture, bx - r - offsetX, by - r, mapW, 2 * r);
        gc.drawImage(texture, bx - r - offsetX + mapW, by - r, mapW, 2 * r);

        // 3D spherical shading and limb darkening overlay
        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.72, Color.color(0, 0, 0, 0.40)),
                new Stop(1.0, Color.color(0, 0, 0, 0.85))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();

        if (isSaturn) {
            gc.save();
            gc.translate(bx, by);
            gc.rotate(-18.0);
            drawSaturnRings(gc, r, 0.42, 180, 180);
            gc.restore();
        }
    }

    private void drawCometModel(GraphicsContext gc, NBodyState state, int i, double bx, double by, double r, double originX, double originY) {
        double dx = bx - originX;
        double dy = by - originY;
        double dist = Math.hypot(dx, dy);
        if (dist < 1.0e-3) {
            dx = 1.0;
            dy = 0.0;
            dist = 1.0;
        }
        double ux = dx / dist;
        double uy = dy / dist;
        double px = -uy;
        double py = ux;

        double tailLen = Math.min(180.0, Math.max(35.0, 5.0 * r));

        // 1. Dust Tail (curved, warm glowing ivory-white fan)
        double dustEnd1X = bx + ux * tailLen + px * (0.28 * tailLen);
        double dustEnd1Y = by + uy * tailLen + py * (0.28 * tailLen);
        double dustEnd2X = bx + ux * (tailLen * 0.85) - px * (0.12 * tailLen);
        double dustEnd2Y = by + uy * (tailLen * 0.85) - py * (0.12 * tailLen);

        LinearGradient dustGrad = new LinearGradient(
                bx, by, bx + ux * tailLen, by + uy * tailLen, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#FFF5D9", 0.65)),
                new Stop(0.4, Color.web("#F7E5B5", 0.35)),
                new Stop(0.8, Color.web("#DDC89A", 0.12)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(dustGrad);
        gc.beginPath();
        gc.moveTo(bx, by);
        gc.lineTo(dustEnd1X, dustEnd1Y);
        gc.lineTo(dustEnd2X, dustEnd2Y);
        gc.closePath();
        gc.fill();

        // 2. Ion Tail (straight, vibrant electric cyan stream)
        double ionTailLen = tailLen * 1.35;
        LinearGradient ionGrad = new LinearGradient(
                bx, by, bx + ux * ionTailLen, by + uy * ionTailLen, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#4DF0FF", 0.85)),
                new Stop(0.3, Color.web("#3DDCC7", 0.50)),
                new Stop(0.7, Color.web("#2299BB", 0.18)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setStroke(ionGrad);
        gc.setLineWidth(Math.max(1.5, r * 0.45));
        gc.strokeLine(bx, by, bx + ux * ionTailLen, by + uy * ionTailLen);

        // 3. Coma (bright glowing gas envelope surrounding nucleus)
        double comaR = Math.max(r * 2.2, 8.0);
        RadialGradient comaGrad = new RadialGradient(
                0, 0, bx, by, comaR, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#FFFFFF", 0.95)),
                new Stop(0.35, Color.web("#80F3FF", 0.60)),
                new Stop(0.75, Color.web("#36C8D9", 0.20)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(comaGrad);
        gc.fillOval(bx - comaR, by - comaR, 2 * comaR, 2 * comaR);

        // 4. Comet Nucleus (2D snapshot of real 3D comet model)
        String cName = (state.name != null && i < state.name.length && state.name[i] != null) ? state.name[i] : "comet";
        CelestialBody3DModel cometModel = CelestialBody3DRegistry.getModel(cName, false, false, true);
        double rotPeriod = (state.rotationPeriod != null && i < state.rotationPeriod.length) ? state.rotationPeriod[i] : 0.0;
        double phi = (rotPeriod > 0.0) ? ((state.time / rotPeriod) % 1.0) : 0.0;
        if (phi < 0) phi += 1.0;
        Image cometSnapshot = cometModel.getSnapshot(phi);
        if (cometSnapshot != null) {
            double drawR = cometModel.getDrawRadius(r * 0.9);
            gc.drawImage(cometSnapshot, bx - drawR, by - drawR, 2 * drawR, 2 * drawR);
        } else {
            gc.setFill(Color.web("#2A2624"));
            gc.fillOval(bx - r * 0.65, by - r * 0.55, r * 1.3, r * 1.1);
        }
    }

    private void drawSunModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        // 1. Solar coronal flare halo (expanding to 1.9x r)
        double rCorona = 1.9 * r;
        double pulse = reducedMotion ? 1.0 : (1.0 + 0.04 * Math.sin(time * 0.05));
        RadialGradient coronaGrad = new RadialGradient(
                0, 0, bx, by, rCorona * pulse, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#FFFBE0", 0.85)),
                new Stop(0.45, Color.web("#FFA439", 0.45)),
                new Stop(0.75, Color.web("#EA3F8C", 0.15)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(coronaGrad);
        gc.fillOval(bx - rCorona * pulse, by - rCorona * pulse, 2 * rCorona * pulse, 2 * rCorona * pulse);

        // 2. Solar prominence flare loops around limb
        if (!reducedMotion) {
            gc.setStroke(Color.web("#FF6B35", 0.6));
            gc.setLineWidth(Math.max(1.0, r * 0.08));
            for (int p = 0; p < 4; p++) {
                double angle = p * (Math.PI / 2.0) + (time * 0.02);
                double px = bx + Math.cos(angle) * (r * 1.12);
                double py = by + Math.sin(angle) * (r * 1.12);
                double pr = r * 0.25;
                gc.strokeOval(px - pr, py - pr, 2 * pr, 2 * pr);
            }
        }

        // 3. Photosphere disc
        RadialGradient starGrad = new RadialGradient(
                0, 0, bx - 0.2 * r, by - 0.2 * r, 1.2 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.WHITE),
                new Stop(0.55, Color.web("#FFF7C2")),
                new Stop(0.85, Color.web("#FFC107")),
                new Stop(1.0, Color.web("#E65100"))
        );
        gc.setFill(starGrad);
        gc.fillOval(bx - r, by - r, 2 * r, 2 * r);

        // 4. Rotating sunspots
        double phi = (rotPeriod > 0) ? ((time / rotPeriod) % 1.0) : 0.0;
        if (phi < 0) phi += 1.0;
        double spotAngle = phi * 2.0 * Math.PI;
        if (Math.cos(spotAngle) > 0) {
            double sx = bx + Math.sin(spotAngle) * (0.6 * r);
            double sy = by - 0.15 * r;
            gc.setFill(Color.web("#8A3800")); // Penumbra
            gc.fillOval(sx - 0.12 * r, sy - 0.08 * r, 0.24 * r, 0.16 * r);
            gc.setFill(Color.web("#3A1400")); // Umbra
            gc.fillOval(sx - 0.06 * r, sy - 0.04 * r, 0.12 * r, 0.08 * r);
        }
    }

    private void drawRedDwarfModel(GraphicsContext gc, double bx, double by, double r, double time) {
        double rCorona = 1.7 * r;
        double pulse = reducedMotion ? 1.0 : (1.0 + 0.05 * Math.sin(time * 0.1));
        RadialGradient coronaGrad = new RadialGradient(
                0, 0, bx, by, rCorona * pulse, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#FF5722", 0.85)),
                new Stop(0.45, Color.web("#C62828", 0.45)),
                new Stop(0.80, Color.web("#4A0000", 0.12)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(coronaGrad);
        gc.fillOval(bx - rCorona * pulse, by - rCorona * pulse, 2 * rCorona * pulse, 2 * rCorona * pulse);

        // Deep crimson / orange-red photosphere disc
        RadialGradient discGrad = new RadialGradient(
                0, 0, bx - 0.2 * r, by - 0.2 * r, 1.2 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#FFE0B2")),
                new Stop(0.45, Color.web("#FF3D00")),
                new Stop(0.85, Color.web("#B71C1C")),
                new Stop(1.0, Color.web("#4A0000"))
        );
        gc.setFill(discGrad);
        gc.fillOval(bx - r, by - r, 2 * r, 2 * r);
    }

    private void drawSaturnModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.translate(bx, by);
        gc.rotate(-18.0);

        double squash = 0.42;

        // 1. Draw BACK RINGS (top half: startAngle 0 to 180 in JavaFX = upper half, y < 0)
        drawSaturnRings(gc, r, squash, 0, 180);

        // Planet's shadow on back rings (casts dark shadow onto top-right section of back rings)
        gc.save();
        gc.beginPath();
        gc.moveTo(0, 0);
        gc.lineTo(1.8 * r, -1.2 * r * squash);
        gc.lineTo(2.4 * r, -0.6 * r * squash);
        gc.lineTo(0.5 * r, 0);
        gc.closePath();
        gc.setFill(Color.color(0, 0, 0, 0.65));
        gc.fill();
        gc.restore();

        // 2. Draw PLANET GLOBE (clipped to circle of radius r)
        gc.save();
        gc.beginPath();
        gc.arc(0, 0, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        // Saturn globe base: butterscotch
        gc.setFill(Color.web("#E4C38E"));
        gc.fillRect(-r, -r, 2 * r, 2 * r);

        // Atmospheric bands
        gc.setFill(Color.web("#D1A76C"));
        gc.fillRect(-r, -0.6 * r, 2 * r, 0.18 * r);
        gc.setFill(Color.web("#BD8E52"));
        gc.fillRect(-r, -0.35 * r, 2 * r, 0.22 * r);
        gc.setFill(Color.web("#F6E2B8")); // Equatorial bright zone
        gc.fillRect(-r, -0.10 * r, 2 * r, 0.24 * r);
        gc.setFill(Color.web("#C59659"));
        gc.fillRect(-r, 0.18 * r, 2 * r, 0.25 * r);

        // North polar hexagon / dusky cap
        gc.setFill(Color.web("#808F85"));
        gc.fillOval(-0.6 * r, -1.05 * r, 1.2 * r, 0.35 * r);

        // Ring shadow cast across planet's northern hemisphere
        gc.setFill(Color.color(0.08, 0.05, 0.02, 0.70));
        gc.fillRect(-r, -0.28 * r, 2 * r, 0.14 * r);

        // 3D spherical shading and limb darkening
        RadialGradient shading = new RadialGradient(
                0, 0, -0.3 * r, -0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.22)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.72, Color.color(0, 0, 0, 0.38)),
                new Stop(1.0, Color.color(0, 0, 0, 0.82))
        );
        gc.setFill(shading);
        gc.fillRect(-r, -r, 2 * r, 2 * r);

        gc.restore(); // end globe clip

        // 3. Draw FRONT RINGS (bottom half: startAngle 180 to 180 in JavaFX = lower half, y > 0)
        drawSaturnRings(gc, r, squash, 180, 180);

        gc.restore(); // end saturn transform
    }

    private void drawSaturnRings(GraphicsContext gc, double r, double squash, double startAngle, double arcExtent) {
        // C Ring (crepe ring, semi-transparent inner)
        double rC = 1.25 * r;
        double wC = 2 * rC;
        double hC = 2 * rC * squash;
        gc.setStroke(Color.web("#997D54", 0.35));
        gc.setLineWidth(Math.max(1.0, 0.15 * r));
        gc.strokeArc(-rC, -rC * squash, wC, hC, startAngle, arcExtent, ArcType.OPEN);

        // B Ring (bright, dense golden-cream inner main ring)
        double rB = 1.62 * r;
        double wB = 2 * rB;
        double hB = 2 * rB * squash;
        gc.setStroke(Color.web("#EAD3A2", 0.95));
        gc.setLineWidth(Math.max(1.0, 0.55 * r));
        gc.strokeArc(-rB, -rB * squash, wB, hB, startAngle, arcExtent, ArcType.OPEN);

        // Cassini Division (dark gap)
        double rCassini = 1.95 * r;
        double wCassini = 2 * rCassini;
        double hCassini = 2 * rCassini * squash;
        gc.setStroke(Color.color(0, 0, 0, 0.85));
        gc.setLineWidth(Math.max(1.0, 0.08 * r));
        gc.strokeArc(-rCassini, -rCassini * squash, wCassini, hCassini, startAngle, arcExtent, ArcType.OPEN);

        // A Ring (silver-tan outer main ring)
        double rA = 2.15 * r;
        double wA = 2 * rA;
        double hA = 2 * rA * squash;
        gc.setStroke(Color.web("#D2BA8E", 0.85));
        gc.setLineWidth(Math.max(1.0, 0.32 * r));
        gc.strokeArc(-rA, -rA * squash, wA, hA, startAngle, arcExtent, ArcType.OPEN);

        // Encke Gap (fine outer gap)
        double rEncke = 2.26 * r;
        double wEncke = 2 * rEncke;
        double hEncke = 2 * rEncke * squash;
        gc.setStroke(Color.color(0, 0, 0, 0.6));
        gc.setLineWidth(Math.max(0.5, 0.03 * r));
        gc.strokeArc(-rEncke, -rEncke * squash, wEncke, hEncke, startAngle, arcExtent, ArcType.OPEN);
    }

    private void drawEarthModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        // 1. Ocean base
        RadialGradient oceanGrad = new RadialGradient(
                0, 0, bx - 0.25 * r, by - 0.25 * r, 1.3 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#1E4B8A")),
                new Stop(0.7, Color.web("#0F2D59")),
                new Stop(1.0, Color.web("#0A1C38"))
        );
        gc.setFill(oceanGrad);
        gc.fillOval(bx - r, by - r, 2 * r, 2 * r);

        // 2. Continents with rotation
        double phi = (rotPeriod > 0) ? ((time / rotPeriod) % 1.0) : 0.0;
        if (phi < 0) phi += 1.0;
        double mapW = 4.0 * r;
        double baseOffsetX = bx - (phi * mapW);

        for (int rep = -1; rep <= 1; rep++) {
            double ox = baseOffsetX + rep * mapW;
            if (ox + mapW < bx - r || ox > bx + r) continue;

            // Africa & Sahara
            gc.setFill(Color.web("#3A7A38"));
            gc.fillOval(ox + 1.8 * r, by - 0.1 * r, 0.65 * r, 0.9 * r);
            gc.setFill(Color.web("#B89456"));
            gc.fillOval(ox + 1.8 * r, by - 0.3 * r, 0.75 * r, 0.35 * r);

            // Eurasia
            gc.setFill(Color.web("#2E6A35"));
            gc.fillOval(ox + 1.9 * r, by - 0.7 * r, 1.2 * r, 0.55 * r);
            gc.fillOval(ox + 2.4 * r, by - 0.5 * r, 0.9 * r, 0.45 * r);

            // Americas (North & South)
            gc.setFill(Color.web("#36753B"));
            gc.fillOval(ox + 0.4 * r, by - 0.65 * r, 0.85 * r, 0.55 * r);
            gc.setFill(Color.web("#997740"));
            gc.fillOval(ox + 0.35 * r, by - 0.55 * r, 0.35 * r, 0.35 * r);
            gc.setFill(Color.web("#25632A"));
            gc.fillOval(ox + 0.8 * r, by + 0.05 * r, 0.55 * r, 0.75 * r);

            // Australia
            gc.setFill(Color.web("#A86A36"));
            gc.fillOval(ox + 2.8 * r, by + 0.3 * r, 0.45 * r, 0.35 * r);
        }

        // 3. Polar Ice Caps
        gc.setFill(Color.web("#F0F8FF", 0.95));
        gc.fillOval(bx - 0.55 * r, by - 1.05 * r, 1.1 * r, 0.32 * r);
        gc.fillOval(bx - 0.65 * r, by + 0.75 * r, 1.3 * r, 0.35 * r);

        // 4. Atmospheric Swirling Clouds
        double cloudPhi = (rotPeriod > 0) ? (((time * 1.08) / rotPeriod) % 1.0) : 0.0;
        if (cloudPhi < 0) cloudPhi += 1.0;
        double cloudBaseX = bx - (cloudPhi * mapW);
        gc.setFill(Color.color(1.0, 1.0, 1.0, 0.45));
        for (int rep = -1; rep <= 1; rep++) {
            double cx = cloudBaseX + rep * mapW;
            if (cx + mapW < bx - r || cx > bx + r) continue;

            gc.fillOval(cx + 0.2 * r, by - 0.05 * r, 0.9 * r, 0.12 * r);
            gc.fillOval(cx + 1.4 * r, by - 0.02 * r, 1.1 * r, 0.14 * r);
            gc.fillOval(cx + 2.7 * r, by - 0.06 * r, 0.8 * r, 0.12 * r);
            gc.fillOval(cx + 0.7 * r, by - 0.45 * r, 0.8 * r, 0.20 * r);
            gc.fillOval(cx + 2.1 * r, by - 0.50 * r, 0.9 * r, 0.22 * r);
            gc.fillOval(cx + 1.0 * r, by + 0.45 * r, 1.0 * r, 0.16 * r);
            gc.fillOval(cx + 2.3 * r, by + 0.50 * r, 0.85 * r, 0.18 * r);
        }

        // 5. Specular ocean glint & 3D limb darkening
        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.30)),
                new Stop(0.35, Color.color(1, 1, 1, 0.0)),
                new Stop(0.70, Color.color(0, 0, 0, 0.40)),
                new Stop(1.0, Color.color(0, 0, 0, 0.85))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();

        // 6. Atmospheric cyan rim glow
        RadialGradient atmoGrad = new RadialGradient(
                0, 0, bx, by, 1.12 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.85, Color.TRANSPARENT),
                new Stop(0.95, Color.web("#4CC3FF", 0.45)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(atmoGrad);
        gc.fillOval(bx - 1.12 * r, by - 1.12 * r, 2.24 * r, 2.24 * r);
    }

    private void drawJupiterModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        // Base cream/tan
        gc.setFill(Color.web("#E4C299"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        // Belts and zones
        gc.setFill(Color.web("#807567"));
        gc.fillRect(bx - r, by - r, 2 * r, 0.25 * r);
        gc.setFill(Color.web("#A36E4A"));
        gc.fillRect(bx - r, by - 0.65 * r, 2 * r, 0.12 * r);
        gc.fillRect(bx - r, by - 0.48 * r, 2 * r, 0.08 * r);
        gc.setFill(Color.web("#873E1E")); // North Equatorial Belt
        gc.fillRect(bx - r, by - 0.35 * r, 2 * r, 0.22 * r);
        gc.setFill(Color.web("#F6ECD6")); // Equatorial Zone
        gc.fillRect(bx - r, by - 0.13 * r, 2 * r, 0.24 * r);
        gc.setFill(Color.web("#8E4222")); // South Equatorial Belt
        gc.fillRect(bx - r, by + 0.11 * r, 2 * r, 0.26 * r);
        gc.setFill(Color.web("#A07251"));
        gc.fillRect(bx - r, by + 0.45 * r, 2 * r, 0.12 * r);
        gc.setFill(Color.web("#7B7163"));
        gc.fillRect(bx - r, by + 0.75 * r, 2 * r, 0.25 * r);

        // Atmospheric boundary festoons
        double phi = (rotPeriod > 0) ? ((time / rotPeriod) % 1.0) : 0.0;
        if (phi < 0) phi += 1.0;
        gc.setFill(Color.web("#C58F61", 0.5));
        for (int k = 0; k < 8; k++) {
            double fx = bx - r + ((k / 8.0 + phi) % 1.0) * 2.0 * r;
            gc.fillOval(fx - 0.12 * r, by - 0.14 * r, 0.24 * r, 0.08 * r);
            gc.fillOval(fx - 0.08 * r, by + 0.08 * r, 0.20 * r, 0.07 * r);
        }

        // Great Red Spot (GRS) rotating across visible disc
        double grsAngle = 2.0 * Math.PI * phi;
        double grsXOffset = Math.sin(grsAngle);
        double grsVisibility = Math.cos(grsAngle);
        if (grsVisibility > -0.2) {
            double spotX = bx + grsXOffset * (0.65 * r);
            double spotY = by + 0.23 * r;
            double spotW = 0.38 * r * Math.max(0.3, grsVisibility);
            double spotH = 0.24 * r;

            gc.setFill(Color.web("#F8DFC2"));
            gc.fillOval(spotX - spotW * 0.6, spotY - spotH * 0.6, spotW * 1.2, spotH * 1.2);
            gc.setFill(Color.web("#BD4324"));
            gc.fillOval(spotX - spotW * 0.5, spotY - spotH * 0.5, spotW, spotH);
            gc.setFill(Color.web("#872710"));
            gc.fillOval(spotX - spotW * 0.25, spotY - spotH * 0.25, spotW * 0.5, spotH * 0.5);
        }

        // 3D spherical shading
        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.22)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.72, Color.color(0, 0, 0, 0.38)),
                new Stop(1.0, Color.color(0, 0, 0, 0.82))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawMarsModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        // Base rust / iron oxide red
        gc.setFill(Color.web("#C8572D"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        double phi = (rotPeriod > 0) ? ((time / rotPeriod) % 1.0) : 0.0;
        if (phi < 0) phi += 1.0;
        double mapW = 4.0 * r;
        double baseOffsetX = bx - (phi * mapW);

        // Dark volcanic basalt markings (Syrtis Major, Sinus Meridiani)
        gc.setFill(Color.web("#542817"));
        for (int rep = -1; rep <= 1; rep++) {
            double ox = baseOffsetX + rep * mapW;
            if (ox + mapW < bx - r || ox > bx + r) continue;

            // Syrtis Major
            gc.fillPolygon(
                    new double[]{ox + 1.2 * r, ox + 1.55 * r, ox + 1.35 * r},
                    new double[]{by - 0.1 * r, by - 0.05 * r, by + 0.35 * r},
                    3
            );
            gc.fillOval(ox + 2.2 * r, by + 0.1 * r, 1.1 * r, 0.35 * r);
            gc.fillOval(ox + 0.3 * r, by + 0.15 * r, 0.8 * r, 0.30 * r);

            // Hellas Planitia
            gc.setFill(Color.web("#E29258"));
            gc.fillOval(ox + 1.6 * r, by + 0.42 * r, 0.45 * r, 0.35 * r);
            gc.setFill(Color.web("#542817"));
        }

        // Polar Ice Caps
        gc.setFill(Color.web("#F5FBFC"));
        gc.fillOval(bx - 0.40 * r, by - 1.02 * r, 0.80 * r, 0.22 * r);
        gc.fillOval(bx - 0.25 * r, by + 0.85 * r, 0.50 * r, 0.18 * r);

        // 3D spherical shading
        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.70, Color.color(0, 0, 0, 0.40)),
                new Stop(1.0, Color.color(0, 0, 0, 0.82))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();

        // Subtle amber atmospheric haze rim
        RadialGradient haze = new RadialGradient(
                0, 0, bx, by, 1.06 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.90, Color.TRANSPARENT),
                new Stop(0.98, Color.web("#DDA77A", 0.25)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(haze);
        gc.fillOval(bx - 1.06 * r, by - 1.06 * r, 2.12 * r, 2.12 * r);
    }

    private void drawMoonModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod, boolean isMercury) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        Color highlandColor = isMercury ? Color.web("#9E9287") : Color.web("#A8A8A8");
        Color mareColor = isMercury ? Color.web("#695F56") : Color.web("#505055");

        gc.setFill(highlandColor);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        double phi = (rotPeriod > 0) ? ((time / rotPeriod) % 1.0) : 0.0;
        if (phi < 0) phi += 1.0;
        double mapW = 4.0 * r;
        double baseOffsetX = bx - (phi * mapW);

        // Maria (dark basaltic plains)
        gc.setFill(mareColor);
        for (int rep = -1; rep <= 1; rep++) {
            double ox = baseOffsetX + rep * mapW;
            if (ox + mapW < bx - r || ox > bx + r) continue;

            gc.fillOval(ox + 0.6 * r, by - 0.5 * r, 0.7 * r, 0.6 * r);
            gc.fillOval(ox + 1.1 * r, by - 0.4 * r, 0.5 * r, 0.5 * r);
            gc.fillOval(ox + 1.4 * r, by - 0.25 * r, 0.45 * r, 0.4 * r);
            gc.fillOval(ox + 1.5 * r, by - 0.05 * r, 0.5 * r, 0.45 * r);
            gc.fillOval(ox + 1.9 * r, by - 0.15 * r, 0.3 * r, 0.25 * r);
        }

        // Tycho crater & ejecta rays
        gc.setFill(Color.web("#E0E0E0", 0.8));
        gc.fillOval(bx - 0.2 * r, by + 0.45 * r, 0.12 * r, 0.12 * r);
        gc.setStroke(Color.web("#FFFFFF", 0.25));
        gc.setLineWidth(1.0);
        gc.strokeLine(bx - 0.2 * r, by + 0.45 * r, bx - 0.6 * r, by + 0.1 * r);
        gc.strokeLine(bx - 0.2 * r, by + 0.45 * r, bx + 0.3 * r, by + 0.7 * r);
        gc.strokeLine(bx - 0.2 * r, by + 0.45 * r, bx - 0.1 * r, by + 0.85 * r);

        // Stark airless 3D spherical terminator
        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.35 * r, by - 0.35 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.35, Color.color(1, 1, 1, 0.0)),
                new Stop(0.65, Color.color(0, 0, 0, 0.55)),
                new Stop(1.0, Color.color(0, 0, 0, 0.95))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawVenusModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#FFF2B8"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.setFill(Color.web("#E4D3A2", 0.45));
        gc.fillRect(bx - r, by - 0.45 * r, 2 * r, 0.25 * r);
        gc.fillRect(bx - r, by + 0.20 * r, 2 * r, 0.25 * r);
        gc.setFill(Color.web("#FBF7EB", 0.60));
        gc.fillRect(bx - r, by - 0.15 * r, 2 * r, 0.30 * r);

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.30)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.70, Color.color(0, 0, 0, 0.30)),
                new Stop(1.0, Color.color(0, 0, 0, 0.70))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawUranusModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        // Delicate rings
        gc.setStroke(Color.web("#CDEBEB", 0.25));
        gc.setLineWidth(Math.max(1.0, r * 0.06));
        gc.strokeOval(bx - 0.4 * r, by - 1.8 * r, 0.8 * r, 3.6 * r);

        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#80D4D4"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.setFill(Color.web("#B3ECEC", 0.35));
        gc.fillRect(bx - r, by - 0.2 * r, 2 * r, 0.4 * r);

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.45, Color.color(1, 1, 1, 0.0)),
                new Stop(0.75, Color.color(0, 0, 0, 0.35)),
                new Stop(1.0, Color.color(0, 0, 0, 0.75))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawNeptuneModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#2658C4"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        // Great Dark Spot
        gc.setFill(Color.web("#142B6E"));
        gc.fillOval(bx - 0.6 * r, by - 0.25 * r, 0.45 * r, 0.25 * r);

        // White methane cirrus cloud streaks
        gc.setFill(Color.web("#E4EDFF", 0.75));
        gc.fillOval(bx - 0.8 * r, by + 0.15 * r, 0.6 * r, 0.08 * r);
        gc.fillOval(bx + 0.1 * r, by + 0.25 * r, 0.7 * r, 0.09 * r);
        gc.fillOval(bx - 0.4 * r, by - 0.38 * r, 0.5 * r, 0.07 * r);

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.45, Color.color(1, 1, 1, 0.0)),
                new Stop(0.75, Color.color(0, 0, 0, 0.35)),
                new Stop(1.0, Color.color(0, 0, 0, 0.80))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawTitanModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#E69238"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.setFill(Color.web("#7A451A", 0.55));
        gc.fillOval(bx - 0.7 * r, by - 1.1 * r, 1.4 * r, 0.45 * r);

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.70, Color.color(0, 0, 0, 0.35)),
                new Stop(1.0, Color.color(0, 0, 0, 0.75))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();

        RadialGradient haze = new RadialGradient(
                0, 0, bx, by, 1.1 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.85, Color.TRANSPARENT),
                new Stop(0.96, Color.web("#FFA540", 0.35)),
                new Stop(1.0, Color.TRANSPARENT)
        );
        gc.setFill(haze);
        gc.fillOval(bx - 1.1 * r, by - 1.1 * r, 2.2 * r, 2.2 * r);
    }

    private void drawIoModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#F6D842"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.setFill(Color.web("#E57A21", 0.65));
        gc.fillOval(bx - 0.6 * r, by - 0.3 * r, 0.5 * r, 0.4 * r);
        gc.fillOval(bx + 0.1 * r, by + 0.1 * r, 0.6 * r, 0.5 * r);

        gc.setFill(Color.web("#2B150A"));
        gc.fillOval(bx - 0.4 * r, by - 0.15 * r, 0.14 * r, 0.14 * r);
        gc.fillOval(bx + 0.3 * r, by + 0.25 * r, 0.16 * r, 0.16 * r);

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.45, Color.color(1, 1, 1, 0.0)),
                new Stop(0.75, Color.color(0, 0, 0, 0.40)),
                new Stop(1.0, Color.color(0, 0, 0, 0.85))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawEuropaModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#EDF4FA"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.setStroke(Color.web("#8A4A28", 0.5));
        gc.setLineWidth(Math.max(1.0, r * 0.06));
        gc.strokeLine(bx - 0.8 * r, by - 0.4 * r, bx + 0.7 * r, by + 0.5 * r);
        gc.strokeLine(bx - 0.6 * r, by + 0.5 * r, bx + 0.6 * r, by - 0.6 * r);
        gc.strokeLine(bx - 0.2 * r, by - 0.8 * r, bx - 0.1 * r, by + 0.7 * r);
        gc.strokeLine(bx + 0.2 * r, by - 0.5 * r, bx + 0.8 * r, by + 0.2 * r);

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.45, Color.color(1, 1, 1, 0.0)),
                new Stop(0.75, Color.color(0, 0, 0, 0.40)),
                new Stop(1.0, Color.color(0, 0, 0, 0.85))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawEnceladusModel(GraphicsContext gc, double bx, double by, double r) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#F8FAFD"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.setStroke(Color.web("#7FB3DF", 0.65));
        gc.setLineWidth(Math.max(1.0, 0.08 * r));
        gc.strokeLine(bx - 0.35 * r, by + 0.65 * r, bx - 0.20 * r, by + 0.90 * r);
        gc.strokeLine(bx - 0.10 * r, by + 0.60 * r, bx + 0.05 * r, by + 0.92 * r);
        gc.strokeLine(bx + 0.15 * r, by + 0.65 * r, bx + 0.30 * r, by + 0.88 * r);

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.70, Color.color(0, 0, 0, 0.35)),
                new Stop(1.0, Color.color(0, 0, 0, 0.80))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawPlutoModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#AC7451"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);
        gc.setFill(Color.web("#543120"));
        gc.fillRect(bx - r, by + 0.1 * r, 2 * r, 0.45 * r);

        double phi = (rotPeriod > 0) ? ((time / rotPeriod) % 1.0) : 0.0;
        if (phi < 0) phi += 1.0;
        double hAngle = phi * 2.0 * Math.PI;
        if (Math.cos(hAngle) > -0.2) {
            double hx = bx + Math.sin(hAngle) * (0.5 * r);
            double hy = by - 0.05 * r;
            gc.setFill(Color.web("#FFF6EB", 0.95));
            gc.fillOval(hx - 0.28 * r, hy - 0.25 * r, 0.32 * r, 0.45 * r);
            gc.fillOval(hx - 0.08 * r, hy - 0.20 * r, 0.30 * r, 0.38 * r);
        }

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.20)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.70, Color.color(0, 0, 0, 0.45)),
                new Stop(1.0, Color.color(0, 0, 0, 0.88))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawCharonModel(GraphicsContext gc, double bx, double by, double r, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(Color.web("#757575"));
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.setFill(Color.web("#864433", 0.85));
        gc.fillOval(bx - 0.5 * r, by - 1.05 * r, 1.0 * r, 0.4 * r);

        gc.setStroke(Color.web("#404040", 0.7));
        gc.setLineWidth(Math.max(1.0, 0.07 * r));
        gc.strokeLine(bx - r, by + 0.1 * r, bx + r, by + 0.05 * r);

        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.22)),
                new Stop(0.40, Color.color(1, 1, 1, 0.0)),
                new Stop(0.70, Color.color(0, 0, 0, 0.45)),
                new Stop(1.0, Color.color(0, 0, 0, 0.90))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        gc.restore();
    }

    private void drawGenericPlanetModel(GraphicsContext gc, double bx, double by, double r, Color bodyColor, double time, double rotPeriod) {
        gc.save();
        gc.beginPath();
        gc.arc(bx, by, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        gc.setFill(bodyColor);
        gc.fillRect(bx - r, by - r, 2 * r, 2 * r);

        if (rotPeriod > 0.0) {
            double phi = (time / rotPeriod) % 1.0;
            if (phi < 0) phi += 1.0;

            // Surface bands (latitude belts)
            gc.setFill(bodyColor.deriveColor(0, 1.15, 0.75, 0.30));
            gc.fillRect(bx - r, by - 0.45 * r, 2 * r, 0.25 * r);
            gc.fillRect(bx - r, by + 0.15 * r, 2 * r, 0.20 * r);
            gc.setFill(bodyColor.deriveColor(0, 0.85, 1.25, 0.20));
            gc.fillRect(bx - r, by - 0.15 * r, 2 * r, 0.22 * r);

            // Rotating longitude meridians
            gc.setStroke(bodyColor.deriveColor(0, 1.2, 0.7, 0.35));
            gc.setLineWidth(Math.max(1.0, r * 0.08));
            for (int m = 0; m < 6; m++) {
                double angle = m * (Math.PI / 3.0) + phi * 2.0 * Math.PI;
                double sin = Math.sin(angle);
                if (sin > 0) {
                    double cos = Math.cos(angle);
                    double mrx = Math.max(0.5, r * cos);
                    gc.strokeOval(bx - mrx, by - r, mrx * 2, r * 2);
                }
            }
        }

        // 3D spherical shading and limb darkening
        RadialGradient shading = new RadialGradient(
                0, 0, bx - 0.3 * r, by - 0.3 * r, 1.4 * r, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.color(1, 1, 1, 0.25)),
                new Stop(0.45, Color.color(1, 1, 1, 0.0)),
                new Stop(0.75, Color.color(0, 0, 0, 0.35)),
                new Stop(1.0, Color.color(0, 0, 0, 0.75))
        );
        gc.setFill(shading);
        gc.fillRect(bx - r, by - r, r * 2, r * 2);

        gc.restore();
    }

    /**
     * Draws the dynamic spacetime curvature fabric across the viewport.
     * Functions like an infinite CAD / Blender / game-engine coordinate grid:
     * <ul>
     *     <li>Grid lines are anchored in world coordinates, stretching smoothly with zoom and pan.</li>
     *     <li>When zoom increases, grid cells stretch and seamlessly subdivide into finer grids via 1-2-5 steps.</li>
     *     <li>Gravitational warping is evaluated for EVERY body in the universe, using scale-coupled potential
     *         so that zooming into any body (e.g. Jupiter, Earth, Moon) reveals its prominent local spacetime indentation.</li>
     *     <li>In an empty universe (N = 0), a flat, unperturbed spacetime grid is rendered and responds to pan/zoom.</li>
     * </ul>
     */
    private void drawSpacetimeMesh(GraphicsContext gc, NBodyState state, double w, double h, double scale, double originX, double originY) {
        if (w <= 0 || h <= 0 || !Double.isFinite(scale) || scale <= 0) return;

        // 1. Determine world grid step using 1-2-5 decade sequence
        double targetWorldSpacing = MESH_TARGET_SPACING_PX / scale;
        if (!Double.isFinite(targetWorldSpacing) || targetWorldSpacing <= 0) return;

        double exp = Math.floor(Math.log10(targetWorldSpacing));
        double base = Math.pow(10.0, exp);
        double fraction = targetWorldSpacing / base;

        double step;
        int subdiv;
        if (fraction < 2.0) {
            step = base;         // 1 x 10^exp
            subdiv = 10;
        } else if (fraction < 5.0) {
            step = 2.0 * base;   // 2 x 10^exp
            subdiv = 5;
        } else {
            step = 5.0 * base;   // 5 x 10^exp
            subdiv = 2;
        }

        double minWorldX = (0.0 - originX) / scale;
        double maxWorldX = (w - originX) / scale;
        double minWorldY = (originY - h) / scale;
        double maxWorldY = (originY - 0.0) / scale;

        long cMin = (long) Math.floor(minWorldX / step) - 1;
        long cMax = (long) Math.ceil(maxWorldX / step) + 1;
        long rMin = (long) Math.floor(minWorldY / step) - 1;
        long rMax = (long) Math.ceil(maxWorldY / step) + 1;

        int cols = (int) (cMax - cMin + 1);
        int rows = (int) (rMax - rMin + 1);
        if (cols < 2 || rows < 2 || cols > 100 || rows > 100) return;

        double[][] dispX = new double[cols][rows];
        double[][] dispY = new double[cols][rows];

        int n = (state != null) ? state.getN() : 0;
        double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

        // Precompute screen positions, scale-coupled potential strengths, and softening radiuses for all bodies
        double[] bx = new double[n];
        double[] by = new double[n];
        double[] vStrength = new double[n];
        double[] eps2 = new double[n];

        for (int i = 0; i < n; i++) {
            bx[i] = originX + state.positionX[i] * scale;
            by[i] = originY - state.positionY[i] * scale;
            double rWorld = Math.max(state.radius[i], 1.0);
            double rScreen = rWorld * scale;
            double phiSurf = (G * state.mass[i]) / rWorld; // Surface potential (m^2/s^2)

            // Scaled perceptual visual potential: phiSurf^0.65 * rScreen
            vStrength[i] = Math.pow(phiSurf, 0.65) * rScreen;

            double eps;
            if (state.isCompactObject(i, G)) {
                eps = Math.max(rScreen * 0.8, 5.0);
            } else {
                eps = Math.max(rScreen * 0.9, 12.0);
            }
            eps2[i] = eps * eps;
        }

        for (int c = 0; c < cols; c++) {
            double wx = (cMin + c) * step;
            double px = originX + wx * scale;
            for (int r = 0; r < rows; r++) {
                double wy = (rMin + r) * step;
                double py = originY - wy * scale;

                double dxTotal = 0.0;
                double dyTotal = 0.0;

                for (int i = 0; i < n; i++) {
                    double rx = bx[i] - px;
                    double ry = by[i] - py;
                    double dist2 = rx * rx + ry * ry;
                    if (dist2 < 0.01) continue;
                    double d = Math.sqrt(dist2);

                    // Scale-invariant screen coupling: local potential gradient
                    double denom = Math.pow(dist2 + eps2[i], 0.75);
                    double rawPull = MESH_COUPLING_FACTOR * (vStrength[i] / denom);

                    // Soft-saturate and prevent crossing over body center
                    double bodyPull = Math.min(d * 0.65, MESH_MAX_DISPLACEMENT_PX * Math.tanh(rawPull / MESH_MAX_DISPLACEMENT_PX));

                    dxTotal += (rx / d) * bodyPull;
                    dyTotal += (ry / d) * bodyPull;
                }

                double totalDispMag = Math.hypot(dxTotal, dyTotal);
                if (totalDispMag > MESH_MAX_DISPLACEMENT_PX) {
                    double cap = MESH_MAX_DISPLACEMENT_PX / totalDispMag;
                    dxTotal *= cap;
                    dyTotal *= cap;
                }

                dispX[c][r] = px + dxTotal;
                dispY[c][r] = py + dyTotal;
            }
        }

        // Screen spacing and fade factor for smooth subdivision transition
        double screenSpacing = step * scale;
        double t = (screenSpacing - 25.0) / (65.0 - 25.0);
        t = Math.max(0.0, Math.min(1.0, t));
        double minorAlpha = 0.40 + 0.45 * t;

        // 1. Draw Minor grid lines
        gc.setLineWidth(0.5);
        gc.setStroke(Color.color(0.13, 0.13, 0.20, minorAlpha));

        // Horizontal minor lines
        for (int r = 0; r < rows; r++) {
            long lineIndex = rMin + r;
            if (Math.floorMod(lineIndex, subdiv) == 0) continue;
            gc.beginPath();
            gc.moveTo(dispX[0][r], dispY[0][r]);
            for (int c = 1; c < cols; c++) {
                gc.lineTo(dispX[c][r], dispY[c][r]);
            }
            gc.stroke();
        }

        // Vertical minor lines
        for (int c = 0; c < cols; c++) {
            long lineIndex = cMin + c;
            if (Math.floorMod(lineIndex, subdiv) == 0) continue;
            gc.beginPath();
            gc.moveTo(dispX[c][0], dispY[c][0]);
            for (int r = 1; r < rows; r++) {
                gc.lineTo(dispX[c][r], dispY[c][r]);
            }
            gc.stroke();
        }

        // 2. Draw Major grid lines
        gc.setLineWidth(0.85);
        gc.setStroke(Color.web("#32324A"));

        // Horizontal major lines
        for (int r = 0; r < rows; r++) {
            long lineIndex = rMin + r;
            if (Math.floorMod(lineIndex, subdiv) != 0) continue;
            gc.beginPath();
            gc.moveTo(dispX[0][r], dispY[0][r]);
            for (int c = 1; c < cols; c++) {
                gc.lineTo(dispX[c][r], dispY[c][r]);
            }
            gc.stroke();
        }

        // Vertical major lines
        for (int c = 0; c < cols; c++) {
            long lineIndex = cMin + c;
            if (Math.floorMod(lineIndex, subdiv) != 0) continue;
            gc.beginPath();
            gc.moveTo(dispX[c][0], dispY[c][0]);
            for (int r = 1; r < rows; r++) {
                gc.lineTo(dispX[c][r], dispY[c][r]);
            }
            gc.stroke();
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
     * The hovered/selected/watched-body inspector: name, mass/radius,
     * position, velocity — every numeric field in {@code %.3e}, per this
     * class's javadoc. Shared by all three cases (see {@link #draw}),
     * distinguished only by a label prefix and the explicit {@code
     * anchorTopRight} flag — hover and the round 1.2 "Watching: " info body
     * both anchor beside the body itself (like the pendulum's bob
     * inspector); only "Selected: " anchors at a fixed top-right box, since
     * that's the one case tied to a halo elsewhere on the body itself.
     */
    private void drawBodyHud(GraphicsContext gc, NBodyState state, int body,
                              double scale, double originX, double originY, double canvasW,
                              String labelPrefix, boolean anchorTopRight) {
        if (body < 0 || body >= state.getN()) return;

        double bx = originX + state.positionX[body] * scale;
        double by = originY - state.positionY[body] * scale;

        Font font = FONT_HUD;
        gc.setFont(font);

        String line1 = labelPrefix + state.name[body];
        String line2 = String.format("m=%.3e kg   r=%.3e m", state.mass[body], state.radius[body]);
        String line3 = String.format("x=%+.3e  y=%+.3e m", state.positionX[body], state.positionY[body]);
        String line4 = String.format("vx=%+.3e  vy=%+.3e m/s", state.velocityX[body], state.velocityY[body]);
        String line5;
        if (state.hasMagneticField(body)) {
            double b0 = state.equatorialFieldMicroTesla(body);
            double tilt = (state.magneticTiltDegrees != null && body < state.magneticTiltDegrees.length)
                    ? state.magneticTiltDegrees[body] : 0.0;
            double standoff = MagnetopauseCalculator.computeStandoff(body, state);
            double rBody = state.radius[body];
            double standoffRatio = (rBody > 0) ? (standoff / rBody) : 0.0;
            line5 = String.format("B₀=%.1f µT  tilt=%.1f°  Rmp=%.1f R_body", b0, tilt, standoffRatio);
        } else {
            line5 = "B₀=0.0 µT (inactive dynamo)";
        }

        double boxW = hudBoxWidth(font, line1, line2, line3, line4, line5);
        double boxH = 94;
        double boxX, boxY;
        if (!anchorTopRight) {
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
        gc.fillText(line5, boxX + HUD_TEXT_PADDING, boxY + 87);
    }
}
