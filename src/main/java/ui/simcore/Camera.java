package ui.simcore;

/**
 * Owns pan/zoom and the world&harr;screen transform for a {@link SimCanvas}.
 * Pure math/state — no event wiring, no rendering, no knowledge of what's
 * actually being drawn.
 *
 * <p><b>Resize behaviour:</b> neither {@link #getScale} nor the pan offset
 * react to a width/height change on their own — a resize is not, on its
 * own, a refit or a re-frame. {@code SimCanvas#renderFrame} is what decides
 * a resize actually happened (its own width/height differing from the
 * previous frame's) and which of two things to do about it: the very first
 * real frame calls {@link #fitToContent} (there's nothing yet to preserve);
 * every resize after that calls {@link #rescaleForViewport} instead, which
 * scales {@link #baseScale} and the pan offset in place (per-axis, not by
 * one shared ratio — see that method's javadoc for why) rather than
 * recomputing a fresh centered fit (round 1.3 §1). Neither is ever
 * triggered by the content's own extent changing (Add/Delete, a length
 * edit) — see {@code SimCanvas#renderFrame}'s javadoc. The origin fraction
 * ({@link #originXFraction}/{@link
 * #originYFraction}) is stored as a fraction of width/height rather than an
 * absolute pixel offset so that {@link #originX}/{@link #originY} track a
 * resizing viewport continuously even between the explicit rescale calls
 * above.
 */
public final class Camera {

    private static final double MIN_ZOOM = 0.025, MAX_ZOOM = 50;

    private double baseScale = 1.0;
    private double originXFraction = 0.5, originYFraction = 0.46; // fraction of W/H — resize-proof
    private double zoom = 1.0;
    private double panX = 0.0, panY = 0.0;

    /** Recomputes the base scale so {@code contentExtent} world-units fit the given viewport, and resets zoom/pan to identity. */
    public void fitToContent(double width, double height, double contentExtent) {
        this.baseScale = Math.min(width * 0.40, height * 0.40) / Math.max(contentExtent, 0.1);
        this.zoom = 1.0;
        this.panX = 0.0;
        this.panY = 0.0;
    }

    /** Translates the origin by a screen-space delta — used for drag-to-pan. */
    public void pan(double dxScreen, double dyScreen) {
        panX += dxScreen;
        panY += dyScreen;
    }

    /**
     * Scales the camera in place for a viewport-size change, instead of
     * re-fitting from scratch. Two different ratios are deliberately in
     * play, not one:
     *
     * <ul>
     *   <li>{@link #baseScale} scales by {@code min(newWidth,newHeight) /
     *       min(oldWidth,oldHeight)} — the same ratio a fresh {@link
     *       #fitToContent} would have produced (its {@code contentExtent}
     *       and 0.4 constant both cancel out of it), so content grows/
     *       shrinks isotropically (never stretched) exactly like a fresh
     *       fit would at the new size.</li>
     *   <li>{@code panX}/{@code panY} scale by {@code newWidth/oldWidth}
     *       and {@code newHeight/oldHeight} <em>respectively</em> — not
     *       the same isotropic ratio above. {@link #originX}/{@link
     *       #originY} are {@code originFraction*dimension + pan}; scaling
     *       each pan component by its own dimension's ratio is what keeps
     *       {@code originX(width)/width} (and the {@code Y} equivalent)
     *       exactly constant across the resize — e.g. a pivot sitting 55%
     *       of the way across stays at 55% after a sidebar toggle that
     *       only changes width, not just after a uniform resize. Reusing
     *       the isotropic ratio here instead would only preserve that
     *       relative position when width and height happen to change by
     *       the same factor — never true for a sidebar/graph column, which
     *       only ever moves width.</li>
     * </ul>
     *
     * <p>The distinction from {@link #fitToContent} is the whole point:
     * that method recenters on the world origin at zoom 1, discarding
     * whatever the user had panned/zoomed to — exactly right for Reset/
     * Apply Changes, wrong for a sidebar or graph column merely resizing
     * the viewport out from under an unrelated gesture. Scaling {@code
     * baseScale} (not {@link #zoom}) keeps the user's deliberate zoom
     * multiplier meaningful as "how far beyond the viewport-fit baseline."
     */
    public void rescaleForViewport(double oldWidth, double oldHeight, double newWidth, double newHeight) {
        if (oldWidth <= 0 || oldHeight <= 0) return; // nothing sane to scale from — leave the camera as-is
        double scaleRatio = Math.min(newWidth, newHeight) / Math.min(oldWidth, oldHeight);
        baseScale *= scaleRatio;
        panX *= newWidth / oldWidth;
        panY *= newHeight / oldHeight;
    }

    /**
     * Multiplies the zoom factor, clamped to [{@link #MIN_ZOOM}, {@link
     * #MAX_ZOOM}], while keeping the world point currently under {@code
     * (anchorScreenX, anchorScreenY)} fixed on screen — standard
     * zoom-to-cursor behaviour.
     *
     * <p>Needs {@code width}/{@code height}: the screen origin is {@code
     * originXFraction*width + panX} (see {@link #originX}), not {@code
     * panX} alone, so keeping the anchor fixed means solving for a new
     * {@code panX} against that whole expression — using {@code
     * anchorScreenX} in place of it (as if the origin fraction were zero)
     * makes the anchor drift sideways by {@code originXFraction*width*
     * (newScale/oldScale - 1)} every zoom step, worse the further the
     * fraction sits from 0.
     */
    public void zoomBy(double factor, double anchorScreenX, double anchorScreenY, double width, double height) {
        double oldScale = getScale();
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        double newScale = getScale();
        double k = newScale / oldScale;
        double originXFrac = originXFraction * width;
        double originYFrac = originYFraction * height;
        panX = (anchorScreenX - originXFrac) * (1 - k) + k * panX;
        panY = (anchorScreenY - originYFrac) * (1 - k) + k * panY;
    }

    public double getScale() { return baseScale * zoom; }

    public double originX(double width)  { return originXFraction * width  + panX; }
    public double originY(double height) { return originYFraction * height + panY; }

    public double worldToScreenX(double worldX, double width)  { return originX(width) + worldX * getScale(); }
    public double worldToScreenY(double worldY, double height) { return originY(height) - worldY * getScale(); }

    public double screenToWorldX(double screenX, double width)  { return (screenX - originX(width)) / getScale(); }
    public double screenToWorldY(double screenY, double height) { return -(screenY - originY(height)) / getScale(); }
}
