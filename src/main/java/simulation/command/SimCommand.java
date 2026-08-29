package simulation.command;

/**
 * A mutation applied to a simulation engine, executed exclusively on the
 * physics thread between integration steps.
 *
 * <p>This is the one and only path by which the JavaFX Application Thread is
 * allowed to change simulation state. Before this existed, {@code
 * SimulationLoop} exposed one {@code volatile}-backed setter per parameter
 * (gravity, then whatever came next) — that scales linearly in boilerplate
 * and cannot express a mutation that touches more than one field atomically
 * with respect to a step boundary.
 *
 * <p><b>Extension point:</b> every planned interactive feature is a
 * {@code SimCommand}:
 * <ul>
 *   <li>Per-link parameter edits (length/mass/angle) — read the engine's
 *       current arrays, write the edited ones, matching Phase 2's
 *       per-link inspector.</li>
 *   <li>Runtime add/remove-link — replaces the engine's internal arrays
 *       with resized ones carrying over existing state.</li>
 *   <li>Drag/grab/fling — sets one link's angle and angular velocity
 *       directly from pointer state, still applied only between steps.</li>
 * </ul>
 * None of those need a change to {@link simulation.SimulationLoop} — they
 * only need a new {@code SimCommand} implementation (or lambda) submitted
 * via {@link simulation.SimulationLoop#submit(SimCommand)}.
 *
 * <p><b>Round 2 §2:</b> genericized over the concrete engine type {@code E}
 * (not {@code simulation.SimulationEngine<S>} itself) — a command needs full
 * type-specific access to the engine it mutates (e.g. {@code
 * PhysicsEngine#setGravity}, which is not part of the generic contract), and
 * {@link simulation.SimulationLoop} never needs to know what a queued
 * command actually does with the engine it's handed, only that it can hand
 * one over. This is what lets a future n-body engine's commands see
 * n-body-specific setters without {@code SimulationLoop} knowing they exist.
 *
 * @param <E> the concrete engine type this command mutates
 */
@FunctionalInterface
public interface SimCommand<E> {

    void apply(E engine);
}
