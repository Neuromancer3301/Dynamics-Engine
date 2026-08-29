package simulation.command;

/**
 * Replaces the physics thread's engine instance outright, rather than
 * mutating a field on the existing one.
 *
 * <p>Distinct from {@link SimCommand} on purpose: a {@code SimCommand}
 * mutates the current engine and cannot change its dimensions (N) or its
 * immutable per-link arrays (length, mass). Structural edits — the per-link
 * parameter editor and runtime add/remove-link — need to construct a brand
 * new engine from an edited config and swap it in. Keeping this as its own
 * queue means the common case ({@code SimCommand}, e.g. a gravity slider or
 * a drag) stays a one-line lambda, while the rarer structural case is
 * explicit about what it's doing.
 *
 * <p>Applied on the physics thread, same as {@code SimCommand}, so a
 * rebuild can never race with an in-flight integration step.
 *
 * <p><b>Round 2 §2:</b> genericized over the concrete engine type {@code E},
 * same reasoning as {@link SimCommand} — a rebuilder needs to construct a
 * real {@code E} (e.g. {@code new PhysicsEngine(newConfig)}), which is
 * necessarily type-specific; {@link simulation.SimulationLoop} only needs to
 * swap the reference it's handed back, never to construct one itself.
 *
 * @param <E> the concrete engine type this rebuilder replaces
 */
@FunctionalInterface
public interface EngineRebuilder<E> {

    E rebuild(E current);
}
