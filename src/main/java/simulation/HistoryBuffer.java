package simulation;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A rolling window of recent state snapshots, enabling scrubbing backward
 * through recent history — the answer to "wait, what just happened?" —
 * without pausing or resetting the live simulation.
 *
 * <p>Sampled from the JavaFX render loop ({@code
 * controller.SimulationController}), not the physics thread: {@link
 * StateBuffer} already publishes at up to ~500Hz, far denser than scrubbing
 * needs. This buffer decimates to whatever rate its owner chooses to call
 * {@link #record}, so a useful window (tens of seconds) stays a small,
 * bounded number of entries rather than tens of thousands.
 *
 * <p><b>Scope note:</b> this is a view into the past, not a way to rewind
 * the live simulation itself. Scrubbing shows a historical frame while the
 * physics thread keeps running unaffected underneath; there is deliberately
 * no "resume simulation from this point" — that would mean overwriting the
 * live engine's actual state from a historical snapshot, a materially
 * different (and riskier) feature than the one built here.
 *
 * <p><b>Round 2 §4:</b> genericized over {@code S} — this was already pure
 * storage with zero pendulum-specific logic, so this is a behavior-free
 * change that gives any future simulation type scrubbing for free.
 *
 * @param <S> the state snapshot type being recorded
 */
public final class HistoryBuffer<S> {

    private final int capacity;
    private final Deque<S> samples = new ArrayDeque<>();

    /** @param capacity maximum snapshots retained; the oldest is discarded once full. */
    public HistoryBuffer(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Appends a snapshot, evicting the oldest once {@link #capacity} is
     * exceeded — the rolling-window behaviour that bounds memory. Null is
     * ignored so the caller need not guard against the very first frames,
     * before the physics thread has published anything.
     */
    public void record(S state) {
        if (state == null) return;
        samples.addLast(state);                                   // newest at the tail
        while (samples.size() > capacity) samples.removeFirst();   // drop the oldest
    }

    /** Discards all history. Called on reset and after a structural rebuild, where old samples describe a state that no longer exists. */
    public void clear() {
        samples.clear();
    }

    /** Number of snapshots currently retained — drives the history slider's maximum. */
    public int size() {
        return samples.size();
    }

    /**
     * {@code index} 0 is the oldest recorded sample, {@code size()-1} the
     * most recent. Returns {@code null} if out of range. Linear in {@code
     * index} — fine at this buffer's scale (a few hundred to ~1200 entries)
     * and call rate (slider-drag events, not a hot path).
     */
    public S get(int index) {
        if (index < 0 || index >= samples.size()) return null;
        int i = 0;
        for (S s : samples) {
            if (i++ == index) return s;
        }
        return null; // unreachable given the bounds check above
    }
}
