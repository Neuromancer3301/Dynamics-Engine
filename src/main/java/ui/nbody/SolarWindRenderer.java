package ui.nbody;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import physics.nbody.MagnetopauseCalculator;
import physics.nbody.NBodyState;
import ui.simcore.Camera;

import java.util.ArrayList;
import java.util.List;

/**
 * Solar-wind particle dynamics & Parker spiral rendering engine.
 * Computes Archimedean spiral advection, binary wind contact discontinuities,
 * and planetary magnetopause sheath deflection.
 */
public final class SolarWindRenderer {

    private final SolarWindParticlePool pool = new SolarWindParticlePool();
    private double lastSimTime = Double.NaN;

    public SolarWindRenderer() {}

    public SolarWindParticlePool getPool() {
        return pool;
    }

    public void reset() {
        pool.reset();
        lastSimTime = Double.NaN;
    }

    /**
     * Updates advection and renders solar wind plasma sparks and streaks.
     */
    public void updateAndRender(
            GraphicsContext gc, NBodyState state, Camera camera,
            double screenW, double screenH, double simTime, double dt,
            boolean showWind, boolean showBowShock, double luminescence) {

        if (state == null || state.getN() == 0 || camera == null) return;

        // 1. Identify active stars (M >= 0.08 M_sun)
        List<Integer> starIndices = new ArrayList<>(4);
        for (int i = 0; i < state.getN(); i++) {
            if (state.isStar(i)) {
                starIndices.add(i);
                if (starIndices.size() >= SolarWindParticlePool.MAX_STARS) break;
            }
        }

        if (starIndices.isEmpty()) {
            pool.reset();
            return;
        }

        // 2. Emit particles from each star
        int numStars = starIndices.size();
        for (int s = 0; s < numStars; s++) {
            int starIdx = starIndices.get(s);
            double sx = state.positionX[starIdx];
            double sy = state.positionY[starIdx];
            double sr = state.radius[starIdx];
            double sRot = state.rotationPeriod[starIdx];

            // Emit ~16 particles per active star per frame
            pool.emit(s, starIdx, sx, sy, sr, sRot, simTime, 16);
        }

        // 3. Binary star interaction setup (Alpha Centauri A & B contact plane)
        boolean hasBinaryContact = false;
        double binaryContactX = 0, binaryContactY = 0;
        double binaryNormX = 0, binaryNormY = 0;
        if (numStars >= 2) {
            int sA = starIndices.get(0);
            int sB = starIndices.get(1);
            double sep = Math.hypot(state.positionX[sB] - state.positionX[sA], state.positionY[sB] - state.positionY[sA]);
            if (sep < 5.0e12) { // within 5e12 m
                hasBinaryContact = true;
                double massSum = state.mass[sA] + state.mass[sB];
                binaryContactX = (state.positionX[sA] * state.mass[sB] + state.positionX[sB] * state.mass[sA]) / massSum;
                binaryContactY = (state.positionY[sA] * state.mass[sB] + state.positionY[sB] * state.mass[sA]) / massSum;
                binaryNormX = (state.positionX[sB] - state.positionX[sA]) / sep;
                binaryNormY = (state.positionY[sB] - state.positionY[sA]) / sep;
            }
        }

        // 4. Update Parker spiral positions and boundary deflections
        for (int i = 0; i < SolarWindParticlePool.MAX_PARTICLES; i++) {
            if (!pool.active[i]) continue;

            double elapsed = simTime - pool.birthTime[i];
            if (elapsed < 0.0) {
                pool.active[i] = false;
                continue;
            }

            double vr = pool.vr[i];
            double rStar = pool.starRadius[i];
            double omega = pool.starRotOmega[i];
            double r = rStar + vr * elapsed;

            // Parker spiral formula: theta(r, t) = theta_0 + Omega * (t - (r - R_star)/v_w)
            double theta = pool.birthAngle[i] + omega * pool.birthTime[i];

            double cosT = Math.cos(theta);
            double sinT = Math.sin(theta);
            double px = pool.starOriginX[i] + r * cosT;
            double py = pool.starOriginY[i] + r * sinT;

            // Approximate velocity: radial + tangential due to stellar rotation
            double pvx = vr * cosT - r * omega * sinT;
            double pvy = vr * sinT + r * omega * cosT;

            // Binary wind collision plane deflection
            if (hasBinaryContact) {
                double toPlaneX = px - binaryContactX;
                double toPlaneY = py - binaryContactY;
                double proj = toPlaneX * binaryNormX + toPlaneY * binaryNormY;
                if (Math.abs(proj) < 1.0e11) {
                    // Deflect outward perpendicular to binary axis
                    double perpX = -binaryNormY;
                    double perpY = binaryNormX;
                    double vPerp = pvx * perpX + pvy * perpY;
                    pvx = 0.95 * vPerp * perpX;
                    pvy = 0.95 * vPerp * perpY;
                    px += pvx * 0.1;
                    py += pvy * 0.1;
                }
            }

            // Planetary magnetopause deflection
            for (int b = 0; b < state.getN(); b++) {
                if (!state.hasMagneticField(b)) continue;

                double dx = px - state.positionX[b];
                double dy = py - state.positionY[b];
                double dist = Math.hypot(dx, dy);
                if (dist <= 1.0e-3) continue;

                double standoff = MagnetopauseCalculator.computeStandoff(b, state);
                int nearestStar = MagnetopauseCalculator.findNearestStar(b, state);
                double uStarX = 1.0, uStarY = 0.0;
                if (nearestStar >= 0) {
                    double sdx = state.positionX[nearestStar] - state.positionX[b];
                    double sdy = state.positionY[nearestStar] - state.positionY[b];
                    double sdist = Math.hypot(sdx, sdy);
                    if (sdist > 1.0e-3) {
                        uStarX = sdx / sdist;
                        uStarY = sdy / sdist;
                    }
                }

                double rx = dx / dist;
                double ry = dy / dist;
                double cosAlpha = rx * uStarX + ry * uStarY;
                double denom = Math.max(1.0e-4, 1.0 + cosAlpha);
                double rMpAlpha = standoff * Math.pow(2.0 / denom, 0.60);

                if (dist < rMpAlpha) {
                    // Deflect along magnetosheath: v' = 0.95 * v_parallel + 0.1 * v_perp
                    double nX = rx, nY = ry;
                    double tX = -nY, tY = nX;
                    if (pvx * tX + pvy * tY < 0.0) {
                        tX = -tX;
                        tY = -tY;
                    }
                    double vTangential = pvx * tX + pvy * tY;
                    double vNormal = pvx * nX + pvy * nY;

                    pvx = 0.95 * vTangential * tX + 0.1 * Math.abs(vNormal) * nX;
                    pvy = 0.95 * vTangential * tY + 0.1 * Math.abs(vNormal) * nY;

                    // Push particle outward to boundary to create plasma-free cavity
                    px = state.positionX[b] + rMpAlpha * nX;
                    py = state.positionY[b] + rMpAlpha * nY;
                }
            }

            pool.x[i] = px;
            pool.y[i] = py;
            pool.vx[i] = pvx;
            pool.vy[i] = pvy;
        }

        // 5. Cull out-of-bounds particles
        pool.cull(camera, screenW, screenH, 100.0);

        // 6. Render if showWind is enabled
        if (!showWind) return;

        double lum = Math.max(0.05, Math.min(1.0, luminescence));
        gc.setLineWidth(1.5);

        for (int i = 0; i < SolarWindParticlePool.MAX_PARTICLES; i++) {
            if (!pool.active[i] || pool.alpha[i] <= 0.0) continue;

            double sx = camera.worldToScreenX(pool.x[i], screenW);
            double sy = camera.worldToScreenY(pool.y[i], screenH);

            if (sx < -10 || sx > screenW + 10 || sy < -10 || sy > screenH + 10) continue;

            double vSpeed = Math.hypot(pool.vx[i], pool.vy[i]);
            double dirX = (vSpeed > 1e-3) ? (pool.vx[i] / vSpeed) : 1.0;
            double dirY = (vSpeed > 1e-3) ? (pool.vy[i] / vSpeed) : 0.0;

            double sparkAlpha = pool.alpha[i] * lum;
            gc.setStroke(Color.color(0.91, 0.83, 0.29, sparkAlpha)); // #E8D34A golden yellow
            gc.strokeLine(sx, sy, sx - dirX * 3.0, sy - dirY * 3.0);
        }

        lastSimTime = simTime;
    }
}
