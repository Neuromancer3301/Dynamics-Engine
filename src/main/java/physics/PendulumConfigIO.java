package physics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads and writes {@link PendulumConfig} as a small, hand-rolled JSON
 * format.
 *
 * <p>Deliberately not {@code java.io.Serializable}/{@code
 * ObjectOutputStream}: deserializing an untrusted {@code .pendulum} file
 * through Java's native object serialization is a well-known
 * remote-code-execution vector (arbitrary classes reachable from the
 * classpath can be instantiated and have their {@code readObject()} run
 * during deserialization). A flat JSON object with five known fields,
 * parsed into explicit primitives, has no such surface — the worst a
 * malicious file can do here is fail to parse or fail {@link
 * PendulumConfig}'s own validation.
 *
 * <p>No JSON library dependency, by design — the schema is five fixed
 * fields, and this project's zero-third-party-dependency property is worth
 * keeping for something this small (see {@link MiniJson}).
 */
public final class PendulumConfigIO {

    private static final Logger LOG = Logger.getLogger(PendulumConfigIO.class.getName());

    private PendulumConfigIO() {}

    public static final String FILE_EXTENSION = ".pendulum";

    // A legitimate scenario file — even at the link editor's 60-link cap —
    // is a few KB. Rejecting anything wildly larger before parsing means a
    // file claiming a huge array can't force a huge allocation: the
    // rejection happens before a single byte of content is even read into
    // a JSON value.
    private static final long MAX_FILE_BYTES = 1_000_000;

    // A second, independent bound on N itself once parsing succeeds —
    // clamped here, before PendulumConfig (and the arrays it hands to
    // PhysicsEngine) ever sees it.
    private static final int MAX_N_FROM_FILE = 500;

    public static void save(PendulumConfig config, Path path) throws IOException {
        try {
            Files.writeString(path, toJson(config), StandardCharsets.UTF_8);
            LOG.info(() -> "Saved scenario (N=" + config.getN() + ") to " + path.getFileName());
        } catch (IOException ex) {
            // Message-only, no stack trace: I/O failures here (permissions,
            // full disk) are routine user-facing conditions, not bugs — the
            // caller already surfaces ex.getMessage() in the UI too.
            LOG.warning("Failed to save scenario to " + path + ": " + ex.getMessage());
            throw ex;
        }
    }

    public static PendulumConfig load(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES) {
            IOException ex = new IOException("Scenario file is too large (" + size + " bytes, max " + MAX_FILE_BYTES + ")");
            LOG.warning("Rejected oversized scenario file " + path + ": " + ex.getMessage());
            throw ex;
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        try {
            PendulumConfig config = fromJson(json);
            LOG.info(() -> "Loaded scenario (N=" + config.getN() + ") from " + path.getFileName());
            return config;
        } catch (IOException ex) {
            // Same reasoning as above: a malformed/adversarial file failing
            // validation is an expected, handled outcome (that's the whole
            // point of PendulumConfigIO's javadoc'd defenses) — not worth a
            // full stack trace in the log.
            LOG.warning("Rejected malformed or invalid scenario file " + path + ": " + ex.getMessage());
            throw ex;
        }
    }

    static String toJson(PendulumConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"n\": ").append(config.getN()).append(",\n");
        sb.append("  \"lengths\": ").append(arrayJson(config.getLengths())).append(",\n");
        sb.append("  \"masses\": ").append(arrayJson(config.getMasses())).append(",\n");
        sb.append("  \"initAngles\": ").append(arrayJson(config.getInitAngles())).append(",\n");
        sb.append("  \"gravity\": ").append(config.getGravity()).append(",\n");
        sb.append("  \"speedMultiplier\": ").append(config.getSpeedMultiplier()).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String arrayJson(double[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    static PendulumConfig fromJson(String json) throws IOException {
        Map<String, Object> obj;
        try {
            obj = new MiniJson(json).parseObject();
        } catch (IllegalArgumentException ex) {
            throw new IOException("Malformed scenario file: " + ex.getMessage(), ex);
        }

        int n = requireInt(obj, "n");
        if (n < 1 || n > MAX_N_FROM_FILE) {
            throw new IOException("N must be between 1 and " + MAX_N_FROM_FILE + " (file specified " + n + ")");
        }

        double[] lengths    = requireDoubleArray(obj, "lengths", n);
        double[] masses     = requireDoubleArray(obj, "masses", n);
        double[] initAngles = requireDoubleArray(obj, "initAngles", n);
        double   gravity    = requireDouble(obj, "gravity");
        double   speed      = requireDouble(obj, "speedMultiplier");

        try {
            return new PendulumConfig(n, lengths, masses, initAngles, gravity, speed);
        } catch (IllegalArgumentException ex) {
            // PendulumConfig's own validation (positive finite lengths/masses,
            // finite angles/gravity/speed) is the last line of defense —
            // surfaced as an IOException so callers only need to catch one type.
            throw new IOException("Invalid scenario: " + ex.getMessage(), ex);
        }
    }

    private static int requireInt(Map<String, Object> obj, String key) throws IOException {
        return (int) requireDouble(obj, key);
    }

    private static double requireDouble(Map<String, Object> obj, String key) throws IOException {
        Object v = obj.get(key);
        if (!(v instanceof Double d)) throw new IOException("Missing or invalid field: " + key);
        return d;
    }

    private static double[] requireDoubleArray(Map<String, Object> obj, String key, int expectedLength) throws IOException {
        Object v = obj.get(key);
        if (!(v instanceof List<?> list)) throw new IOException("Missing or invalid field: " + key);
        if (list.size() != expectedLength) {
            throw new IOException("Field '" + key + "' has " + list.size() + " entries, expected " + expectedLength);
        }
        double[] result = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Double d)) throw new IOException("Field '" + key + "'[" + i + "] is not a number");
            result[i] = d;
        }
        return result;
    }
}
