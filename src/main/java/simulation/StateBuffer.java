package simulation;

import physics.SimState;
import java.util.concurrent.atomic.AtomicReference;

public final class StateBuffer {
    
    private final AtomicReference<SimState> slot = new AtomicReference<>();

    public void write(SimState state) {
        slot.set(state);
    }

    public SimState read() {
        return slot.get();
    }
}