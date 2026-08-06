package audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Continuously synthesizes a single sine tone whose frequency tracks a live
 * physics quantity — the tip bob's angular speed, by convention (see
 * {@code controller.SimulationController}), mapped into an audible range.
 * "Hearing" the chaos is a different channel than watching it: two nearly
 * identical starting angles sound identical at first and then audibly
 * diverge, the same story the butterfly-effect ensemble tells visually.
 *
 * <p>Runs entirely on its own daemon thread against a {@link
 * SourceDataLine}, decoupled from the render loop the same way {@code
 * simulation.SimulationLoop} is decoupled from the JavaFX thread: the
 * frequency is a {@code volatile double}, written from whichever thread
 * computes it and read every audio buffer without any lock, which is all a
 * single scalar needs.
 *
 * <p>Phase accumulates continuously across buffers (never reset when the
 * frequency changes) specifically to avoid the audible click a phase reset
 * would cause; the same reasoning covers the short linear fade-in/out on
 * {@link #start}/{@link #stop}.
 *
 * <p>If no audio device is available (a headless CI runner, a machine with
 * output disabled), construction degrades to an inert no-op rather than
 * throwing — this is a "nice to have" demonstration feature, not something
 * that should be able to prevent the rest of the app from starting.
 */
public final class Sonifier {

    private static final Logger LOG = Logger.getLogger(Sonifier.class.getName());

    private static final float  SAMPLE_RATE   = 44100f;
    private static final int    BUFFER_FRAMES = 1024;
    private static final double FADE_SECONDS  = 0.05;
    private static final short  MAX_AMPLITUDE = 6000; // well under Short.MAX_VALUE — a demonstration tone, not a full-volume alarm

    private final SourceDataLine line;
    private final boolean available;

    private volatile double frequencyHz = 440.0;
    private volatile boolean running = false;
    private Thread thread;

    public Sonifier() {
        SourceDataLine openedLine;
        boolean ok;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, true);
            openedLine = AudioSystem.getSourceDataLine(format);
            openedLine.open(format, BUFFER_FRAMES * 4);
            ok = true;
        } catch (LineUnavailableException | IllegalArgumentException ex) {
            LOG.log(Level.WARNING, "No audio output available — sonification will be a no-op", ex);
            openedLine = null;
            ok = false;
        }
        this.line = openedLine;
        this.available = ok;
    }

    public boolean isAvailable() { return available; }

    /** Sets the tone's frequency in Hz. Safe to call from any thread, at any rate — see the class javadoc. */
    public void setFrequency(double hz) {
        this.frequencyHz = Math.max(20.0, Math.min(4000.0, hz));
    }

    public synchronized void start() {
        if (!available || running) return;
        running = true;
        line.start();
        thread = new Thread(this::synthesize, "Sonifier");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        if (!available || !running) return;
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        line.flush();
        line.stop();
    }

    private void synthesize() {
        byte[] buffer = new byte[BUFFER_FRAMES * 2]; // 16-bit mono => 2 bytes/frame
        double phase = 0.0;
        long framesWritten = 0;
        long fadeInFrames  = (long) (FADE_SECONDS * SAMPLE_RATE);

        while (running) {
            for (int i = 0; i < BUFFER_FRAMES; i++) {
                double hz = frequencyHz; // one volatile read per sample — see class javadoc
                phase += 2.0 * Math.PI * hz / SAMPLE_RATE;
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;

                double envelope = Math.min(1.0, (double) framesWritten / Math.max(1, fadeInFrames));
                short sample = (short) (Math.sin(phase) * MAX_AMPLITUDE * envelope);

                buffer[i * 2]     = (byte) (sample >> 8);
                buffer[i * 2 + 1] = (byte) sample;
                framesWritten++;
            }
            line.write(buffer, 0, buffer.length);
        }
    }
}
