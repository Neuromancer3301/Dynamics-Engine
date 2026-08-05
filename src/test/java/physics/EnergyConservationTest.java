package physics;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies RK4 energy conservation across a spread of N — the property the
 * project's central "the physics is correct" claim rests on. N values span
 * a single pendulum up through what the runtime N control's link editor is
 * actually likely to be used for; a benchmark of this engine (see project
 * notes) measured well into the hundreds of thousands of steps/sec even at
 * N=20, so this stays fast despite covering eight configurations.
 */
class EnergyConservationTest {

    private static final double G = 9.81;
    private static final double DT = 0.002;
    private static final double SIM_SECONDS = 10.0;

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {1, 2, 3, 5, 8, 12, 16, 20})
    void energyDriftStaysBelowHalfAPercentOverTenSeconds(int n) {
        // Deliberately not PI/2: the engine's PE convention (-mgL*cos(theta))
        // is zeroed at exactly theta=PI/2, which would make the *reference*
        // energy ~1e-16 here (all links at rest, all starting horizontal) —
        // any relative-drift check then divides real-but-tiny FP noise by a
        // near-zero denominator and "detects" an astronomical fake drift.
        // PI/3 keeps the reference energy a real, non-degenerate value.
        PendulumConfig config = PendulumTestSupport.uniformConfig(n, 3.0, Math.PI / 3.0, G);
        PhysicsEngine engine = new PhysicsEngine(config);

        double e0 = engine.getState().totalEnergy;
        int steps = (int) Math.round(SIM_SECONDS / DT);
        for (int i = 0; i < steps; i++) engine.step(DT);
        double e1 = engine.getState().totalEnergy;

        double driftFraction = Math.abs(e1 - e0) / Math.abs(e0);
        assertTrue(driftFraction < 0.005,
                "N=" + n + " energy drifted " + (driftFraction * 100) + "% over " + SIM_SECONDS + "s (limit 0.5%)");
    }
}
