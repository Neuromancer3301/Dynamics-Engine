package physics.nbody;

/**
 * An immutable snapshot of the n-body simulation at one instant — the
 * n-body analogue of {@code physics.SimState}. Mirrors its shape and
 * discipline exactly: every array defensively copied in the constructor,
 * fields public final, so a snapshot can safely cross the physics thread /
 * UI thread boundary with no locking (see {@code simulation.StateBuffer}).
 *
 * <p><b>Momentum and angular momentum are new here — {@code SimState} has
 * no equivalent.</b> A pinned pendulum pivot is an external constraint:
 * it's constantly exerting whatever force/torque is needed to keep the
 * pivot fixed, which is exactly what breaks conservation of linear and
 * angular momentum for a pendulum chain. Nothing pins an n-body scene, so
 * both are genuinely conserved quantities here, computed once in the
 * constructor the same way kinetic/potential energy already are:
 *
 * <pre>
 * totalMomentumX = Σ mass[i]*velocityX[i]
 * totalMomentumY = Σ mass[i]*velocityY[i]
 * totalAngularMomentum = Σ mass[i]*(positionX[i]*velocityY[i] - positionY[i]*velocityX[i])
 * </pre>
 *
 * <p>Angular momentum is taken about the fixed world origin, not the
 * center of mass — for an isolated system (zero net external force),
 * angular momentum about any fixed point is separately conserved, so the
 * origin is the simplest valid choice; no center-of-mass tracking is
 * needed just to compute this.
 *
 * <p>Drift-percentage readouts (the UI's "Drift %" equivalent for
 * momentum/angular momentum) are a UI-layer computation — subtract from
 * the value at reset, divide, format — built from the raw components
 * exposed here, nothing more is needed in this class.
 */
public final class NBodyState {

    /** Simulation time in seconds since the last reset. */
    public final double time;

    /** Each body's x/y position in meters, world-space (SI, see {@code NBodyConfig}'s unit policy). */
    public final double[] positionX, positionY;

    /** Each body's x/y velocity in meters per second. */
    public final double[] velocityX, velocityY;

    /** Each body's mass in kilograms — carried along so the renderer can size/label bodies without a second lookup. */
    public final double[] mass;

    /** Each body's physical radius in meters — round 1.2: this now IS the basis for its on-screen render radius too (see {@code ui.nbody.NBodyRenderer#radiusForBody}), true to scale through the same camera transform positions use, floored at a small minimum so it doesn't vanish at wide zoom. */
    public final double[] radius;

    /** Each body's display label — a user-created body defaults to "Body N". */
    public final String[] name;

    /** Energy of motion at this instant. */
    public final double kineticEnergy;

    /** Gravitational potential energy at this instant (negative — bound systems have negative total PE by convention). */
    public final double potentialEnergy;

    /** KE + PE. Should stay constant in an ideal simulation. */
    public final double totalEnergy;

    /** Total linear momentum, x and y components — conserved for an isolated n-body system (see class javadoc). */
    public final double totalMomentumX, totalMomentumY;

    /** Total angular momentum about the world origin — conserved for an isolated system (see class javadoc). */
    public final double totalAngularMomentum;

    /** Copies every array defensively — see the class javadoc for why that is essential, not optional. */
    public NBodyState(double time, double[] positionX, double[] positionY,
                       double[] velocityX, double[] velocityY,
                       double[] mass, double[] radius, String[] name,
                       double kineticEnergy, double potentialEnergy) {
        this.time            = time;
        this.positionX       = positionX.clone();
        this.positionY       = positionY.clone();
        this.velocityX       = velocityX.clone();
        this.velocityY       = velocityY.clone();
        this.mass            = mass.clone();
        this.radius          = radius.clone();
        this.name            = name.clone();
        this.kineticEnergy   = kineticEnergy;
        this.potentialEnergy = potentialEnergy;
        this.totalEnergy     = kineticEnergy + potentialEnergy;

        double px = 0, py = 0, angularMomentum = 0;
        int n = this.positionX.length;
        for (int i = 0; i < n; i++) {
            px += this.mass[i] * this.velocityX[i];
            py += this.mass[i] * this.velocityY[i];
            angularMomentum += this.mass[i] * (this.positionX[i] * this.velocityY[i] - this.positionY[i] * this.velocityX[i]);
        }
        this.totalMomentumX = px;
        this.totalMomentumY = py;
        this.totalAngularMomentum = angularMomentum;
    }

    /** Number of bodies in this snapshot. Derived from the array length rather than stored separately, so it can never disagree. */
    public int getN() { return positionX.length; }
}
