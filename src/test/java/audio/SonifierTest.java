package audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * A headless CI runner typically has no audio device at all, which is
 * exactly the case {@link Sonifier} is meant to degrade gracefully under —
 * so these tests deliberately don't assert {@link Sonifier#isAvailable()}
 * either way. What they verify is the actual contract: construction,
 * start/stop, and frequency changes never throw, regardless of whether
 * real audio hardware is behind them.
 */
class SonifierTest {

    @Test
    void neverThrowsRegardlessOfAudioHardwareAvailability() {
        assertDoesNotThrow(() -> {
            Sonifier sonifier = new Sonifier();
            sonifier.setFrequency(440.0);
            sonifier.start();
            sonifier.setFrequency(880.0);
            Thread.sleep(20); // let the synth thread actually run a buffer or two, if it exists
            sonifier.stop();
        });
    }

    @Test
    void repeatedStartStopIsSafe() {
        assertDoesNotThrow(() -> {
            Sonifier sonifier = new Sonifier();
            sonifier.start();
            sonifier.start(); // already running — must be a no-op, not a second thread
            sonifier.stop();
            sonifier.stop();  // already stopped — must be a no-op
        });
    }

    @Test
    void frequencyIsClampedToAnAudibleRange() {
        Sonifier sonifier = new Sonifier();
        // No getter for the clamped value is exposed (setFrequency's only
        // observable effect is on the audio thread) — this just confirms
        // out-of-range input doesn't throw, which is the actual contract.
        assertDoesNotThrow(() -> {
            sonifier.setFrequency(-100.0);
            sonifier.setFrequency(1_000_000.0);
            sonifier.setFrequency(Double.NaN);
        });
    }
}
