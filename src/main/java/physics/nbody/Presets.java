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
            new Preset("TRAPPIST-1", trappist1()),
            new Preset("Alpha Centauri", alphaCentauri()),
            new Preset("Clear All", clearAll()),
        };
    }

    private static final double DEFAULT_SPEED_MULTIPLIER = 1.0e6;
    private static final double GOLDEN_ANGLE_RADIANS = Math.toRadians(137.5);

    private record Row(String name, double mass, double radius, double semiMajorAxis, int parent, boolean retrograde, double rotationPeriod) {}

    private static final Row[] ROSTER = {
        new Row("Sun",             1.989e30, 6.957e8, 0.0,          -1, false, 2.164e6),
        new Row("Mercury",         3.301e23, 2.440e6, 5.791e10,      0, false, 5.067e6),
        new Row("Venus",           4.867e24, 6.052e6, 1.082e11,      0, false, 2.0997e7),
        new Row("Earth",           5.972e24, 6.371e6, 1.496e11,      0, false, 86164.0),
        new Row("Moon",            7.342e22, 1.737e6, 3.844e8,       3, false, 2.361e6),
        new Row("Mars",            6.417e23, 3.390e6, 2.279e11,      0, false, 88642.0),
        new Row("Phobos",          1.066e16, 1.13e4,  9.376e6,       5, false, 2.755e4),
        new Row("Deimos",          1.476e15, 6.2e3,   2.346e7,       5, false, 1.091e5),
        new Row("Jupiter",         1.898e27, 6.991e7, 7.785e11,      0, false, 35730.0),
        new Row("Io",              8.932e22, 1.822e6, 4.217e8,       8, false, 1.528e5),
        new Row("Europa",          4.800e22, 1.561e6, 6.709e8,       8, false, 3.068e5),
        new Row("Ganymede",        1.482e23, 2.634e6, 1.070e9,       8, false, 6.182e5),
        new Row("Callisto",        1.076e23, 2.410e6, 1.883e9,       8, false, 1.442e6),
        new Row("Saturn",          5.683e26, 5.823e7, 1.434e12,      0, false, 38052.0),
        new Row("Titan",           1.345e23, 2.575e6, 1.222e9,      13, false, 1.378e6),
        new Row("Enceladus",       1.08e20,  2.52e5,  2.38e8,       13, false, 1.184e5),
        new Row("Mimas",           3.75e19,  1.98e5,  1.855e8,      13, false, 81389.0),
        new Row("Iapetus",         1.805e21, 7.34e5,  3.561e9,      13, false, 6.853e6),
        new Row("Rhea",            2.31e21,  7.64e5,  5.270e8,      13, false, 3.904e5),
        new Row("Dione",           1.095e21, 5.62e5,  3.774e8,      13, false, 2.365e5),
        new Row("Tethys",          6.17e20,  5.31e5,  2.947e8,      13, false, 1.631e5),
        new Row("Uranus",          8.681e25, 2.536e7, 2.873e12,      0, false, 62064.0),
        new Row("Titania",         3.4e21,   7.89e5,  4.361e8,      21, false, 7.522e5),
        new Row("Oberon",          3.08e21,  7.61e5,  5.831e8,      21, false, 1.163e6),
        new Row("Ariel",           1.25e21,  5.79e5,  1.910e8,      21, false, 2.177e5),
        new Row("Umbriel",         1.27e21,  5.85e5,  2.660e8,      21, false, 3.580e5),
        new Row("Miranda",         6.4e19,   2.36e5,  1.297e8,      21, false, 1.221e5),
        new Row("Neptune",         1.024e26, 2.462e7, 4.495e12,      0, false, 57996.0),
        new Row("Triton",          2.14e22,  1.353e6, 3.548e8,      27, true,  5.078e5),
        new Row("Ceres",           9.38e20,  4.70e5,  4.14e11,       0, false, 32666.0),
        new Row("Vesta",           2.59e20,  2.63e5,  3.53e11,       0, false, 19231.0),
        new Row("Pluto",           1.303e22, 1.188e6, 5.906e12,      0, false, 5.518e5),
        new Row("Charon",          1.586e21, 6.06e5,  1.959e7,      31, false, 5.518e5),
        new Row("Halley's Comet",  2.2e14,   5.5e3,   2.667e12,      0, false, 1.901e5),
    };

    /**
     * Builds the home solar system: Sun, eight planets, their major moons,
     * three dwarf planets/asteroids, and Halley's Comet, with real sidereal rotation periods.
     */
    public static NBodyConfig homeSolarSystem() {
        int n = ROSTER.length;
        double g = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

        double[] mass = new double[n], radius = new double[n];
        double[] positionX = new double[n], positionY = new double[n];
        double[] velocityX = new double[n], velocityY = new double[n];
        double[] rotationPeriod = new double[n];
        String[] name = new String[n];

        int orbitIndex = 0;
        for (int i = 0; i < n; i++) {
            Row row = ROSTER[i];
            mass[i]           = row.mass();
            radius[i]         = row.radius();
            name[i]           = row.name();
            rotationPeriod[i] = row.rotationPeriod();

            if (row.parent() < 0) {
                positionX[i] = 0; positionY[i] = 0;
                velocityX[i] = 0; velocityY[i] = 0;
                continue;
            }

            int parent = row.parent();
            double theta = (orbitIndex++) * GOLDEN_ANGLE_RADIANS;
            double vCirc = Math.sqrt(g * mass[parent] / row.semiMajorAxis());

            double tangentX = row.retrograde() ?  Math.sin(theta) : -Math.sin(theta);
            double tangentY = row.retrograde() ? -Math.cos(theta) :  Math.cos(theta);

            positionX[i] = positionX[parent] + row.semiMajorAxis() * Math.cos(theta);
            positionY[i] = positionY[parent] + row.semiMajorAxis() * Math.sin(theta);
            velocityX[i] = velocityX[parent] + vCirc * tangentX;
            velocityY[i] = velocityY[parent] + vCirc * tangentY;
        }

        // Zero-momentum initialization
        double totalMass = 0;
        for (double m : mass) totalMass += m;
        double avgVx = 0, avgVy = 0;
        for (int i = 0; i < n; i++) { avgVx += mass[i] * velocityX[i]; avgVy += mass[i] * velocityY[i]; }
        avgVx /= totalMass;
        avgVy /= totalMass;
        for (int i = 0; i < n; i++) { velocityX[i] -= avgVx; velocityY[i] -= avgVy; }

        return new NBodyConfig(n, mass, radius, positionX, positionY, velocityX, velocityY, name,
                rotationPeriod, NBodyConfig.DEFAULT_SOFTENING_LENGTH, g, DEFAULT_SPEED_MULTIPLIER);
    }

    /**
     * Builds TRAPPIST-1: ultra-cool red dwarf star with 7 resonant exoplanets (b through h).
     */
    public static NBodyConfig trappist1() {
        double g = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;
        int n = 8; // star + 7 planets

        double[] mass = {
            1.786e29, // TRAPPIST-1 primary
            8.21e24,  // b
            7.81e24,  // c
            2.32e24,  // d
            4.13e24,  // e
            6.21e24,  // f
            7.89e24,  // g
            1.95e24   // h
        };

        double[] radius = {
            8.42e7, // Primary
            7.11e6, // b
            6.99e6, // c
            5.02e6, // d
            5.86e6, // e
            6.66e6, // f
            7.19e6, // g
            4.81e6  // h
        };

        double[] semiMajorAxis = {
            0.0,
            1.72e9, // b
            2.36e9, // c
            3.33e9, // d
            4.38e9, // e
            5.76e9, // f
            6.99e9, // g
            9.26e9  // h
        };

        double[] rotationPeriod = {
            2.85e5,  // Primary (3.30 days)
            1.305e5, // b (1.51 days)
            2.091e5, // c (2.42 days)
            3.499e5, // d (4.05 days)
            5.270e5, // e (6.10 days)
            7.957e5, // f (9.21 days)
            1.067e6, // g (12.35 days)
            1.622e6  // h (18.77 days)
        };

        String[] names = {
            "TRAPPIST-1", "TRAPPIST-1 b", "TRAPPIST-1 c", "TRAPPIST-1 d",
            "TRAPPIST-1 e", "TRAPPIST-1 f", "TRAPPIST-1 g", "TRAPPIST-1 h"
        };

        double[] positionX = new double[n], positionY = new double[n];
        double[] velocityX = new double[n], velocityY = new double[n];

        positionX[0] = 0.0; positionY[0] = 0.0;
        velocityX[0] = 0.0; velocityY[0] = 0.0;

        double primaryMass = mass[0];
        for (int i = 1; i < n; i++) {
            double theta = (i - 1) * GOLDEN_ANGLE_RADIANS;
            double a = semiMajorAxis[i];
            double vCirc = Math.sqrt(g * primaryMass / a);

            positionX[i] = a * Math.cos(theta);
            positionY[i] = a * Math.sin(theta);
            velocityX[i] = -vCirc * Math.sin(theta);
            velocityY[i] =  vCirc * Math.cos(theta);
        }

        // Zero-momentum initialization
        double totalMass = 0;
        for (double m : mass) totalMass += m;
        double avgVx = 0, avgVy = 0;
        for (int i = 0; i < n; i++) { avgVx += mass[i] * velocityX[i]; avgVy += mass[i] * velocityY[i]; }
        avgVx /= totalMass;
        avgVy /= totalMass;
        for (int i = 0; i < n; i++) { velocityX[i] -= avgVx; velocityY[i] -= avgVy; }

        return new NBodyConfig(n, mass, radius, positionX, positionY, velocityX, velocityY, names,
                rotationPeriod, NBodyConfig.DEFAULT_SOFTENING_LENGTH, g, DEFAULT_SPEED_MULTIPLIER);
    }

    /**
     * Builds Alpha Centauri: close binary pair (Alpha Centauri A & B),
     * distant red dwarf Proxima Centauri, and exoplanet Proxima b.
     */
    public static NBodyConfig alphaCentauri() {
        double g = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;
        int n = 4;

        double[] mass = {
            2.188e30, // Alpha Centauri A
            1.804e30, // Alpha Centauri B
            2.43e29,  // Proxima Centauri
            7.0e24    // Proxima b
        };

        double[] radius = {
            8.51e8, // A
            6.00e8, // B
            1.07e8, // Proxima
            6.8e6   // Proxima b
        };

        double[] rotationPeriod = {
            1.90e6, // A (22 days)
            3.11e6, // B (36 days)
            7.17e6, // Proxima (83 days)
            9.68e5  // Proxima b (11.2 days)
        };

        String[] names = {
            "Alpha Centauri A", "Alpha Centauri B", "Proxima Centauri", "Proxima b"
        };

        double[] positionX = new double[n], positionY = new double[n];
        double[] velocityX = new double[n], velocityY = new double[n];

        // Close binary pair A and B orbiting their shared barycenter
        double sepAB = 3.5e12; // semi-major separation
        double massAB = mass[0] + mass[1];
        double distA = sepAB * (mass[1] / massAB);
        double distB = sepAB * (mass[0] / massAB);
        double vRel = Math.sqrt(g * massAB / sepAB);
        double vA = vRel * (mass[1] / massAB);
        double vB = vRel * (mass[0] / massAB);

        positionX[0] = -distA; positionY[0] = 0.0;
        velocityX[0] = 0.0;    velocityY[0] = -vA;

        positionX[1] = distB;  positionY[1] = 0.0;
        velocityX[1] = 0.0;    velocityY[1] = vB;

        // Distant red dwarf Proxima Centauri orbiting the A-B system
        double sepProxima = 1.5e13;
        double vProxima = Math.sqrt(g * massAB / sepProxima);

        positionX[2] = 0.0;       positionY[2] = sepProxima;
        velocityX[2] = -vProxima; velocityY[2] = 0.0;

        // Exoplanet Proxima b orbiting Proxima Centauri
        double sepPb = 7.26e9;
        double vPb = Math.sqrt(g * mass[2] / sepPb);

        positionX[3] = sepPb;     positionY[3] = sepProxima;
        velocityX[3] = -vProxima; velocityY[3] = vPb;

        // Zero-momentum initialization across all 4 bodies
        double totalMass = 0;
        for (double m : mass) totalMass += m;
        double avgVx = 0, avgVy = 0;
        for (int i = 0; i < n; i++) { avgVx += mass[i] * velocityX[i]; avgVy += mass[i] * velocityY[i]; }
        avgVx /= totalMass;
        avgVy /= totalMass;
        for (int i = 0; i < n; i++) { velocityX[i] -= avgVx; velocityY[i] -= avgVy; }

        return new NBodyConfig(n, mass, radius, positionX, positionY, velocityX, velocityY, names,
                rotationPeriod, NBodyConfig.DEFAULT_SOFTENING_LENGTH, g, DEFAULT_SPEED_MULTIPLIER);
    }

    /**
     * Builds an empty universe (N = 0) with zero bodies.
     */
    public static NBodyConfig clearAll() {
        return new NBodyConfig(0,
                new double[0], new double[0],
                new double[0], new double[0],
                new double[0], new double[0],
                new String[0], new double[0],
                NBodyConfig.DEFAULT_SOFTENING_LENGTH,
                NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT,
                DEFAULT_SPEED_MULTIPLIER);
    }
}
