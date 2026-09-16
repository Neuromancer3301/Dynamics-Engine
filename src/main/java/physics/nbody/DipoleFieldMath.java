package physics.nbody;

import java.util.ArrayList;
import java.util.List;

/**
 * Dipole field vector mathematics: closed-form parametric dipole loops
 * with Chapman-Ferraro boundary deformation, and numerical 4th-order Runge-Kutta
 * vector field streamline integration.
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
            double offset = state.magneticOffsetRatio[i];
            if (offset > 0.0 && rBody > 0.0) {
                x0 += offset * rBody * Math.cos(tiltRad);
                y0 += offset * rBody * Math.sin(tiltRad);
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

            // Dipole moment vector m = mMag * (cos(tilt), sin(tilt))
            double mx = mMag * Math.cos(tiltRad);
            double my = mMag * Math.sin(tiltRad);

            // 3 * (m · rHat) * rHat - m
            double mDotRHat = mx * rHatX + my * rHatY;
            double numX = 3.0 * mDotRHat * rHatX - mx;
            double numY = 3.0 * mDotRHat * rHatY - my;

            // B_i = (mu_0 / 4pi) * num / rEff^3
            double factor = MU0_OVER_4PI / (rEff * rEff * rEff);
            bx += factor * numX;
            by += factor * numY;
        }

        return new double[]{bx, by};
    }

    /**
     * Generates closed-form deformed dipole loops for bodyIndex using Chapman-Ferraro/Shue
     * deformation compressed toward standoffRadius on dayside and stretched downstream on nightside.
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
        double offset = state.magneticOffsetRatio[bodyIndex];
        if (offset > 0.0 && rBody > 0.0) {
            bx += offset * rBody * Math.cos(tiltRad);
            by += offset * rBody * Math.sin(tiltRad);
        }

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

        int pts = Math.max(16, pointsPerLoop);
        int loops = Math.max(2, numLoops);

        // Apex distances from 1.4 * rBody up to ~1.2 * rMp
        for (int l = 0; l < loops; l++) {
            double frac = (l + 1.0) / loops;
            double r0 = rBody * (1.3 + (rMp / rBody - 0.8) * frac);

            // Generate two symmetrical loop lobes: positive hemisphere (+pi/2) and negative hemisphere (-pi/2)
            for (int side : new int[]{1, -1}) {
                double[] poly = new double[pts * 2];
                int idx = 0;

                for (int p = 0; p < pts; p++) {
                    // Colatitude angle phi relative to magnetic axis: from near pole (0.05*pi) to opposite pole (0.95*pi)
                    double t = (double) p / (pts - 1);
                    double phi = (0.04 + 0.92 * t) * Math.PI;

                    // Dipole equation: r(phi) = r0 * sin^2(phi)
                    double sinPhi = Math.sin(phi);
                    double r = r0 * sinPhi * sinPhi;
                    r = Math.max(rBody * 0.9, r);

                    // Angle in orbital plane
                    double theta = tiltRad + side * (phi - Math.PI / 2.0);
                    double px = r * Math.cos(theta);
                    double py = r * Math.sin(theta);

                    // Chapman-Ferraro deformation
                    double curDist = Math.hypot(px, py);
                    if (curDist > 1.0e-6) {
                        double rx = px / curDist;
                        double ry = py / curDist;
                        double cosAlpha = rx * uStarX + ry * uStarY;

                        // Shue boundary radius: r_mp(alpha) = rMp * (2 / (1 + cosAlpha))^gamma
                        double denom = Math.max(1.0e-4, 1.0 + cosAlpha);
                        double rBoundary = rMp * Math.pow(2.0 / denom, SHUE_FLARING_GAMMA);

                        if (cosAlpha > 0.0) {
                            // Dayside squashing: stay within 0.95 * rBoundary
                            if (curDist > 0.95 * rBoundary) {
                                double scale = (0.95 * rBoundary) / curDist;
                                px *= scale;
                                py *= scale;
                            }
                        } else {
                            // Nightside downstream elongation: shear along uTail
                            double shearRatio = (curDist / r0);
                            double shear = 2.5 * rMp * shearRatio * shearRatio * (1.0 - frac * 0.4);
                            px += shear * uTailX;
                            py += shear * uTailY;
                        }
                    }

                    poly[idx++] = bx + px;
                    poly[idx++] = by + py;
                }
                polylines.add(poly);
            }
        }

        return polylines;
    }

    /**
     * Integrates a numerical streamline along B_total using 4th-order Runge-Kutta.
     * Terminates if streamline enters any body radius, reaches a neutral point (|B| < 1e-15 T),
     * or exceeds maxSteps.
     *
     * @return Flat array of coordinates [x0, y0, x1, y1, ...]
     */
    public static double[] integrateRK4Streamlines(
            NBodyState state, double startX, double startY, double stepSize, int maxSteps) {

        if (state == null || state.getN() == 0 || maxSteps <= 0 || stepSize <= 0.0) {
            return new double[]{startX, startY};
        }

        List<Double> coords = new ArrayList<>(maxSteps * 2);
        double curX = startX;
        double curY = startY;
        coords.add(curX);
        coords.add(curY);

        int n = state.getN();
        double h = stepSize;

        for (int step = 0; step < maxSteps; step++) {
            // k1 = f(x, y)
            double[] b1 = evaluateB(curX, curY, state);
            double mag1 = Math.hypot(b1[0], b1[1]);
            if (mag1 < NEUTRAL_POINT_THRESHOLD) break;
            double k1x = b1[0] / mag1;
            double k1y = b1[1] / mag1;

            // k2 = f(x + 0.5*h*k1x, y + 0.5*h*k1y)
            double x2 = curX + 0.5 * h * k1x;
            double y2 = curY + 0.5 * h * k1y;
            double[] b2 = evaluateB(x2, y2, state);
            double mag2 = Math.hypot(b2[0], b2[1]);
            if (mag2 < NEUTRAL_POINT_THRESHOLD) break;
            double k2x = b2[0] / mag2;
            double k2y = b2[1] / mag2;

            // k3 = f(x + 0.5*h*k2x, y + 0.5*h*k2y)
            double x3 = curX + 0.5 * h * k2x;
            double y3 = curY + 0.5 * h * k2y;
            double[] b3 = evaluateB(x3, y3, state);
            double mag3 = Math.hypot(b3[0], b3[1]);
            if (mag3 < NEUTRAL_POINT_THRESHOLD) break;
            double k3x = b3[0] / mag3;
            double k3y = b3[1] / mag3;

            // k4 = f(x + h*k3x, y + h*k3y)
            double x4 = curX + h * k3x;
            double y4 = curY + h * k3y;
            double[] b4 = evaluateB(x4, y4, state);
            double mag4 = Math.hypot(b4[0], b4[1]);
            if (mag4 < NEUTRAL_POINT_THRESHOLD) break;
            double k4x = b4[0] / mag4;
            double k4y = b4[1] / mag4;

            curX += (h / 6.0) * (k1x + 2.0 * k2x + 2.0 * k3x + k4x);
            curY += (h / 6.0) * (k1y + 2.0 * k2y + 2.0 * k3y + k4y);

            coords.add(curX);
            coords.add(curY);

            // Check collision with any celestial body
            boolean insideBody = false;
            for (int i = 0; i < n; i++) {
                double dx = curX - state.positionX[i];
                double dy = curY - state.positionY[i];
                if (dx * dx + dy * dy < state.radius[i] * state.radius[i]) {
                    insideBody = true;
                    break;
                }
            }
            if (insideBody) break;
        }

        double[] result = new double[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            result[i] = coords.get(i);
        }
        return result;
    }
}
