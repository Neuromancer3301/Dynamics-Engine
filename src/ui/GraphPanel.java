package ui;

import physics.SimState;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Real-time analytics graph panel rendered on a JavaFX Canvas.
 *
 * Supports three graph modes:
 *  1. ANGLE        — θ₁(t): first link angle vs time (scrolling window)
 *  2. ENERGY       — E(t): total, kinetic, potential energy vs time
 *  3. PHASE        — Phase portrait (θ₁, ω₁): angle vs angular velocity
 *
 * Each mode uses a ring buffer of fixed capacity.
 */
public final class GraphPanel extends Canvas {

    /** Graph display modes. */
    public enum Mode { ANGLE, ENERGY, PHASE }

    // ---- Layout constants ----
    private static final int    MARGIN  = 52;
    private static final int    MAX_PTS = 800;
    private static final double TIME_WINDOW = 12.0; // seconds visible in time-series modes

    // ---- Colors ----
    private static final Color C_ANGLE  = Color.web("#4ECDC4");   // teal
    private static final Color C_ENERGY = Color.web("#FFD700");   // gold
    private static final Color C_KE     = Color.web("#FF6B6B");   // coral
    private static final Color C_PE     = Color.web("#A29BFE");   // lavender
    private static final Color C_PHASE  = Color.web("#FF6B6B");   // coral

    private Mode mode = Mode.ANGLE;

    /**
     * Ring buffer entry: [time, theta1, omega1, totalE, KE, PE]
     */
    private final Deque<double[]> data = new ArrayDeque<>();

    public GraphPanel(double width, double height) {
        super(width, height);
    }

    public void setMode(Mode newMode) {
        this.mode = newMode;
        data.clear();
    }

    /**
     * Ingest a new state snapshot. Call from the JavaFX Application Thread.
     */
    public void addDataPoint(SimState state) {
        if (state == null) return;
        data.addLast(new double[]{
            state.time,
            state.angles[0],
            state.angularVelocities[0],
            state.totalEnergy,
            state.kineticEnergy,
            state.potentialEnergy
        });
        while (data.size() > MAX_PTS) data.removeFirst();
    }

    /**
     * Full redraw — called every render tick from the AnimationTimer.
     */
    public void render() {
        GraphicsContext gc = getGraphicsContext2D();
        double W = getWidth();
        double H = getHeight();
        double plotW = W - MARGIN * 2;
        double plotH = H - MARGIN * 2 - 20; // room for title at top

        drawBackground(gc, W, H);
        drawPlotArea(gc, plotW, plotH);

        if (data.size() >= 2) {
            switch (mode) {
                case ANGLE  -> drawAngleSeries(gc, plotW, plotH);
                case ENERGY -> drawEnergySeries(gc, plotW, plotH);
                case PHASE  -> drawPhasePortrait(gc, plotW, plotH);
            }
        }

        drawAxes(gc, W, H, plotW, plotH);
        drawTitle(gc, plotW);
    }

    public void clear() { data.clear(); }

    // -------------------------------------------------------------------------
    // Drawing sub-routines
    // -------------------------------------------------------------------------

    private void drawBackground(GraphicsContext gc, double W, double H) {
        gc.setFill(Color.web("#0A0A1E"));
        gc.fillRect(0, 0, W, H);
    }

    private void drawPlotArea(GraphicsContext gc, double plotW, double plotH) {
        // Plot background
        gc.setFill(Color.web("#131330"));
        gc.fillRect(MARGIN, MARGIN + 20, plotW, plotH);

        // Grid lines
        gc.setStroke(Color.web("#2A2A5A", 0.6));
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
        double[] last = peekLast();
        if (last == null) return;

        double tMax = last[0];
        double tMin = Math.max(0, tMax - TIME_WINDOW);

        // Y range: θ in [−π, π]
        double yMin = -Math.PI;
        double yMax =  Math.PI;

        // Zero line
        drawZeroLine(gc, plotW, plotH, yMin, yMax);

        gc.setLineWidth(1.8);
        gc.setStroke(C_ANGLE);
        gc.beginPath();
        boolean first = true;
        for (double[] pt : data) {
            double t = pt[0];
            if (t < tMin) continue;
            double sx = toScreenX(t, tMin, tMax, plotW);
            double sy = toScreenY(pt[1], yMin, yMax, plotH);
            if (first) { gc.moveTo(sx, sy); first = false; }
            else         gc.lineTo(sx, sy);
        }
        gc.stroke();
    }

    // ---- Mode: Energy vs Time ----
    private void drawEnergySeries(GraphicsContext gc, double plotW, double plotH) {
        double[] last = peekLast();
        if (last == null) return;

        double tMax = last[0];
        double tMin = Math.max(0, tMax - TIME_WINDOW);

        // Auto-scale Y from data in visible window
        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (double[] pt : data) {
            if (pt[0] < tMin) continue;
            yMin = Math.min(yMin, Math.min(pt[5], Math.min(pt[4], pt[3])));
            yMax = Math.max(yMax, Math.max(pt[3], Math.max(pt[4], 0)));
        }
        double pad = Math.max(0.5, (yMax - yMin) * 0.1);
        yMin -= pad; yMax += pad;

        // Draw three series: Total, KE, PE
        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 3, C_ENERGY, 2.0); // total
        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 4, C_KE,     1.2); // KE
        drawSeries(gc, tMin, tMax, yMin, yMax, plotW, plotH, 5, C_PE,     1.2); // PE

        // Legend
        gc.setFont(Font.font("Monospaced", 10));
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
            if (pt[0] < tMin) continue;
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
        gc.setFont(Font.font("Monospaced", 9));
        gc.setFill(Color.web("#6A6A9A"));
        gc.fillText(String.format("θ  [%.2f, %.2f] rad", thetaMin, thetaMax),
                    MARGIN + 4, MARGIN + 20 + plotH + 15);
        gc.fillText(String.format("ω  [%.2f, %.2f] rad/s", omegaMin, omegaMax),
                    MARGIN + 4, MARGIN + 20 + plotH + 26);
    }

    // -------------------------------------------------------------------------
    // Axis, title, and utility drawing
    // -------------------------------------------------------------------------

    private void drawAxes(GraphicsContext gc, double W, double H, double plotW, double plotH) {
        gc.setStroke(Color.web("#3A3A6A"));
        gc.setLineWidth(1.5);
        gc.strokeRect(MARGIN, MARGIN + 20, plotW, plotH);
    }

    private void drawTitle(GraphicsContext gc, double plotW) {
        String title = switch (mode) {
            case ANGLE  -> "θ₁(t) — First Link Angle";
            case ENERGY -> "E(t) — Energy Components";
            case PHASE  -> "Phase Portrait  (θ₁, ω₁)";
        };
        gc.setFont(Font.font("System", FontWeight.BOLD, 12));
        gc.setFill(Color.web("#8888CC"));
        gc.fillText(title, MARGIN + 4, MARGIN + 14);
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
}
