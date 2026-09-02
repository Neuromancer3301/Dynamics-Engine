package physics.nbody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the n-body engine degrades gracefully rather than catastrophically
 * at adversarial inputs — the n-body analogue of {@code
 * physics.RobustnessTest}. "Graceful" means finite output, not necessarily
 * physically exact: two near-coincident bodies are expected to interact
 * violently (that's what the softening length exists to keep numerically
 * sane, not to make physically pretty — see the n-body implementation spec
 * §11), it just must never hand NaN/Infinity to the render loop.
 */
class NBodyRobustnessTest {

    private static final double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

    @Test
    void nearCoincidentBodiesStayFiniteThanksToSoftening() {
        // Separation is a small fraction of the softening length itself —
        // exactly the regime softening exists for: without it, 1/dist^2
        // would spike towards infinity as dist approaches zero.
        double softening = NBodyConfig.DEFAULT_SOFTENING_LENGTH;
        double separation = softening * 1.0e-3;

        NBodyConfig config = new NBodyConfig(2,
                new double[]{1.0e24, 1.0e24}, new double[]{1.0e6, 1.0e6},
                new double[]{-separation / 2, separation / 2}, new double[]{0.0, 0.0},
                new double[]{0.0, 0.0}, new double[]{0.0, 0.0},
                null, softening, G, 1.0);
        NBodyEngine engine = new NBodyEngine(config);

        for (int i = 0; i < 5000; i++) {
            engine.step(1.0);
            assertTrue(NBodyTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at step " + i + " with near-coincident bodies");
        }
    }

    @Test
    void nearZeroMassStaysFiniteRatherThanProducingNaN() {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(4, G);
        // Overwrite one planet's mass to be vanishingly small via a fresh
        // config built from the same arrays — NBodyConfig requires mass >
        // 0, so "near-zero" rather than literally zero, mirroring
        // PendulumConfig's near-zero-mass robustness case.
        double[] mass = config.getMasses();
        mass[1] = 1.0e-9;
        NBodyConfig degenerate = new NBodyConfig(config.getN(), mass, config.getRadii(),
                config.getPositionsX(), config.getPositionsY(),
                config.getVelocitiesX(), config.getVelocitiesY(),
                config.getNames(), config.getSofteningLength(),
                config.getGravitationalConstant(), config.getSpeedMultiplier());
        NBodyEngine engine = new NBodyEngine(degenerate);

        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 500.0;
        for (int i = 0; i < 5000; i++) {
            engine.step(dt);
            assertTrue(NBodyTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at step " + i + " with a near-zero mass body");
        }
    }

    @Test
    void largeNStaysFiniteAtRepresentativeUpperBound() {
        // 32 mirrors the real Presets.homeSolarSystem() roster size — the
        // representative "large N" this app's own UI actually builds.
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(32, G);
        NBodyEngine engine = new NBodyEngine(config);

        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 500.0;
        for (int i = 0; i < 2000; i++) {
            engine.step(dt);
            assertTrue(NBodyTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at step " + i + " for N=32");
        }
    }

    @Test
    void extremeSpeedMultiplierScaleStepStaysFiniteAtLargeFixedDt() {
        // Mirrors §14's "one frame at N=32, fixedDt=3600, speedMultiplier
        // approx 1.5e6 renders smoothly" acceptance item at the engine
        // level: a single very large dt (the kind a high-speed-multiplier
        // frame subdivided by a 3600s fixedDt would use) must not blow up.
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(32, G);
        NBodyEngine engine = new NBodyEngine(config);

        for (int i = 0; i < 200; i++) {
            engine.step(3600.0);
            assertTrue(NBodyTestSupport.isFiniteState(engine.getState()),
                    "State became non-finite at step " + i + " using a 3600s fixedDt at N=32");
        }
    }
}
