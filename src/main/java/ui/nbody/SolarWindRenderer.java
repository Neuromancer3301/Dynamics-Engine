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
 * Computes Archimedean spiral advection across interplanetary space (macro scale),
 * binary wind contact discontinuities, coronal mass ejection (CME) shock fronts,
 * and physical magnetopause deflection with shock-heated magnetosheath flow
 * across magnetized planets in a unified physical domain.
 */
public final class SolarWindRenderer {

    private final SolarWindParticlePool pool = new SolarWindParticlePool();
    private double lastSimTime = Double.NaN;
    private boolean paused = false;
    private double speedMultiplier = 100_000.0;

    // Coronal Mass Ejection (CME) / Solar Flare wave state per star
    private final double[] cmeRadius = new double[SolarWindParticlePool.MAX_STARS];
    private final double[] cmeMaxRadius = new double[SolarWindParticlePool.MAX_STARS];
    private final boolean[] cmeActive = new boolean[SolarWindParticlePool.MAX_STARS];
    private final double[] cmeCooldown = new double[SolarWindParticlePool.MAX_STARS];

    public SolarWindRenderer() {
        for (int s = 0; s < SolarWindParticlePool.MAX_STARS; s++) {
            cmeCooldown[s] = 3.0 + s * 4.0;
        }
    }

    public SolarWindParticlePool getPool() {
        return pool;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setSpeedMultiplier(double speedMultiplier) {
        this.speedMultiplier = speedMultiplier;
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    public void reset() {
        pool.reset();
        lastSimTime = Double.NaN;
        for (int s = 0; s < SolarWindParticlePool.MAX_STARS; s++) {
            cmeActive[s] = false;
            cmeRadius[s] = 0.0;
            cmeMaxRadius[s] = 0.0;
            cmeCooldown[s] = 3.0 + s * 4.0;
        }
    }

    /**
     * Compatibility overload matching the original signature.
     */
    public void updateAndRender(
            GraphicsContext gc, NBodyState state, Camera camera,
            double screenW, double screenH, double simTime, double dt,
            boolean showWind, boolean showBowShock, double luminescence) {
        updateAndRender(gc, state, camera, screenW, screenH, simTime, dt,
                showWind, showBowShock, luminescence, this.paused, this.speedMultiplier);
    }

    /**
     * Updates advection and renders solar wind plasma sparks, streaks, and CME shock waves.
     */
    public void updateAndRender(
            GraphicsContext gc, NBodyState state, Camera camera,
            double screenW, double screenH, double simTime, double dt,
            boolean showWind, boolean showBowShock, double luminescence,
            boolean isPaused, double currentSpeedMultiplier) {

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
            reset();
            return;
        }

        // 2. Emit and maintain continuous particles from each star (PURE WORLD SPACE)
        int numStars = starIndices.size();
        for (int s = 0; s < numStars; s++) {
            int starIdx = starIndices.get(s);
            double sx = state.positionX[starIdx];
            double sy = state.positionY[starIdx];
            double sr = state.radius[starIdx];
            double sRot = state.rotationPeriod[starIdx];

            pool.updateEmitter(s, starIdx, sx, sy, sr, sRot, simTime, state);
        }

        // 3. Binary star interaction setup (e.g. Alpha Centauri A & B contact plane)
        boolean hasBinaryContact = false;
        double binaryContactX = 0, binaryContactY = 0;
        double binaryNormX = 0, binaryNormY = 0;
        if (numStars >= 2) {
            int sA = starIndices.get(0);
            int sB = starIndices.get(1);
            double sep = Math.hypot(state.positionX[sB] - state.positionX[sA], state.positionY[sB] - state.positionY[sA]);
            if (sep < 5.0e12) {
                hasBinaryContact = true;
                double massSum = state.mass[sA] + state.mass[sB];
                binaryContactX = (state.positionX[sA] * state.mass[sB] + state.positionX[sB] * state.mass[sA]) / massSum;
                binaryContactY = (state.positionY[sA] * state.mass[sB] + state.positionY[sB] * state.mass[sA]) / massSum;
                binaryNormX = (state.positionX[sB] - state.positionX[sA]) / sep;
                binaryNormY = (state.positionY[sB] - state.positionY[sA]) / sep;
            }
        }

        double camScale = camera.getScale();

        // Effective dt step for simulation time and visual streaming
        double streamDt;

        if (isPaused) {
            // Complete freeze when paused
            streamDt = 0.0;
        } else {
            // Logarithmic speed scaling with simulation speed multiplier (Item 3)
            double m = (currentSpeedMultiplier > 0.0) ? currentSpeedMultiplier : 100_000.0;
            double speedFactor;
            if (m >= 1000.0) {
                speedFactor = 1.0 + 0.38 * Math.log10(m / 1000.0);
            } else {
                speedFactor = Math.max(0.25, 1.0 + 0.38 * Math.log10(m / 1000.0));
            }
            // Item 1: Halve solar wind speed across the board (camera-independent single domain)
            double baseStreamDt = 250.0; // Halved from 500.0
            streamDt = baseStreamDt * speedFactor;
        }

        // 4. Advance CME shock waves and particles
        for (int s = 0; s < numStars; s++) {
            int startIdx = s * SolarWindParticlePool.PARTICLES_PER_STAR;
            int endIdx = startIdx + SolarWindParticlePool.PARTICLES_PER_STAR;

            double starOriginX = pool.starOriginX[startIdx];
            double starOriginY = pool.starOriginY[startIdx];
            double omega = pool.starRotOmega[startIdx];
            double rBase = pool.starRadius[startIdx];

            // Coronal Mass Ejection (CME) shock wave update
            if (!isPaused) {
                if (!cmeActive[s]) {
                    cmeCooldown[s] -= 0.016;
                    if (cmeCooldown[s] <= 0.0) {
                        cmeActive[s] = true;
                        cmeRadius[s] = rBase;
                        cmeMaxRadius[s] = SolarWindParticlePool.MAX_OUTER_RADIUS;
                    }
                } else {
                    double vCme = 1.8 * SolarWindParticlePool.NOMINAL_V_W; // ~810 km/s CME shock wave
                    cmeRadius[s] += vCme * streamDt;
                    if (cmeRadius[s] >= cmeMaxRadius[s]) {
                        cmeActive[s] = false;
                        cmeCooldown[s] = 8.0 + Math.random() * 6.0; // 8-14s cooldown between bursts
                    }
                }
            }

            double curCmeR = cmeRadius[s];
            boolean cmeLive = cmeActive[s];
            double cmeSigma = 0.06 * curCmeR + 6.0e9;

            for (int i = startIdx; i < endIdx; i++) {
                if (!pool.active[i]) continue;

                double vr = pool.vr[i];
                pool.radius[i] += vr * streamDt;

                byte sType = pool.streamType[i];
                double r = pool.radius[i];
                double spiralTheta;

                if (sType == 0) {
                    // Broad 360-degree interplanetary Parker spiral wind
                    if (r > SolarWindParticlePool.MAX_OUTER_RADIUS) {
                        r = rBase + (r - SolarWindParticlePool.MAX_OUTER_RADIUS);
                        pool.radius[i] = r;
                        pool.birthAngle[i] = Math.random() * 2.0 * Math.PI;
                    } else if (r < rBase) {
                        pool.radius[i] = rBase;
                        r = rBase;
                    }
                    spiralTheta = pool.birthAngle[i] - (omega / vr) * (r - rBase) + omega * simTime;
                } else {
                    // Planetary stream bundle (feeder or near-planet)
                    int b = pool.targetPlanet[i];
                    if (b < 0 || b >= state.getN() || state.isStar(b) || !state.hasMagneticField(b)) {
                        // Fallback to broad wind if planet is invalid
                        if (r > SolarWindParticlePool.MAX_OUTER_RADIUS) {
                            r = rBase + (r - SolarWindParticlePool.MAX_OUTER_RADIUS);
                            pool.radius[i] = r;
                            pool.birthAngle[i] = Math.random() * 2.0 * Math.PI;
                        }
                        spiralTheta = pool.birthAngle[i] - (omega / vr) * (r - rBase) + omega * simTime;
                    } else {
                        double bX = state.positionX[b];
                        double bY = state.positionY[b];
                        double dPlanet = Math.hypot(bX - starOriginX, bY - starOriginY);
                        double phiPlanet = Math.atan2(bY - starOriginY, bX - starOriginX);
                        double wCross = pool.interactionWidth[i];

                        if (sType == 1) {
                            // Feeder stream: from star to dPlanet - 1.5 * wCross
                            double rFeederMax = Math.max(rBase + 1.0e9, dPlanet - 1.5 * wCross);
                            if (r > rFeederMax) {
                                r = rBase + (r - rFeederMax);
                                pool.radius[i] = r;
                                pool.birthAngle[i] = (Math.random() * 2.0 - 1.0);
                            } else if (r < rBase) {
                                pool.radius[i] = rBase;
                                r = rBase;
                            }
                        } else {
                            // Near-planet interaction stream: dPlanet - 2.0 * wCross to dPlanet + 6.0 * wCross
                            double rNearMin = Math.max(rBase, dPlanet - 2.0 * wCross);
                            double rNearMax = dPlanet + 6.0 * wCross;
                            if (r > rNearMax) {
                                r = rNearMin + (r - rNearMax);
                                pool.radius[i] = r;
                                pool.birthAngle[i] = (Math.random() * 2.0 - 1.0);
                            } else if (r < rNearMin) {
                                pool.radius[i] = rNearMin;
                                r = rNearMin;
                            }
                        }

                        double deltaThetaMax = (dPlanet > 1.0e6) ? (wCross / dPlanet) : 0.01;
                        double deltaTheta = pool.birthAngle[i] * deltaThetaMax;
                        spiralTheta = phiPlanet + deltaTheta + (omega / vr) * (dPlanet - r);
                    }
                }

                double cosT = Math.cos(spiralTheta);
                double sinT = Math.sin(spiralTheta);
                double px = starOriginX + r * cosT;
                double py = starOriginY + r * sinT;

                // Velocity: radial + azimuthal corotation
                double pvx = vr * cosT - r * omega * sinT;
                double pvy = vr * sinT + r * omega * cosT;

                // Evaluate CME wave interaction on particle
                double particleCme = 0.0;
                if (cmeLive) {
                    double dr = Math.abs(r - curCmeR);
                    if (dr < 2.5 * cmeSigma) {
                        double u = dr / cmeSigma;
                        double geomDecay = Math.max(0.3, Math.min(1.0, Math.sqrt(2.0e11 / Math.max(1.0e10, curCmeR))));
                        particleCme = Math.exp(-0.5 * u * u) * geomDecay;
                        // Forward momentum boost from expanding shock front
                        pvx += particleCme * 0.40 * vr * cosT;
                        pvy += particleCme * 0.40 * vr * sinT;
                    }
                }
                pool.cmeIntensity[i] = particleCme;

                // Binary wind contact plane deflection
                if (hasBinaryContact) {
                    double toPlaneX = px - binaryContactX;
                    double toPlaneY = py - binaryContactY;
                    double proj = toPlaneX * binaryNormX + toPlaneY * binaryNormY;
                    if (Math.abs(proj) < 1.0e11) {
                        double perpX = -binaryNormY;
                        double perpY = binaryNormX;
                        double vPerp = pvx * perpX + pvy * perpY;
                        pvx = 0.95 * vPerp * perpX;
                        pvy = 0.95 * vPerp * perpY;
                        px += pvx * 0.1;
                        py += pvy * 0.1;
                    }
                }

                // Planetary magnetopause & bow shock deflection (STRICTLY EXCLUDE STARS)
                boolean insideAnySheath = false;
                for (int b = 0; b < state.getN(); b++) {
                    if (state.isStar(b) || !state.hasMagneticField(b)) continue;

                    double dx = px - state.positionX[b];
                    double dy = py - state.positionY[b];
                    double dist = Math.hypot(dx, dy);
                    if (dist <= 1.0e-3) continue;

                    double standoff = MagnetopauseCalculator.computeStandoff(b, state);
                    if (dist > 3.2 * standoff) continue;

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
                    double uTailX = -uStarX;
                    double uTailY = -uStarY;
                    double uPerpX = -uStarY;
                    double uPerpY = uStarX;

                    double xTail = dx * uTailX + dy * uTailY;
                    double yPerp = dx * uPerpX + dy * uPerpY;
                    double sideSign = Math.signum(yPerp);
                    if (sideSign == 0.0) sideSign = 1.0;

                    double cosAlpha = -xTail / dist;
                    double denom = Math.max(1.0e-4, 1.0 + cosAlpha);
                    double rMpAlpha = standoff * Math.pow(2.0 / denom, 0.60);
                    double rBsAlpha = 1.28 * rMpAlpha;

                    // Smooth hydrodynamic potential-flow streamline deflection around magnetopause cavity
                    double rScale = 2.4 * rBsAlpha;
                    double wDeflect = Math.exp(-(dist * dist) / (rScale * rScale));
                    double yTarget = sideSign * Math.sqrt(yPerp * yPerp + rMpAlpha * rMpAlpha);
                    double yNew = (1.0 - wDeflect) * yPerp + wDeflect * yTarget;

                    px = state.positionX[b] + xTail * uTailX + yNew * uPerpX;
                    py = state.positionY[b] + xTail * uTailY + yNew * uPerpY;

                    double dNew = Math.hypot(px - state.positionX[b], py - state.positionY[b]);

                    // Tangential flow direction along deflected sheath
                    double tx = sideSign * uPerpX + 0.65 * uTailX;
                    double ty = sideSign * uPerpY + 0.65 * uTailY;
                    double tNorm = Math.hypot(tx, ty);
                    tx /= tNorm;
                    ty /= tNorm;

                    if (dNew <= rBsAlpha) {
                        // Magnetosheath cushion: decelerated, heated, and deflected along flanks
                        double blend = 0.85 * (1.0 - Math.max(0.0, dNew - rMpAlpha) / Math.max(1.0, rBsAlpha - rMpAlpha));
                        pvx = (1.0 - blend) * pvx + blend * (0.55 * vr * tx);
                        pvy = (1.0 - blend) * pvy + blend * (0.55 * vr * ty);
                        insideAnySheath = true;
                        pool.alpha[i] = 1.0;
                        break;
                    }
                }

                pool.inSheath[i] = insideAnySheath;
                if (!insideAnySheath) {
                    pool.alpha[i] = 0.85 + 0.15 * particleCme;
                }

                pool.x[i] = px;
                pool.y[i] = py;
                pool.vx[i] = pvx;
                pool.vy[i] = pvy;
            }
        }

        // 5. Render if showWind is enabled
        if (!showWind) {
            lastSimTime = simTime;
            return;
        }

        double lum = Math.max(0.05, Math.min(1.0, luminescence));

        // Render CME expanding shock front cushions in space
        for (int s = 0; s < numStars; s++) {
            if (cmeActive[s]) {
                int startIdx = s * SolarWindParticlePool.PARTICLES_PER_STAR;
                drawCmeShockFront(gc, camera, pool.starOriginX[startIdx], pool.starOriginY[startIdx],
                        cmeRadius[s], screenW, screenH, lum);
            }
        }

        // Render solar wind plasma sparks and streaks
        for (int i = 0; i < SolarWindParticlePool.MAX_PARTICLES; i++) {
            if (!pool.active[i] || pool.alpha[i] <= 0.0) continue;

            double sx = camera.worldToScreenX(pool.x[i], screenW);
            double sy = camera.worldToScreenY(pool.y[i], screenH);

            if (sx < -20 || sx > screenW + 20 || sy < -20 || sy > screenH + 20) continue;

            double vSpeed = Math.hypot(pool.vx[i], pool.vy[i]);
            double dirX = (vSpeed > 1e-3) ? (pool.vx[i] / vSpeed) : 1.0;
            double dirY = (vSpeed > 1e-3) ? (-pool.vy[i] / vSpeed) : 0.0; // Y inversion for screen

            if (pool.inSheath[i]) {
                // Shock-heated glowing sheath plasma (length 4.2 px, halved from 6.5 px)
                gc.setLineWidth(2.4);
                gc.setStroke(Color.color(1.0, 0.82, 0.28, Math.min(1.0, pool.alpha[i] * lum * 1.35)));
                gc.strokeLine(sx, sy, sx - dirX * 4.2, sy - dirY * 4.2);
            } else if (pool.cmeIntensity[i] > 0.05) {
                // Coronal Mass Ejection / Solar Flare energetic plasma wave
                double cme = pool.cmeIntensity[i];
                double len = 3.2 + cme * 3.0;
                gc.setLineWidth(2.6);
                gc.setStroke(Color.color(1.0, 0.78, 0.20, Math.min(1.0, (pool.alpha[i] * 0.7 + cme * 0.5) * lum)));
                gc.strokeLine(sx, sy, sx - dirX * len, sy - dirY * len);

                gc.setLineWidth(1.3);
                gc.setStroke(Color.color(1.0, 0.98, 0.75, Math.min(1.0, (pool.alpha[i] + cme) * lum)));
                gc.strokeLine(sx, sy, sx - dirX * (len * 0.7), sy - dirY * (len * 0.7));
            } else {
                // Undisturbed fast solar wind streaks (nominal length 3.2 px, halved from 5.0 px)
                gc.setLineWidth(1.3);
                gc.setStroke(Color.color(0.96, 0.88, 0.45, Math.min(1.0, pool.alpha[i] * lum * 0.85)));
                gc.strokeLine(sx, sy, sx - dirX * 3.2, sy - dirY * 3.2);
            }
        }

        lastSimTime = simTime;
    }

    /**
     * Renders an expanding CME plasma shock front cushion across space.
     */
    private void drawCmeShockFront(GraphicsContext gc, Camera camera, double ox, double oy,
                                   double rCme, double screenW, double screenH, double lum) {
        double scx = camera.worldToScreenX(ox, screenW);
        double scy = camera.worldToScreenY(oy, screenH);
        double rPx = rCme * camera.getScale();
        if (rPx < 5.0 || rPx > Math.max(screenW, screenH) * 2.5) return;

        double decay = Math.max(0.25, Math.min(1.0, Math.sqrt(2.0e11 / Math.max(1.0e10, rCme))));
        double baseAlpha = 0.26 * decay * lum;

        gc.save();
        // 1. Broad soft amber halo
        gc.setLineWidth(12.0);
        gc.setStroke(Color.color(1.0, 0.72, 0.18, baseAlpha * 0.35));
        gc.strokeOval(scx - rPx, scy - rPx, rPx * 2.0, rPx * 2.0);

        // 2. Radiant golden compression shock
        gc.setLineWidth(4.0);
        gc.setStroke(Color.color(1.0, 0.86, 0.32, baseAlpha * 0.75));
        gc.strokeOval(scx - rPx, scy - rPx, rPx * 2.0, rPx * 2.0);

        // 3. Incandescent core
        gc.setLineWidth(1.5);
        gc.setStroke(Color.color(1.0, 0.98, 0.80, baseAlpha));
        gc.strokeOval(scx - rPx, scy - rPx, rPx * 2.0, rPx * 2.0);
        gc.restore();
    }
}
