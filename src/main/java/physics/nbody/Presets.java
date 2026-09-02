package physics.nbody;

/**
 * Curated starting configurations for the n-body simulation — the n-body
 * analogue of {@code physics.Presets}. Just one preset for Phase 1 (the
 * home solar system); additional presets (TRAPPIST-1, Alpha Centauri) are
 * explicitly out of scope for this phase — see the n-body implementation
 * spec §11.
 */
public final class Presets {

    private Presets() {}

    /** A named preset, as shown in the picker. Mirrors {@code physics.Presets.Preset}'s shape exactly. */
    public record Preset(String name, NBodyConfig config) {
        @Override public String toString() { return name; } // so a plain ComboBox<Preset> displays the name
    }

    /** Every preset, in the order shown in the picker. Returned as a fresh array so a caller cannot reorder the shared list. */
    public static Preset[] all() {
        return new Preset[] {
            new Preset("Home Solar System", homeSolarSystem()),
        };
    }

    // Judgment call — no default is specified anywhere in the grounding
    // documents. Chosen so the scene is visibly alive the moment the screen
    // opens rather than looking like a still image: at this multiplier
    // Mercury (~7.6e6s period) completes an orbit in a little over 7 real
    // seconds, and Earth (~3.16e7s) in about half a minute — see the n-body
    // implementation spec §3's own worked example for the scale this sits
    // against. The outer planets stay slow (Neptune takes 165 real years to
    // orbit regardless of this number) — that asymmetry is realistic, not a
    // bug: the point is the inner system visibly moving, not every body
    // completing a lap on a human timescale.
    private static final double DEFAULT_SPEED_MULTIPLIER = 1.0e6;

    // θ_k = k * 137.5° — see the n-body implementation spec §4.2: avoids the
    // visually-odd "everything starts in a dead straight line" a naive
    // theta=0-for-everyone default would produce. One shared, monotonically
    // increasing counter across every orbiting body in the roster
    // (incremented once per non-Sun body, regardless of which parent it
    // orbits) — the simplest reading of "give each body its own θ," and it
    // means no two bodies in the whole scene ever start at the same angle,
    // not just no two bodies sharing one parent.
    private static final double GOLDEN_ANGLE_RADIANS = Math.toRadians(137.5);

    /**
     * One row of the roster table (n-body implementation spec §4.3):
     * name, mass (kg), physical radius (m), semi-major axis (m) — meaningless
     * for the Sun itself, since it has no orbit — and the roster index of
     * the body it orbits ({@code -1} for the Sun). {@code retrograde} is
     * true only for Triton (spec §4.2 gotcha 2).
     */
    private record Row(String name, double mass, double radius, double semiMajorAxis, int parent, boolean retrograde) {}

    /**
     * The roster, in parent-before-child order — required, not incidental:
     * {@link #homeSolarSystem()} composes each moon's initial state relative
     * to its already-built parent's (position, velocity) (spec §4.2 gotcha
     * 1), which only works if the parent's row has already been processed.
     * Every parent index below refers to an earlier row in this same array.
     */
    private static final Row[] ROSTER = {
        new Row("Sun",             1.989e30, 6.957e8, 0.0,          -1, false),
        new Row("Mercury",         3.301e23, 2.440e6, 5.791e10,      0, false),
        new Row("Venus",           4.867e24, 6.052e6, 1.082e11,      0, false),
        new Row("Earth",           5.972e24, 6.371e6, 1.496e11,      0, false),
        new Row("Moon",            7.342e22, 1.737e6, 3.844e8,       3, false),
        new Row("Mars",            6.417e23, 3.390e6, 2.279e11,      0, false),
        new Row("Phobos",          1.066e16, 1.13e4,  9.376e6,       5, false),
        new Row("Deimos",          1.476e15, 6.2e3,   2.346e7,       5, false),
        new Row("Jupiter",         1.898e27, 6.991e7, 7.785e11,      0, false),
        new Row("Io",              8.932e22, 1.822e6, 4.217e8,       8, false),
        new Row("Europa",          4.800e22, 1.561e6, 6.709e8,       8, false),
        new Row("Ganymede",        1.482e23, 2.634e6, 1.070e9,       8, false),
        new Row("Callisto",        1.076e23, 2.410e6, 1.883e9,       8, false),
        new Row("Saturn",          5.683e26, 5.823e7, 1.434e12,      0, false),
        new Row("Titan",           1.345e23, 2.575e6, 1.222e9,      13, false),
        new Row("Enceladus",       1.08e20,  2.52e5,  2.38e8,       13, false),
        new Row("Mimas",           3.75e19,  1.98e5,  1.855e8,      13, false),
        new Row("Iapetus",         1.805e21, 7.34e5,  3.561e9,      13, false),
        new Row("Rhea",            2.31e21,  7.64e5,  5.270e8,      13, false),
        new Row("Dione",           1.095e21, 5.62e5,  3.774e8,      13, false),
        new Row("Tethys",          6.17e20,  5.31e5,  2.947e8,      13, false),
        new Row("Uranus",          8.681e25, 2.536e7, 2.873e12,      0, false),
        new Row("Titania",         3.4e21,   7.89e5,  4.361e8,      21, false),
        new Row("Oberon",          3.08e21,  7.61e5,  5.831e8,      21, false),
        new Row("Ariel",           1.25e21,  5.79e5,  1.910e8,      21, false),
        new Row("Umbriel",         1.27e21,  5.85e5,  2.660e8,      21, false),
        new Row("Miranda",         6.4e19,   2.36e5,  1.297e8,      21, false),
        new Row("Neptune",         1.024e26, 2.462e7, 4.495e12,      0, false),
        new Row("Triton",          2.14e22,  1.353e6, 3.548e8,      27, true),
        new Row("Ceres",           9.38e20,  4.70e5,  4.14e11,       0, false),
        new Row("Vesta",           2.59e20,  2.63e5,  3.53e11,       0, false),
        new Row("Pluto",           1.303e22, 1.188e6, 5.906e12,      0, false),
        new Row("Charon",          1.586e21, 6.06e5,  1.959e7,      31, false),
        new Row("Halley's Comet",  2.2e14,   5.5e3,   2.667e12,      0, false),
    };

    /**
     * Builds the home solar system: Sun, eight planets, their major moons,
     * three dwarf planets/asteroids, and Halley's Comet — {@link #ROSTER}
     * above, turned into initial positions/velocities.
     *
     * <p><b>The approximation, and why it's enough.</b> The Relativity
     * Engine's own §2.2 non-goals explicitly tolerate "plausible,
     * roughly-real figures, not mission-planning-grade ephemerides," so a
     * full Keplerian-elements epoch-accurate ephemeris is out of scope.
     * Every orbiting body is instead given a circular-orbit approximation
     * relative to its immediate parent: {@code v = sqrt(G * parentMass /
     * r)}, tangential to a golden-angle-spaced starting position (see
     * {@link #GOLDEN_ANGLE_RADIANS}). This is deliberately used even for
     * Halley's Comet, whose real orbit (e≈0.967) is far too eccentric for a
     * circular approximation to look remotely right — flagged, not an
     * oversight; a proper elliptical/vis-viva initial-velocity computation
     * is a clean, well-scoped later refinement, not required for Phase 1.
     *
     * <p><b>Moons compose against their already-built parent, not the
     * Sun</b> (spec §4.2 gotcha 1) — {@link #ROSTER}'s parent-before-child
     * ordering is what makes {@code px[row.parent()]}/{@code
     * vx[row.parent()]} below always already valid by the time a child row
     * reads them. Computing a moon's velocity as if it orbited the Sun
     * directly would give it a wildly wrong velocity relative to the
     * barycentric frame, and it would immediately fly off instead of
     * following its planet.
     *
     * <p><b>Triton is retrograde</b> (spec §4.2 gotcha 2, a real,
     * well-known fact) — its tangential direction is negated relative to
     * every other body in the roster.
     *
     * <p><b>Zero-momentum initialization</b> (spec §4.2/Groundwork B.5)
     * happens once, here, after every body's velocity is composed: the
     * mass-weighted average velocity is subtracted from all of them. This
     * is a scene-construction-time step, not a live invariant maintained
     * after user edits — adding one user-created body later doesn't
     * silently renormalize everyone else's velocity.
     */
    public static NBodyConfig homeSolarSystem() {
        int n = ROSTER.length;
        double g = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

        double[] mass = new double[n], radius = new double[n];
        double[] positionX = new double[n], positionY = new double[n];
        double[] velocityX = new double[n], velocityY = new double[n];
        String[] name = new String[n];

        int orbitIndex = 0; // golden-angle slot — one per orbiting (non-Sun) body, in roster order
        for (int i = 0; i < n; i++) {
            Row row = ROSTER[i];
            mass[i]   = row.mass();
            radius[i] = row.radius();
            name[i]   = row.name();

            if (row.parent() < 0) {
                // The Sun: no parent to orbit, placed at rest at the origin
                // (before the zero-momentum correction below, which may
                // give it a small residual velocity to balance everyone
                // else — physically correct: the real Sun does wobble
                // slightly around the solar system's true barycenter).
                positionX[i] = 0; positionY[i] = 0;
                velocityX[i] = 0; velocityY[i] = 0;
                continue;
            }

            int parent = row.parent();
            double theta = (orbitIndex++) * GOLDEN_ANGLE_RADIANS;
            double vCirc = Math.sqrt(g * mass[parent] / row.semiMajorAxis());

            // Prograde (counter-clockwise) by default: tangential direction
            // (-sin theta, cos theta). Triton negates it (spec gotcha 2).
            double tangentX = row.retrograde() ?  Math.sin(theta) : -Math.sin(theta);
            double tangentY = row.retrograde() ? -Math.cos(theta) :  Math.cos(theta);

            positionX[i] = positionX[parent] + row.semiMajorAxis() * Math.cos(theta);
            positionY[i] = positionY[parent] + row.semiMajorAxis() * Math.sin(theta);
            velocityX[i] = velocityX[parent] + vCirc * tangentX;
            velocityY[i] = velocityY[parent] + vCirc * tangentY;
        }

        // Zero-momentum initialization — see this method's javadoc.
        double totalMass = 0;
        for (double m : mass) totalMass += m;
        double avgVx = 0, avgVy = 0;
        for (int i = 0; i < n; i++) { avgVx += mass[i] * velocityX[i]; avgVy += mass[i] * velocityY[i]; }
        avgVx /= totalMass;
        avgVy /= totalMass;
        for (int i = 0; i < n; i++) { velocityX[i] -= avgVx; velocityY[i] -= avgVy; }

        return new NBodyConfig(n, mass, radius, positionX, positionY, velocityX, velocityY, name,
                NBodyConfig.DEFAULT_SOFTENING_LENGTH, g, DEFAULT_SPEED_MULTIPLIER);
    }
}
