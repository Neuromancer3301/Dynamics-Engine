package physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the RK4 integrator against closed-form results — the two places
 * a chaotic multi-pendulum system has an exact answer to check against.
 * Everywhere else, "correct" can only mean "conserves energy" (see {@link
 * EnergyConservationTest}); here it means "matches known physics".
 */
class AnalyticComparisonTest {

    private static final double G = 9.81;

    @Test
    void smallAngleMotionMatchesSimpleHarmonicApproximation() {
        double length = 1.0;
        double theta0 = 0.05; // small enough that sin(theta) ~ theta to within ~0.02%
        double omega  = Math.sqrt(G / length);

        PendulumConfig config = PendulumTestSupport.uniformConfig(1, length, theta0, G);
        PhysicsEngine engine = new PhysicsEngine(config);

        double dt     = 0.001;
        double period = 2 * Math.PI / omega;
        int    steps  = (int) Math.round(1.5 * period / dt); // long enough to mean something, short enough that nonlinear phase drift stays negligible

        for (int i = 0; i < steps; i++) {
            engine.step(dt);
            double t        = engine.getState().time;
            double expected = theta0 * Math.cos(omega * t);
            // Nonlinear phase drift over 1.5 periods at this amplitude is
            // ~7e-5 rad (theta0^2/16 relative frequency error); 0.002 gives
            // ~28x margin so this stays sensitive to a real integrator bug
            // without being flaky.
            assertEquals(expected, engine.getState().angles[0], 0.002,
                    "Simulated angle diverged from small-angle SHM approximation at t=" + t);
        }
    }

    @Test
    void periodScalesWithSquareRootOfLength() {
        double theta0 = 0.05;
        double dt = 0.001;

        double period1 = measure(1.0, theta0, dt);
        double period4 = measure(4.0, theta0, dt);

        // T = 2*pi*sqrt(L/g) — quadrupling L should double the period.
        double ratio = period4 / period1;
        assertEquals(2.0, ratio, 0.02, "Period should scale as sqrt(L): quadrupling L should double it");
    }

    private double measure(double length, double theta0, double dt) {
        PendulumConfig config = PendulumTestSupport.uniformConfig(1, length, theta0, G);
        PhysicsEngine engine = new PhysicsEngine(config);
        double expectedPeriod = 2 * Math.PI * Math.sqrt(length / G);
        double period = PendulumTestSupport.measurePeriod(engine, dt, expectedPeriod * 3.5);
        assertTrue(Double.isFinite(period), "Failed to detect enough zero-crossings to measure a period for L=" + length);
        return period;
    }
}
