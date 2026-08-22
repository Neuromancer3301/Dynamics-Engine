package ui.simcore;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Simulation-agnostic base for a JavaFX {@link Canvas} that renders a
 * scrollable/zoomable/pannable 2D scene via a {@link Camera}.
 *
 * <p>Owns the background grid, the "waiting for first frame" message, the
 * scroll-to-zoom gesture, and the world-unit scale-bar indicator — nothing
 * here knows what's actually being drawn; that's {@link #drawContent} and
 * {@link #contentExtent()}, supplied by a subclass (see {@code
 * ui.pendulum.PendulumCanvas}).
 *
 * <p>Pan-on-empty-space is deliberately <em>not</em> wired here — see {@code
 * ui.pendulum.PendulumInteraction}'s javadoc for why that needs to live with
 * a subclass's own hit-testing instead of in this base class.
 */
public abstract class SimCanvas extends Canvas {

    protected final Camera camera = new Camera();
    private boolean everFitted = false;
    private double lastWidth = -1, lastHeight = -1;

    // Candidate reference lengths for the scale bar, in the same world units
    // a simulation's own content uses. "Nice" values, map-legend style, so
    // the bar reads as a round number rather than something like "0.73 m".
    private static final double[] SCALE_BAR_NICE_LENGTHS =
            {0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500};
    private static final double SCALE_BAR_TARGET_PX = 70;

    protected SimCanvas(double width, double height) {
        super(width, height);
        setOnScroll(this::handleScroll);
    }

    private void handleScroll(ScrollEvent e) {
        double factor = e.getDeltaY() > 0 ? 1.1 : (1 / 1.1);
        camera.zoomBy(factor, e.getX(), e.getY(), getWidth(), getHeight());
        e.consume();
    }

    /** The world-unit extent of the current content (e.g. total chain length) — feeds {@link Camera#fitToContent}. */
    protected abstract double contentExtent();

    /** Draws whatever this simulation renders, once background/waiting-message/scale-bar are already handled. */
    protected abstract void drawContent(GraphicsContext gc, double width, double height);

    /** Whether there's anything to draw yet — while false, {@link #renderFrame} shows the waiting message instead. */
    protected abstract boolean hasContent();

    /** Re-fits the camera to the current content and viewport size. */
    public final void fitToContent() {
        camera.fitToContent(getWidth(), getHeight(), contentExtent());
    }

    /**
     * Draws one full frame: background (including the world-origin axes),
     * then either the waiting message or the real content plus the scale
     * indicator.
     *
     * <p>Round 1.3 §1: re-fitting is tied to an actual <em>viewport</em>
     * resize (this canvas's own width/height changing frame-to-frame —
     * sidebar/graph column toggle, window resize), never to the content's
     * own extent changing (Add/Delete, a length edit) — that part is
     * unchanged from round 1.2. What changed is what a detected resize
     * <em>does</em>: the very first real frame calls {@link #fitToContent}
     * (there's no prior view yet to preserve), but every resize after that
     * calls {@link Camera#rescaleForViewport} instead — scaling whatever
     * the camera currently is (fresh-fit, or however the user panned/
     * zoomed it) by the viewport's size ratio, rather than snapping back to
     * a fresh centered fit at zoom 1. A full {@link #fitToContent} on every
     * resize (round 1.2's approach) looked fine before the user ever
     * touched the camera — a centered fit and "the untouched view scaled to
     * the new size" are the same thing — but after a manual pan/zoom it
     * silently threw that framing away on the next sidebar toggle, which
     * reads as the camera snapping back to origin rather than the canvas
     * just resizing. Content growing or shrinking on its own still never
     * triggers either path — Add, Delete, and a length edit never touch
     * this canvas's own width/height — matching round 1's original intent
     * that only Reset/Apply Changes (via their own explicit {@link
     * #fitToContent} calls) move the camera for a structural edit.
     *
     * <p>Both paths are guarded by {@code w > 0 && h > 0}: the canvas can
     * report a 0 width/height for a frame or two before its bound host pane
     * has actually been laid out, and unlike a full re-fit (idempotent —
     * garbage in, garbage out, but self-correcting the instant a real size
     * arrives), {@link Camera#rescaleForViewport} <em>multiplies</em>
     * whatever's already there by a ratio computed against the previous
     * size — so a zero-sized transient at the moment {@link #everFitted}
     * first goes true would otherwise get locked in as {@code baseScale}
     * either directly (a 0/0 fit) or, worse, as the "old" size a later
     * genuine resize divides by (a division blowing {@code baseScale} up
     * towards infinity instead of self-correcting). Skipping the fit/
     * rescale entirely on a 0-sized frame is harmless — nothing is visibly
     * drawn into a 0×0 canvas anyway — and leaves {@link #everFitted}
     * false, so the first frame that reports a real size is treated as the
     * initial fit, never as a resize away from a bogus one.
     */
    protected final void renderFrame() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();

        drawBackground(gc, w, h);

        if (!hasContent()) {
            drawWaitingMessage(gc, w, h);
            return;
        }
        if (w > 0 && h > 0) {
            if (!everFitted) {
                fitToContent();
                everFitted = true;
            } else if (w != lastWidth || h != lastHeight) {
                camera.rescaleForViewport(lastWidth, lastHeight, w, h);
            }
            lastWidth = w;
            lastHeight = h;
        }
        drawContent(gc, w, h);
        drawScaleIndicator(gc, w, h);
    }

    /** Near-void, neutral grey grid plus the world-origin axes — moved verbatim from {@code ui.PendulumCanvas}, minus the old fixed-center line (see {@link #drawOriginAxes}). */
    protected void drawBackground(GraphicsContext gc, double w, double h) {
        gc.setFill(Color.web("#08080B"));
        gc.fillRect(0, 0, w, h);

        gc.setStroke(Color.web("#1C1C22", 0.7));
        gc.setLineWidth(0.5);
        for (double x = 0; x < w; x += 45) gc.strokeLine(x, 0, x, h);
        for (double y = 0; y < h; y += 45) gc.strokeLine(0, y, w, y);

        drawOriginAxes(gc, w, h);
    }

    /**
     * The X and Y axes through the world origin (0, 0) — simulation-agnostic
     * so every {@code SimCanvas} subclass gets them for free, not just the
     * pendulum. Anchored to {@link Camera#originX}/{@link Camera#originY}
     * rather than a fixed screen point, so — unlike the single fixed-center
     * vertical line this replaces — both lines pan (and re-center on a
     * resize) exactly in step with everything else drawn in world space.
     */
    protected void drawOriginAxes(GraphicsContext gc, double w, double h) {
        double originX = camera.originX(w);
        double originY = camera.originY(h);

        gc.setStroke(Color.web("#2E2E36", 0.6));
        gc.setLineWidth(1.0);
        gc.strokeLine(originX, 0, originX, h);
        gc.strokeLine(0, originY, w, originY);
    }

    /** Shown for the brief window before the first real frame is available. Moved verbatim from {@code ui.PendulumCanvas}. */
    protected void drawWaitingMessage(GraphicsContext gc, double w, double h) {
        gc.setFill(Color.web("#7C7C88", 0.9));
        gc.setFont(Font.font("System", javafx.scene.text.FontWeight.NORMAL, 17));
        gc.fillText("Initialising physics engine...", w / 2 - 105, h / 2);
    }

    /**
     * A ruler showing what one world-length unit looks like at the current
     * {@link Camera#getScale}. Anchored to a fixed corner (bottom-left)
     * rather than a pivot — this base class has no notion of a pivot; a
     * subclass wanting a pivot-anchored indicator can override this.
     * Judgment call, flagged: this changes where the ruler sits on screen
     * relative to the previous pivot-anchored version.
     */
    protected void drawScaleIndicator(GraphicsContext gc, double w, double h) {
        double scale = camera.getScale();
        double referenceLength = pickScaleBarLength(scale);
        double barPx = referenceLength * scale;
        double barX = 12;
        double barY = h - 12;

        gc.setStroke(Color.web("#8A8A94", 0.85));
        gc.setLineWidth(1.5);
        gc.strokeLine(barX, barY, barX + barPx, barY);
        gc.strokeLine(barX, barY - 4, barX, barY + 4);
        gc.strokeLine(barX + barPx, barY - 4, barX + barPx, barY + 4);

        gc.setFill(Color.web("#8A8A94"));
        gc.setFont(Font.font("Monospaced", 10));
        gc.setTextAlign(TextAlignment.CENTER);
        String label = (referenceLength == Math.floor(referenceLength))
                ? String.format("%d m", (int) referenceLength)
                : String.format("%s m", referenceLength);
        gc.fillText(label, barX + barPx / 2.0, barY - 8);
        gc.setTextAlign(TextAlignment.LEFT); // restore default for every other fillText
    }

    /** Picks the nice reference length whose pixel length lands closest to {@link #SCALE_BAR_TARGET_PX}. */
    private static double pickScaleBarLength(double scale) {
        double best = SCALE_BAR_NICE_LENGTHS[0];
        double bestDiff = Double.MAX_VALUE;
        for (double candidate : SCALE_BAR_NICE_LENGTHS) {
            double diff = Math.abs(candidate * scale - SCALE_BAR_TARGET_PX);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        return best;
    }
}
