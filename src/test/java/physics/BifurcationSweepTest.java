package physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link BifurcationSweep} at small, fast settings — the real
 * defaults used by {@code controller.SimulationController} take tens of
 * seconds by design (see its own comment), which has no place in a test
 * that runs on every build.
 */
class BifurcationSweepTest {

    private static final double G = 9.81;

    @Test
    void producesOneColumnPerRequestedStep() {
        PendulumConfig base = PendulumTestSupport.uniformConfig(2, 1.0, Math.PI / 2.0, G);

        BifurcationSweep.Result result = BifurcationSweep.sweep(
                base, 0.2, 1.5, 5, 0.2, 0.2, null, () -> false);

        assertEquals(5, result.paramValues().length);
        assertEquals(5, result.samples().size());
        // Ascending because sweep() walks paramMin -> paramMax in order.
        for (int i = 1; i < result.paramValues().length; i++) {
            assertTrue(result.paramValues()[i] > result.paramValues()[i - 1]);
        }
    }

    @Test
    void everySampleIsFinite() {
        PendulumConfig base = PendulumTestSupport.uniformConfig(3, 1.0, Math.PI / 2.0, G);

        BifurcationSweep.Result result = BifurcationSweep.sweep(
                base, 0.5, 2.5, 4, 0.3, 0.3, null, () -> false);

        for (double[] column : result.samples()) {
            for (double y : column) {
                assertTrue(Double.isFinite(y), "Bifurcation sample must be finite");
            }
        }
    }

    @Test
    void singleLinkFallsBackToSamplingTheta1DirectlyInsteadOfProducingNoData() {
        // N=1 has no second link to detect a Poincaré-style crossing
        // against — must still produce samples, not an empty column.
        PendulumConfig base = PendulumTestSupport.uniformConfig(1, 1.0, Math.PI / 3.0, G);

        BifurcationSweep.Result result = BifurcationSweep.sweep(
                base, 0.2, 1.0, 3, 0.2, 0.3, null, () -> false);

        for (double[] column : result.samples()) {
            assertTrue(column.length > 0, "N=1 fallback sampling should still produce points");
        }
    }

    @Test
    void cancellationStopsEarlyWithoutThrowing() {
        PendulumConfig base = PendulumTestSupport.uniformConfig(2, 1.0, Math.PI / 2.0, G);

        BifurcationSweep.Result result = BifurcationSweep.sweep(
                base, 0.2, 1.5, 20, 0.2, 0.2, null, () -> true); // cancelled from the very first check

        assertEquals(0, result.paramValues().length, "Cancelling before any column completes should yield an empty result");
    }
}
