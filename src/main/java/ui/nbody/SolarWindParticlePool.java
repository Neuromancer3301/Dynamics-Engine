package ui.nbody;

import ui.simcore.Camera;

import java.util.Random;

/**
 * Pre-allocated zero-GC particle ring buffer for solar wind plasma advection.
 * Holds up to 2,048 particles per star emitter.
 */
public final class SolarWindParticlePool {

    public static final int PARTICLES_PER_STAR = 2048;
    public static final int MAX_STARS = 4;
    public static final int MAX_PARTICLES = PARTICLES_PER_STAR * MAX_STARS;

    public static final double NOMINAL_V_W = 450_000.0; // 450 km/s
    public static final double V_W_SPREAD = 50_000.0;   // +/- 50 km/s
    public static final double MAX_OUTER_RADIUS = 1.0e13; // 10^13 m outer solar system boundary

    public final double[] x = new double[MAX_PARTICLES];
    public final double[] y = new double[MAX_PARTICLES];
    public final double[] vx = new double[MAX_PARTICLES];
    public final double[] vy = new double[MAX_PARTICLES];
    public final double[] vr = new double[MAX_PARTICLES];
    public final double[] birthAngle = new double[MAX_PARTICLES];
    public final double[] birthTime = new double[MAX_PARTICLES];
    public final double[] starRadius = new double[MAX_PARTICLES];
    public final double[] starRotOmega = new double[MAX_PARTICLES];
    public final double[] starOriginX = new double[MAX_PARTICLES];
    public final double[] starOriginY = new double[MAX_PARTICLES];
    public final double[] alpha = new double[MAX_PARTICLES];
    public final boolean[] active = new boolean[MAX_PARTICLES];
    public final int[] starSource = new int[MAX_PARTICLES];

    private final int[] nextSlot = new int[MAX_STARS];
    private final Random rng = new Random(42);

    public SolarWindParticlePool() {
        for (int s = 0; s < MAX_STARS; s++) {
            nextSlot[s] = s * PARTICLES_PER_STAR;
        }
    }

    /**
     * Emits count particles from starSlot around (originX, originY).
     */
    public void emit(int starSlot, int starIndex, double originX, double originY,
                     double rStar, double starRotationPeriod, double simTime, int count) {
        if (starSlot < 0 || starSlot >= MAX_STARS || count <= 0) return;

        int startIdx = starSlot * PARTICLES_PER_STAR;
        int endIdx = startIdx + PARTICLES_PER_STAR;
        double omega = (starRotationPeriod > 0.0) ? (2.0 * Math.PI / starRotationPeriod) : 0.0;

        for (int i = 0; i < count; i++) {
            int slot = nextSlot[starSlot];
            nextSlot[starSlot]++;
            if (nextSlot[starSlot] >= endIdx) {
                nextSlot[starSlot] = startIdx;
            }

            double theta0 = rng.nextDouble() * 2.0 * Math.PI;
            double speed = NOMINAL_V_W + (rng.nextDouble() * 2.0 - 1.0) * V_W_SPREAD;

            birthAngle[slot] = theta0;
            birthTime[slot] = simTime;
            vr[slot] = speed;
            starRadius[slot] = Math.max(1.0e6, rStar);
            starRotOmega[slot] = omega;
            starOriginX[slot] = originX;
            starOriginY[slot] = originY;
            starSource[slot] = starIndex;

            x[slot] = originX + rStar * Math.cos(theta0);
            y[slot] = originY + rStar * Math.sin(theta0);
            vx[slot] = speed * Math.cos(theta0);
            vy[slot] = speed * Math.sin(theta0);
            alpha[slot] = 1.0;
            active[slot] = true;
        }
    }

    /**
     * Culls particles that exceed MAX_OUTER_RADIUS or leave the camera viewport plus margin.
     */
    public void cull(Camera camera, double screenW, double screenH, double viewportMargin) {
        if (camera == null) return;
        double minX = -viewportMargin;
        double maxX = screenW + viewportMargin;
        double minY = -viewportMargin;
        double maxY = screenH + viewportMargin;

        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!active[i]) continue;

            double dx = x[i] - starOriginX[i];
            double dy = y[i] - starOriginY[i];
            if (dx * dx + dy * dy > MAX_OUTER_RADIUS * MAX_OUTER_RADIUS) {
                active[i] = false;
                continue;
            }

            double sx = camera.worldToScreenX(x[i], screenW);
            double sy = camera.worldToScreenY(y[i], screenH);
            if (sx < minX || sx > maxX || sy < minY || sy > maxY) {
                // Keep active if within outer solar system so spiral topology doesn't abruptly vanish offscreen
                // But fade alpha
                alpha[i] = Math.max(0.0, alpha[i] - 0.05);
                if (alpha[i] <= 0.0) {
                    active[i] = false;
                }
            }
        }
    }

    /** Clears all particles. */
    public void reset() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            active[i] = false;
            alpha[i] = 0.0;
        }
        for (int s = 0; s < MAX_STARS; s++) {
            nextSlot[s] = s * PARTICLES_PER_STAR;
        }
    }
}
