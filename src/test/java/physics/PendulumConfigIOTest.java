package physics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link PendulumConfigIO}'s round-trip and, more importantly, its
 * defenses against malformed or adversarial input — this is the one file in
 * the project that parses content from outside the process, so it's worth
 * testing as such rather than only testing the happy path.
 */
class PendulumConfigIOTest {

    @Test
    void roundTripsAllFieldsExactly(@TempDir Path dir) throws IOException {
        PendulumConfig original = new PendulumConfig(
                3, new double[]{1.0, 0.8, 0.6}, new double[]{1.5, 1.0, 0.8},
                new double[]{Math.PI / 2, Math.PI / 3, Math.PI / 4}, 9.81, 1.5);

        Path file = dir.resolve("scenario" + PendulumConfigIO.FILE_EXTENSION);
        PendulumConfigIO.save(original, file);
        PendulumConfig loaded = PendulumConfigIO.load(file);

        assertEquals(original.getN(), loaded.getN());
        assertArrayEquals(original.getLengths(), loaded.getLengths(), 1e-12);
        assertArrayEquals(original.getMasses(), loaded.getMasses(), 1e-12);
        assertArrayEquals(original.getInitAngles(), loaded.getInitAngles(), 1e-12);
        assertEquals(original.getGravity(), loaded.getGravity(), 1e-12);
        assertEquals(original.getSpeedMultiplier(), loaded.getSpeedMultiplier(), 1e-12);
    }

    @Test
    void rejectsTruncatedJson(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("broken" + PendulumConfigIO.FILE_EXTENSION);
        Files.writeString(file, "{ \"n\": 2, \"lengths\": [1.0, ");
        assertThrows(IOException.class, () -> PendulumConfigIO.load(file));
    }

    @Test
    void rejectsMismatchedArrayLength(@TempDir Path dir) throws IOException {
        // n=2 but only one length supplied.
        Path file = dir.resolve("mismatched" + PendulumConfigIO.FILE_EXTENSION);
        Files.writeString(file,
                "{ \"n\": 2, \"lengths\": [1.0], \"masses\": [1.0, 1.0], "
              + "\"initAngles\": [0.1, 0.2], \"gravity\": 9.81, \"speedMultiplier\": 1.0 }");
        assertThrows(IOException.class, () -> PendulumConfigIO.load(file));
    }

    @Test
    void rejectsNAboveTheDocumentedCeiling(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("huge" + PendulumConfigIO.FILE_EXTENSION);
        Files.writeString(file, "{ \"n\": 999999, \"lengths\": [], \"masses\": [], "
              + "\"initAngles\": [], \"gravity\": 9.81, \"speedMultiplier\": 1.0 }");
        assertThrows(IOException.class, () -> PendulumConfigIO.load(file));
    }

    @Test
    void rejectsFilesLargerThanTheSizeCeiling(@TempDir Path dir) throws IOException {
        // Oversized before parsing is even attempted — the defense this test
        // targets is the raw-byte-count check, not the parser itself.
        Path file = dir.resolve("oversized" + PendulumConfigIO.FILE_EXTENSION);
        String padding = "0".repeat(1_500_000);
        Files.writeString(file, "{ \"n\": 1, \"padding\": \"" + padding + "\" }");
        assertThrows(IOException.class, () -> PendulumConfigIO.load(file));
    }

    @Test
    void rejectsNonFiniteValuesPropagatedFromTheFile(@TempDir Path dir) throws IOException {
        // JSON has no NaN/Infinity literal, but nothing stops a hand-edited
        // file from trying a value PendulumConfig itself must still catch.
        Path file = dir.resolve("degenerate" + PendulumConfigIO.FILE_EXTENSION);
        Files.writeString(file, "{ \"n\": 1, \"lengths\": [-1.0], \"masses\": [1.0], "
              + "\"initAngles\": [0.0], \"gravity\": 9.81, \"speedMultiplier\": 1.0 }");
        assertThrows(IOException.class, () -> PendulumConfigIO.load(file));
    }
}
