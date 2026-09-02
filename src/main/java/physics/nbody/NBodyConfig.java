package physics.nbody;

/**
 * The complete, validated description of one n-body scene — the "recipe" an
 * {@link NBodyEngine} is built from. Mirrors {@code physics.PendulumConfig}'s
 * structural-vs-runtime discipline exactly:
 * <ul>
 *   <li><b>Structural</b> ({@code n}, {@code mass}, {@code radius}, {@code
 *       positionX/Y}, {@code velocityX/Y}, {@code name}, {@code
 *       softeningLength}) — {@code final}, because changing any of them
 *       means the engine's internal state vector is the wrong size, or the
 *       physical meaning of an existing slot has changed. Editing these
 *       requires building a whole new engine (see {@code
 *       simulation.command.EngineRebuilder}).</li>
 *   <li><b>Runtime</b> ({@code gravitationalConstant}, {@code
 *       speedMultiplier}) — mutable and {@code volatile}, because they can
 *       be changed mid-flight without disturbing anything's dimensions.</li>
 * </ul>
 *
 * <p><b>Units — SI throughout, no exceptions.</b> Positions in meters,
 * velocities in meters/second, masses in kilograms, time in seconds. This
 * is the canonical-internal-unit pattern {@code
 * ui.pendulum.LinkEditorPanel#useDegrees} already applies to angles: the
 * physics layer stores one unambiguous unit, and any alternate display (AU,
 * scientific notation, degrees) is a boundary conversion applied by a UI
 * layer that hasn't been built yet (Phase 5 — see the n-body implementation
 * spec §2.1 and §11). Storing SI now costs nothing and means that later
 * conversion UI never has to touch this class.
 *
 * <p><b>Why {@code Double.isFinite} and not just a sign check.</b> Same
 * reasoning as {@code PendulumConfig}: {@code NaN <= 0} is {@code false} in
 * Java, so a plain positivity check silently lets NaN through. Validating
 * here, in the one constructor every input path funnels through (typed
 * dialogs, a future scenario file, {@link Presets}), means nothing else in
 * the app ever needs to re-check these values.
 */
public final class NBodyConfig {

    /**
     * Standard CODATA gravitational constant, in m³·kg⁻¹·s⁻² — the physical
     * constant matching this class's SI unit choice above.
     */
    public static final double DEFAULT_GRAVITATIONAL_CONSTANT = 6.674e-11;

    /**
     * Softening length default, in meters — judgment call (no numeric value
     * is specified by either source document this phase is grounded
     * against): bigger than any default-roster body's own radius, tiny next
     * to any default-roster orbital separation. Existing purely to keep
     * close approaches numerically sane, not to resolve a collision (that's
     * Phase 4 — see the n-body implementation spec §11).
     */
    public static final double DEFAULT_SOFTENING_LENGTH = 1.0e7;

    private final int n;
    private final double[] mass, radius;
    private final double[] positionX, positionY;
    private final double[] velocityX, velocityY;
    private final String[] name;
    private final double softeningLength;
    private volatile double gravitationalConstant;
    private volatile double speedMultiplier;

    public NBodyConfig(int n, double[] mass, double[] radius,
                        double[] positionX, double[] positionY,
                        double[] velocityX, double[] velocityY,
                        String[] name, double softeningLength,
                        double gravitationalConstant, double speedMultiplier) {
        if (n < 1) throw new IllegalArgumentException("N must be >= 1");
        if (mass.length != n || radius.length != n || positionX.length != n || positionY.length != n
                || velocityX.length != n || velocityY.length != n)
            throw new IllegalArgumentException("Array lengths must equal N");
        if (name != null && name.length != n)
            throw new IllegalArgumentException("name array length must equal N");

        // isFinite rejects both NaN and +/-Infinity — neither is caught by a
        // plain "<= 0" check — so without this a malformed dialog field or a
        // corrupt saved scenario could reach the physics engine's arrays and
        // silently corrupt every subsequent frame rather than failing loudly
        // here. See the class javadoc.
        for (double m : mass)
            if (!Double.isFinite(m) || m <= 0) throw new IllegalArgumentException("All masses must be a positive, finite number");
        for (double r : radius)
            if (!Double.isFinite(r) || r <= 0) throw new IllegalArgumentException("All radii must be a positive, finite number");
        for (double x : positionX)
            if (!Double.isFinite(x)) throw new IllegalArgumentException("All x positions must be finite");
        for (double y : positionY)
            if (!Double.isFinite(y)) throw new IllegalArgumentException("All y positions must be finite");
        for (double vx : velocityX)
            if (!Double.isFinite(vx)) throw new IllegalArgumentException("All x velocities must be finite");
        for (double vy : velocityY)
            if (!Double.isFinite(vy)) throw new IllegalArgumentException("All y velocities must be finite");
        if (!Double.isFinite(softeningLength) || softeningLength <= 0)
            throw new IllegalArgumentException("Softening length must be a positive, finite number");
        if (!Double.isFinite(gravitationalConstant) || gravitationalConstant <= 0)
            throw new IllegalArgumentException("Gravitational constant must be a positive, finite number");
        if (!Double.isFinite(speedMultiplier) || speedMultiplier <= 0)
            throw new IllegalArgumentException("Speed multiplier must be a positive, finite number");

        this.n         = n;
        this.mass      = mass.clone();
        this.radius    = radius.clone();
        this.positionX = positionX.clone();
        this.positionY = positionY.clone();
        this.velocityX = velocityX.clone();
        this.velocityY = velocityY.clone();

        // Structural, non-null: any missing/blank entry defaults to "Body N"
        // (1-indexed, matching how every other index is shown in this app's UI).
        this.name = new String[n];
        for (int i = 0; i < n; i++) {
            String supplied = (name != null) ? name[i] : null;
            this.name[i] = (supplied != null && !supplied.isBlank()) ? supplied : ("Body " + (i + 1));
        }

        this.softeningLength       = softeningLength;
        this.gravitationalConstant = gravitationalConstant;
        this.speedMultiplier       = speedMultiplier;
    }

    /** Number of bodies in the scene. */
    public int getN() { return n; }

    /** Mass of body {@code i}, in kilograms. */
    public double getMass(int i) { return mass[i]; }

    /** Physical radius of body {@code i}, in meters. */
    public double getRadius(int i) { return radius[i]; }

    /** World-space x position of body {@code i}, in meters. */
    public double getPositionX(int i) { return positionX[i]; }

    /** World-space y position of body {@code i}, in meters. */
    public double getPositionY(int i) { return positionY[i]; }

    /** x velocity of body {@code i}, in meters/second. */
    public double getVelocityX(int i) { return velocityX[i]; }

    /** y velocity of body {@code i}, in meters/second. */
    public double getVelocityY(int i) { return velocityY[i]; }

    /** Display label of body {@code i}. Never null or blank — see the constructor's default-naming logic. */
    public String getName(int i) { return name[i]; }

    /** Softening length ε, in meters — see {@link #DEFAULT_SOFTENING_LENGTH}'s javadoc for what this is for. */
    public double getSofteningLength() { return softeningLength; }

    /** Gravitational constant G, in m³·kg⁻¹·s⁻². Mutable at runtime. */
    public double getGravitationalConstant() { return gravitationalConstant; }

    /** How fast the simulation runs relative to real time (1.0 = real time). Mutable at runtime. */
    public double getSpeedMultiplier() { return speedMultiplier; }

    // The array getters below each return a CLONE, never the internal array
    // — see PendulumConfig's identical javadoc note: final protects the
    // reference, not the contents, so a caller reaching in and mutating one
    // of these would otherwise bypass the validation the constructor did.

    /** Defensive copy of every body's mass. */
    public double[] getMasses() { return mass.clone(); }

    /** Defensive copy of every body's physical radius. */
    public double[] getRadii() { return radius.clone(); }

    /** Defensive copy of every body's x position. */
    public double[] getPositionsX() { return positionX.clone(); }

    /** Defensive copy of every body's y position. */
    public double[] getPositionsY() { return positionY.clone(); }

    /** Defensive copy of every body's x velocity. */
    public double[] getVelocitiesX() { return velocityX.clone(); }

    /** Defensive copy of every body's y velocity. */
    public double[] getVelocitiesY() { return velocityY.clone(); }

    /** Defensive copy of every body's display name. */
    public String[] getNames() { return name.clone(); }

    /** Sets G mid-simulation. Clamped to a tiny positive floor (well below any physically meaningful value) so it can never reach zero or negative — see {@code physics.PendulumConfig#setGravity} for the identical reasoning at pendulum-appropriate magnitudes. */
    public void setGravitationalConstant(double g) { this.gravitationalConstant = Math.max(1.0e-15, g); }

    /** Sets the speed multiplier mid-simulation. Clamped above zero so the loop can never stall or run backwards via this path. */
    public void setSpeedMultiplier(double speedMultiplier) { this.speedMultiplier = Math.max(0.01, speedMultiplier); }
}
