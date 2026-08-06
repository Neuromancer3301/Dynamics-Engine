package physics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a short, free-text description ("5 links, heavy first bob, low
 * gravity, start near horizontal") into a {@link PendulumConfig} via
 * keyword and regex matching — deliberately <em>not</em> a call to an LLM
 * API. That was the original scope for this feature; a local, offline,
 * zero-latency, zero-third-party-dependency parser was chosen instead, for
 * the same reasons {@code MiniJson} exists rather than a JSON library and
 * {@code PendulumConfigIO} hand-rolls its format rather than using Java
 * serialization: this project consistently favors a small hand-rolled
 * mechanism it fully owns over an external dependency, especially one
 * (a network call to a paid API) this simulator would otherwise have zero
 * other reason to need.
 *
 * <p>This is honestly a heuristic, not natural-language understanding: it
 * recognizes a fixed set of patterns (see the fields below) and silently
 * ignores anything it doesn't recognize, falling back to {@code fallback}'s
 * values for whatever wasn't mentioned. It will not understand phrasings
 * outside what it's built for — the UI surfaces this as "keyword parser,"
 * not "AI," on purpose.
 */
public final class NaturalLanguageSceneParser {

    private NaturalLanguageSceneParser() {}

    private static final Pattern LINK_COUNT =
            Pattern.compile("(\\d+)\\s*(?:link|pendulum|arm|chain|segment)s?", Pattern.CASE_INSENSITIVE);

    private static final Pattern GRAVITY_NUMERIC =
            Pattern.compile("gravity\\s*(?:of|is|=|:)?\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern ANGLE_DEGREES =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*degrees?", Pattern.CASE_INSENSITIVE);

    // Named gravity presets (m/s^2) — real values, not arbitrary round numbers.
    private static final double GRAVITY_EARTH = 9.81;
    private static final double GRAVITY_MOON = 1.62;
    private static final double GRAVITY_MARS = 3.71;
    private static final double GRAVITY_JUPITER = 24.79;
    private static final double GRAVITY_ZERO = 0.05; // engine requires > 0; this reads as "effectively none"
    private static final double GRAVITY_LOW = 3.0;
    private static final double GRAVITY_HIGH = 20.0;

    private static final int DEFAULT_MAX_N = 60;

    /** Convenience overload using {@link #DEFAULT_MAX_N} as the link-count ceiling. */
    public static PendulumConfig parse(String text, PendulumConfig fallback) {
        return parse(text, fallback, DEFAULT_MAX_N);
    }

    /**
     * @param text     the free-text description to interpret
     * @param fallback supplies every value this heuristic doesn't recognize in {@code text}
     * @param maxN     upper bound for a recognized link count (typically the caller's own measured/UI limit)
     */
    public static PendulumConfig parse(String text, PendulumConfig fallback, int maxN) {
        String t = text == null ? "" : text.toLowerCase();

        int n = parseLinkCount(t, fallback.getN(), maxN);

        double[] lengths = new double[n];
        double[] masses = new double[n];
        double[] angles = new double[n];
        double fallbackAngle = fallback.getN() > 0 ? fallback.getInitAngle(0) : Math.PI / 2.0;
        double fallbackLength = fallback.getN() > 0 ? fallback.getLength(0) : 0.5;
        double fallbackMass = fallback.getN() > 0 ? fallback.getMass(0) : 1.0;

        double uniformAngle = parseAngle(t, fallbackAngle);
        for (int i = 0; i < n; i++) {
            lengths[i] = fallbackLength;
            masses[i] = fallbackMass;
            angles[i] = uniformAngle;
        }

        applyLinkModifiers(t, "heavy", "first", masses, 0, 3.0);
        applyLinkModifiers(t, "light", "first", masses, 0, 1.0 / 3.0);
        applyLinkModifiers(t, "heavy", "last", masses, n - 1, 3.0);
        applyLinkModifiers(t, "light", "last", masses, n - 1, 1.0 / 3.0);
        applyLinkModifiers(t, "long", "first", lengths, 0, 2.0);
        applyLinkModifiers(t, "short", "first", lengths, 0, 0.5);
        applyLinkModifiers(t, "long", "last", lengths, n - 1, 2.0);
        applyLinkModifiers(t, "short", "last", lengths, n - 1, 0.5);

        double gravity = parseGravity(t, fallback.getGravity());

        return new PendulumConfig(n, lengths, masses, angles, gravity, fallback.getSpeedMultiplier());
    }

    private static int parseLinkCount(String t, int fallbackN, int maxN) {
        Matcher m = LINK_COUNT.matcher(t);
        if (!m.find()) return fallbackN;
        try {
            int n = Integer.parseInt(m.group(1));
            return Math.max(1, Math.min(maxN, n));
        } catch (NumberFormatException ex) {
            return fallbackN;
        }
    }

    private static double parseAngle(String t, double fallbackAngle) {
        if (t.contains("horizontal")) return Math.PI / 2.0;
        if (t.contains("vertical") || t.contains("straight down") || t.contains("at rest")) return 0.0;
        if (t.contains("inverted") || t.contains("upside down") || t.contains("near vertical up")) return Math.PI - 0.05;

        Matcher m = ANGLE_DEGREES.matcher(t);
        if (m.find()) {
            try {
                double degrees = Double.parseDouble(m.group(1));
                return Math.toRadians(degrees);
            } catch (NumberFormatException ignored) { /* fall through to fallback */ }
        }
        return fallbackAngle;
    }

    private static double parseGravity(String t, double fallbackGravity) {
        if (t.contains("moon gravity") || t.contains("lunar gravity")) return GRAVITY_MOON;
        if (t.contains("mars gravity")) return GRAVITY_MARS;
        if (t.contains("jupiter gravity")) return GRAVITY_JUPITER;
        if (t.contains("earth gravity")) return GRAVITY_EARTH;
        if (t.contains("zero gravity") || t.contains("zero-g") || t.contains("no gravity")) return GRAVITY_ZERO;
        if (t.contains("low gravity") || t.contains("weak gravity")) return GRAVITY_LOW;
        if (t.contains("high gravity") || t.contains("strong gravity")) return GRAVITY_HIGH;

        Matcher m = GRAVITY_NUMERIC.matcher(t);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) { /* fall through to fallback */ }
        }
        return fallbackGravity;
    }

    /** If {@code t} contains both {@code adjective} and {@code position} near a link noun, scales {@code target[index]} by {@code factor}. */
    private static void applyLinkModifiers(String t, String adjective, String position,
                                            double[] target, int index, double factor) {
        if (index < 0 || index >= target.length) return;
        if (t.contains(adjective) && t.contains(position)) {
            target[index] *= factor;
        }
    }
}
