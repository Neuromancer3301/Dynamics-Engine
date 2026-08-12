package physics;

/**
 * An immutable snapshot of the simulation at one instant — the single data
 * type that crosses the physics thread / UI thread boundary.
 *
 * <p><b>Why immutable?</b> The physics thread produces these ~500 times a
 * second while the JavaFX thread reads them at 60 fps. If the UI could see
 * a half-updated object it would render a chain whose angles came from two
 * different moments — visible as tearing or jitter. Because every field is
 * {@code final} and every array is defensively copied in the constructor,
 * a {@code SimState} can never change after it is built, so no locking is
 * needed to share it. See {@code simulation.StateBuffer} for the hand-off.
 *
 * <p><b>Why the arrays are copied.</b> {@code PhysicsEngine} reuses its
 * internal buffers on every step (a deliberate zero-allocation design). If
 * this class stored those references directly, the "snapshot" would mutate
 * underneath the renderer. The {@code .clone()} calls below are what make
 * the snapshot genuinely a snapshot.
 *
 * <p>Fields are public and final rather than behind getters: this is a
 * pure data carrier read in tight render loops, and final fields cannot be
 * tampered with anyway.
 */
public final class SimState {

    /** Simulation time in seconds since the last reset. Can decrease when running in reverse. */
    public final double   time;

    /** Each link's angle in radians, measured from straight down, wrapped to (-pi, pi]. */
    public final double[] angles;

    /** Each link's angular velocity in radians per second. */
    public final double[] angularVelocities;

    /** Each bob's absolute x position in world units, already accumulated down the chain. */
    public final double[] bobX;

    /** Each bob's absolute y position; NEGATIVE is downward, matching the angle convention. */
    public final double[] bobY;

    /** Each link's mass — carried along so the renderer can size bobs by mass without a second lookup. */
    public final double[] masses;

    /** Energy of motion at this instant. */
    public final double   kineticEnergy;

    /** Energy of position (height) at this instant. Negative below the pivot. */
    public final double   potentialEnergy;

    /** KE + PE. Should stay constant in an ideal simulation — the app's "Drift %" measures how far it has wandered. */
    public final double   totalEnergy;

    /** Copies every array defensively — see the class javadoc for why that is essential, not optional. */
    public SimState(double time, double[] angles, double[] angularVelocities,
                    double[] bobX, double[] bobY, double[] masses,
                    double kineticEnergy, double potentialEnergy) {
        this.time              = time;
        this.angles            = angles.clone();
        this.angularVelocities = angularVelocities.clone();
        this.bobX              = bobX.clone();
        this.bobY              = bobY.clone();
        this.masses            = masses.clone();
        this.kineticEnergy     = kineticEnergy;
        this.potentialEnergy   = potentialEnergy;
        this.totalEnergy       = kineticEnergy + potentialEnergy;
    }

    /** Number of links in this snapshot. Derived from the array length rather than stored separately, so it can never disagree. */
    public int getN() { return angles.length; }
}