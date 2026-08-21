package ui.simcore;

/**
 * Owns pan/zoom and the world&harr;screen transform for a {@link SimCanvas}.
 * Pure math/state — no event wiring, no rendering, no knowledge of what's
 * actually being drawn.
 *
 * <p><b>Resize behaviour:</b> {@link #getScale} and the pan offset never
 * react to a width/height change on their own — a resize is not a refit.
 * {@link #fitToContent} is the only thing that moves them, and callers
 * decide when that's warranted (see {@code SimCanvas#renderFrame} for the
 * one-time initial fit, and round 1 §8 for the deliberate refit points).
 * The origin fraction ({@link #originXFraction}/{@link #originYFraction})
 * is stored as a fraction of width/height rather than an absolute pixel
 * offset specifically so a layout column collapsing/expanding re-centers
 * smoothly instead of leaving content pinned to a stale pivot.
 */
public final class Camera {
    private static final double MIN_ZOOM = 0.25, MAX_ZOOM = 6.0;

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
     * Multiplies the zoom factor, clamped to [{@link #MIN_ZOOM}, {@link
     * #MAX_ZOOM}], while keeping the world point currently under {@code
     * (anchorScreenX, anchorScreenY)} fixed on screen — standard
     * zoom-to-cursor behaviour.
     */
    public void zoomBy(double factor, double anchorScreenX, double anchorScreenY) {
        double oldScale = getScale();
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        double newScale = getScale();
        panX = anchorScreenX - (anchorScreenX - panX) * (newScale / oldScale);
        panY = anchorScreenY - (anchorScreenY - panY) * (newScale / oldScale);
    }

    public double getScale() { return baseScale * zoom; }

    public double originX(double width)  { return originXFraction * width  + panX; }
    public double originY(double height) { return originYFraction * height + panY; }

    public double worldToScreenX(double worldX, double width)  { return originX(width) + worldX * getScale(); }
    public double worldToScreenY(double worldY, double height) { return originY(height) - worldY * getScale(); }

    public double screenToWorldX(double screenX, double width)  { return (screenX - originX(width)) / getScale(); }
    public double screenToWorldY(double screenY, double height) { return -(screenY - originY(height)) / getScale(); }
}
