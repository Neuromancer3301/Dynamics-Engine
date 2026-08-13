package ui.icon;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import theme.Theme;

/**
 * Hand-rolled icon set — plain-JavaFX line-art glyphs, no icon-font or icon
 * library dependency (see the project's stated convention of hand-rolling
 * small mechanisms rather than pulling in a dependency, e.g.
 * {@code physics.NaturalLanguageSceneParser}, {@code physics.MiniJson}).
 *
 * <p>Each {@link Glyph} is drawn on a small square {@link Canvas} rather
 * than as an {@code SVGPath} — several of these (the gear, the magnet, the
 * eye) are built from arcs and radial ticks that are far more legibly
 * expressed as {@link GraphicsContext} calls than as hand-authored SVG path
 * data. Every glyph is simple geometric line-art (strokes, arcs, polylines)
 * at a fixed logical size, single-color, and fully recolorable after
 * construction via {@link IconView#setColor} — callers swap between an
 * idle, hover, and active tint the same way {@code -ink-2}/{@code -ink}/
 * {@code -accent} are used everywhere else in the "Signal" identity (see
 * {@code theme.css}'s own comment block). Because these are raw canvas
 * pixels, not styleable nodes, colors are hand-picked to match those tokens
 * rather than looked up from CSS — see {@link #idleColor}, {@link
 * #hoverColor}, {@link #activeColor} for the single place that mapping
 * lives, so no caller hardcodes a hex value of its own.
 */
public final class Icons {

    private Icons() {}

    /** Every glyph this app currently needs. See each draw method below for what it looks like. */
    public enum Glyph {
        SETTINGS, INFO, CHEVRON, SELECT, ADD, SNAP,
        MOTION, CHAOS, GRAPHS, HISTORY, LINKS, DISPLAY, PENDULUM, RESERVED
    }

    /** Creates a fixed-size, recolorable icon node. {@code size} is the glyph's logical (square) pixel size. */
    public static IconView create(Glyph glyph, double size, Color color) {
        return new IconView(glyph, size, color);
    }

    /** {@code -ink-2} equivalent — idle/unselected glyph tint. Matches {@code theme.css}'s token exactly. */
    public static Color idleColor(Theme theme) {
        return theme == Theme.DARK ? Color.web("#B6B6C2") : Color.web("#55555E");
    }

    /** {@code -ink} equivalent — hovered/emphasized glyph tint. */
    public static Color hoverColor(Theme theme) {
        return theme == Theme.DARK ? Color.web("#F2F2F6") : Color.web("#18181B");
    }

    /** {@code -accent} equivalent — active/selected/live glyph tint, the one hue this app reserves for "live." */
    public static Color activeColor(Theme theme) {
        return theme == Theme.DARK ? Color.web("#F2489A") : Color.web("#C81F72");
    }

    /** {@code -ink-3} equivalent — dimmed tint for a reserved/disabled glyph (see NavCardController's coming-soon cards). */
    public static Color dimColor(Theme theme) {
        return theme == Theme.DARK ? Color.web("#96969F") : Color.web("#6B6B76");
    }

    /**
     * Fixed, theme-independent near-white — for an icon drawn on {@code
     * .canvas-overlay-button}'s chip (see
     * controller.SimulationController#buildSidebarToggle), which always sits
     * on {@code ui.PendulumCanvas}'s permanently-dark background and so
     * paints a fixed dark chip regardless of the app's own light/dark theme
     * (see that class's own CSS comment). The icon needs to follow the same
     * "ignore the app theme" logic, or it goes invisible in light mode where
     * {@link #hoverColor} would otherwise return a near-black. Deliberately
     * a hardcoded constant, not a re-lookup of {@code Theme.DARK}'s own
     * {@link #hoverColor} — so it reads as an intentional fixed value here,
     * not a leftover theme reference.
     */
    public static Color onDarkOverlayColor() {
        return Color.web("#F2F2F6");
    }

    /**
     * A single glyph, redrawn in place whenever {@link #setColor} is called —
     * cheap enough (a handful of stroke calls on a tiny canvas) to repaint on
     * every hover/selection change without needing a cache.
     */
    public static final class IconView extends Canvas {
        private final Glyph glyph;
        private Color color;

        IconView(Glyph glyph, double size, Color color) {
            super(size, size);
            this.glyph = glyph;
            this.color = color;
            paint();
        }

        public void setColor(Color color) {
            this.color = color;
            paint();
        }

        public Color getColor() { return color; }

        private void paint() {
            GraphicsContext gc = getGraphicsContext2D();
            double w = getWidth(), h = getHeight();
            gc.clearRect(0, 0, w, h);
            gc.setStroke(color);
            gc.setFill(color);
            gc.setLineWidth(Math.max(1.3, w / 13.0));
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineJoin(StrokeLineJoin.ROUND);
            switch (glyph) {
                case SETTINGS -> drawSettings(gc, w, h);
                case INFO     -> drawInfo(gc, w, h);
                case CHEVRON  -> drawChevron(gc, w, h);
                case SELECT   -> drawSelect(gc, w, h);
                case ADD      -> drawAdd(gc, w, h);
                case SNAP     -> drawSnap(gc, w, h);
                case MOTION   -> drawMotion(gc, w, h);
                case CHAOS    -> drawChaos(gc, w, h);
                case GRAPHS   -> drawGraphs(gc, w, h);
                case HISTORY  -> drawHistory(gc, w, h);
                case LINKS    -> drawLinks(gc, w, h);
                case DISPLAY  -> drawDisplay(gc, w, h);
                case PENDULUM -> drawPendulum(gc, w, h);
                case RESERVED -> drawReserved(gc, w, h);
            }
        }

        // ---------------------------------------------------------------
        // Individual glyphs — every coordinate is a fraction of w/h so the
        // same drawing code renders correctly at any requested size.
        // ---------------------------------------------------------------

        private static void drawSettings(GraphicsContext gc, double w, double h) {
            double cx = w / 2, cy = h / 2;
            double r = w * 0.26;
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
            double innerR = r * 0.42;
            gc.strokeOval(cx - innerR, cy - innerR, innerR * 2, innerR * 2);
            int teeth = 8;
            double tickIn = r * 1.15, tickOut = r * 1.5;
            for (int i = 0; i < teeth; i++) {
                double a = Math.toRadians(i * 360.0 / teeth);
                double x1 = cx + tickIn * Math.cos(a), y1 = cy + tickIn * Math.sin(a);
                double x2 = cx + tickOut * Math.cos(a), y2 = cy + tickOut * Math.sin(a);
                gc.strokeLine(x1, y1, x2, y2);
            }
        }

        private static void drawInfo(GraphicsContext gc, double w, double h) {
            double cx = w / 2, cy = h / 2;
            double r = w * 0.36;
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
            double dotR = w * 0.045;
            gc.fillOval(cx - dotR, cy - r * 0.5 - dotR, dotR * 2, dotR * 2);
            gc.strokeLine(cx, cy - r * 0.1, cx, cy + r * 0.5);
        }

        /** A single right-pointing chevron — callers rotate the node 180° to flip its meaning (see class javadoc). */
        private static void drawChevron(GraphicsContext gc, double w, double h) {
            gc.strokePolyline(
                    new double[]{w * 0.36, w * 0.66, w * 0.36},
                    new double[]{h * 0.22, h * 0.50, h * 0.78}, 3);
        }

        private static void drawSelect(GraphicsContext gc, double w, double h) {
            // A simple pointer-cursor silhouette.
            double[] xs = {w * 0.27, w * 0.27, w * 0.46, w * 0.57, w * 0.68, w * 0.55, w * 0.75};
            double[] ys = {h * 0.14, h * 0.78, h * 0.60, h * 0.85, h * 0.79, h * 0.56, h * 0.52};
            gc.fillPolygon(xs, ys, xs.length);
        }

        private static void drawAdd(GraphicsContext gc, double w, double h) {
            gc.strokeLine(w * 0.5, h * 0.22, w * 0.5, h * 0.78);
            gc.strokeLine(w * 0.22, h * 0.5, w * 0.78, h * 0.5);
        }

        private static void drawSnap(GraphicsContext gc, double w, double h) {
            // Horseshoe magnet: an open arc with two legs and pole-cap ticks.
            double cx = w / 2, cy = h * 0.42, r = w * 0.24;
            gc.strokeArc(cx - r, cy - r, r * 2, r * 2, 0, 180, ArcType.OPEN);
            double legBottom = h * 0.78;
            gc.strokeLine(cx - r, cy, cx - r, legBottom);
            gc.strokeLine(cx + r, cy, cx + r, legBottom);
            // Pole caps
            gc.strokeLine(cx - r - w * 0.06, legBottom, cx - r + w * 0.06, legBottom);
            gc.strokeLine(cx + r - w * 0.06, legBottom, cx + r + w * 0.06, legBottom);
        }

        private static void drawMotion(GraphicsContext gc, double w, double h) {
            double cy = h / 2, amp = h * 0.2;
            int segments = 24;
            double[] xs = new double[segments + 1];
            double[] ys = new double[segments + 1];
            for (int i = 0; i <= segments; i++) {
                double t = (double) i / segments;
                xs[i] = w * 0.12 + t * w * 0.76;
                ys[i] = cy + amp * Math.sin(t * Math.PI * 2);
            }
            gc.strokePolyline(xs, ys, xs.length);
        }

        private static void drawChaos(GraphicsContext gc, double w, double h) {
            double originX = w * 0.18, originY = h * 0.5;
            gc.fillOval(originX - w * 0.035, originY - w * 0.035, w * 0.07, w * 0.07);
            gc.strokeLine(originX, originY, w * 0.82, h * 0.22);
            gc.strokeLine(originX, originY, w * 0.82, h * 0.78);
        }

        private static void drawGraphs(GraphicsContext gc, double w, double h) {
            gc.strokeLine(w * 0.18, h * 0.18, w * 0.18, h * 0.82);
            gc.strokeLine(w * 0.18, h * 0.82, w * 0.86, h * 0.82);
            gc.strokePolyline(
                    new double[]{w * 0.26, w * 0.42, w * 0.56, w * 0.72, w * 0.82},
                    new double[]{h * 0.62, h * 0.44, h * 0.58, h * 0.28, h * 0.36}, 5);
        }

        private static void drawHistory(GraphicsContext gc, double w, double h) {
            double cx = w / 2, cy = h / 2, r = w * 0.3;
            // Circle with a gap, plus an arrowhead — reads as "rewind/undo."
            gc.strokeArc(cx - r, cy - r, r * 2, r * 2, 20, 300, ArcType.OPEN);
            double ax = cx + r * Math.cos(Math.toRadians(20));
            double ay = cy - r * Math.sin(Math.toRadians(20));
            gc.strokePolyline(
                    new double[]{ax - w * 0.09, ax, ax - w * 0.02},
                    new double[]{ay - h * 0.02, ay, ay + h * 0.09}, 3);
            // Clock hand
            gc.strokeLine(cx, cy, cx, cy - r * 0.55);
            gc.strokeLine(cx, cy, cx + r * 0.4, cy);
        }

        private static void drawLinks(GraphicsContext gc, double w, double h) {
            double ringW = w * 0.34, ringH = h * 0.46;
            gc.strokeOval(w * 0.14, h * 0.27, ringW, ringH);
            gc.strokeOval(w * 0.52, h * 0.27, ringW, ringH);
        }

        private static void drawDisplay(GraphicsContext gc, double w, double h) {
            double cx = w / 2, cy = h / 2;
            gc.strokeArc(w * 0.1, h * 0.14, w * 0.8, h * 0.62, 0, 180, ArcType.OPEN);
            gc.strokeArc(w * 0.1, h * 0.24, w * 0.8, h * 0.62, 180, 180, ArcType.OPEN);
            double r = w * 0.1;
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }

        /**
         * A double pendulum: fixed pivot, first rod to bob one, second rod
         * bent at an angle to bob two — the app's own icon for the
         * N-Pendulum Chain card (see MainMenuController), reading as the
         * actual apparatus rather than an unrelated glyph.
         */
        private static void drawPendulum(GraphicsContext gc, double w, double h) {
            double pivotX = w * 0.5, pivotY = h * 0.12;
            double bob1X = w * 0.78, bob1Y = h * 0.46;
            double bob2X = w * 0.30, bob2Y = h * 0.88;

            gc.strokeLine(pivotX, pivotY, bob1X, bob1Y);
            gc.strokeLine(bob1X, bob1Y, bob2X, bob2Y);

            double pivotR = w * 0.045;
            gc.fillOval(pivotX - pivotR, pivotY - pivotR, pivotR * 2, pivotR * 2);

            double bob1R = w * 0.11;
            gc.fillOval(bob1X - bob1R, bob1Y - bob1R, bob1R * 2, bob1R * 2);

            double bob2R = w * 0.14;
            gc.fillOval(bob2X - bob2R, bob2Y - bob2R, bob2R * 2, bob2R * 2);
        }

        /** A plain open ring — a reserved-but-unbuilt slot's icon (see NavCardController#configureComingSoon). */
        private static void drawReserved(GraphicsContext gc, double w, double h) {
            double cx = w / 2, cy = h / 2, r = w * 0.32;
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }
    }
}
