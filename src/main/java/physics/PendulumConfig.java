package physics;

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

    public int    getN()               { return n; }
    public double getLength(int i)     { return lengths[i]; }
    public double getMass(int i)       { return masses[i]; }
    public double getInitAngle(int i)  { return initAngles[i]; }
    public double getGravity()         { return gravity; }
    public double getSpeedMultiplier() { return speedMultiplier; }
    public double[] getLengths()       { return lengths.clone(); }
    public double[] getMasses()        { return masses.clone(); }
    public double[] getInitAngles()    { return initAngles.clone(); }

    public double getTotalLength() {
        double total = 0;
        for (double l : lengths) total += l;
        return total;
    }

    public void setGravity(double gravity)                { this.gravity = Math.max(0.01, gravity); }
    public void setSpeedMultiplier(double speedMultiplier){ this.speedMultiplier = Math.max(0.01, speedMultiplier); }
}