package physics;

/**
 * The complete, validated description of one pendulum chain — the "recipe"
 * a {@link PhysicsEngine} is built from.
 *
 * <p><b>Structural vs. runtime parameters.</b> This class deliberately
 * separates two kinds of value:
 * <ul>
 *   <li><b>Structural</b> ({@code n}, {@code lengths}, {@code masses},
 *       {@code initAngles}) — {@code final}, because changing any of them
 *       means the engine's internal matrices are the wrong <em>size</em> or
 *       shape. Editing these requires building a whole new engine (see
 *       {@code simulation.command.EngineRebuilder}).</li>
 *   <li><b>Runtime</b> ({@code gravity}, {@code speedMultiplier}) —
 *       mutable and {@code volatile}, because they can be changed mid-flight
 *       without disturbing anything's dimensions.</li>
 * </ul>
 *
 * <p><b>Validation is in the constructor, on purpose.</b> Every value is
 * checked before the object exists, so an invalid {@code PendulumConfig}
 * is impossible to construct. That makes this the single trust boundary:
 * once something holds one of these, it needs no defensive checks. Inputs
 * arrive from three untrusted directions — typed text fields, saved
 * {@code .pendulum} files, and the natural-language parser — and all three
 * funnel through here.
 *
 * <p><b>Why {@code Double.isFinite} and not just {@code <= 0}.</b> In Java,
 * {@code NaN <= 0} evaluates to {@code false}, so a plain positivity check
 * silently lets NaN through. A NaN reaching the engine would propagate
 * through the mass matrix and corrupt every subsequent frame with no error
 * message. These checks are the reason that cannot happen.
 */
public final class PendulumConfig {

    private final int n;
    private final double[] lengths;
    private final double[] masses;
    private final double[] initAngles;
    private volatile double gravity;
    private volatile double speedMultiplier;

    public PendulumConfig(int n, double[] lengths, double[] masses,
                          double[] initAngles, double gravity, double speedMultiplier) {
        if (n < 1) throw new IllegalArgumentException("N must be >= 1");
        if (lengths.length != n || masses.length != n || initAngles.length != n)
            throw new IllegalArgumentException("Array lengths must equal N");
        // isFinite rejects both NaN and +/-Infinity — neither is caught by a
        // plain "<= 0" check (NaN <= 0 is false in Java), so without this a
        // malformed text field or a corrupt saved scenario file could reach
        // the physics engine's arrays and silently corrupt every subsequent
        // frame rather than failing loudly here.
        for (double m : masses)
            if (!Double.isFinite(m) || m <= 0) throw new IllegalArgumentException("All masses must be a positive, finite number");
        for (double l : lengths)
            if (!Double.isFinite(l) || l <= 0) throw new IllegalArgumentException("All lengths must be a positive, finite number");
        for (double a : initAngles)
            if (!Double.isFinite(a)) throw new IllegalArgumentException("All initial angles must be finite");
        if (!Double.isFinite(gravity) || gravity <= 0)
            throw new IllegalArgumentException("Gravity must be a positive, finite number");
        if (!Double.isFinite(speedMultiplier) || speedMultiplier <= 0)
            throw new IllegalArgumentException("Speed multiplier must be a positive, finite number");

        this.n               = n;
        this.lengths         = lengths.clone();
        this.masses          = masses.clone();
        this.initAngles      = initAngles.clone();
        this.gravity         = gravity;
        this.speedMultiplier = speedMultiplier;
    }

    /** The three-link chain the app opens with — long enough to be visibly chaotic, small enough to stay readable. */
    public static PendulumConfig defaultConfig() {
        int n = 3;
        return new PendulumConfig(
                n,
                new double[]{1.0, 0.8, 0.6},
                new double[]{1.5, 1.0, 0.8},
                new double[]{Math.PI / 2.0, Math.PI / 3.0, Math.PI / 4.0},
                9.81,
                1.0
        );
    }

    /** Number of links in the chain. */
    public int    getN()               { return n; }

    /** Rod length of link {@code i}, in world units (metres). */
    public double getLength(int i)     { return lengths[i]; }

    /** Bob mass of link {@code i}, in kilograms. */
    public double getMass(int i)       { return masses[i]; }

    /** Starting angle of link {@code i} in radians, measured from straight down. */
    public double getInitAngle(int i)  { return initAngles[i]; }

    /** Gravitational acceleration in m/s². Mutable at runtime. */
    public double getGravity()         { return gravity; }

    /** How fast the simulation runs relative to real time (1.0 = real time). Mutable at runtime. */
    public double getSpeedMultiplier() { return speedMultiplier; }

    // The three array getters below each return a CLONE, never the internal
    // array. Without this a caller could reach in and mutate a "final"
    // structural parameter behind the validation performed in the
    // constructor — final protects the reference, not the contents.

    /** Defensive copy of all rod lengths. */
    public double[] getLengths()       { return lengths.clone(); }

    /** Defensive copy of all bob masses. */
    public double[] getMasses()        { return masses.clone(); }

    /** Defensive copy of all starting angles. */
    public double[] getInitAngles()    { return initAngles.clone(); }

    /**
     * Sum of every rod length — the chain's fully-extended reach. Used by
     * {@code ui.PendulumCanvas} to pick a render scale that keeps the whole
     * chain on screen no matter how it is configured.
     */
    public double getTotalLength() {
        double total = 0;
        for (double l : lengths) total += l;
        return total;
    }

    /** Sets gravity mid-simulation. Clamped above zero, matching {@link PhysicsEngine#setGravity}. */
    public void setGravity(double gravity)                { this.gravity = Math.max(0.01, gravity); }

    /** Sets the speed multiplier mid-simulation. Clamped above zero so the loop can never stall or run backwards via this path. */
    public void setSpeedMultiplier(double speedMultiplier){ this.speedMultiplier = Math.max(0.01, speedMultiplier); }
}