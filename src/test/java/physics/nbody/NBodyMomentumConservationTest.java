package physics.nbody;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies total linear momentum stays conserved (~0, since every fixture
 * here is zero-momentum-initialized — see {@link
 * NBodyTestSupport#twoBodyCircularOrbit} and {@link
 * NBodyTestSupport#sunAndPlanets}) for the n-body engine. No pendulum
 * precedent to copy — a pinned pivot is an external constraint that breaks
 * momentum conservation, so {@code physics.SimState} has no equivalent
 * quantity at all (see {@code physics.nbody.NBodyState}'s own javadoc).
 *
 * <p>"Stays ~0" is checked as a fraction of a characteristic momentum scale
 * for the scene (the heaviest body's mass times a representative orbital
 * speed) rather than an absolute tolerance, so the bound means the same
 * thing regardless of which fixture/N is under test.
 */
class NBodyMomentumConservationTest {

    private static final double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {2, 5, 10, 32})
    void totalMomentumStaysNearZero(int n) {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(n, G);
        NBodyEngine engine = new NBodyEngine(config);

        double characteristicMomentum = characteristicMomentumScale(engine.getState());
        assertTrue(characteristicMomentum > 0, "Fixture should have some nonzero motion to measure against");

        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 1000.0;
        double maxMomentumMagnitude = 0;
        for (int step = 0; step < 2000; step++) {
            engine.step(dt);
            NBodyState s = engine.getState();
            double magnitude = Math.hypot(s.totalMomentumX, s.totalMomentumY);
            maxMomentumMagnitude = Math.max(maxMomentumMagnitude, magnitude);
        }

        double fraction = maxMomentumMagnitude / characteristicMomentum;
        assertTrue(fraction < 0.01,
                "N=" + n + " total momentum reached " + (fraction * 100)
                        + "% of the scene's characteristic momentum scale (limit 1%) — should stay ~0");
    }

    @org.junit.jupiter.api.Test
    void twoBodyOrbitMomentumStaysNearZero() {
        double m1 = 1.0e24, m2 = 1.0e22, separation = 1.0e9;
        NBodyConfig config = NBodyTestSupport.twoBodyCircularOrbit(m1, m2, separation, G);
        NBodyEngine engine = new NBodyEngine(config);

        double characteristicMomentum = characteristicMomentumScale(engine.getState());
        double period = NBodyTestSupport.twoBodyOrbitalPeriod(m1, m2, separation, G);
        double dt = period / 2000.0;

        double maxMomentumMagnitude = 0;
        for (int step = 0; step < 2000 * 3; step++) {
            engine.step(dt);
            NBodyState s = engine.getState();
            maxMomentumMagnitude = Math.max(maxMomentumMagnitude, Math.hypot(s.totalMomentumX, s.totalMomentumY));
        }

        double fraction = maxMomentumMagnitude / characteristicMomentum;
        assertTrue(fraction < 0.001,
                "Exact two-body orbit's total momentum reached " + (fraction * 100)
                        + "% of its characteristic momentum scale (limit 0.1%)");
    }

    /** The largest body's mass times the fastest body's speed — a representative "how big is a real momentum here" scale to compare drift against. */
    private static double characteristicMomentumScale(NBodyState state) {
        double maxMass = 0, maxSpeed = 0;
        for (int i = 0; i < state.getN(); i++) {
            maxMass = Math.max(maxMass, state.mass[i]);
            maxSpeed = Math.max(maxSpeed, Math.hypot(state.velocityX[i], state.velocityY[i]));
        }
        return maxMass * maxSpeed;
    }
}
