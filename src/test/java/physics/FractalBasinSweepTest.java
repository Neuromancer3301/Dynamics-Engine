package physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link FractalBasinSweep} at a small resolution — the app's real
 * 200x200 default takes ~11 s, which has no place in a per-build suite.
 */
class FractalBasinSweepTest {

    @Test
    void producesAGridOfTheRequestedSize() {
        FractalBasinSweep.Result r = FractalBasinSweep.sweep(9.81, 12, 2.0, null, () -> false);
        assertEquals(12, r.resolution());
        assertEquals(12, r.timeToFlip().length);
        for (double[] row : r.timeToFlip()) assertEquals(12, row.length);
    }

    @Test
    void everyCellIsFiniteAndWithinTheTimeBudget() {
        double max = 2.0;
        FractalBasinSweep.Result r = FractalBasinSweep.sweep(9.81, 12, max, null, () -> false);
        for (double[] row : r.timeToFlip())
            for (double v : row) {
                assertTrue(Double.isFinite(v), "time-to-flip must be finite");
                assertTrue(v >= 0 && v <= max, "time-to-flip must lie in [0, maxSeconds]; was " + v);
            }
    }

    @Test
    void aChainHangingAtRestNeverFlips() {
        // The grid centre is (0,0): both links straight down, released at
        // rest. Zero torque, zero velocity — it must sit there forever.
        // This is the single strongest correctness check on the flip test:
        // a false positive here would mean the wrap detector is firing on
        // ordinary motion rather than on a genuine crossing.
        int n = 21; // odd, so a cell lands exactly on (0,0)
        double max = 3.0;
        FractalBasinSweep.Result r = FractalBasinSweep.sweep(9.81, n, max, null, () -> false);
        assertEquals(max, r.timeToFlip()[n / 2][n / 2], 1e-9,
                "a pendulum hanging straight down at rest must never flip");
    }

    @Test
    void highEnergyStartsDoFlipSoSomeCellsAreBelowTheBudget() {
        // The counterpart to the test above: if nothing ever flipped, the
        // image would be uniformly blank and the flip detector broken.
        FractalBasinSweep.Result r = FractalBasinSweep.sweep(9.81, 16, 6.0, null, () -> false);
        boolean anyFlipped = false;
        for (double[] row : r.timeToFlip())
            for (double v : row) if (v < 6.0) { anyFlipped = true; break; }
        assertTrue(anyFlipped, "some high-energy starting angles must flip within the budget");
    }

    @Test
    void cancellationReturnsWithoutThrowing() {
        FractalBasinSweep.Result r = FractalBasinSweep.sweep(9.81, 16, 2.0, null, () -> true);
        assertEquals(16, r.resolution()); // grid still allocated, just unfilled
    }
}
