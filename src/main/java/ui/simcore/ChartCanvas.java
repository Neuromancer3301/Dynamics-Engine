package ui.simcore;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Simulation-agnostic base for a JavaFX {@link Canvas} that plots time
 * series / phase portraits / point clouds inside a bordered plot area with
 * axis ticks — the charting mechanics {@code ui.GraphPanel} used to
 * duplicate across its main-plot and small-multiples drawing paths.
 *
 * <p>Owns background fill, the dirty-flag render-skip optimisation,
 * plot-area/grid drawing, axis-tick drawing, the zero line, the title, and
 * the value&rarr;pixel coordinate mapping. A subclass (see {@code
 * ui.pendulum.PendulumGraphPanel}) owns everything about what's actually
 * plotted: the data buffer, the mode switch, and each mode's own drawing
 * routine.
 */
public abstract class ChartCanvas extends Canvas {

    // MARGIN is the gutter holding the axis tick labels.
    protected static final int MARGIN = 60;

    // Small-multiples band layout — see PendulumGraphPanel's mini-* drawing.
    protected static final double MINI_MARGIN  = 6;
    protected static final double MINI_TITLE_H = 17;

    private boolean dirty = true;

    protected ChartCanvas(double width, double height) {
        super(width, height);
    }

    /** Marks the canvas for redraw on the next {@link #render()} call. */
    protected final void markDirty() {
        dirty = true;
    }

    /**
     * Redraws, but only if something has changed since the last call
     * actually painted a frame. Safe to call unconditionally from an
     * {@code AnimationTimer} at any cadence.
     */
    public final void render() {
        if (!dirty) return;
        dirty = false;

        GraphicsContext gc = getGraphicsContext2D();
        drawBackground(gc, getWidth(), getHeight());
        drawModeContent(gc, getWidth(), getHeight());
    }

    /** Draws whatever this chart's current mode calls for, background already painted. */
    protected abstract void drawModeContent(GraphicsContext gc, double width, double height);

    protected void drawBackground(GraphicsContext gc, double w, double h) {
        gc.setFill(Color.web("#08080B"));
        gc.fillRect(0, 0, w, h);
    }

    /** Paints the inner plot rectangle and its 5x5 reference grid, at the standard {@link #MARGIN}-based origin. Drawn before any series so the data sits on top. */
    protected void drawPlotArea(GraphicsContext gc, double plotW, double plotH) {
        gc.setFill(Color.web("#101014"));
        gc.fillRect(MARGIN, MARGIN + 20, plotW, plotH);

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

    /** Strokes the plot rectangle's border, at the standard {@link #MARGIN}-based origin. */
    protected void drawAxes(GraphicsContext gc, double plotW, double plotH) {
        gc.setStroke(Color.web("#2E2E36"));
        gc.setLineWidth(1.5);
        gc.strokeRect(MARGIN, MARGIN + 20, plotW, plotH);
    }

    /**
     * Numeric labels along both axes of the standard {@link #MARGIN}-based
     * plot — without these a plot has grid lines but no way to read an
     * actual value off it.
     */
    protected void drawAxisTicks(GraphicsContext gc, double plotW, double plotH,
                                  double tMin, double tMax, double yMin, double yMax) {
        gc.setFont(Font.font("Monospaced", 10));
        gc.setFill(Color.web("#8A8A96"));

        int gridN = 5;
        for (int i = 0; i <= gridN; i++) {
            double frac  = (double) i / gridN;
            double value = yMax - frac * (yMax - yMin); // frac=0 -> top -> yMax
            double sy    = MARGIN + 20 + frac * plotH;
            gc.fillText(formatAxisValue(value), 4, sy + 4);
        }

        for (int i = 0; i <= gridN; i++) {
            double frac = (double) i / gridN;
            double t    = tMin + frac * (tMax - tMin);
            double sx   = MARGIN + frac * plotW;
            gc.fillText(String.format("%.1fs", t), sx - 16, MARGIN + 20 + plotH + 17);
        }
    }

    /** Formats an axis tick, collapsing values that round to zero into a bare "0" rather than "-0.00" or "0.00". */
    protected static String formatAxisValue(double v) {
        return (Math.abs(v) < 0.005) ? "0" : String.format("%.2f", v);
    }

    /** A faint horizontal line at data-value zero, in the standard {@link #MARGIN}-based plot. */
    protected void drawZeroLine(GraphicsContext gc, double plotW, double plotH, double yMin, double yMax) {
        double sy = mapY(0.0, yMin, yMax, MARGIN + 20, plotH);
        gc.setStroke(Color.web("#FFFFFF", 0.12));
        gc.setLineWidth(1.0);
        gc.strokeLine(MARGIN, sy, MARGIN + plotW, sy);
    }

    /** Draws the heading naming the active mode, above the standard {@link #MARGIN}-based plot area. */
    protected void drawTitle(GraphicsContext gc, double plotW, String title) {
        gc.setFont(Font.font("System", FontWeight.BOLD, 14));
        gc.setFill(Color.web("#C7C7D1"));
        gc.fillText(title, MARGIN + 4, MARGIN + 16);
    }

    /**
     * Maps a data value to an x pixel, given an origin and extent —
     * replaces both the main plot's {@code toScreenX} and the
     * small-multiples bands' {@code miniX}, which were the exact same
     * formula called with different origin/extent parameters.
     */
    protected static double mapX(double val, double vMin, double vMax, double x0, double w) {
        double frac = (val - vMin) / Math.max(vMax - vMin, 1e-10);
        return x0 + frac * w;
    }

    /**
     * Maps a data value to a y pixel, given an origin and extent, flipping
     * the axis (screen y grows downward, data grows upward) and clamping to
     * the target rectangle so an out-of-range value can't draw outside it —
     * replaces both {@code toScreenY} and {@code miniY}.
     */
    protected static double mapY(double val, double vMin, double vMax, double y0, double h) {
        double frac = (val - vMin) / Math.max(vMax - vMin, 1e-10);
        double sy = y0 + (1.0 - frac) * h;
        return Math.max(y0, Math.min(y0 + h, sy));
    }
}
