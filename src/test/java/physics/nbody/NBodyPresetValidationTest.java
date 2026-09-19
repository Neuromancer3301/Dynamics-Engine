package physics.nbody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests validating preset roster expansion, dynamics, and momentum conservation
 * for Home Solar System, TRAPPIST-1, Alpha Centauri, and Clear All presets.
 */
public class NBodyPresetValidationTest {

    @Test
    void allPresetsReturnedInExactOrder() {
        Presets.Preset[] all = Presets.all();
        assertEquals(4, all.length);
        assertEquals("Home Solar System", all[0].name());
        assertEquals("TRAPPIST-1", all[1].name());
        assertEquals("Alpha Centauri", all[2].name());
        assertEquals("Clear All", all[3].name());
    }

    @Test
    void homeSolarSystemHasRotationPeriodsAndZeroMomentum() {
        NBodyConfig config = Presets.homeSolarSystem();
        assertEquals(34, config.getN());

        double[] periods = config.getRotationPeriods();
        assertEquals(34, periods.length);
        for (int i = 0; i < 34; i++) {
            assertTrue(periods[i] > 0, "Body " + config.getName(i) + " must have a positive rotation period");
        }

        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();
        assertMomentumConserved(state);
    }

    @Test
    void trappist1InitializesWithSevenPlanetsAndZeroMomentum() {
        NBodyConfig config = Presets.trappist1();
        assertEquals(8, config.getN()); // 1 star + 7 exoplanets

        assertEquals("TRAPPIST-1", config.getName(0));
        assertEquals("TRAPPIST-1 b", config.getName(1));
        assertEquals("TRAPPIST-1 c", config.getName(2));
        assertEquals("TRAPPIST-1 d", config.getName(3));
        assertEquals("TRAPPIST-1 e", config.getName(4));
        assertEquals("TRAPPIST-1 f", config.getName(5));
        assertEquals("TRAPPIST-1 g", config.getName(6));
        assertEquals("TRAPPIST-1 h", config.getName(7));

        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();
        assertMomentumConserved(state);

        // Step engine
        double initEnergy = state.totalEnergy;
        engine.step(3600.0);
        NBodyState stepped = engine.getState();
        assertMomentumConserved(stepped);
        // Energy should be closely conserved
        assertEquals(initEnergy, stepped.totalEnergy, Math.abs(initEnergy) * 1.0e-4);
    }

    @Test
    void alphaCentauriInitializesWithFourBodiesAndZeroMomentum() {
        NBodyConfig config = Presets.alphaCentauri();
        assertEquals(4, config.getN());

        assertEquals("Alpha Centauri A", config.getName(0));
        assertEquals("Alpha Centauri B", config.getName(1));
        assertEquals("Proxima Centauri", config.getName(2));
        assertEquals("Proxima b", config.getName(3));

        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();
        assertMomentumConserved(state);

        // Step engine
        double initEnergy = state.totalEnergy;
        engine.step(3600.0);
        NBodyState stepped = engine.getState();
        assertMomentumConserved(stepped);
        assertEquals(initEnergy, stepped.totalEnergy, Math.abs(initEnergy) * 1.0e-4);
    }

    private static void assertMomentumConserved(NBodyState state) {
        double maxMass = 0, maxSpeed = 0;
        for (int i = 0; i < state.getN(); i++) {
            maxMass = Math.max(maxMass, state.mass[i]);
            maxSpeed = Math.max(maxSpeed, Math.hypot(state.velocityX[i], state.velocityY[i]));
        }
        double characteristicMomentum = maxMass * maxSpeed;
        double momentum = Math.hypot(state.totalMomentumX, state.totalMomentumY);
        double fraction = characteristicMomentum > 0 ? momentum / characteristicMomentum : 0.0;
        assertTrue(fraction < 1.0e-6, "Total momentum fraction " + fraction + " must be < 1e-6");
    }

    @Test
    void clearAllProducesZeroBodyConfig() {
        NBodyConfig config = Presets.clearAll();
        assertEquals(0, config.getN());
        assertEquals(0, config.getMasses().length);
        assertEquals(0, config.getRadii().length);
        assertEquals(0, config.getRotationPeriods().length);

        NBodyEngine engine = new NBodyEngine(config);
        NBodyState state = engine.getState();
        assertEquals(0, state.getN());
        assertEquals(0.0, state.totalEnergy);
        assertEquals(0.0, state.totalMomentumX);
        assertEquals(0.0, state.totalMomentumY);
    }
}
