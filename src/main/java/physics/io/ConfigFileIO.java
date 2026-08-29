package physics.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * The mechanical part of reading/writing a small config file, factored out
 * of {@code physics.PendulumConfigIO} in Round 2 §5 of the physics-layer
 * modularity pass: size-capping a file before a single byte of its content
 * is parsed, and writing text with a consistent success log line. Neither
 * operation knows anything about JSON, or about what schema the caller maps
 * the text to/from — that mapping is exactly what stays in the caller (see
 * {@code PendulumConfigIO#toJson}/{@code #fromJson}), which is the part that
 * genuinely differs between one simulation's config file and another's.
 *
 * <p>A future {@code NBodyConfigIO} with a completely different schema reuses
 * these same two calls for its own read/write boilerplate.
 */
public final class ConfigFileIO {

    private ConfigFileIO() {}

    /**
     * Reads a file's full text, rejecting it outright if larger than {@code
     * maxBytes} before a single byte of content is read. This ordering
     * matters: a file claiming a huge structure can't force a huge
     * allocation, because the rejection happens before parsing ever starts.
     *
     * @param kindLabel human-readable noun for the error message, e.g. {@code "Scenario"}
     * @throws IOException if the file exceeds {@code maxBytes}, or the underlying read fails
     */
    public static String readCapped(Path path, long maxBytes, String kindLabel) throws IOException {
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IOException(kindLabel + " file is too large (" + size + " bytes, max " + maxBytes + ")");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Writes {@code content} to {@code path}, logging a caller-supplied
     * success message on completion. Failure is neither caught nor logged
     * here — callers write their own {@code catch} for a failure message
     * specific to what they're saving (see {@code PendulumConfigIO#save}),
     * since that wording is exactly the part that should differ per caller.
     */
    public static void writeLogged(Path path, String content, Logger log, java.util.function.Supplier<String> successMessage) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        log.info(successMessage::get);
    }
}
