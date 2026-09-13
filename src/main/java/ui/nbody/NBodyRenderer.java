package ui.nbody;

import physics.nbody.NBodyConfig;
import physics.nbody.NBodyState;
import ui.simcore.Camera;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
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

        drawSpacetimeMesh(gc, state, w, h, scale, originX, originY);

        if (state.getN() > 0) {
            ensureTrailCapacity(state.getN());
            recordTrailPoints(state);
            drawTrails(gc, state, scale, originX, originY);

            drawBodies(gc, state, scale, originX, originY);
            drawSelectionHalo(gc, state, scale, originX, originY, selectedBody);
            drawBodyHud(gc, state, hoveredBody, scale, originX, originY, w, "", false);
            if (selectedBody != hoveredBody) drawBodyHud(gc, state, selectedBody, scale, originX, originY, w, "Selected: ", true);
            if (infoBody != hoveredBody && infoBody != selectedBody) drawBodyHud(gc, state, infoBody, scale, originX, originY, w, "Watching: ", false);
        }

        drawStatusOverlay(gc, state);
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

    private void drawBodies(GraphicsContext gc, NBodyState state, double scale, double originX, double originY) {
        double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;
        for (int i = 0; i < state.getN(); i++) {
            double bx = originX + state.positionX[i] * scale;
            double by = originY - state.positionY[i] * scale;
            double r = radiusForBody(state, i);
            double dScreen = 2.0 * r;
            Color bodyColor = bodyColors[i % bodyColors.length];

            if (dScreen < 10.0) {
                // LOD 0: solid anti-aliased circle with soft photometric glow halo
                double rGlow = r + 3.0;
                gc.setFill(bodyColor.deriveColor(0, 1.0, 1.0, 0.25));
                gc.fillOval(bx - rGlow, by - rGlow, rGlow * 2, rGlow * 2);

                gc.setFill(bodyColor);
                gc.fillOval(bx - r, by - r, r * 2, r * 2);
            } else {
                // LOD 1: dScreen >= 10 px
                if (state.isCompactObject(i, G)) {
                    // Black hole: pitch-black core, glowing photon ring & lensing halo
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
                } else if (state.isStar(i)) {
                    // Fusion star: brilliant white/yellow disc with luminous coronal flare halo expanding 1.8x r
                    double rCorona = 1.8 * r;
                    RadialGradient coronaGrad = new RadialGradient(
                            0, 0, bx, by, rCorona, false, CycleMethod.NO_CYCLE,
                            new Stop(0.0, Color.web("#FFF5C0", 0.75)),
                            new Stop(0.50, Color.web("#FF9E3D", 0.40)),
                            new Stop(0.80, Color.web("#EA3F8C", 0.15)),
                            new Stop(1.0, Color.TRANSPARENT)
                    );
                    gc.setFill(coronaGrad);
                    gc.fillOval(bx - rCorona, by - rCorona, rCorona * 2, rCorona * 2);

                    RadialGradient starGrad = new RadialGradient(
                            0, 0, bx, by, r, false, CycleMethod.NO_CYCLE,
                            new Stop(0.0, Color.WHITE),
                            new Stop(0.65, Color.web("#FFFBE0")),
                            new Stop(1.0, Color.web("#FFD147"))
                    );
                    gc.setFill(starGrad);
                    gc.fillOval(bx - r, by - r, r * 2, r * 2);
                } else {
                    // Planet/moon/body: rotating spherical billboard
                    gc.save();
                    gc.beginPath();
                    gc.arc(bx, by, r, r, 0, 360);
                    gc.closePath();
                    gc.clip();

                    gc.setFill(bodyColor);
                    gc.fillRect(bx - r, by - r, r * 2, r * 2);

                    double rotPeriod = (state.rotationPeriod != null && i < state.rotationPeriod.length)
                            ? state.rotationPeriod[i] : 0.0;
                    if (rotPeriod > 0.0) {
                        double phi = (state.time / rotPeriod) % 1.0;
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

                    // 3D spherical shading and limb darkening (applies to both rotating and unassigned period)
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
            }
        }
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

        double boxW = hudBoxWidth(font, line1, line2, line3, line4);
        double boxH = 76;
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
    }
}
