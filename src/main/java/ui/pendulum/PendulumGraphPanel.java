package ui.pendulum;

import physics.SimState;
import ui.simcore.ChartCanvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
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
 * Real-time analytics graph panel for the pendulum screen. Moved from {@code
 * ui.GraphPanel} and split the same way {@code ui.PendulumCanvas} was: the
 * generic charting mechanics live in {@link ChartCanvas}; everything below
 * is pendulum-specific data and per-mode drawing — see round 1 §9 of the UI
 * restructuring plan.
 *
 * Supports seven graph modes:
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
 *  8. FRACTAL      — the classic double-pendulum basin-of-attraction
 *                    fractal: one pixel per (θ₁, θ₂) starting pair, coloured
 *                    by how fast link 2 flips; fed externally via {@link
 *                    #setFractalData}, computed by {@code
 *                    physics.FractalBasinSweep}. Round 3 — ported from
 *                    {@code feature/manual-and-bugfixes}, see that branch's
 *                    {@code ui.GraphPanel#drawFractal}/{@code
 *                    #buildFractalImage} for the original.
 *
 * ANGLE/ENERGY/PHASE/ALL share one ring buffer of fixed capacity.
 */
public final class PendulumGraphPanel extends ChartCanvas {

    /** Graph display modes. */
    public enum Mode { ANGLE, ENERGY, PHASE, ALL, POINCARE, COMPARISON, BIFURCATION, FRACTAL }

    /** One integrator's energy-drift-over-time trace for {@link Mode#COMPARISON}. */
    public record ComparisonSeries(String name, double[] times, double[] energyDrift, Color color) {}

    private static final int    MAX_PTS = 800;
    private static final double TIME_WINDOW = 12.0; // seconds visible in time-series modes

    // Poincaré points accumulate for the life of the run (cleared only by
    // clear()), so this bounds memory rather than controlling what's visible.
    private static final int MAX_POINCARE_POINTS = 5000;

    private static final double LEGEND_LINE_H = 15;

    // ---- Colors ----
    // "Signal" palette: any single-focus mode (one live trace or one
    // accumulating point cloud) reads in the app's one accent magenta —
    // ANGLE, PHASE, POINCARE, and BIFURCATION are all exactly that. ENERGY
    // is the one graph that's genuinely multi-series (Total/KE/PE at once),
    // so it draws from the same real oscilloscope multi-channel colors
    // (magenta/cyan/yellow) ui.pendulum.PendulumChainRenderer's bob palette
    // and A/B compare chain use.
    private static final Color C_ANGLE  = Color.web("#EA3F8C");   // magenta (accent)
    private static final Color C_ENERGY = Color.web("#EA3F8C");   // magenta — Total, the headline series
    private static final Color C_KE     = Color.web("#3DDCC7");   // cyan — matches A/B compare's "second channel"
    private static final Color C_PE     = Color.web("#E8D34A");   // yellow — third scope channel
    private static final Color C_PHASE  = Color.web("#EA3F8C");   // magenta (accent)
    private static final Color C_POINCARE = Color.web("#EA3F8C"); // magenta (accent)

    // Round 2.1: hoisted out of the per-mode draw methods below — same
    // reasoning as ui.simcore.ChartCanvas/ChainRenderer's own constants.
    private static final Color CAPTION_TEXT  = Color.web("#8A8A96");
    private static final Color DIVIDER_LINE  = Color.web("#2E2E36");
    private static final Color MINI_ZERO_LINE = Color.web("#FFFFFF", 0.1);
    private static final Color MINI_BG        = Color.web("#101014");
    private static final Color MINI_TITLE_COLOR = Color.web("#C7C7D1");
    private static final Color FRACTAL_NEVER_FLIPPED = Color.web("#0B0B10");
    private static final Font FONT_LEGEND    = Font.font("Monospaced", 11);
    private static final Font FONT_CAPTION   = Font.font("Monospaced", 10);
    private static final Font FONT_EMPTY_MSG = Font.font("System", 12);
    private static final Font FONT_MINI_TITLE = Font.font("System", FontWeight.BOLD, 10);

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

    // Basin-of-attraction fractal. Rendered once into a WritableImage when
    // the data arrives rather than per-frame: at the default resolution
    // that's 40,000 cells, far too many to redraw as individual rectangles
    // every repaint. Null until a sweep completes.
    private WritableImage fractalImage;
    private double fractalMaxSeconds = 1.0;

    /** @param width,height initial size; later bound to the host pane so the graph fills its column. */
    public PendulumGraphPanel(double width, double height) {
        super(width, height);
    }

    /** Switches which analysis view is drawn. Deliberately does NOT clear the buffer — see the inline note. */
    public void setMode(Mode newMode) {
        // No data.clear() here: every mode reads from the same ring buffer
        // entry — recorded on every addDataPoint() call regardless of which
        // mode is active — so switching modes has no reason to discard
        // history you were watching build up.
        this.mode = newMode;
        markDirty();
    }

    /** Ingest a new state snapshot. Call from the JavaFX Application Thread. */
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

        // Rising zero-crossing of theta1: prev < 0, current >= 0.
        if (hasSecondLink && !Double.isNaN(prevTheta1) && prevTheta1 < 0 && theta1 >= 0) {
            poincarePoints.add(new double[]{theta2, omega2});
            if (poincarePoints.size() > MAX_POINCARE_POINTS) poincarePoints.remove(0);
        }
        prevTheta1 = theta1;
        markDirty();
    }

    /** Supplies the traces {@link Mode#COMPARISON} draws. See controller.SimulationController for how these are computed. */
    public void setComparisonData(List<ComparisonSeries> series) {
        this.comparisonSeries = series;
        markDirty();
    }

    /** Supplies the columns {@link Mode#BIFURCATION} draws. See {@code physics.BifurcationSweep}. */
    public void setBifurcationData(double[] paramValues, List<double[]> samples) {
        this.bifurcationParams = paramValues;
        this.bifurcationSamples = samples;
        markDirty();
    }

    /**
     * Supplies the grid {@link Mode#FRACTAL} draws, converting it to an
     * image immediately — see {@link #fractalImage} for why this happens
     * once here rather than on every repaint.
     */
    public void setFractalData(double[][] timeToFlip, double maxSeconds) {
        this.fractalMaxSeconds = Math.max(maxSeconds, 1e-9);
        this.fractalImage = buildFractalImage(timeToFlip);
        markDirty();
    }

    /** True once a fractal sweep has produced an image — gates the empty-state message. */
    public boolean hasFractalData() {
        return fractalImage != null;
    }

    @Override
    protected void drawModeContent(GraphicsContext gc, double W, double H) {
        if (mode == Mode.ALL) {
            renderSmallMultiples(gc, W, H);
            return;
        }

        double plotW = W - MARGIN * 2;
        double plotH = H - MARGIN * 2 - 20; // room for title at top

        drawPlotArea(gc, plotW, plotH);

        switch (mode) {
            case ANGLE      -> { if (data.size() >= 2) drawAngleSeries(gc, plotW, plotH); }
            case ENERGY     -> { if (data.size() >= 2) drawEnergySeries(gc, plotW, plotH); }
            case PHASE      -> { if (data.size() >= 2) drawPhasePortrait(gc, plotW, plotH); }
            case POINCARE     -> drawPoincareSection(gc, plotW, plotH);
            case COMPARISON   -> drawComparison(gc, plotW, plotH);
            case BIFURCATION  -> drawBifurcation(gc, plotW, plotH);
            case FRACTAL      -> drawFractal(gc, plotW, plotH);
            case ALL          -> { /* handled above, before the early return */ }
        }

        drawAxes(gc, plotW, plotH);
        drawTitle(gc, plotW, titleFor(mode));
    }

    private static String titleFor(Mode mode) {
        return switch (mode) {
            case ANGLE      -> "θ₁(t) — First Link Angle";
            case ENERGY     -> "E(t) — Energy Components";
            case PHASE      -> "Phase Portrait  (θ₁, ω₁)";
            case POINCARE   -> "Poincaré Section  (θ₂, ω₂ at θ₁=0⁺)";
            case COMPARISON -> "Integrator Comparison — |E(t) − E₀|";
            case BIFURCATION -> "Bifurcation Diagram — swept θ₁ initial angle";
            case FRACTAL     -> "Basin Fractal — time until link 2 flips";
            case ALL        -> ""; // drawModeContent returns before this is ever reached for ALL
        };
    }

    /** Discards all accumulated data — the scrolling buffer, the Poincaré point cloud, and the crossing detector's memory. */
    public void clear() {
        data.clear();
        poincarePoints.clear();
        prevTheta1 = Double.NaN;
        markDirty();
    }

    /**
     * Writes the currently-buffered time series to a CSV file — whatever
     * {@link #addDataPoint} has accumulated in {@code data} (up to {@link
     * #MAX_PTS} most recent samples), independent of which {@link Mode} is
     * currently displayed.
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

    // ---- Mode: Angle vs Time ----
    private void drawAngleSeries(GraphicsContext gc, double plotW, double plotH) {
        if (peekLast() == null) return;

        double[] bounds = windowBounds();
        double tMin = bounds[0], tMax = bounds[1];
        double yMin = -Math.PI, yMax = Math.PI;

        drawZeroLine(gc, plotW, plotH, yMin, yMax);
        drawAxisTicks(gc, plotW, plotH, tMin, tMax, yMin, yMax);

        gc.setLineWidth(1.8);
        gc.setStroke(C_ANGLE);
        gc.beginPath();
        boolean first = true;
        for (double[] pt : data) {
            double t = pt[0];
            if (t < tMin || t > tMax) continue;
            double sx = mapX(t, tMin, tMax, MARGIN, plotW);
            double sy = mapY(pt[1], yMin, yMax, MARGIN + 20, plotH);
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

        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (double[] pt : data) {
            if (pt[0] < tMin || pt[0] > tMax) continue;
            yMin = Math.min(yMin, Math.min(pt[5], Math.min(pt[4], pt[3])));
            yMax = Math.max(yMax, Math.max(pt[3], Math.max(pt[4], 0)));
        }
        double pad = Math.max(0.5, (yMax - yMin) * 0.1);
        yMin -= pad; yMax += pad;

        drawAxisTicks(gc, plotW, plotH, tMin, tMax, yMin, yMax);

        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 3, C_ENERGY, 2.0); // total
        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 4, C_KE,     1.2); // KE
        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 5, C_PE,     1.2); // PE

        gc.setFont(FONT_LEGEND);
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
            double sx = mapX(pt[0], tMin, tMax, MARGIN, plotW);
            double sy = mapY(pt[fieldIdx], yMin, yMax, MARGIN + 20, plotH);
            if (first) { gc.moveTo(sx, sy); first = false; }
            else         gc.lineTo(sx, sy);
        }
        gc.stroke();
    }

    // ---- Mode: Phase Portrait ----
    private void drawPhasePortrait(GraphicsContext gc, double plotW, double plotH) {
        double thetaMin =  Double.MAX_VALUE, thetaMax = -Double.MAX_VALUE;
        double omegaMin =  Double.MAX_VALUE, omegaMax = -Double.MAX_VALUE;
        for (double[] pt : data) {
            thetaMin = Math.min(thetaMin, pt[1]); thetaMax = Math.max(thetaMax, pt[1]);
            omegaMin = Math.min(omegaMin, pt[2]); omegaMax = Math.max(omegaMax, pt[2]);
        }

        double thetaRange = Math.max(thetaMax - thetaMin, 0.2);
        double omegaRange = Math.max(omegaMax - omegaMin, 0.2);
        double thetaPad = thetaRange * 0.08, omegaPad = omegaRange * 0.08;
        thetaMin -= thetaPad; thetaMax += thetaPad;
        omegaMin -= omegaPad; omegaMax += omegaPad;

        drawZeroLine(gc, plotW, plotH, omegaMin, omegaMax);

        int total = data.size();
        int idx   = 0;
        double[] prev = null;

        for (double[] pt : data) {
            double sx = mapX(pt[1], thetaMin, thetaMax, MARGIN, plotW);
            double sy = mapY(pt[2], omegaMin, omegaMax, MARGIN + 20, plotH);

            if (prev != null) {
                double alpha = 0.10 + 0.80 * ((double) idx / total);
                gc.setStroke(C_PHASE.deriveColor(0, 1, 1, alpha));
                gc.setLineWidth(1.2);
                gc.strokeLine(prev[0], prev[1], sx, sy);
            }
            prev = new double[]{sx, sy};
            idx++;
        }

        if (prev != null) {
            gc.setFill(Color.WHITE);
            gc.fillOval(prev[0] - 3, prev[1] - 3, 6, 6);
        }

        gc.setFont(FONT_CAPTION);
        gc.setFill(CAPTION_TEXT);
        gc.fillText(String.format("θ  [%.2f, %.2f] rad", thetaMin, thetaMax),
                    MARGIN + 4, MARGIN + 20 + plotH + 18);
        gc.fillText(String.format("ω  [%.2f, %.2f] rad/s", omegaMin, omegaMax),
                    MARGIN + 4, MARGIN + 20 + plotH + 33);
    }

    // ---- Mode: Poincaré Section ----
    private void drawPoincareSection(GraphicsContext gc, double plotW, double plotH) {
        if (poincarePoints.isEmpty()) {
            gc.setFont(FONT_EMPTY_MSG);
            gc.setFill(CAPTION_TEXT);
            gc.fillText("Accumulating — a point is plotted each time θ₁",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 - 10);
            gc.fillText("crosses zero moving forward (needs N ≥ 2)",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 + 10);
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
            double sx = mapX(pt[0], thetaMin, thetaMax, MARGIN, plotW);
            double sy = mapY(pt[1], omegaMin, omegaMax, MARGIN + 20, plotH);
            gc.fillOval(sx - 1.5, sy - 1.5, 3, 3);
        }

        gc.setFont(FONT_CAPTION);
        gc.setFill(CAPTION_TEXT);
        gc.fillText(String.format("%d crossings   θ₂ [%.2f, %.2f]   ω₂ [%.2f, %.2f]",
                        poincarePoints.size(), thetaMin, thetaMax, omegaMin, omegaMax),
                MARGIN + 4, MARGIN + 20 + plotH + 18);
    }

    // ---- Mode: Integrator Comparison ----
    private void drawComparison(GraphicsContext gc, double plotW, double plotH) {
        if (comparisonSeries.isEmpty()) {
            gc.setFont(FONT_EMPTY_MSG);
            gc.setFill(CAPTION_TEXT);
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
        gc.setFont(FONT_LEGEND);
        for (ComparisonSeries s : comparisonSeries) {
            gc.setStroke(s.color());
            gc.setLineWidth(1.6);
            gc.beginPath();
            boolean first = true;
            for (int i = 0; i < s.times().length; i++) {
                double sx = mapX(s.times()[i], tMin, tMax, MARGIN, plotW);
                double sy = mapY(s.energyDrift()[i], yMin, yMax, MARGIN + 20, plotH);
                if (first) { gc.moveTo(sx, sy); first = false; } else gc.lineTo(sx, sy);
            }
            gc.stroke();

            gc.setFill(s.color());
            gc.fillText("─ " + s.name(), MARGIN + 6, legendY);
            legendY += LEGEND_LINE_H;
        }
    }

    // ---- Mode: Bifurcation Diagram ----
    private void drawBifurcation(GraphicsContext gc, double plotW, double plotH) {
        if (bifurcationParams.length == 0) {
            gc.setFont(FONT_EMPTY_MSG);
            gc.setFill(CAPTION_TEXT);
            gc.fillText("Press \"Generate Bifurcation Map\" in the sidebar to run this.",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 - 10);
            gc.fillText("Sweeps the first link's initial angle — takes a while.",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 + 10);
            return;
        }

        double xMin = bifurcationParams[0], xMax = bifurcationParams[bifurcationParams.length - 1];
        if (xMax <= xMin) xMax = xMin + 1;
        double yMin = -Math.PI, yMax = Math.PI; // angles are already wrapped into this range by PhysicsEngine

        drawAxisTicks(gc, plotW, plotH, xMin, xMax, yMin, yMax);

        gc.setFill(C_ANGLE.deriveColor(0, 1, 1, 0.35));
        for (int c = 0; c < bifurcationParams.length; c++) {
            double sx = mapX(bifurcationParams[c], xMin, xMax, MARGIN, plotW);
            for (double y : bifurcationSamples.get(c)) {
                double sy = mapY(y, yMin, yMax, MARGIN + 20, plotH);
                gc.fillOval(sx - 0.75, sy - 0.75, 1.5, 1.5);
            }
        }

        gc.setFont(FONT_CAPTION);
        gc.setFill(CAPTION_TEXT);
        gc.fillText(String.format("θ₁ initial swept [%.2f, %.2f] rad · y = last link's angle at each θ₁ crossing",
                        xMin, xMax),
                MARGIN + 4, MARGIN + 20 + plotH + 18);
    }

    // ---- Mode: Basin Fractal ----
    // Round 3: ported from feature/manual-and-bugfixes's ui.GraphPanel —
    // physics.FractalBasinSweep itself needed no changes at all (it only
    // touches PendulumConfig/PhysicsEngine's public API, untouched by the
    // Round 2 physics-layer pass), so this is the drawing half of that port.

    /**
     * Draws the precomputed basin image, scaled to fill the plot area and
     * kept square so the (θ₁, θ₂) grid isn't visually distorted.
     */
    private void drawFractal(GraphicsContext gc, double plotW, double plotH) {
        if (fractalImage == null) {
            gc.setFont(FONT_EMPTY_MSG);
            gc.setFill(CAPTION_TEXT);
            gc.fillText("Press \"Generate Basin Fractal\" in the sidebar to run this.",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 - 10);
            gc.fillText("Sweeps every pair of starting angles — takes a few seconds.",
                    MARGIN + 12, MARGIN + 20 + plotH / 2.0 + 10);
            return;
        }

        // Square, centred: the two axes are the same quantity (an angle over
        // the full circle), so a stretched aspect ratio would misrepresent it.
        double side = Math.min(plotW, plotH);
        double x = MARGIN + (plotW - side) / 2.0;
        double y = MARGIN + 20 + (plotH - side) / 2.0;
        gc.drawImage(fractalImage, x, y, side, side);

        gc.setStroke(DIVIDER_LINE);
        gc.setLineWidth(1.0);
        gc.strokeRect(x, y, side, side);

        gc.setFont(FONT_CAPTION);
        gc.setFill(CAPTION_TEXT);
        gc.fillText("x = θ₁ start, y = θ₂ start, both −π..π · bright = flips fast · dark = never flips",
                MARGIN + 4, MARGIN + 20 + plotH + 18);
    }

    /**
     * Converts the sweep's time-to-flip grid into a colour image.
     *
     * <p>Cells that never flipped are painted near-black: those are the
     * smooth, predictable regions, and leaving them dark makes the
     * intricate boundary read as the subject. Everything else ramps from
     * magenta (flips almost immediately) through to deep blue (took nearly
     * the whole budget), using the app's own accent hue at the hot end for
     * the same reason the velocity tint does.
     */
    private WritableImage buildFractalImage(double[][] timeToFlip) {
        int h = timeToFlip.length;
        int w = timeToFlip[0].length;
        WritableImage image = new WritableImage(w, h);
        PixelWriter pixels = image.getPixelWriter();

        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                double t = timeToFlip[row][col];
                Color c;
                if (t >= fractalMaxSeconds) {
                    c = FRACTAL_NEVER_FLIPPED; // never flipped — stable region
                } else {
                    double frac = t / fractalMaxSeconds;      // 0 = instant, 1 = only just
                    double hue = 330.0 - frac * 110.0;        // 330 magenta -> 220 blue
                    c = Color.hsb(hue, 0.85, 1.0 - 0.35 * frac);
                }
                // Row 0 is theta2 = -pi. Flipped vertically so +theta2 points
                // up on screen, matching how every other plot here reads.
                pixels.setColor(col, h - 1 - row, c);
            }
        }
        return image;
    }

    // -------------------------------------------------------------------------
    // Mode: ALL — small multiples
    // -------------------------------------------------------------------------
    //
    // Deliberately a separate, self-contained set of mini-drawing routines
    // rather than reusing drawAngleSeries/drawEnergySeries/drawPhasePortrait
    // with a smaller margin threaded through: those three are built around
    // the MARGIN-based origin and the full canvas, and retrofitting three
    // independent coordinate systems through them would touch more surface
    // than duplicating the (much simpler) plotting logic here, scoped to an
    // explicit (x0, y0, w, h) band.

    /**
     * Draws the ALL mode: angle, energy, and phase portrait stacked in three
     * equal horizontal bands. "Small multiples" is the standard data-viz term
     * for repeating one chart shape across several variables so they can be
     * compared at a glance.
     */
    private void renderSmallMultiples(GraphicsContext gc, double W, double H) {
        double bandH = H / 3.0;
        drawMiniAngle(gc, 0, bandH, W);
        drawMiniEnergy(gc, bandH, bandH, W);
        drawMiniPhase(gc, bandH * 2, bandH, W);

        gc.setStroke(DIVIDER_LINE);
        gc.setLineWidth(1.0);
        gc.strokeLine(0, bandH, W, bandH);
        gc.strokeLine(0, bandH * 2, W, bandH * 2);
    }

    /** The angle band of the small-multiples view, drawn into its own (y0, h) strip. */
    private void drawMiniAngle(GraphicsContext gc, double y0, double h, double W) {
        drawMiniBackground(gc, y0, h, W, "θ₁(t)");
        if (data.size() < 2) return;

        double plotX = MINI_MARGIN, plotY = y0 + MINI_TITLE_H;
        double plotW = W - MINI_MARGIN * 2, plotH = h - MINI_TITLE_H - MINI_MARGIN;

        double[] bounds = windowBounds();
        double tMin = bounds[0], tMax = bounds[1];
        double yMin = -Math.PI, yMax = Math.PI;

        gc.setStroke(MINI_ZERO_LINE);
        gc.setLineWidth(1.0);
        double zeroY = mapY(0, yMin, yMax, plotY, plotH);
        gc.strokeLine(plotX, zeroY, plotX + plotW, zeroY);

        drawMiniSeries(gc, tMin, tMax, yMin, yMax, plotX, plotY, plotW, plotH, 1, C_ANGLE, 1.2);
    }

    /** The energy band of the small-multiples view — all three energy series, auto-scaled to the visible window. */
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

    /** The phase-portrait band of the small-multiples view, with the same age-fade and current-point marker as the full-size version. */
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
            double sx = mapX(pt[1], thetaMin, thetaMax, plotX, plotW);
            double sy = mapY(pt[2], omegaMin, omegaMax, plotY, plotH);
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

    /** Fills one small-multiples band and labels it. */
    private void drawMiniBackground(GraphicsContext gc, double y0, double h, double W, String title) {
        gc.setFill(MINI_BG);
        gc.fillRect(0, y0, W, h);
        gc.setFont(FONT_MINI_TITLE);
        gc.setFill(MINI_TITLE_COLOR);
        gc.fillText(title, MINI_MARGIN, y0 + 12);
    }

    /** Plots one field of the ring buffer inside a small-multiples band. The band-local equivalent of {@link #drawSeries}. */
    private void drawMiniSeries(GraphicsContext gc, double tMin, double tMax, double yMin, double yMax,
                                double x0, double y0, double w, double h, int fieldIdx, Color color, double lineWidth) {
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        gc.beginPath();
        boolean first = true;
        for (double[] pt : data) {
            if (pt[0] < tMin || pt[0] > tMax) continue;
            double sx = mapX(pt[0], tMin, tMax, x0, w);
            double sy = mapY(pt[fieldIdx], yMin, yMax, y0, h);
            if (first) { gc.moveTo(sx, sy); first = false; } else gc.lineTo(sx, sy);
        }
        gc.stroke();
    }

    // ---- Buffer window helpers ----

    /** The most recent sample, or {@code null} if none. */
    private double[] peekLast() {
        if (data.isEmpty()) return null;
        return data.peekLast();
    }

    /**
     * {@code [tMin, tMax]} for the trailing {@link #TIME_WINDOW}-second
     * slice of {@link #data}, computed from the buffer's own first/last
     * samples rather than assumed forward-increasing — {@code
     * simulation.SimulationLoop#setTimeReversed} makes sim time genuinely
     * decrease over a run, so this always returns a properly ordered pair.
     */
    private double[] windowBounds() {
        double[] first = data.isEmpty() ? null : data.peekFirst();
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
