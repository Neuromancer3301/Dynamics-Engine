package simulation;

import physics.SimState;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The hand-off point between the physics thread and the JavaFX UI thread —
 * a single-slot, lock-free mailbox holding the most recent {@link SimState}.
 *
 * <p><b>The problem it solves.</b> Two threads run at different speeds: the
 * physics thread publishes ~500 snapshots per second, the renderer consumes
 * ~60. They must not block each other — if rendering ever made the physics
 * thread wait, the simulation would stutter, and vice versa.
 *
 * <p><b>Why this works without locks.</b> {@link AtomicReference} guarantees
 * that a reader sees either the complete old snapshot or the complete new
 * one, never a mixture. Combined with {@code SimState} being deeply
 * immutable, that is all the synchronisation required: there is no shared
 * mutable data left to protect. No {@code synchronized}, no lock
 * contention, no chance of deadlock.
 *
 * <p><b>Why only one slot, with no queue.</b> The renderer only ever wants
 * the newest frame. Older snapshots are worthless the moment a newer one
 * exists, so intermediate values are deliberately overwritten and dropped
 * rather than buffered. (The separate {@code HistoryBuffer} exists for the
 * case where retaining the past genuinely matters.)
 */
public final class StateBuffer {

    // Single slot. Writes from the physics thread, reads from the JavaFX
    // thread; AtomicReference makes the reference swap atomic and visible.
    private final AtomicReference<SimState> slot = new AtomicReference<>();
    
    /** Publishes a new snapshot, discarding whatever was there. Called from the physics thread. */
    public void write(SimState state) {
        slot.set(state);
    }

    /** Returns the most recent snapshot, or {@code null} before the first has been published. Called from the JavaFX thread. */
    public SimState read() {
        return slot.get();
    }
}