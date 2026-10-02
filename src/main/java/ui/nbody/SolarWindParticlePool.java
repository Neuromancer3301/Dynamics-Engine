package ui.nbody;

import physics.nbody.MagnetopauseCalculator;
import physics.nbody.NBodyState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pre-allocated zero-GC single-domain particle pool for solar wind plasma advection.
 * All particles exist in real physical world coordinates (x, y) with physical velocities (vx, vy).
 * Continuous Parker spiral streamlines are maintained across both macroscopic interplanetary space
 * (AU scale) and planetary magnetopause environments (planetary scale) in a single unified physical domain,
 * completely independent of camera zoom or viewport coordinates.
 */
public final class SolarWindParticlePool {

    public static final int PARTICLES_PER_STAR = 8192;
    public static final int GLOBAL_PARTICLES = 4096; // 360-degree interplanetary Parker spiral coverage
    public static final int PLANETARY_PARTICLES = PARTICLES_PER_STAR - GLOBAL_PARTICLES; // 4096 stream particles

    public static final int MAX_STARS = 4;
    public static final int MAX_PARTICLES = PARTICLES_PER_STAR * MAX_STARS;

    public static final double NOMINAL_V_W = 450_000.0; // 450 km/s
    public static final double V_W_SPREAD = 50_000.0;   // +/- 50 km/s
    public static final double MAX_OUTER_RADIUS = 6.0e12; // 6*10^12 m (~40 AU, Kuiper belt boundary)

    // Stream types:
    // 0 = 360-degree broad interplanetary Parker spiral
    // 1 = Interplanetary feeder stream towards a magnetized planet
    // 2 = Near-planet magnetosphere / magnetopause interaction stream
    public final byte[] streamType = new byte[MAX_PARTICLES];
    public final int[] targetPlanet = new int[MAX_PARTICLES];
    public final double[] interactionWidth = new double[MAX_PARTICLES];

    // Unified single-domain physical particle arrays in world space (meters, m/s)
    public final double[] x = new double[MAX_PARTICLES];
    public final double[] y = new double[MAX_PARTICLES];
    public final double[] vx = new double[MAX_PARTICLES];
    public final double[] vy = new double[MAX_PARTICLES];
    public final double[] vr = new double[MAX_PARTICLES];
    public final double[] radius = new double[MAX_PARTICLES];
    public final double[] birthAngle = new double[MAX_PARTICLES];
    public final double[] birthTime = new double[MAX_PARTICLES];
    public final double[] starRadius = new double[MAX_PARTICLES];
    public final double[] starRotOmega = new double[MAX_PARTICLES];
    public final double[] starOriginX = new double[MAX_PARTICLES];
    public final double[] starOriginY = new double[MAX_PARTICLES];
    public final double[] alpha = new double[MAX_PARTICLES];
    public final double[] cmeIntensity = new double[MAX_PARTICLES];
    public final boolean[] active = new boolean[MAX_PARTICLES];
    public final boolean[] inSheath = new boolean[MAX_PARTICLES];
    public final int[] starSource = new int[MAX_PARTICLES];
    public final boolean[] starInitialized = new boolean[MAX_STARS];

    private final Random rng = new Random(42);
    private long lastStateFingerprint = 0L;

    public SolarWindParticlePool() {}

    private static long computeStateFingerprint(NBodyState state) {
        if (state == null) return 0L;
        long hash = state.getN();
        for (int i = 0; i < state.getN(); i++) {
            hash = hash * 31L + Double.doubleToLongBits(state.mass[i]);
            hash = hash * 31L + Double.doubleToLongBits(state.magneticMoment[i]);
        }
        return hash;
    }

    /**
     * Initializes unified world-space particle distributions for the star:
     * 1) 360-degree interplanetary Parker spiral wind spanning [R_star, MAX_OUTER_RADIUS]
     * 2) Physical planetary flux tube streams targeted at every magnetized planet in the state.
     * ZERO CAMERA DEPENDENCE: completely independent of camera position, zoom, or viewport.
     */
    public void updateEmitter(int starSlot, int starIndex, double originX, double originY,
                              double rStar, double starRotationPeriod, double simTime,
                              NBodyState state) {
        if (starSlot < 0 || starSlot >= MAX_STARS) return;

        long fp = computeStateFingerprint(state);
        if (fp != lastStateFingerprint && fp != 0L) {
            reset();
            lastStateFingerprint = fp;
        }

        int startIdx = starSlot * PARTICLES_PER_STAR;
        double omega = (starRotationPeriod > 0.0) ? (2.0 * Math.PI / starRotationPeriod) : 0.0;
        double rBase = Math.max(1.0e6, rStar);

        // Record star origin for reference
        for (int slot = startIdx; slot < startIdx + PARTICLES_PER_STAR; slot++) {
            starOriginX[slot] = originX;
            starOriginY[slot] = originY;
            starRadius[slot] = rBase;
            starRotOmega[slot] = omega;
            starSource[slot] = starIndex;
        }

        if (starInitialized[starSlot]) {
            return;
        }

        // Find all magnetized planets associated with this star
        List<Integer> magnetizedPlanets = new ArrayList<>();
        if (state != null) {
            for (int b = 0; b < state.getN(); b++) {
                if (!state.isStar(b) && state.hasMagneticField(b)) {
                    int nearest = MagnetopauseCalculator.findNearestStar(b, state);
                    if (nearest == starIndex || nearest == -1) {
                        magnetizedPlanets.add(b);
                    }
                }
            }
        }

        int numPlanets = magnetizedPlanets.size();
        int globalCount = (numPlanets > 0) ? GLOBAL_PARTICLES : PARTICLES_PER_STAR;

        // 1. Initialize 360-degree broad interplanetary Parker spiral particles
        for (int slot = startIdx; slot < startIdx + globalCount; slot++) {
            double u = rng.nextDouble();
            double rad = rBase + (MAX_OUTER_RADIUS - rBase) * u;
            double speed = NOMINAL_V_W + (rng.nextDouble() * 2.0 - 1.0) * V_W_SPREAD;
            double theta0 = rng.nextDouble() * 2.0 * Math.PI;

            streamType[slot] = 0;
            targetPlanet[slot] = -1;
            interactionWidth[slot] = 0.0;

            initParticle(slot, starIndex, originX, originY, rBase, omega, rad, theta0, speed, simTime);
        }

        // 2. Initialize physical planetary stream bundles for magnetized planets
        if (numPlanets > 0) {
            int remainingSlots = PARTICLES_PER_STAR - globalCount;
            int perPlanet = remainingSlots / numPlanets;

            for (int p = 0; p < numPlanets; p++) {
                int b = magnetizedPlanets.get(p);
                double px = state.positionX[b];
                double py = state.positionY[b];
                double dPlanet = Math.hypot(px - originX, py - originY);
                double standoff = MagnetopauseCalculator.computeStandoff(b, state, starIndex);
                double wCross = Math.min(dPlanet * 0.35, Math.max(3.2 * standoff, 6.0 * state.radius[b]));

                int pStart = startIdx + globalCount + p * perPlanet;
                int pEnd = (p == numPlanets - 1) ? (startIdx + PARTICLES_PER_STAR) : (pStart + perPlanet);

                int feederCount = (pEnd - pStart) / 4;
                int nearCount = (pEnd - pStart) - feederCount;

                // 2a. Interplanetary feeder stream from star to planet
                double rFeederMin = rBase;
                double rFeederMax = Math.max(rBase + 1.0e9, dPlanet - 1.5 * wCross);
                for (int slot = pStart; slot < pStart + feederCount; slot++) {
                    double u = rng.nextDouble();
                    double rad = rFeederMin + (rFeederMax - rFeederMin) * u;
                    double speed = NOMINAL_V_W + (rng.nextDouble() * 2.0 - 1.0) * 8_000.0;
                    double relOffset = rng.nextDouble() * 2.0 - 1.0;

                    streamType[slot] = 1;
                    targetPlanet[slot] = b;
                    interactionWidth[slot] = wCross;

                    initPlanetaryParticle(slot, starIndex, originX, originY, rBase, omega, rad, relOffset,
                            speed, simTime, dPlanet, wCross, px, py);
                }

                // 2b. High-density magnetospheric interaction stream around planet
                double rNearMin = Math.max(rBase, dPlanet - 2.0 * wCross);
                double rNearMax = dPlanet + 6.0 * wCross;
                for (int slot = pStart + feederCount; slot < pEnd; slot++) {
                    double u = rng.nextDouble();
                    double rad = rNearMin + (rNearMax - rNearMin) * u;
                    double speed = NOMINAL_V_W + (rng.nextDouble() * 2.0 - 1.0) * 8_000.0;
                    double relOffset = rng.nextDouble() * 2.0 - 1.0;

                    streamType[slot] = 2;
                    targetPlanet[slot] = b;
                    interactionWidth[slot] = wCross;

                    initPlanetaryParticle(slot, starIndex, originX, originY, rBase, omega, rad, relOffset,
                            speed, simTime, dPlanet, wCross, px, py);
                }
            }
        }

        starInitialized[starSlot] = true;
    }

    private void initParticle(int slot, int starIndex, double originX, double originY,
                              double rBase, double omega, double rad, double theta0,
                              double speed, double simTime) {
        radius[slot] = rad;
        birthAngle[slot] = theta0;
        vr[slot] = speed;
        starRadius[slot] = rBase;
        starRotOmega[slot] = omega;
        starOriginX[slot] = originX;
        starOriginY[slot] = originY;
        starSource[slot] = starIndex;

        double travelTime = (rad - rBase) / speed;
        birthTime[slot] = simTime - travelTime;
        double spiralAngle = theta0 - (omega / speed) * (rad - rBase) + omega * simTime;

        x[slot] = originX + rad * Math.cos(spiralAngle);
        y[slot] = originY + rad * Math.sin(spiralAngle);
        vx[slot] = speed * Math.cos(spiralAngle) - rad * omega * Math.sin(spiralAngle);
        vy[slot] = speed * Math.sin(spiralAngle) + rad * omega * Math.cos(spiralAngle);
        alpha[slot] = 1.0;
        inSheath[slot] = false;
        active[slot] = true;
    }

    private void initPlanetaryParticle(int slot, int starIndex, double originX, double originY,
                                       double rBase, double omega, double rad, double relOffset,
                                       double speed, double simTime, double dPlanet, double wCross,
                                       double px, double py) {
        radius[slot] = rad;
        birthAngle[slot] = relOffset;
        vr[slot] = speed;
        starRadius[slot] = rBase;
        starRotOmega[slot] = omega;
        starOriginX[slot] = originX;
        starOriginY[slot] = originY;
        starSource[slot] = starIndex;

        double phiPlanet = Math.atan2(py - originY, px - originX);
        double deltaThetaMax = (dPlanet > 1.0e6) ? (wCross / dPlanet) : 0.01;
        double deltaTheta = relOffset * deltaThetaMax;
        double spiralAngle = phiPlanet + deltaTheta + (omega / speed) * (dPlanet - rad);

        x[slot] = originX + rad * Math.cos(spiralAngle);
        y[slot] = originY + rad * Math.sin(spiralAngle);
        vx[slot] = speed * Math.cos(spiralAngle) - rad * omega * Math.sin(spiralAngle);
        vy[slot] = speed * Math.sin(spiralAngle) + rad * omega * Math.cos(spiralAngle);
        alpha[slot] = 1.0;
        inSheath[slot] = false;
        active[slot] = true;
    }

    /** Clears all particles. */
    public void reset() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            active[i] = false;
            inSheath[i] = false;
            alpha[i] = 0.0;
            cmeIntensity[i] = 0.0;
            radius[i] = 0.0;
            streamType[i] = 0;
            targetPlanet[i] = -1;
            interactionWidth[i] = 0.0;
        }
        for (int s = 0; s < MAX_STARS; s++) {
            starInitialized[s] = false;
        }
    }
}
