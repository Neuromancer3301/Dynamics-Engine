package simulation;

/**
 * A hook called once per {@link SimulationLoop} iteration, after the primary
 * engine has been advanced (whether by a manual step or a normal wall-clock
 * advance), before the new state is published.
 *
 * <p><b>Round 2 §3.</b> This exists specifically so orchestration that must
 * stay in lock-step with the primary engine's stepping — a butterfly-effect
 * ensemble, an A/B "compare" engine — can live outside {@link
 * SimulationLoop} without losing that synchronization. Both are genuinely
 * pendulum-specific (an ensemble means perturbing angle/velocity; A/B
 * compare offsets link 0's angle) and don't belong generalized into the loop
 * itself — see {@code ui.pendulum.PendulumChaosFeatures}, which implements
 * this interface and owns that orchestration.
 *
 * <p>Exactly one listener at a time ({@link SimulationLoop#setStepListener}
 * replaces, rather than adds) — this loop has never needed more than one
 * simultaneous "also step this alongside the primary" concern, and a list
 * would be unused generality until a second real caller shows up.
 *
 * @param <E> the concrete primary-engine type being stepped
 */
@FunctionalInterface
public interface StepListener<E> {

    /**
     * @param primaryEngine the just-advanced primary engine, for a listener
     *                      that needs to read its resulting state
     * @param dt            the per-step timestep just used (signed — negative while time-reversed)
     * @param steps         how many steps of {@code dt} the primary engine was just advanced by
     */
    void onStep(E primaryEngine, double dt, int steps);
}
