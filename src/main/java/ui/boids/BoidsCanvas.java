package ui.boids;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import simulation.boids.Boid;
import simulation.boids.Flock;
import ui.simcore.SimCanvas;

public class BoidsCanvas extends SimCanvas {

    private Flock flock;
    private float boidSize = 67.5f;
    private double simulatedTimeSeconds = 0.0;

    private boolean showPerceptionRadius = false;
    private boolean showCanvasBoundary = false;

    public void setShowPerceptionRadius(boolean show) {
        this.showPerceptionRadius = show;
        render();
    }

    public void setShowCanvasBoundary(boolean show) {
        this.showCanvasBoundary = show;
        render();
    }

    private static final Color ACCENT = Color.web("#EA3F8C");
    private static final Color PREDATOR_COLOR = Color.web("#FF8A3D");

    public BoidsCanvas(double width, double height) {
        super(width, height);
    }

    public void setFlock(Flock flock) {
        this.flock = flock;
    }

    public void setBoidSize(float size) {
        this.boidSize = size;
    }

    public void setSimulatedTime(double seconds) {
        this.simulatedTimeSeconds = seconds;
    }

    public double getWorldWidth() {
        return 9000;
    }

    public double getWorldHeight() {
        return 9000;
    }

    @Override
    protected double contentExtent() {
        // Return 6000 so the camera zooms to fit 6000 units.
        // Since the world is 9000x9000, the bounds will physically overflow the screen
        // by 1.5x!
        return 6000;
    }

    @Override
    protected boolean hasContent() {
        return flock != null;
    }

    @Override
    protected void drawContent(GraphicsContext gc, double w, double h) {
        if (flock == null)
            return;

        double worldW = getWorldWidth();
        double worldH = getWorldHeight();

        // SimCanvas has originYFraction = 0.46, so we offset by +0.04*h to perfectly
        // center the box
        double centerY = camera.originY(h) + 0.04 * h;

        // Draw bounding box
        double scaledW = worldW * camera.getScale();
        double scaledH = worldH * camera.getScale();
        double topLeftX = camera.originX(w) - scaledW / 2.0;
        double topLeftY = centerY - scaledH / 2.0;

        if (showCanvasBoundary) {
            gc.setStroke(ACCENT.deriveColor(0, 1, 1, 0.4));
            gc.setLineDashes(15, 10);
            gc.setLineWidth(2);
            gc.strokeRect(topLeftX, topLeftY, scaledW, scaledH);

            gc.setStroke(ACCENT.deriveColor(0, 1, 1, 0.1));
            gc.setLineDashes(); // reset dashes
            gc.setLineWidth(8);
            gc.strokeRect(topLeftX, topLeftY, scaledW, scaledH);
        }

        for (int i = 0; i < flock.size(); ++i) {
            Boid b = flock.get(i);

            gc.save();
            // Map boids to centered world bounds
            double bx = camera.originX(w) + (b.position.x - worldW / 2.0) * camera.getScale();
            double by = centerY + (b.position.y - worldH / 2.0) * camera.getScale();

            gc.translate(bx, by);

            // Boid angle is usually in degrees. In boids_java it's b.angle() which returns
            // degrees.
            gc.rotate(b.angle());

            double size = (b.isPredator ? boidSize * 1.5 : boidSize * 1.2) * camera.getScale();
            Color baseColor = b.isPredator ? PREDATOR_COLOR : ACCENT;

            if (showPerceptionRadius) {
                // Draw glow
                gc.setFill(baseColor.deriveColor(0, 1, 1, 0.18));
                double glowR = size * 3.0;
                gc.fillOval(-glowR, -glowR, glowR * 2, glowR * 2);
            }

            // Draw dart shape
            gc.beginPath();
            gc.moveTo(0, -size * 2);
            gc.lineTo(-size, size);
            gc.lineTo(size, size);
            gc.closePath();

            RadialGradient gradient = new RadialGradient(
                    0, 0,
                    0, 0,
                    size * 2,
                    false, CycleMethod.NO_CYCLE,
                    new Stop(0, baseColor.brighter()),
                    new Stop(1, baseColor.darker().darker()));
            gc.setFill(gradient);
            gc.fill();

            gc.setStroke(Color.web("#000000", 0.35));
            gc.setLineWidth(1.0);
            gc.stroke();

            gc.restore();
        }

        // Draw HUD overlay matching Pendulum style
        javafx.scene.text.Font hudFont = javafx.scene.text.Font.font("Monospaced", 14);
        gc.setFont(hudFont);

        String timeStr = String.format("t  = %7.2f s", simulatedTimeSeconds);

        javafx.scene.text.Text textNode = new javafx.scene.text.Text(timeStr);
        textNode.setFont(hudFont);
        double maxTextW = textNode.getLayoutBounds().getWidth();
        double boxW = Math.max(150, 18 + maxTextW);
        double boxH = 28;
        double boxX = 8;
        double boxY = 8;

        gc.setFill(Color.web("#000000", 0.6));
        gc.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

        gc.setFill(ACCENT);
        gc.fillText(timeStr, boxX + 9, boxY + 19);
    }

    public void render() {
        renderFrame();
    }

    public double screenToWorldX(double screenX) {
        double worldW = getWorldWidth();
        return (screenX - camera.originX(getWidth())) / camera.getScale() + (worldW / 2.0);
    }

    public double screenToWorldY(double screenY) {
        double worldH = getWorldHeight();
        double centerY = camera.originY(getHeight()) + 0.04 * getHeight();
        return (screenY - centerY) / camera.getScale() + (worldH / 2.0);
    }
}
