package simulation.command;

import physics.PhysicsEngine;

/**
 * Replaces the physics thread's {@link PhysicsEngine} instance outright,
 * rather than mutating a field on the existing one.
 *
 * <p>Distinct from {@link SimCommand} on purpose: a {@code SimCommand}
 * mutates the current engine and cannot change its dimensions (N) or its
 * immutable per-link arrays (length, mass). Structural edits — the per-link
 * parameter editor and runtime add/remove-link — need to construct a brand
 * new {@code PhysicsEngine} from an edited {@code PendulumConfig} and swap
 * it in. Keeping this as its own queue means the common case ({@code
 * SimCommand}, e.g. a gravity slider or a drag) stays a one-line lambda,
 * while the rarer structural case is explicit about what it's doing.
 *
 * <p>Applied on the physics thread, same as {@code SimCommand}, so a
 * rebuild can never race with an in-flight RK4 step.
 */
@FunctionalInterface
public interface EngineRebuilder {

    PhysicsEngine rebuild(PhysicsEngine current);
}
