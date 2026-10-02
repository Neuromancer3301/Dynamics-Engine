package physics.nbody;

/**
 * Calculates stellar wind dynamic ram pressure and planetary magnetopause
 * standoff distance R_mp based on Chapman-Ferraro pressure balance.
 */
public final class MagnetopauseCalculator {

    /** Solar mass loss rate M_dot, in kg/s (~1.4e9 kg/s). */
    public static final double SOLAR_MASS_LOSS_RATE = 1.4e9;

    /** Nominal stellar wind speed v_w, in m/s (450 km/s). */
    public static final double NOMINAL_WIND_SPEED = 450_000.0;

    /** Interstellar medium background ram pressure, in Pascals (1e-13 Pa). */
    public static final double P_ISM = 1.0e-13;

    /** Vacuum permeability mu_0, in H/m. */
    public static final double MU_0 = 4.0 * Math.PI * 1.0e-7;

    /** Chapman-Ferraro boundary current compression factor f. */
    public static final double CHAPMAN_FERRARO_FACTOR = 2.44;

    private MagnetopauseCalculator() {}

    /**
     * Calculates stellar wind ram pressure at distance D from a star with mass loss rate and wind velocity.
     * P_wind = (M_dot * v_w) / (4 * pi * D^2)
     */
    public static double computeWindPressure(double distanceMeters, double massLossRateKgS, double windSpeedMs) {
        if (distanceMeters <= 0.0 || !Double.isFinite(distanceMeters)) {
            return P_ISM;
        }
        double denom = 4.0 * Math.PI * distanceMeters * distanceMeters;
        double pressure = (massLossRateKgS * windSpeedMs) / denom;
        return Math.max(P_ISM, pressure);
    }

    /**
     * Finds the index of the nearest active star (M >= 0.08 M_sun) to bodyIndex in state.
     * Returns -1 if no star exists.
     */
    public static int findNearestStar(int bodyIndex, NBodyState state) {
        if (state == null || state.getN() == 0 || bodyIndex < 0 || bodyIndex >= state.getN()) {
            return -1;
        }
        int nearestStar = -1;
        double minDistSq = Double.MAX_VALUE;
        double bx = state.positionX[bodyIndex];
        double by = state.positionY[bodyIndex];

        for (int i = 0; i < state.getN(); i++) {
            if (i == bodyIndex) continue;
            if (state.isStar(i)) {
                double dx = state.positionX[i] - bx;
                double dy = state.positionY[i] - by;
                double distSq = dx * dx + dy * dy;
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                    nearestStar = i;
                }
            }
        }
        return nearestStar;
    }

    /**
     * Computes the subsolar magnetopause standoff radius R_mp for bodyIndex.
     * Clamped to [1.2 * R_body, 50.0 * R_body].
     */
    public static double computeStandoff(int bodyIndex, NBodyState state) {
        int starIndex = findNearestStar(bodyIndex, state);
        return computeStandoff(bodyIndex, state, starIndex);
    }

    /**
     * Computes the subsolar magnetopause standoff radius R_mp for bodyIndex relative to primaryStarIndex.
     */
    public static double computeStandoff(int bodyIndex, NBodyState state, int primaryStarIndex) {
        if (state == null || bodyIndex < 0 || bodyIndex >= state.getN()) {
            return 1.0;
        }
        double rBody = state.radius[bodyIndex];
        if (rBody <= 0.0) return 1.0;

        double b0MicroTesla = state.equatorialFieldMicroTesla(bodyIndex);
        if (b0MicroTesla <= 0.0 || !state.hasMagneticField(bodyIndex)) {
            return 1.2 * rBody;
        }

        double pWind;
        if (primaryStarIndex >= 0 && primaryStarIndex < state.getN() && primaryStarIndex != bodyIndex) {
            double dx = state.positionX[primaryStarIndex] - state.positionX[bodyIndex];
            double dy = state.positionY[primaryStarIndex] - state.positionY[bodyIndex];
            double dist = Math.hypot(dx, dy);
            pWind = computeWindPressure(dist, SOLAR_MASS_LOSS_RATE, NOMINAL_WIND_SPEED);
        } else {
            pWind = P_ISM;
        }

        // B_0 in Tesla
        double b0Tesla = b0MicroTesla * 1.0e-6;
        double f = CHAPMAN_FERRARO_FACTOR;
        // R_mp = R_body * ( (f^2 * B_0^2) / (2 * mu_0 * P_wind) )^(1/6)
        double ratio = (f * f * b0Tesla * b0Tesla) / (2.0 * MU_0 * pWind);
        double rMp = rBody * Math.pow(Math.max(1.0e-12, ratio), 1.0 / 6.0);

        // Clamp to [1.2 * R_body, 50.0 * R_body]
        return Math.max(1.2 * rBody, Math.min(50.0 * rBody, rMp));
    }
}
