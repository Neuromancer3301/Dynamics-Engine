package ui;

import physics.SimState;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * JavaFX Canvas that renders the N-pendulum chain.
 *
 * Rendering features:
 *  - Dark-space gradient background with subtle grid
 *  - Glowing trail for the tip bob (last link)
 *  - Individually colored bobs with radial gradient highlights
 *  - Rod rendering with depth shadow
 *  - Info overlay: simulation time and total energy
 */
public final class PendulumCanvas extends Canvas {

    // ---- Visual constants ----
    private static final int    TRAIL_MAX    = 600;
    private static final double BOB_RADIUS   = 11.0;
    private static final double ROD_WIDTH    = 2.5;
    private static final double PIVOT_RADIUS = 6.0;

    // Per-bob color palette
    private static final Color[] BOB_COLORS = {
        Color.web("#FF6B6B"),   // coral red
        Color.web("#4ECDC4"),   // teal
        Color.web("#A29BFE"),   // lavender
        Color.web("#FDCB6E"),   // gold
        Color.web("#55EFC4"),   // mint
        Color.web("#FD79A8"),   // pink
        Color.web("#74B9FF"),   // sky blue
        Color.web("#E17055"),   // orange
    };

    private final double totalLength; // used for scale calculation
    private final Deque<double[]> trail = new ArrayDeque<>(); // {screenX, screenY}

    public PendulumCanvas(double width, double height, double totalLength) {
        super(width, height);
        this.totalLength = totalLength;
    }

    /**
     * Full render call — invoked every frame from the AnimationTimer.
     */
    public void render(SimState state) {
        GraphicsContext gc = getGraphicsContext2D();
        double W = getWidth();
        double H = getHeight();

        drawBackground(gc, W, H);

        if (state == null) {
            drawWaitingMessage(gc, W, H);
            return;
        }

        // ---- Coordinate mapping ----
        // scale: fit full arm length in ~45% of canvas width (allows full swing)
        double scale  = Math.min(W * 0.44, H * 0.80) / Math.max(totalLength, 0.1);
        double pivotX = W / 2.0;
        double pivotY = H * 0.14;   // pivot near top-centre

        // Update and draw trail for the last bob
        if (state.getN() > 0) {
            int last = state.getN() - 1;
            double tx = pivotX + state.bobX[last] * scale;
            double ty = pivotY - state.bobY[last] * scale;   // flip Y (screen Y down)
            trail.addLast(new double[]{tx, ty});
            while (trail.size() > TRAIL_MAX) trail.removeFirst();
        }

        drawTrail(gc);
        drawPivot(gc, pivotX, pivotY);
        drawChain(gc, state, scale, pivotX, pivotY);
        drawInfoOverlay(gc, state, W);
    }

    public void clearTrail() { trail.clear(); }

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

    private void drawTrail(GraphicsContext gc) {
        if (trail.size() < 2) return;

        int total = trail.size();
        int idx   = 0;
        double[] prev = null;

        for (double[] pt : trail) {
            if (prev != null) {
                double alpha = 0.05 + 0.65 * ((double) idx / total);
                double width = 0.5 + 2.0 * ((double) idx / total);
                gc.setStroke(Color.web("#FF6B6B", alpha));
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

            // Bob glow (larger semi-transparent halo)
            Color bobColor = BOB_COLORS[i % BOB_COLORS.length];
            gc.setFill(bobColor.deriveColor(0, 1, 1, 0.18));
            double glowR = BOB_RADIUS * 1.8;
            gc.fillOval(bx - glowR, by - glowR, glowR * 2, glowR * 2);

            // Bob shadow
            gc.setFill(Color.web("#000000", 0.35));
            gc.fillOval(bx - BOB_RADIUS + 2, by - BOB_RADIUS + 2,
                        BOB_RADIUS * 2, BOB_RADIUS * 2);

            // Bob body — radial gradient
            RadialGradient gradient = new RadialGradient(
                    0, 0,
                    bx - BOB_RADIUS * 0.35, by - BOB_RADIUS * 0.35,
                    BOB_RADIUS * 1.2,
                    false, CycleMethod.NO_CYCLE,
                    new Stop(0, bobColor.brighter()),
                    new Stop(1, bobColor.darker().darker())
            );
            gc.setFill(gradient);
            gc.fillOval(bx - BOB_RADIUS, by - BOB_RADIUS, BOB_RADIUS * 2, BOB_RADIUS * 2);

            // Bob specular highlight
            gc.setFill(Color.web("#FFFFFF", 0.35));
            double hl = BOB_RADIUS * 0.55;
            gc.fillOval(bx - hl * 0.9, by - hl * 0.9, hl, hl);

            prevX = bx;
            prevY = by;
        }
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
