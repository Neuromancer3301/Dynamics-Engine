package physics.nbody;

/** Shared helpers for the n-body test suite. Test-scope only — never shipped. */
final class NBodyTestSupport {

    private NBodyTestSupport() {}

    // "Sun" parameters shared by sunAndPlanets/innermostOrbitalPeriod so the
    // two stay consistent with each other without repeating literals.
    private static final double SUN_MASS      = 2.0e30;
    private static final double SUN_RADIUS    = 7.0e8;
    private static final double PLANET_MASS   = 6.0e24; // << SUN_MASS, so the fixed-parent circular approximation below is accurate
    private static final double PLANET_RADIUS = 6.0e6;
    private static final double BASE_ORBIT_RADIUS = 5.0e10;
    private static final double ORBIT_RADIUS_STEP  = 3.0e10;
    private static final double GOLDEN_ANGLE_RADIANS = Math.toRadians(137.5);

    /**
     * A two-body scene in the zero-net-momentum center-of-mass frame, set up
     * for an <em>exact</em> circular orbit: both bodies trace circles around
     * a common, stationary center of mass at one shared angular velocity —
     * the standard reduced two-body solution, {@code omega = sqrt(G*(m1+m2)
     * / separation^3)}. Unlike {@code sunAndPlanets} below (or {@code
     * physics.nbody.Presets}' real roster), this is not an approximation:
     * for exactly two bodies it is the exact solution of the gravitational
     * two-body problem, which is what makes it the right fixture for
     * isolating whether the engine/integrator plumbing itself is correct
     * (see the n-body implementation spec §13 step 2) before trusting any
     * looser many-body check.
     */
    static NBodyConfig twoBodyCircularOrbit(double m1, double m2, double separation, double g) {
        double totalMass = m1 + m2;
        double d1 = (m2 / totalMass) * separation; // body 0's distance from the (stationary) COM
        double d2 = (m1 / totalMass) * separation; // body 1's distance from the COM
        double omega = Math.sqrt(g * totalMass / (separation * separation * separation));

        double[] mass   = {m1, m2};
        double[] radius = {1.0e6, 1.0e6};
        double[] px = {-d1, d2};
        double[] py = {0.0, 0.0};
        double[] vx = {0.0, 0.0};
        double[] vy = {-omega * d1, omega * d2};
        String[] name = {"A", "B"};

        return new NBodyConfig(2, mass, radius, px, py, vx, vy, name,
                NBodyConfig.DEFAULT_SOFTENING_LENGTH, g, 1.0);
    }

    /** The orbital period of {@link #twoBodyCircularOrbit}'s exact solution, in seconds. */
    static double twoBodyOrbitalPeriod(double m1, double m2, double separation, double g) {
        double omega = Math.sqrt(g * (m1 + m2) / (separation * separation * separation));
        return 2 * Math.PI / omega;
    }

    /**
     * One dominant "sun" plus {@code n-1} much lighter "planets," each given
     * a fixed-parent circular-orbit velocity ({@code v = sqrt(G*sunMass/r)},
     * same approximation formula {@code physics.nbody.Presets} uses for the
     * real roster — see the n-body implementation spec §4.2) at golden-angle-
     * spaced starting positions, then zero-momentum corrected. An
     * approximate multi-body scene for exercising the engine at whatever N a
     * test needs — not a physically curated roster (see {@code Presets} for
     * that).
     */
    static NBodyConfig sunAndPlanets(int n, double g) {
        if (n < 1) throw new IllegalArgumentException("n must be >= 1");

        double[] mass = new double[n], radius = new double[n];
        double[] px = new double[n], py = new double[n];
        double[] vx = new double[n], vy = new double[n];
        String[] name = new String[n];

        mass[0] = SUN_MASS; radius[0] = SUN_RADIUS;
        px[0] = 0; py[0] = 0; vx[0] = 0; vy[0] = 0; name[0] = "Sun";

        for (int i = 1; i < n; i++) {
            double r = BASE_ORBIT_RADIUS + (i - 1) * ORBIT_RADIUS_STEP;
            double theta = (i - 1) * GOLDEN_ANGLE_RADIANS;
            double vCirc = Math.sqrt(g * SUN_MASS / r);

            px[i] = r * Math.cos(theta);
            py[i] = r * Math.sin(theta);
            vx[i] = -vCirc * Math.sin(theta);
            vy[i] =  vCirc * Math.cos(theta);
            mass[i]   = PLANET_MASS;
            radius[i] = PLANET_RADIUS;
            name[i]   = "Planet " + i;
        }

        // Zero-momentum initialization — same step physics.nbody.Presets
        // performs on the real roster (spec §4.2): subtract the
        // mass-weighted average velocity from every body, once.
        double totalMass = 0;
        for (double m : mass) totalMass += m;
        double avgVx = 0, avgVy = 0;
        for (int i = 0; i < n; i++) { avgVx += mass[i] * vx[i]; avgVy += mass[i] * vy[i]; }
        avgVx /= totalMass; avgVy /= totalMass;
        for (int i = 0; i < n; i++) { vx[i] -= avgVx; vy[i] -= avgVy; }

        return new NBodyConfig(n, mass, radius, px, py, vx, vy, name,
                NBodyConfig.DEFAULT_SOFTENING_LENGTH, g, 1.0);
    }

    /** The innermost (fastest, most numerically demanding) planet's orbital period in {@link #sunAndPlanets}, for sizing a safe test {@code dt}. */
    static double innermostOrbitalPeriod(double g) {
        return 2 * Math.PI * Math.sqrt(Math.pow(BASE_ORBIT_RADIUS, 3) / (g * SUN_MASS));
    }

    /** True iff every position and velocity component in the state is finite — false if a degenerate step produced NaN/Infinity. */
    static boolean isFiniteState(NBodyState state) {
        for (double v : state.positionX) if (!Double.isFinite(v)) return false;
        for (double v : state.positionY) if (!Double.isFinite(v)) return false;
        for (double v : state.velocityX) if (!Double.isFinite(v)) return false;
        for (double v : state.velocityY) if (!Double.isFinite(v)) return false;
        return true;
    }
}
