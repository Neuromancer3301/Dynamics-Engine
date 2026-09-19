package physics.nbody;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies total angular momentum (about the world origin — see {@code
 * physics.nbody.NBodyState}'s javadoc for why the origin, not the center of
 * mass, is the right fixed point) stays conserved for the n-body engine. No
 * pendulum precedent to copy, same reasoning as {@link
 * NBodyMomentumConservationTest} — a pinned pivot constantly exerts torque,
 * which is exactly what breaks angular-momentum conservation for a
 * pendulum chain.
 *
 * <p>Unlike total linear momentum, total angular momentum here is generally
 * <em>nonzero</em> (these fixtures orbit, they don't sit still) — so this
 * checks it stays close to its own starting value, as a percentage, the
 * same shape {@code physics.EnergyConservationTest} uses for energy.
 */
class NBodyAngularMomentumConservationTest {

    private static final double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {2, 5, 10, 32})
    void angularMomentumDriftStaysBoundedAcrossN(int n) {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(n, G);
        NBodyEngine engine = new NBodyEngine(config);

        double l0 = engine.getState().totalAngularMomentum;
        assertTrue(Math.abs(l0) > 0, "Fixture should have nonzero angular momentum to measure drift against");

        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 1000.0;
        for (int step = 0; step < 2000; step++) engine.step(dt);
        double l1 = engine.getState().totalAngularMomentum;

        double driftFraction = Math.abs(l1 - l0) / Math.abs(l0);
        assertTrue(driftFraction < 0.01,
                "N=" + n + " angular momentum drifted " + (driftFraction * 100) + "% (limit 1%)");
    }

    @org.junit.jupiter.api.Test
    void twoBodyOrbitAngularMomentumStaysTight() {
        double m1 = 1.0e24, m2 = 1.0e22, separation = 1.0e9;
        NBodyConfig config = NBodyTestSupport.twoBodyCircularOrbit(m1, m2, separation, G);
        NBodyEngine engine = new NBodyEngine(config);

        double l0 = engine.getState().totalAngularMomentum;
        double period = NBodyTestSupport.twoBodyOrbitalPeriod(m1, m2, separation, G);
        double dt = period / 2000.0;

        for (int step = 0; step < 2000 * 3; step++) engine.step(dt);
        double l1 = engine.getState().totalAngularMomentum;

        double driftFraction = Math.abs(l1 - l0) / Math.abs(l0);
        assertTrue(driftFraction < 0.001,
                "Exact two-body orbit's angular momentum drifted " + (driftFraction * 100) + "% over 3 orbits (limit 0.1%)");
    }
}
