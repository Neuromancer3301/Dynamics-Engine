package physics.nbody;

import java.util.ArrayList;
import java.util.List;

/**
 * Dipole field vector mathematics: closed-form parametric dipole loops
 * with Chapman-Ferraro boundary deformation and polar-anchored magnetotail stretching,
 * and numerical 4th-order Runge-Kutta vector field streamline integration.
 */
public final class DipoleFieldMath {

    /** Vacuum permeability constant divided by 4*pi: mu_0 / (4 * pi) = 1e-7 T*m/A. */
    public static final double MU0_OVER_4PI = 1.0e-7;

    /** Neutral point magnetic field threshold in Tesla (1e-15 T). */
    public static final double NEUTRAL_POINT_THRESHOLD = 1.0e-15;

    /** Chapman-Ferraro/Shue flaring exponent gamma. */
    public static final double SHUE_FLARING_GAMMA = 0.60;

    private DipoleFieldMath() {}

    /**
     * Evaluates total magnetic field vector B_total(x, y) = sum_i B_i(x, y) in Tesla.
     * Uses softening epsilon_mag = 0.5 * R_i to prevent singularities.
     */
    public static double[] evaluateB(double x, double y, NBodyState state) {
        return evaluateB(x, y, state, false);
    }

    /**
     * Evaluates magnetic field vector B(x, y) in Tesla, optionally including
     * Chapman-Ferraro boundary compression and magnetotail current sheet deformation.
     */
    public static double[] evaluateB(double x, double y, NBodyState state, boolean includeSolarWindDeformation) {
        double bx = 0.0;
        double by = 0.0;
        if (state == null || state.getN() == 0) {
            return new double[]{0.0, 0.0};
        }

        int n = state.getN();
        for (int i = 0; i < n; i++) {
            if (!state.hasMagneticField(i)) continue;

            double mMag = state.magneticMoment[i];
            if (mMag <= 0.0) continue;

            double rBody = state.radius[i];
            double tiltRad = Math.toRadians(state.magneticTiltDegrees[i]);

            // Body position + center offset
            double x0 = state.positionX[i];
            double y0 = state.positionY[i];

            // Determine star vector & meridional magnetic dipole orientation
            int starIdx = MagnetopauseCalculator.findNearestStar(i, state);
            double uStarX = 1.0, uStarY = 0.0;
            double uTailX = -1.0, uTailY = 0.0;
            double uPerpX = 0.0, uPerpY = 1.0;
            boolean hasStar = false;
            double standoff = 10.0 * rBody;

            if (starIdx >= 0) {
                double sdx = state.positionX[starIdx] - x0;
                double sdy = state.positionY[starIdx] - y0;
                double sdist = Math.hypot(sdx, sdy);
                if (sdist > 1.0e-3) {
                    uStarX = sdx / sdist;
                    uStarY = sdy / sdist;
                    uTailX = -uStarX;
                    uTailY = -uStarY;
                    uPerpX = -uStarY;
                    uPerpY = uStarX;
                    hasStar = true;
                    standoff = MagnetopauseCalculator.computeStandoff(i, state, starIdx);
                }
            }

            // Magnetic dipole axis: normal to star vector in 2D meridional slice, tilted by tiltRad
            double mAxX, mAxY;
            if (hasStar) {
                mAxX = Math.cos(tiltRad) * uPerpX + Math.sin(tiltRad) * uStarX;
                mAxY = Math.cos(tiltRad) * uPerpY + Math.sin(tiltRad) * uStarY;
            } else {
                mAxX = Math.cos(tiltRad);
                mAxY = Math.sin(tiltRad);
            }

            double offset = state.magneticOffsetRatio[i];
            if (offset > 0.0 && rBody > 0.0) {
                x0 += offset * rBody * mAxX;
                y0 += offset * rBody * mAxY;
            }

            double dx = x - x0;
            double dy = y - y0;
            double epsMag = 0.5 * rBody;
            double distSq = dx * dx + dy * dy + epsMag * epsMag;
            double rEff = Math.sqrt(distSq);
            if (rEff <= 1.0e-6) continue;

            // Unit direction from dipole center
            double rHatX = dx / rEff;
            double rHatY = dy / rEff;

            // Dipole moment vector m = mMag * mAx
            double mx = mMag * mAxX;
            double my = mMag * mAxY;

            // 3 * (m · rHat) * rHat - m
            double mDotRHat = mx * rHatX + my * rHatY;
            double numX = 3.0 * mDotRHat * rHatX - mx;
            double numY = 3.0 * mDotRHat * rHatY - my;

            // B_i = (mu_0 / 4pi) * num / rEff^3
            double factor = MU0_OVER_4PI / (rEff * rEff * rEff);
            bx += factor * numX;
            by += factor * numY;

            // Solar wind boundary deformation: Chapman-Ferraro compression & cross-tail currents
            if (includeSolarWindDeformation && !state.isStar(i) && hasStar) {
                double cosPsi = rHatX * uStarX + rHatY * uStarY;
                double b0 = state.equatorialFieldMicroTesla(i) * 1.0e-6; // in Tesla

                if (cosPsi > 0.0) {
                    // Chapman-Ferraro dayside compression field along magnetic axis
                    double cfScale = 2.0 * b0 * Math.pow(rBody / standoff, 3.0)
                            * cosPsi * Math.exp(-rEff / (2.0 * standoff));
                    bx += cfScale * mAxX;
                    by += cfScale * mAxY;
                } else {
                    // Nightside cross-tail current sheet field (Harris sheet reversal)
                    double zM = dx * mAxX + dy * mAxY; // magnetic latitude distance
                    double xTail = dx * uTailX + dy * uTailY;
                    if (xTail > 0.0) {
                        double bLobe = b0 * Math.pow(rBody / standoff, 1.5) * 0.7;
                        double tailFactor = -bLobe * Math.tanh(zM / (1.5 * rBody))
                                * (1.0 - Math.exp(-xTail / (1.8 * rBody)));
                        bx += tailFactor * uStarX;
                        by += tailFactor * uStarY;
                    }
                }
            }
        }

        return new double[]{bx, by};
    }

    /**
     * Generates closed-form deformed dipole loops for bodyIndex using smooth conformal
     * Chapman-Ferraro dayside compression and polar-anchored nightside magnetotail stretching.
     * Matches the physical magnetosphere morphology from reference imagery.
     *
     * @return List of polylines (each is a flat double[]: x0, y0, x1, y1, ...)
     */
    public static List<double[]> generateParametricLoops(
            int bodyIndex, NBodyState state, double standoffRadius,
            double[] starPos, int numLoops, int pointsPerLoop) {

        List<double[]> polylines = new ArrayList<>();
        if (state == null || bodyIndex < 0 || bodyIndex >= state.getN() || !state.hasMagneticField(bodyIndex)) {
            return polylines;
        }

        double bx = state.positionX[bodyIndex];
        double by = state.positionY[bodyIndex];
        double rBody = state.radius[bodyIndex];
        double tiltRad = Math.toRadians(state.magneticTiltDegrees[bodyIndex]);

        double rMp = Math.max(1.2 * rBody, standoffRadius);

        // Unit vector toward star (dayside) and tail (nightside)
        double uStarX = 1.0, uStarY = 0.0;
        if (starPos != null && starPos.length >= 2) {
            double sdx = starPos[0] - bx;
            double sdy = starPos[1] - by;
            double sdist = Math.hypot(sdx, sdy);
            if (sdist > 1.0e-3) {
                uStarX = sdx / sdist;
                uStarY = sdy / sdist;
            }
        }
        double uTailX = -uStarX;
        double uTailY = -uStarY;

        // Perpendicular vector to tail axis
        double uPerpX = -uTailY;
        double uPerpY = uTailX;

        // Magnetic dipole orientation vectors in 2D meridional slice
        double mAxX, mAxY, mEqX, mEqY;
        if (starPos != null) {
            mAxX = Math.cos(tiltRad) * uPerpX + Math.sin(tiltRad) * uStarX;
            mAxY = Math.cos(tiltRad) * uPerpY + Math.sin(tiltRad) * uStarY;
            mEqX = -Math.sin(tiltRad) * uPerpX + Math.cos(tiltRad) * uStarX;
            mEqY = -Math.sin(tiltRad) * uPerpY + Math.cos(tiltRad) * uStarY;
        } else {
            mAxX = Math.cos(tiltRad);
            mAxY = Math.sin(tiltRad);
            mEqX = -Math.sin(tiltRad);
            mEqY = Math.cos(tiltRad);
        }

        double offset = state.magneticOffsetRatio[bodyIndex];
        if (offset > 0.0 && rBody > 0.0) {
            bx += offset * rBody * mAxX;
            by += offset * rBody * mAxY;
        }

        int pts = Math.max(16, pointsPerLoop);
        int loops = Math.max(2, numLoops);

        // Span L shells from close to planet surface out to magnetopause standoff
        for (int l = 0; l < loops; l++) {
            double frac = (l + 0.5) / loops;
            double lShell = rBody * (1.35 + (rMp / rBody - 1.1) * frac);

            // Polar footpoint colatitude where r = rBody on planet surface: sin^2(phiMin) = rBody / lShell
            double sinSqMin = Math.min(0.92, rBody / lShell);
            double phiMin = Math.asin(Math.sqrt(sinSqMin));
            double phiMax = Math.PI - phiMin;

            // Two symmetrical magnetic hemispheres: +mEq and -mEq
            for (int side : new int[]{1, -1}) {
                double[] poly = new double[pts * 2];
                int idx = 0;

                for (int p = 0; p < pts; p++) {
                    double t = (double) p / (pts - 1);
                    double phi = phiMin + t * (phiMax - phiMin);

                    // Unperturbed dipole radial equation: r(phi) = L * sin^2(phi)
                    double sinPhi = Math.sin(phi);
                    double sinSqPhi = sinPhi * sinPhi;
                    double rUnperturbed = lShell * sinSqPhi;

                    // Magnetic coordinates: dEq along equatorial plane, dAx along magnetic axis
                    double dEq = side * rUnperturbed * sinPhi;
                    double dAx = rUnperturbed * Math.cos(phi);

                    double rx = dEq * mEqX + dAx * mAxX;
                    double ry = dEq * mEqY + dAx * mAxY;
                    double rDist = Math.hypot(rx, ry);

                    // Anchor weighting: exactly 0 on planetary surface, 1 at magnetic equator
                    double anchorWeight = Math.max(0.0, Math.min(1.0, (sinSqPhi - sinSqMin) / (1.0 - sinSqMin)));

                    if (starPos != null && rDist > 1.0e-6 && anchorWeight > 0.0) {
                        double dirX = rx / rDist;
                        double dirY = ry / rDist;
                        double cosPsi = dirX * uStarX + dirY * uStarY;

                        // Smooth C^1 blending across terminator [-0.15, +0.15]
                        double tBlend = Math.max(0.0, Math.min(1.0, (cosPsi + 0.15) / 0.30));
                        double dayWeight = tBlend * tBlend * (3.0 - 2.0 * tBlend);
                        double nightWeight = 1.0 - dayWeight;

                        // Dayside Chapman-Ferraro smooth conformal compression
                        double denom = Math.max(1.0e-4, 1.0 + Math.max(0.0, cosPsi));
                        double rBound = 0.90 * rMp * Math.pow(2.0 / denom, SHUE_FLARING_GAMMA);
                        double ratio = rDist / rBound;
                        double rComp = rDist / Math.pow(1.0 + ratio * ratio * ratio * ratio, 0.25);
                        double deltaR = (rComp - rDist) * anchorWeight;
                        double deltaDayX = deltaR * dirX;
                        double deltaDayY = deltaR * dirY;

                        // Nightside magnetotail stretching & current sheet narrowing
                        double tailScale = rMp * 2.2 * Math.pow(Math.max(0.05, (lShell - rBody) / rMp), 1.5);
                        double stretch = tailScale * anchorWeight * Math.max(0.0, -cosPsi);
                        double deltaNightX = stretch * uTailX;
                        double deltaNightY = stretch * uTailY;

                        double downstreamDist = Math.max(0.0, (rx + deltaNightX) * uTailX + (ry + deltaNightY) * uTailY);
                        double lateralDist = (rx + deltaNightX) * uPerpX + (ry + deltaNightY) * uPerpY;
                        double pinch = 1.0 / (1.0 + 0.32 * (downstreamDist / rMp));
                        double pinchDelta = lateralDist * (pinch - 1.0) * anchorWeight;
                        deltaNightX += pinchDelta * uPerpX;
                        deltaNightY += pinchDelta * uPerpY;

                        // Blended displacement: perfectly smooth C^inf transition across entire loop
                        rx += dayWeight * deltaDayX + nightWeight * deltaNightX;
                        ry += dayWeight * deltaDayY + nightWeight * deltaNightY;
                    }

                    poly[idx++] = bx + rx;
                    poly[idx++] = by + ry;
                }
                polylines.add(poly);
            }
        }

        return polylines;
    }

    /**
     * Integrates a numerical streamline along B_total using 4th-order Runge-Kutta.
     * Integrates bidirectionally (forward along +B, backward along -B) to yield
     * complete closed dipole loops from footpoint to footpoint.
     *
     * @return Flat array of coordinates [x0, y0, x1, y1, ...]
     */
    public static double[] integrateRK4Streamlines(
            NBodyState state, double startX, double startY, double stepSize, int maxSteps) {

        if (state == null || state.getN() == 0 || maxSteps <= 0 || stepSize <= 0.0) {
            return new double[]{startX, startY};
        }

        int halfSteps = Math.max(16, maxSteps / 2);

        // Forward integration (+B)
        List<Double> forward = integrateOneDirection(state, startX, startY, stepSize, halfSteps, 1.0);

        // Backward integration (-B)
        List<Double> backward = integrateOneDirection(state, startX, startY, stepSize, halfSteps, -1.0);

        // Combine: reverse(backward) + forward (skipping duplicate seed point)
        int totalPoints = (backward.size() / 2 - 1) + (forward.size() / 2);
        double[] result = new double[totalPoints * 2];
        int idx = 0;

        for (int i = backward.size() - 2; i >= 2; i -= 2) {
            result[idx++] = backward.get(i);
            result[idx++] = backward.get(i + 1);
        }
        for (int i = 0; i < forward.size(); i++) {
            result[idx++] = forward.get(i);
        }

        return result;
    }

    private static List<Double> integrateOneDirection(
            NBodyState state, double startX, double startY, double stepSize, int maxSteps, double directionSign) {

        List<Double> coords = new ArrayList<>(maxSteps * 2);
        double curX = startX;
        double curY = startY;
        coords.add(curX);
        coords.add(curY);

        int n = state.getN();
        double h = stepSize * directionSign;

        for (int step = 0; step < maxSteps; step++) {
            // k1 = f(x, y)
            double[] b1 = evaluateB(curX, curY, state, true);
            double mag1 = Math.hypot(b1[0], b1[1]);
            if (mag1 < NEUTRAL_POINT_THRESHOLD) break;
            double k1x = b1[0] / mag1;
            double k1y = b1[1] / mag1;

            // k2 = f(x + 0.5*h*k1x, y + 0.5*h*k1y)
            double x2 = curX + 0.5 * h * k1x;
            double y2 = curY + 0.5 * h * k1y;
            double[] b2 = evaluateB(x2, y2, state, true);
            double mag2 = Math.hypot(b2[0], b2[1]);
            if (mag2 < NEUTRAL_POINT_THRESHOLD) break;
            double k2x = b2[0] / mag2;
            double k2y = b2[1] / mag2;

            // k3 = f(x + 0.5*h*k2x, y + 0.5*h*k2y)
            double x3 = curX + 0.5 * h * k2x;
            double y3 = curY + 0.5 * h * k2y;
            double[] b3 = evaluateB(x3, y3, state, true);
            double mag3 = Math.hypot(b3[0], b3[1]);
            if (mag3 < NEUTRAL_POINT_THRESHOLD) break;
            double k3x = b3[0] / mag3;
            double k3y = b3[1] / mag3;

            // k4 = f(x + h*k3x, y + h*k3y)
            double x4 = curX + h * k3x;
            double y4 = curY + h * k3y;
            double[] b4 = evaluateB(x4, y4, state, true);
            double mag4 = Math.hypot(b4[0], b4[1]);
            if (mag4 < NEUTRAL_POINT_THRESHOLD) break;
            double k4x = b4[0] / mag4;
            double k4y = b4[1] / mag4;

            curX += (h / 6.0) * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
            curY += (h / 6.0) * (k1y + 2.0 * k2y + 2.0 * k3y + k4y);

            coords.add(curX);
            coords.add(curY);

            // Check collision with any celestial body (skip seed point at step 0)
            if (step > 0) {
                boolean insideBody = false;
                for (int i = 0; i < n; i++) {
                    double dx = curX - state.positionX[i];
                    double dy = curY - state.positionY[i];
                    if (dx * dx + dy * dy < state.radius[i] * state.radius[i] * 0.98) {
                        insideBody = true;
                        break;
                    }
                }
                if (insideBody) break;

                // Terminate dayside streamlines that exit past the dayside magnetopause
                boolean exitedDayside = false;
                for (int i = 0; i < n; i++) {
                    if (state.isStar(i) || !state.hasMagneticField(i)) continue;
                    int starIdx = MagnetopauseCalculator.findNearestStar(i, state);
                    if (starIdx < 0) continue;

                    double dx = curX - state.positionX[i];
                    double dy = curY - state.positionY[i];
                    double dist = Math.hypot(dx, dy);

                    double standoff = MagnetopauseCalculator.computeStandoff(i, state, starIdx);
                    // Only apply to the body whose magnetosphere this streamline belongs to
                    if (dist > 2.5 * standoff) continue;

                    double sdx = state.positionX[starIdx] - state.positionX[i];
                    double sdy = state.positionY[starIdx] - state.positionY[i];
                    double sdist = Math.hypot(sdx, sdy);
                    if (sdist <= 1.0e-3) continue;
                    double uStarX = sdx / sdist;
                    double uStarY = sdy / sdist;

                    double cosPsi = (dist > 1.0e-6) ? (dx * uStarX + dy * uStarY) / dist : 0.0;
                    if (cosPsi > 0.20) {
                        double denom = Math.max(1.0e-4, 1.0 + cosPsi);
                        double rMpAlpha = standoff * Math.pow(2.0 / denom, SHUE_FLARING_GAMMA);
                        if (dist > rMpAlpha * 1.08) {
                            exitedDayside = true;
                            break;
                        }
                    }
                }
                if (exitedDayside) break;
            }
        }

        return coords;
    }
}

