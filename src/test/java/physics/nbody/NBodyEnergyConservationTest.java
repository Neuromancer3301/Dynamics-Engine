package physics.nbody;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies RK4 energy conservation for the n-body engine — the n-body
 * analogue of {@code physics.EnergyConservationTest}.
 *
 * <p>Two tiers, deliberately: {@link #twoBodyOrbitEnergyStaysTight()} first,
 * in isolation, against the *exact* reduced two-body circular solution
 * (see {@link NBodyTestSupport#twoBodyCircularOrbit}) — the n-body
 * implementation spec §13 step 2 calls this out specifically as where the
 * §2.3 n-vs-2N integrator-sizing mistake would actually get caught, before
 * any UI exists. {@link #energyDriftStaysBoundedAcrossN(int)} then sweeps
 * N = 2, 5, 10, 32 (per the spec's acceptance checklist §14) against the
 * looser {@link NBodyTestSupport#sunAndPlanets} approximation, to catch any
 * N-dependent regression the two-body case alone wouldn't exercise.
 */
class NBodyEnergyConservationTest {

    private static final double G = NBodyConfig.DEFAULT_GRAVITATIONAL_CONSTANT;

    @Test
    void twoBodyOrbitEnergyStaysTight() {
        double m1 = 1.0e24, m2 = 1.0e22, separation = 1.0e9;
        NBodyConfig config = NBodyTestSupport.twoBodyCircularOrbit(m1, m2, separation, G);
        NBodyEngine engine = new NBodyEngine(config);

        double period = NBodyTestSupport.twoBodyOrbitalPeriod(m1, m2, separation, G);
        double dt = period / 2000.0;
        int steps = 2000 * 3; // three full orbits

        double e0 = engine.getState().totalEnergy;
        for (int i = 0; i < steps; i++) engine.step(dt);
        double e1 = engine.getState().totalEnergy;

        double driftFraction = Math.abs(e1 - e0) / Math.abs(e0);
        assertTrue(driftFraction < 0.001,
                "Exact two-body orbit energy drifted " + (driftFraction * 100) + "% over 3 orbits (limit 0.1%)");
    }

    @ParameterizedTest(name = "N={0}")
    @ValueSource(ints = {2, 5, 10, 32})
    void energyDriftStaysBoundedAcrossN(int n) {
        NBodyConfig config = NBodyTestSupport.sunAndPlanets(n, G);
        NBodyEngine engine = new NBodyEngine(config);

        double dt = NBodyTestSupport.innermostOrbitalPeriod(G) / 1000.0;
        int steps = 2000; // ~2 innermost-planet orbits

        double e0 = engine.getState().totalEnergy;
        for (int i = 0; i < steps; i++) engine.step(dt);
        double e1 = engine.getState().totalEnergy;

        double driftFraction = Math.abs(e1 - e0) / Math.abs(e0);
        assertTrue(driftFraction < 0.01,
                "N=" + n + " energy drifted " + (driftFraction * 100) + "% (limit 1%)");
    }

    @Test
    void twoBodyCircularOrbitRadiusStaysCircular() {
        // A direct check on §14's "two-body circular orbit stays circular"
        // acceptance item, independent of the energy check above: sample the
        // separation distance periodically and assert it never wanders far
        // from its starting value.
        double m1 = 1.0e24, m2 = 1.0e22, separation = 1.0e9;
        NBodyConfig config = NBodyTestSupport.twoBodyCircularOrbit(m1, m2, separation, G);
        NBodyEngine engine = new NBodyEngine(config);

        double period = NBodyTestSupport.twoBodyOrbitalPeriod(m1, m2, separation, G);
        double dt = period / 2000.0;

        double maxSeparation = 0, minSeparation = Double.MAX_VALUE;
        for (int step = 0; step < 2000 * 3; step++) {
            engine.step(dt);
            NBodyState s = engine.getState();
            double dx = s.positionX[1] - s.positionX[0];
            double dy = s.positionY[1] - s.positionY[0];
            double dist = Math.hypot(dx, dy);
            maxSeparation = Math.max(maxSeparation, dist);
            minSeparation = Math.min(minSeparation, dist);
        }

        double wobble = (maxSeparation - minSeparation) / separation;
        assertTrue(wobble < 0.01,
                "Circular-orbit separation wobbled by " + (wobble * 100) + "% of its nominal radius (limit 1%)");
    }
}
