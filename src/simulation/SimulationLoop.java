package simulation;

import physics.PhysicsEngine;
import physics.PendulumConfig;
import physics.SimState;

public final class SimulationLoop implements Runnable {

    private static final double FIXED_DT    = 0.002;
    private static final double MAX_WALL_DT = 0.1;

    private final PhysicsEngine  engine;
    private final StateBuffer    buffer;
    private final PendulumConfig config;

    private volatile boolean running = false;
    private volatile boolean paused  = false;
    private volatile double  speed;

    private Thread thread;

    public SimulationLoop(PendulumConfig config, StateBuffer buffer) {
        this.config = config;
        this.engine = new PhysicsEngine(config);
        this.buffer = buffer;
        this.speed  = config.getSpeedMultiplier();
        buffer.write(engine.getState());
    }

    public void start() {
        running = true;
        thread  = new Thread(this, "PhysicsThread");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() { running = false; if (thread != null) thread.interrupt(); }

    public void setPaused(boolean paused)       { this.paused = paused; }
    public void setSpeedMultiplier(double s)    { this.speed  = Math.max(0.01, s); }
    public void setGravity(double g)            { engine.setGravity(g); }

    public void reset() { engine.reset(); buffer.write(engine.getState()); }

    @Override
    public void run() {
        long lastNanos = System.nanoTime();
        while (running) {
            long now      = System.nanoTime();
            double wallDt = Math.min((now - lastNanos) / 1_000_000_000.0, MAX_WALL_DT);
            lastNanos     = now;
            if (!paused && wallDt > 0) {
                double simDt = wallDt * speed;
                int    steps = Math.max(1, (int) Math.ceil(simDt / FIXED_DT));
                double dt    = simDt / steps;
                for (int i = 0; i < steps; i++) engine.step(dt);
                buffer.write(engine.getState());
            }
            try { Thread.sleep(1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
}