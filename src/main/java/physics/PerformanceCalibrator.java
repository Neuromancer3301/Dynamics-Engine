package physics;

import java.util.logging.Logger;

/**
 * Measures this machine's actual RK4 throughput once, and derives a safe
 * maximum N from it — replacing the link editor's earlier hardcoded guess
 * (60, chosen from benchmarks on one specific development machine) with a
 * number reflecting whatever machine is actually running the app.
 *
 * <p>The engine's dominant cost (Cholesky decomposition) is O(N&sup3;), so
 * throughput measured at one reference N extrapolates to any other N via a
 * cube-root relationship — no need to actually benchmark every candidate N.
 *
 * <p>Calibration runs once (a few hundred milliseconds, typically) and is
 * cached for the process's lifetime via {@link #getMaxLinks()}.
 */
public final class PerformanceCalibrator {

    private static final Logger LOG = Logger.getLogger(PerformanceCalibrator.class.getName());

    private static final int REFERENCE_N = 16;
    private static final double CALIBRATION_DT = 0.002;
    private static final int WARMUP_STEPS = 4000;
    private static final int MEASURED_STEPS = 4000;

    // The physics loop needs ~500 steps/sec for 1x real-time at dt=0.002.
    // The sidebar's speed slider goes up to 8x, so the cap targets enough
    // headroom to sustain that too, not just the 1x case.
    private static final double REQUIRED_STEPS_PER_SEC = 500.0 * 8.0;

    // Bounds regardless of what's measured — insurance against a noisy
    // reading (a busy machine, a laptop just woken from sleep), not the
    // primary mechanism.
    private static final int MIN_SAFE_N     = 20;
    private static final int ABSOLUTE_MAX_N = 200;

    private static volatile Integer cachedMaxLinks;

    private PerformanceCalibrator() {}

    /** Returns the cached result, calibrating on first call. */
    public static int getMaxLinks() {
        Integer cached = cachedMaxLinks;
        if (cached != null) return cached;
        synchronized (PerformanceCalibrator.class) {
            if (cachedMaxLinks == null) {
                cachedMaxLinks = calibrate();
                LOG.info(() -> "Performance calibration: max links = " + cachedMaxLinks);
            }
            return cachedMaxLinks;
        }
    }

    /**
     * Runs the actual benchmark and extrapolates a safe maximum N.
     *
     * <p><b>Why warm up first.</b> The JVM interprets bytecode initially and
     * only compiles hot methods to native code after a few thousand
     * executions. Timing before that finishes would measure the interpreter,
     * not the real steady-state speed, and badly underestimate the machine.
     *
     * <p><b>Why extrapolate rather than measure every N.</b> The dominant
     * cost per step is the Cholesky solve, which is O(N³). So if the machine
     * manages some rate at the reference N, the rate at any other N follows
     * by cube scaling — one benchmark answers for all N, instead of
     * benchmarking dozens of sizes at startup.
     */
    private static int calibrate() {
        PhysicsEngine engine = new PhysicsEngine(uniformConfig(REFERENCE_N));

        for (int i = 0; i < WARMUP_STEPS; i++) engine.step(CALIBRATION_DT); // let the JIT warm up before timing

        long start = System.nanoTime();
        for (int i = 0; i < MEASURED_STEPS; i++) engine.step(CALIBRATION_DT);
        double seconds = (System.nanoTime() - start) / 1.0e9;
        double stepsPerSecAtReference = MEASURED_STEPS / Math.max(seconds, 1.0e-9);

        // O(N^3): steps/sec at N scales as stepsPerSecAtReference *
        // (REFERENCE_N / N)^3. Solve for the N where that equals the target.
        double ratio = stepsPerSecAtReference / REQUIRED_STEPS_PER_SEC;
        double maxN  = REFERENCE_N * Math.cbrt(Math.max(ratio, 0.0));

        return Math.max(MIN_SAFE_N, Math.min(ABSOLUTE_MAX_N, (int) Math.floor(maxN)));
    }

    /** A neutral, uniform test chain for benchmarking — deliberately plain, since only the arithmetic cost matters, not the physics. */
    private static PendulumConfig uniformConfig(int n) {
        double[] lengths = new double[n], masses = new double[n], angles = new double[n];
        for (int i = 0; i < n; i++) {
            lengths[i] = 3.0 / n;
            masses[i]  = 1.0;
            angles[i]  = Math.PI / 2.0;
        }
        return new PendulumConfig(n, lengths, masses, angles, 9.81, 1.0);
    }
}
