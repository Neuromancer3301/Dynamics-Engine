package ui;

import physics.SimState;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Real-time analytics graph panel rendered on a JavaFX Canvas.
 *
 * Supports six graph modes:
 *  1. ANGLE        — θ₁(t): first link angle vs time (scrolling window)
 *  2. ENERGY       — E(t): total, kinetic, potential energy vs time
 *  3. PHASE        — Phase portrait (θ₁, ω₁): angle vs angular velocity
 *  4. ALL          — small multiples: all three above, stacked and compact
 *  5. POINCARE     — (θ₂, ω₂) sampled every time θ₁ crosses zero rising;
 *                    accumulates indefinitely rather than scrolling, and
 *                    needs N &ge; 2 to have a second link to sample
 *  6. COMPARISON   — energy drift over time for several integrators run
 *                    from the same starting state; fed externally via
 *                    {@link #setComparisonData}, not from addDataPoint
 *  7. BIFURCATION  — classic bifurcation diagram: last link's angle at each
 *                    θ₁ zero-crossing, one column per swept initial angle;
 *                    fed externally via {@link #setBifurcationData}, computed
 *                    by {@code physics.BifurcationSweep}
 *
 * ANGLE/ENERGY/PHASE/ALL share one ring buffer of fixed capacity.
 */
public final class GraphPanel extends Canvas {

    /** Graph display modes. */
    public enum Mode { ANGLE, ENERGY, PHASE, ALL, POINCARE, COMPARISON, BIFURCATION }

    /** One integrator's energy-drift-over-time trace for {@link Mode#COMPARISON}. */
    public record ComparisonSeries(String name, double[] times, double[] energyDrift, Color color) {}

    // ---- Layout constants ----
    private static final int    MARGIN  = 52;
    private static final int    MAX_PTS = 800;
    private static final double TIME_WINDOW = 12.0; // seconds visible in time-series modes

    // Poincaré points accumulate for the life of the run (cleared only by
    // clear()), so this bounds memory rather than controlling what's visible.
    private static final int MAX_POINCARE_POINTS = 5000;

    // ---- Colors ----
    // "Signal" palette: any single-focus mode (one live trace or one
    // accumulating point cloud) reads in the app's one accent magenta —
    // ANGLE, PHASE, POINCARE, and BIFURCATION are all exactly that. ENERGY
    // is the one graph that's genuinely multi-series (Total/KE/PE at once),
    // so it draws from the same real oscilloscope multi-channel colors
    // (magenta/cyan/yellow) ui.PendulumCanvas's bob palette and A/B compare
    // chain use — see this file's and that one's own comments.
    private static final Color C_ANGLE  = Color.web("#EA3F8C");   // magenta (accent)
    private static final Color C_ENERGY = Color.web("#EA3F8C");   // magenta — Total, the headline series
    private static final Color C_KE     = Color.web("#3DDCC7");   // cyan — matches A/B compare's "second channel"
    private static final Color C_PE     = Color.web("#E8D34A");   // yellow — third scope channel
    private static final Color C_PHASE  = Color.web("#EA3F8C");   // magenta (accent)
    private static final Color C_POINCARE = Color.web("#EA3F8C"); // magenta (accent)

    private Mode mode = Mode.ANGLE;

    /**
     * Ring buffer entry: [time, theta1, omega1, totalE, KE, PE, theta2, omega2].
     * theta2/omega2 are 0 for an N=1 configuration — only POINCARE mode reads them.
     */
    private final Deque<double[]> data = new ArrayDeque<>();

    // Poincaré section accumulator — deliberately separate from `data`
    // above: `data` is a scrolling window (evicted at MAX_PTS), but a
    // Poincaré map is only useful if it keeps every crossing for the whole
    // run. Detected incrementally in addDataPoint as each new sample arrives.
    private final List<double[]> poincarePoints = new ArrayList<>(); // {theta2, omega2}
    private double prevTheta1 = Double.NaN;

    private List<ComparisonSeries> comparisonSeries = List.of();

    // Bifurcation diagram data — see setBifurcationData. Parallel arrays:
    // bifurcationParams[c] is the swept initial angle for column c,
    // bifurcationSamples.get(c) is that column's collected sample points.
    private double[] bifurcationParams = new double[0];
    private List<double[]> bifurcationSamples = List.of();

    // Decouples "how often render() is called" from "how often it actually
    // redraws." Every mutator below sets this; render() clears it after
    // drawing and bails out immediately when nothing has changed since the
    // last frame it actually painted. This lets a caller poll render() on
    // every AnimationTimer tick (e.g. 60Hz) without needing to hand-roll its
    // own frame-skipping to avoid redrawing an unchanged ~800-point series.
    private boolean dirty = true;

    public GraphPanel(double width, double height) {
        super(width, height);
    }

    public void setMode(Mode newMode) {
        // No data.clear() here: every mode reads from the same ring buffer
        // entry — recorded on every addDataPoint() call regardless of which
        // mode is active — so switching modes has no reason to discard
        // history you were watching build up.
        this.mode = newMode;
        dirty = true;
    }

    /**
     * Ingest a new state snapshot. Call from the JavaFX Application Thread.
     */
    public void addDataPoint(SimState state) {
        if (state == null) return;
        boolean hasSecondLink = state.getN() > 1;
        double theta1 = state.angles[0];
        double theta2 = hasSecondLink ? state.angles[1] : 0.0;
        double omega2 = hasSecondLink ? state.angularVelocities[1] : 0.0;

        data.addLast(new double[]{
            state.time,
            theta1,
            state.angularVelocities[0],
            state.totalEnergy,
            state.kineticEnergy,
            state.potentialEnergy,
            theta2,
            omega2
        });
        while (data.size() > MAX_PTS) data.removeFirst();

        // Rising zero-crossing of theta1: prev < 0, current >= 0. Sampled at
        // this method's call rate (~30Hz from the render loop, not the
        // physics engine's own 500Hz) — coarser than a dedicated Poincaré
        // integration would use, but consistent with how every other mode
        // here already treats the same sampled buffer as its data source.
        if (hasSecondLink && !Double.isNaN(prevTheta1) && prevTheta1 < 0 && theta1 >= 0) {
            poincarePoints.add(new double[]{theta2, omega2});
            if (poincarePoints.size() > MAX_POINCARE_POINTS) poincarePoints.remove(0);
        }
        prevTheta1 = theta1;
        dirty = true;
    }

    /** Supplies the traces {@link Mode#COMPARISON} draws. See controller.SimulationController for how these are computed. */
    public void setComparisonData(List<ComparisonSeries> series) {
        this.comparisonSeries = series;
        dirty = true;
    }

    /** Supplies the columns {@link Mode#BIFURCATION} draws. See {@code physics.BifurcationSweep}. */
    public void setBifurcationData(double[] paramValues, List<double[]> samples) {
        this.bifurcationParams = paramValues;
        this.bifurcationSamples = samples;
        dirty = true;
    }

    /**
     * Redraw, but only if something has changed since the last call actually
     * painted a frame — see {@link #dirty}. Safe to call unconditionally
     * from an AnimationTimer at any cadence.
     */
    public void render() {
        if (!dirty) return;
        dirty = false;

        GraphicsContext gc = getGraphicsContext2D();
        double W = getWidth();
        double H = getHeight();

        if (mode == Mode.ALL) {
            drawBackground(gc, W, H);
            renderSmallMultiples(gc, W, H);
            return;
        }

        double plotW = W - MARGIN * 2;
        double plotH = H - MARGIN * 2 - 20; // room for title at top

        drawBackground(gc, W, H);
        drawPlotArea(gc, plotW, plotH);

        switch (mode) {
            case ANGLE      -> { if (data.size() >= 2) drawAngleSeries(gc, plotW, plotH); }
            case ENERGY     -> { if (data.size() >= 2) drawEnergySeries(gc, plotW, plotH); }
            case PHASE      -> { if (data.size() >= 2) drawPhasePortrait(gc, plotW, plotH); }
            case POINCARE     -> drawPoincareSection(gc, plotW, plotH);
            case COMPARISON   -> drawComparison(gc, plotW, plotH);
            case BIFURCATION  -> drawBifurcation(gc, plotW, plotH);
            case ALL          -> { /* handled above, before the early return */ }
        }

        drawAxes(gc, W, H, plotW, plotH);
        drawTitle(gc, plotW);
    }

    public void clear() {
        data.clear();
        poincarePoints.clear();
        prevTheta1 = Double.NaN;
        dirty = true;
    }

    /**
     * Writes the currently-buffered time series to a CSV file — whatever
     * {@link #addDataPoint} has accumulated in {@code data} (up to {@link
     * #MAX_PTS} most recent samples), independent of which {@link Mode} is
     * currently displayed, so a user can export the raw angle/energy/omega
     * columns even while looking at, say, the phase portrait.
     *
     * <p>Hand-rolled rather than pulling in a CSV library, matching this
     * project's existing zero-third-party-dependency stance for something
     * this small (see {@code physics.MiniJson}).
     */
    public void exportCsv(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("time,theta1,omega1,totalEnergy,kineticEnergy,potentialEnergy,theta2,omega2\n");
        for (double[] row : data) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(row[i]);
            }
            sb.append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    /** True once at least one sample has been recorded — gates whether an export button should be enabled. */
    public boolean hasData() {
        return !data.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Drawing sub-routines
    // -------------------------------------------------------------------------

    private void drawBackground(GraphicsContext gc, double W, double H) {
        gc.setFill(Color.web("#08080B"));
        gc.fillRect(0, 0, W, H);
    }

    private void drawPlotArea(GraphicsContext gc, double plotW, double plotH) {
        // Plot background
        gc.setFill(Color.web("#101014"));
        gc.fillRect(MARGIN, MARGIN + 20, plotW, plotH);

        // Grid lines
        gc.setStroke(Color.web("#232329", 0.7));
        gc.setLineWidth(0.5);
        int gridN = 5;
        for (int i = 0; i <= gridN; i++) {
            double xf = (double) i / gridN;
            double yf = (double) i / gridN;
            gc.strokeLine(MARGIN + xf * plotW, MARGIN + 20,
                          MARGIN + xf * plotW, MARGIN + 20 + plotH);
            gc.strokeLine(MARGIN, MARGIN + 20 + yf * plotH,
                          MARGIN + plotW, MARGIN + 20 + yf * plotH);
        }
    }

    // ---- Mode: Angle vs Time ----
    private void drawAngleSeries(GraphicsContext gc, double plotW, double plotH) {
        if (peekLast() == null) return;

        double[] bounds = windowBounds();
        double tMin = bounds[0], tMax = bounds[1];

        // Y range: θ in [−π, π]
        double yMin = -Math.PI;
        double yMax =  Math.PI;

        // Zero line
        drawZeroLine(gc, plotW, plotH, yMin, yMax);
        drawAxisTicks(gc, plotW, plotH, tMin, tMax, yMin, yMax);

        gc.setLineWidth(1.8);
        gc.setStroke(C_ANGLE);
        gc.beginPath();
        boolean first = true;
        for (double[] pt : data) {
            double t = pt[0];
            if (t < tMin || t > tMax) continue;
            double sx = toScreenX(t, tMin, tMax, plotW);
            double sy = toScreenY(pt[1], yMin, yMax, plotH);
            if (first) { gc.moveTo(sx, sy); first = false; }
            else         gc.lineTo(sx, sy);
        }
        gc.stroke();
    }

    // ---- Mode: Energy vs Time ----
    private void drawEnergySeries(GraphicsContext gc, double plotW, double plotH) {
        if (peekLast() == null) return;

        double[] bounds = windowBounds();
        double tMin = bounds[0], tMax = bounds[1];

        // Auto-scale Y from data in visible window
        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (double[] pt : data) {
            if (pt[0] < tMin || pt[0] > tMax) continue;
            yMin = Math.min(yMin, Math.min(pt[5], Math.min(pt[4], pt[3])));
            yMax = Math.max(yMax, Math.max(pt[3], Math.max(pt[4], 0)));
        }
        double pad = Math.max(0.5, (yMax - yMin) * 0.1);
        yMin -= pad; yMax += pad;

        drawAxisTicks(gc, plotW, plotH, tMin, tMax, yMin, yMax);

        // Draw three series: Total, KE, PE
        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 3, C_ENERGY, 2.0); // total
        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 4, C_KE,     1.2); // KE
        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 5, C_PE,     1.2); // PE

        // Legend
        gc.setFont(Font.font("Monospaced", 14));
        gc.setFill(C_ENERGY); gc.fillText("─ Total", MARGIN + 6, MARGIN + 30);
        gc.setFill(C_KE);     gc.fillText("─ KE",    MARGIN + 6, MARGIN + 42);
        gc.setFill(C_PE);     gc.fillText("─ PE",    MARGIN + 6, MARGIN + 54);
    }

    private void drawSeries(GraphicsContext gc, double tMin, double tMax,
                            double yMin, double yMax,
                            double plotW, double plotH,
                            int fieldIdx, Color color, double lineWidth) {
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        gc.beginPath();
        boolean first = true;
        for (double[] pt : data) {
            if (pt[0] < tMin || pt[0] > tMax) continue;
            double sx = toScreenX(pt[0], tMin, tMax, plotW);
            double sy = toScreenY(pt[fieldIdx], yMin, yMax, plotH);
            if (first) { gc.moveTo(sx, sy); first = false; }
            else         gc.lineTo(sx, sy);
        }
        gc.stroke();
    }

    // ---- Mode: Phase Portrait ----
    private void drawPhasePortrait(GraphicsContext gc, double plotW, double plotH) {
        // Compute data bounds
        double thetaMin =  Double.MAX_VALUE, thetaMax = -Double.MAX_VALUE;
        double omegaMin =  Double.MAX_VALUE, omegaMax = -Double.MAX_VALUE;
        for (double[] pt : data) {
            thetaMin = Math.min(thetaMin, pt[1]); thetaMax = Math.max(thetaMax, pt[1]);
            omegaMin = Math.min(omegaMin, pt[2]); omegaMax = Math.max(omegaMax, pt[2]);
        }

        // Symmetrise and pad
        double thetaRange = Math.max(thetaMax - thetaMin, 0.2);
        double omegaRange = Math.max(omegaMax - omegaMin, 0.2);
        double thetaPad = thetaRange * 0.08, omegaPad = omegaRange * 0.08;
        thetaMin -= thetaPad; thetaMax += thetaPad;
        omegaMin -= omegaPad; omegaMax += omegaPad;

        // Zero lines
        drawZeroLine(gc, plotW, plotH, omegaMin, omegaMax);

        // Phase path with colour-fading by age
        int total = data.size();
        int idx   = 0;
        double[] prev = null;

        for (double[] pt : data) {
            double sx = toScreenX(pt[1], thetaMin, thetaMax, plotW);
            double sy = toScreenY(pt[2], omegaMin, omegaMax, plotH);

            if (prev != null) {
                double alpha = 0.10 + 0.80 * ((double) idx / total);
                gc.setStroke(C_PHASE.deriveColor(0, 1, 1, alpha));
                gc.setLineWidth(1.2);
                gc.strokeLine(prev[0], prev[1], sx, sy);
            }
            prev = new double[]{sx, sy};
            idx++;
        }

        // Current point marker
        if (prev != null) {
            gc.setFill(Color.WHITE);
            gc.fillOval(prev[0] - 3, prev[1] - 3, 6, 6);
        }

        // Axis labels for phase portrait
        gc.setFont(Font.font("Monospaced", 13));
        gc.setFill(Color.web("#8A8A96"));
        gc.fillText(String.format("θ  [%.2f, %.2f] rad", thetaMin, thetaMax),
                    MARGIN + 4, MARGIN + 20 + plotH + 15);
        gc.fillText(String.format("ω  [%.2f, %.2f] rad/s", omegaMin, omegaMax),
                    MARGIN + 4, MARGIN + 20 + plotH + 26);
    }

    // ---- Mode: Poincaré Section ----
    private void drawPoincareSection(GraphicsContext gc, double plotW, double plotH) {
        if (poincarePoints.isEmpty()) {
            gc.setFont(Font.font("System", 15));
            gc.setFill(Color.web("#8A8A96"));
            gc.fillText("Accumulating — a point is plotted each time θ₁",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 - 8);
            gc.fillText("crosses zero moving forward (needs N ≥ 2)",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 + 8);
            return;
        }

        double thetaMin = Double.MAX_VALUE, thetaMax = -Double.MAX_VALUE;
        double omegaMin = Double.MAX_VALUE, omegaMax = -Double.MAX_VALUE;
        for (double[] pt : poincarePoints) {
            thetaMin = Math.min(thetaMin, pt[0]); thetaMax = Math.max(thetaMax, pt[0]);
            omegaMin = Math.min(omegaMin, pt[1]); omegaMax = Math.max(omegaMax, pt[1]);
        }
        double thetaRange = Math.max(thetaMax - thetaMin, 0.2);
        double omegaRange = Math.max(omegaMax - omegaMin, 0.2);
        thetaMin -= thetaRange * 0.08; thetaMax += thetaRange * 0.08;
        omegaMin -= omegaRange * 0.08; omegaMax += omegaRange * 0.08;

        drawZeroLine(gc, plotW, plotH, omegaMin, omegaMax);

        gc.setFill(C_POINCARE.deriveColor(0, 1, 1, 0.6));
        for (double[] pt : poincarePoints) {
            double sx = toScreenX(pt[0], thetaMin, thetaMax, plotW);
            double sy = toScreenY(pt[1], omegaMin, omegaMax, plotH);
            gc.fillOval(sx - 1.5, sy - 1.5, 3, 3);
        }

        gc.setFont(Font.font("Monospaced", 13));
        gc.setFill(Color.web("#8A8A96"));
        gc.fillText(String.format("%d crossings   θ₂ [%.2f, %.2f]   ω₂ [%.2f, %.2f]",
                        poincarePoints.size(), thetaMin, thetaMax, omegaMin, omegaMax),
                MARGIN + 4, MARGIN + 20 + plotH + 15);
    }

    // ---- Mode: Integrator Comparison ----
    private void drawComparison(GraphicsContext gc, double plotW, double plotH) {
        if (comparisonSeries.isEmpty()) {
            gc.setFont(Font.font("System", 15));
            gc.setFill(Color.web("#8A8A96"));
            gc.fillText("Press \"Compare Integrators\" in the sidebar to run this.",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0);
            return;
        }

        double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
        double yMin = 0, yMax = -Double.MAX_VALUE;
        for (ComparisonSeries s : comparisonSeries) {
            for (double t : s.times())        { tMin = Math.min(tMin, t); tMax = Math.max(tMax, t); }
            for (double e : s.energyDrift())  { yMax = Math.max(yMax, e); }
        }
        if (tMax <= tMin) tMax = tMin + 1;
        yMax = Math.max(yMax, 1e-9) * 1.1;

        drawAxisTicks(gc, plotW, plotH, tMin, tMax, yMin, yMax);

        double legendY = MARGIN + 30;
        gc.setFont(Font.font("Monospaced", 14));
        for (ComparisonSeries s : comparisonSeries) {
            gc.setStroke(s.color());
            gc.setLineWidth(1.6);
            gc.beginPath();
            boolean first = true;
            for (int i = 0; i < s.times().length; i++) {
                double sx = toScreenX(s.times()[i], tMin, tMax, plotW);
                double sy = toScreenY(s.energyDrift()[i], yMin, yMax, plotH);
                if (first) { gc.moveTo(sx, sy); first = false; } else gc.lineTo(sx, sy);
            }
            gc.stroke();

            gc.setFill(s.color());
            gc.fillText("─ " + s.name(), MARGIN + 6, legendY);
            legendY += 12;
        }
    }

    // ---- Mode: Bifurcation Diagram ----
    private void drawBifurcation(GraphicsContext gc, double plotW, double plotH) {
        if (bifurcationParams.length == 0) {
            gc.setFont(Font.font("System", 15));
            gc.setFill(Color.web("#8A8A96"));
            gc.fillText("Press \"Generate Bifurcation Map\" in the sidebar to run this.",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 - 8);
            gc.fillText("Sweeps the first link's initial angle — takes a while.",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 + 8);
            return;
        }

        double xMin = bifurcationParams[0], xMax = bifurcationParams[bifurcationParams.length - 1];
        if (xMax <= xMin) xMax = xMin + 1;
        double yMin = -Math.PI, yMax = Math.PI; // angles are already wrapped into this range by PhysicsEngine

        drawAxisTicks(gc, plotW, plotH, xMin, xMax, yMin, yMax);

        gc.setFill(C_ANGLE.deriveColor(0, 1, 1, 0.35));
        for (int c = 0; c < bifurcationParams.length; c++) {
            double sx = toScreenX(bifurcationParams[c], xMin, xMax, plotW);
            for (double y : bifurcationSamples.get(c)) {
                double sy = toScreenY(y, yMin, yMax, plotH);
                gc.fillOval(sx - 0.75, sy - 0.75, 1.5, 1.5);
            }
        }

        gc.setFont(Font.font("Monospaced", 13));
        gc.setFill(Color.web("#8A8A96"));
        gc.fillText(String.format("θ₁ initial swept [%.2f, %.2f] rad · y = last link's angle at each θ₁ crossing",
                        xMin, xMax),
                MARGIN + 4, MARGIN + 20 + plotH + 15);
    }

    // -------------------------------------------------------------------------
    // Mode: ALL — small multiples
    // -------------------------------------------------------------------------
    //
    // Deliberately a separate, self-contained set of mini-drawing routines
    // rather than reusing drawAngleSeries/drawEnergySeries/drawPhasePortrait
    // with a smaller margin threaded through: those three are built around
    // the instance-level MARGIN constant and the full canvas origin, and
    // retrofitting three independent coordinate systems through them would
    // touch more surface than duplicating the (much simpler) plotting logic
    // here, scoped to an explicit (x0, y0, w, h) band.

    private static final double MINI_MARGIN   = 6;
    private static final double MINI_TITLE_H  = 14;

    private void renderSmallMultiples(GraphicsContext gc, double W, double H) {
        double bandH = H / 3.0;
        drawMiniAngle(gc, 0, bandH, W);
        drawMiniEnergy(gc, bandH, bandH, W);
        drawMiniPhase(gc, bandH * 2, bandH, W);

        gc.setStroke(Color.web("#2E2E36"));
        gc.setLineWidth(1.0);
        gc.strokeLine(0, bandH, W, bandH);
        gc.strokeLine(0, bandH * 2, W, bandH * 2);
    }

    private void drawMiniAngle(GraphicsContext gc, double y0, double h, double W) {
        drawMiniBackground(gc, y0, h, W, "θ₁(t)");
        if (data.size() < 2) return;

        double plotX = MINI_MARGIN, plotY = y0 + MINI_TITLE_H;
        double plotW = W - MINI_MARGIN * 2, plotH = h - MINI_TITLE_H - MINI_MARGIN;

        double[] bounds = windowBounds();
        double tMin = bounds[0], tMax = bounds[1];
        double yMin = -Math.PI, yMax = Math.PI;

        gc.setStroke(Color.web("#FFFFFF", 0.1));
        gc.setLineWidth(1.0);
        double zeroY = miniY(0, yMin, yMax, plotY, plotH);
        gc.strokeLine(plotX, zeroY, plotX + plotW, zeroY);

        drawMiniSeries(gc, tMin, tMax, yMin, yMax, plotX, plotY, plotW, plotH, 1, C_ANGLE, 1.2);
    }

    private void drawMiniEnergy(GraphicsContext gc, double y0, double h, double W) {
        drawMiniBackground(gc, y0, h, W, "E(t)");
        if (data.size() < 2) return;

        double plotX = MINI_MARGIN, plotY = y0 + MINI_TITLE_H;
        double plotW = W - MINI_MARGIN * 2, plotH = h - MINI_TITLE_H - MINI_MARGIN;

        double[] bounds = windowBounds();
        double tMin = bounds[0], tMax = bounds[1];

        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (double[] pt : data) {
            if (pt[0] < tMin || pt[0] > tMax) continue;
            yMin = Math.min(yMin, Math.min(pt[5], Math.min(pt[4], pt[3])));
            yMax = Math.max(yMax, Math.max(pt[3], Math.max(pt[4], 0)));
        }
        double pad = Math.max(0.5, (yMax - yMin) * 0.1);
        yMin -= pad; yMax += pad;

        drawMiniSeries(gc, tMin, tMax, yMin, yMax, plotX, plotY, plotW, plotH, 3, C_ENERGY, 1.4);
        drawMiniSeries(gc, tMin, tMax, yMin, yMax, plotX, plotY, plotW, plotH, 4, C_KE,     1.0);
        drawMiniSeries(gc, tMin, tMax, yMin, yMax, plotX, plotY, plotW, plotH, 5, C_PE,     1.0);
    }

    private void drawMiniPhase(GraphicsContext gc, double y0, double h, double W) {
        drawMiniBackground(gc, y0, h, W, "Phase (θ₁,ω₁)");
        if (data.size() < 2) return;

        double plotX = MINI_MARGIN, plotY = y0 + MINI_TITLE_H;
        double plotW = W - MINI_MARGIN * 2, plotH = h - MINI_TITLE_H - MINI_MARGIN;

        double thetaMin = Double.MAX_VALUE, thetaMax = -Double.MAX_VALUE;
        double omegaMin = Double.MAX_VALUE, omegaMax = -Double.MAX_VALUE;
        for (double[] pt : data) {
            thetaMin = Math.min(thetaMin, pt[1]); thetaMax = Math.max(thetaMax, pt[1]);
            omegaMin = Math.min(omegaMin, pt[2]); omegaMax = Math.max(omegaMax, pt[2]);
        }
        double thetaRange = Math.max(thetaMax - thetaMin, 0.2);
        double omegaRange = Math.max(omegaMax - omegaMin, 0.2);
        thetaMin -= thetaRange * 0.08; thetaMax += thetaRange * 0.08;
        omegaMin -= omegaRange * 0.08; omegaMax += omegaRange * 0.08;

        int total = data.size();
        int idx = 0;
        double[] prev = null;
        for (double[] pt : data) {
            double sx = miniX(pt[1], thetaMin, thetaMax, plotX, plotW);
            double sy = miniY(pt[2], omegaMin, omegaMax, plotY, plotH);
            if (prev != null) {
                double alpha = 0.15 + 0.75 * ((double) idx / total);
                gc.setStroke(C_PHASE.deriveColor(0, 1, 1, alpha));
                gc.setLineWidth(1.0);
                gc.strokeLine(prev[0], prev[1], sx, sy);
            }
            prev = new double[]{sx, sy};
            idx++;
        }
        if (prev != null) {
            gc.setFill(Color.WHITE);
            gc.fillOval(prev[0] - 2, prev[1] - 2, 4, 4);
        }
    }

    private void drawMiniBackground(GraphicsContext gc, double y0, double h, double W, String title) {
        gc.setFill(Color.web("#101014"));
        gc.fillRect(0, y0, W, h);
        gc.setFont(Font.font("System", FontWeight.BOLD, 13));
        gc.setFill(Color.web("#C7C7D1"));
        gc.fillText(title, MINI_MARGIN, y0 + 10);
    }

    private void drawMiniSeries(GraphicsContext gc, double tMin, double tMax, double yMin, double yMax,
                                double x0, double y0, double w, double h, int fieldIdx, Color color, double lineWidth) {
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        gc.beginPath();
        boolean first = true;
        for (double[] pt : data) {
            if (pt[0] < tMin || pt[0] > tMax) continue;
            double sx = miniX(pt[0], tMin, tMax, x0, w);
            double sy = miniY(pt[fieldIdx], yMin, yMax, y0, h);
            if (first) { gc.moveTo(sx, sy); first = false; } else gc.lineTo(sx, sy);
        }
        gc.stroke();
    }

    private double miniX(double val, double vMin, double vMax, double x0, double w) {
        double frac = (val - vMin) / Math.max(vMax - vMin, 1e-10);
        return x0 + frac * w;
    }

    private double miniY(double val, double yMin, double yMax, double y0, double h) {
        double frac = (val - yMin) / Math.max(yMax - yMin, 1e-10);
        return y0 + (1.0 - frac) * h;
    }

    // -------------------------------------------------------------------------
    // Axis, title, and utility drawing
    // -------------------------------------------------------------------------

    private void drawAxes(GraphicsContext gc, double W, double H, double plotW, double plotH) {
        gc.setStroke(Color.web("#2E2E36"));
        gc.setLineWidth(1.5);
        gc.strokeRect(MARGIN, MARGIN + 20, plotW, plotH);
    }

    private void drawTitle(GraphicsContext gc, double plotW) {
        String title = switch (mode) {
            case ANGLE      -> "θ₁(t) — First Link Angle";
            case ENERGY     -> "E(t) — Energy Components";
            case PHASE      -> "Phase Portrait  (θ₁, ω₁)";
            case POINCARE   -> "Poincaré Section  (θ₂, ω₂ at θ₁=0⁺)";
            case COMPARISON -> "Integrator Comparison — |E(t) − E₀|";
            case BIFURCATION -> "Bifurcation Diagram — swept θ₁ initial angle";
            case ALL        -> ""; // render() returns before this is ever reached for ALL
        };
        gc.setFont(Font.font("System", FontWeight.BOLD, 17));
        gc.setFill(Color.web("#C7C7D1"));
        gc.fillText(title, MARGIN + 4, MARGIN + 14);
    }

    /**
     * Numeric labels along both axes for the time-series modes (ANGLE,
     * ENERGY) — without these a plot has grid lines but no way to read an
     * actual value off it. PHASE mode prints its ranges as text instead
     * (see {@link #drawPhasePortrait}), so doesn't call this.
     */
    private void drawAxisTicks(GraphicsContext gc, double plotW, double plotH,
                               double tMin, double tMax, double yMin, double yMax) {
        gc.setFont(Font.font("Monospaced", 13));
        gc.setFill(Color.web("#8A8A96"));

        int gridN = 5;
        // Y-axis values, aligned to the same 5 gridlines drawn in drawPlotArea.
        for (int i = 0; i <= gridN; i++) {
            double frac  = (double) i / gridN;
            double value = yMax - frac * (yMax - yMin); // frac=0 -> top -> yMax
            double sy    = MARGIN + 20 + frac * plotH;
            gc.fillText(formatAxisValue(value), 3, sy + 3);
        }

        // X-axis time labels, evenly spaced across the visible window.
        for (int i = 0; i <= gridN; i++) {
            double frac = (double) i / gridN;
            double t    = tMin + frac * (tMax - tMin);
            double sx   = MARGIN + frac * plotW;
            gc.fillText(String.format("%.1fs", t), sx - 12, MARGIN + 20 + plotH + 14);
        }
    }

    private static String formatAxisValue(double v) {
        return (Math.abs(v) < 0.005) ? "0" : String.format("%.2f", v);
    }

    private void drawZeroLine(GraphicsContext gc, double plotW, double plotH,
                              double yMin, double yMax) {
        double yFrac = (0.0 - yMin) / Math.max(yMax - yMin, 1e-10);
        double sy = toScreenYFromFrac(yFrac, plotH);
        gc.setStroke(Color.web("#FFFFFF", 0.12));
        gc.setLineWidth(1.0);
        gc.strokeLine(MARGIN, sy, MARGIN + plotW, sy);
    }

    // ---- Coordinate helpers ----

    private double toScreenX(double val, double vMin, double vMax, double plotW) {
        double frac = (val - vMin) / Math.max(vMax - vMin, 1e-10);
        return MARGIN + frac * plotW;
    }

    private double toScreenY(double val, double yMin, double yMax, double plotH) {
        double frac = (val - yMin) / Math.max(yMax - yMin, 1e-10);
        return toScreenYFromFrac(frac, plotH);
    }

    private double toScreenYFromFrac(double frac, double plotH) {
        // Flip: frac=0 → bottom, frac=1 → top
        double sy = MARGIN + 20 + (1.0 - frac) * plotH;
        return Math.max(MARGIN + 20, Math.min(MARGIN + 20 + plotH, sy));
    }

    private double[] peekLast() {
        if (data.isEmpty()) return null;
        return ((ArrayDeque<double[]>) data).peekLast();
    }

    /**
     * {@code [tMin, tMax]} for the trailing {@link #TIME_WINDOW}-second
     * slice of {@link #data}, computed from the buffer's own first/last
     * samples rather than assumed forward-increasing. Sim time normally
     * only increases, but {@code simulation.SimulationLoop#setTimeReversed}
     * makes it genuinely decrease over a run — {@code tMax = last sample}
     * would then be numerically the <em>smallest</em> value, inverting the
     * range and breaking every caller that assumes {@code tMin <= tMax}.
     * This always returns a properly ordered pair; time-series plots
     * accordingly read newest-first right-to-left while reversed, exactly
     * as an axis whose value is decreasing over time should.
     */
    private double[] windowBounds() {
        double[] first = data.isEmpty() ? null : ((ArrayDeque<double[]>) data).peekFirst();
        double[] last  = peekLast();
        if (first == null || last == null) return new double[]{0, 0};

        double newest = last[0];
        double oldest = first[0];
        double lo = Math.min(newest, oldest);
        double hi = Math.max(newest, oldest);
        if (newest >= oldest) lo = Math.max(lo, hi - TIME_WINDOW); // forward: keep the trailing window
        else                  hi = Math.min(hi, lo + TIME_WINDOW); // reversed: "trailing" means nearest the (smaller) newest value
        return new double[]{lo, hi};
    }
}
