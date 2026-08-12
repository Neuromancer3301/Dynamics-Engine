package simulation;

import physics.PendulumConfig;
import physics.PhysicsEngine;
import physics.SimState;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of near-identical {@link PhysicsEngine} copies, each starting from
 * the primary simulation's exact current state except for a microscopic
 * perturbation on link 0 — sensitive dependence on initial conditions made
 * directly visible. For the first few seconds the copies are indistinguishable
 * from the primary chain; because this is a chaotic system, they then
 * measurably fan apart.
 *
 * <p>Cost is not a concern here: this engine's own benchmark measures
 * thousands-of-times real-time throughput even at small N, so running
 * dozens of extra copies alongside the primary costs a low single-digit
 * percentage of one core.
 *
 * <p>Each member's starting state is set via {@link
 * PhysicsEngine#setLinkState} — the same mechanism built for interactive
 * dragging — rather than through {@link PendulumConfig}, specifically so
 * the ensemble can be spawned from whatever the primary is doing <em>right
 * now</em> (mid-swing, paused, anything) rather than only from a reset.
 */
public final class Ensemble {

    private final List<PhysicsEngine> members;

    public Ensemble(PendulumConfig structuralConfig, double[] currentAngles,
                    double[] currentAngularVelocities, int count, double epsilon) {
        List<PhysicsEngine> built = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            PhysicsEngine engine = new PhysicsEngine(structuralConfig);

            // Alternating sign and growing scale so the ensemble starts as a
            // tight, visually imperceptible cluster rather than one doubled
            // line — and so members separate at a range of rates once chaos
            // takes over, instead of all diverging in lockstep.
            double sign = (i % 2 == 0) ? 1.0 : -1.0;
            double perturbation = sign * epsilon * (1 + i / 2.0);

            for (int link = 0; link < currentAngles.length; link++) {
                double angle = currentAngles[link] + (link == 0 ? perturbation : 0.0);
                engine.setLinkState(link, angle, currentAngularVelocities[link]);
            }
            built.add(engine);
        }
        this.members = built;
    }

    /** Advances every member by {@code steps} steps of {@code dt}. Called from the physics thread, alongside the primary engine. */
    public void step(double dt, int steps) {
        for (PhysicsEngine engine : members) {
            for (int i = 0; i < steps; i++) engine.step(dt);
        }
    }

    /** Snapshots every member's current state for rendering. Called from the JavaFX thread. */
    public List<SimState> snapshot() {
        List<SimState> states = new ArrayList<>(members.size());
        for (PhysicsEngine engine : members) states.add(engine.getState());
        return states;
    }

    /** Number of copies in this ensemble. */
    public int size() { return members.size(); }
}
