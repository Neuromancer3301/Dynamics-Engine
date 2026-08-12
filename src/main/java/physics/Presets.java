package physics;

/**
 * Curated starting configurations — the answer to "sliders with no idea
 * which regions of parameter space are interesting," which is most of it.
 * Each preset is a small, deliberate {@link PendulumConfig} factory method;
 * there's no registry or plugin mechanism because six presets don't need one.
 */
public final class Presets {

    private Presets() {}

    /** A named preset, as shown in the picker. */
    public record Preset(String name, PendulumConfig config) {
        @Override public String toString() { return name; } // so a plain ComboBox<Preset> displays the name
    }

    /** Every preset, in the order shown in the picker. Returned as a fresh array so a caller cannot reorder the shared list. */
    public static Preset[] all() {
        return new Preset[] {
            new Preset("Classic Double Pendulum", classicDouble()),
            new Preset("Near-Vertical Knife Edge", nearVerticalKnifeEdge()),
            new Preset("Heavy-Tip Whip", heavyTipWhip()),
            new Preset("30-Link Rope", rope(30)),
            new Preset("Small-Angle Harmonic", smallAngleHarmonic()),
            new Preset("Butterfly Twins", butterflyTwins()),
        };
    }

    /** The textbook chaotic double pendulum: two equal links, released from horizontal. */
    public static PendulumConfig classicDouble() {
        return new PendulumConfig(2, new double[]{1.0, 1.0}, new double[]{1.0, 1.0},
                new double[]{Math.PI / 2, Math.PI / 2}, 9.81, 1.0);
    }

    /** Starts a hair off the unstable equilibrium (straight up) — the RK4/pivoting stability case discussed in the project's original spec. */
    public static PendulumConfig nearVerticalKnifeEdge() {
        return new PendulumConfig(1, new double[]{1.5}, new double[]{1.0},
                new double[]{Math.PI - 0.02}, 9.81, 1.0);
    }

    /** Two light links whipping a heavy tip — makes the mass-coupling term in the Lagrangian visually obvious. */
    public static PendulumConfig heavyTipWhip() {
        return new PendulumConfig(3, new double[]{0.8, 0.8, 0.8}, new double[]{0.3, 0.3, 3.0},
                new double[]{Math.PI / 2, Math.PI / 2, Math.PI / 2}, 9.81, 1.0);
    }

    /** Many short, light links — the continuum limit where the chain starts moving like a flexible rope rather than a rigid pendulum. */
    public static PendulumConfig rope(int n) {
        double[] lengths = new double[n], masses = new double[n], angles = new double[n];
        for (int i = 0; i < n; i++) { lengths[i] = 3.0 / n; masses[i] = 0.3; angles[i] = Math.PI / 2; }
        return new PendulumConfig(n, lengths, masses, angles, 9.81, 1.0);
    }

    /** Small enough that sin(theta) ~ theta — matches the analytic small-angle comparison in the test suite. */
    public static PendulumConfig smallAngleHarmonic() {
        return new PendulumConfig(1, new double[]{1.0}, new double[]{1.0},
                new double[]{0.05}, 9.81, 1.0);
    }

    /** A moderately chaotic double pendulum, a good starting point right before turning on the butterfly-effect ensemble. */
    public static PendulumConfig butterflyTwins() {
        return new PendulumConfig(2, new double[]{1.0, 1.0}, new double[]{1.0, 1.0},
                new double[]{2.2, 2.6}, 9.81, 1.0);
    }
}
