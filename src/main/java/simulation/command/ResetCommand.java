package simulation.command;

import physics.PhysicsEngine;

/**
 * Restores the engine to its configured initial angles at rest.
 *
 * <p>Kept as a named class rather than a lambda purely to illustrate the
 * pattern for future commands that carry their own parameters and
 * validation logic (an {@code AddLinkCommand} needing a default length and
 * mass, say) — a plain {@code engine -> engine.reset()} lambda would do the
 * same job with less ceremony for a zero-argument command like this one.
 */
public final class ResetCommand implements SimCommand {

    @Override
    public void apply(PhysicsEngine engine) {
        engine.reset();
    }
}
