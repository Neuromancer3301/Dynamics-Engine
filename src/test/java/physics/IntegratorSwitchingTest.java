package physics;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import physics.integrator.IntegratorType;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies every {@link IntegratorType} stays numerically well-behaved on a
 * simple pendulum, and that switching mid-simulation doesn't corrupt state.
 * Not a conservation test for Symplectic Euler or Velocity Verlet — neither
 * carries RK4's accuracy guarantee (see their own javadoc, especially
 * Velocity Verlet's caveat about this system's velocity-dependent
 * acceleration) — just a check that they produce finite, bounded motion
 * rather than silently diverging.
 */
class IntegratorSwitchingTest {

    private static final double G = 9.81;

    @ParameterizedTest
    @EnumSource(IntegratorType.class)
    void everyIntegratorStaysFiniteAndBounded(IntegratorType type) {
        PendulumConfig config = PendulumTestSupport.uniformConfig(2, 2.0, Math.PI / 3.0, G);
        PhysicsEngine engine = new PhysicsEngine(config);
        engine.setIntegrator(type.create(2 * config.getN()));

        double e0 = engine.getState().totalEnergy;
        double maxAbsEnergy = Math.abs(e0);

        for (int i = 0; i < 5000; i++) { // 10 simulated seconds at dt=0.002
            engine.step(0.002);
            assertTrue(PendulumTestSupport.isFiniteState(engine.getState()),
                    type + " produced a non-finite state at step " + i);
            maxAbsEnergy = Math.max(maxAbsEnergy, Math.abs(engine.getState().totalEnergy));
        }

        // Not a tight conservation check (see class javadoc) — just ruling
        // out the failure mode of energy blowing up by orders of magnitude,
        // which is what an actually-broken integrator would do.
        assertTrue(maxAbsEnergy < Math.abs(e0) * 50 + 10,
                type + " energy grew unboundedly: started at " + e0 + ", peaked at " + maxAbsEnergy);
    }

    @org.junit.jupiter.api.Test
    void switchingIntegratorsMidSimulationDoesNotCorruptState() {
        PendulumConfig config = PendulumTestSupport.uniformConfig(3, 2.0, Math.PI / 4.0, G);
        PhysicsEngine engine = new PhysicsEngine(config);

        for (int i = 0; i < 1000; i++) engine.step(0.002);
        engine.setIntegrator(IntegratorType.SYMPLECTIC_EULER.create(2 * config.getN()));
        for (int i = 0; i < 1000; i++) engine.step(0.002);
        engine.setIntegrator(IntegratorType.VELOCITY_VERLET.create(2 * config.getN()));
        for (int i = 0; i < 1000; i++) engine.step(0.002);

        assertTrue(PendulumTestSupport.isFiniteState(engine.getState()),
                "State became non-finite after switching integrators mid-run");
    }
}
