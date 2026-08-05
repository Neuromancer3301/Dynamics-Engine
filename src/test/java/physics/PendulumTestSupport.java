package physics;

import java.util.ArrayList;
import java.util.List;

/** Shared helpers for the physics test suite. Test-scope only — never shipped. */
final class PendulumTestSupport {

    private PendulumTestSupport() {}

    /** An N-link config with identical links: fixed total arm length split evenly, unit mass, all starting at the same angle. */
    static PendulumConfig uniformConfig(int n, double totalLength, double initAngle, double gravity) {
        double[] lengths = new double[n];
        double[] masses  = new double[n];
        double[] angles  = new double[n];
        for (int i = 0; i < n; i++) {
            lengths[i] = totalLength / n;
            masses[i]  = 1.0;
            angles[i]  = initAngle;
        }
        return new PendulumConfig(n, lengths, masses, angles, gravity, 1.0);
    }

    /** True iff every angle and angular velocity in the state is finite — false if a degenerate step produced NaN/Infinity. */
    static boolean isFiniteState(SimState state) {
        for (double v : state.angles) if (!Double.isFinite(v)) return false;
        for (double v : state.angularVelocities) if (!Double.isFinite(v)) return false;
        return true;
    }

    /**
     * Runs {@code engine} for {@code duration} seconds at fixed {@code dt}, detecting rising
     * zero-crossings of link 0's angle (linearly interpolated between samples for sub-step
     * precision), and returns the average time between consecutive crossings — the measured
     * period. Returns NaN if fewer than two crossings were observed in {@code duration}.
     */
    static double measurePeriod(PhysicsEngine engine, double dt, double duration) {
        List<Double> crossings = new ArrayList<>();
        double prevAngle = engine.getState().angles[0];
        double prevTime  = engine.getState().time;

        int steps = (int) Math.round(duration / dt);
        for (int i = 0; i < steps; i++) {
            engine.step(dt);
            double angle = engine.getState().angles[0];
            double time  = engine.getState().time;
            if (prevAngle < 0 && angle >= 0) {
                double frac = -prevAngle / (angle - prevAngle);
                crossings.add(prevTime + frac * (time - prevTime));
            }
            prevAngle = angle;
            prevTime  = time;
        }

        if (crossings.size() < 2) return Double.NaN;
        double span = crossings.get(crossings.size() - 1) - crossings.get(0);
        return span / (crossings.size() - 1);
    }
}
