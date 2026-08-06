package physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link NaturalLanguageSceneParser} against the fixed set of
 * patterns it actually recognizes — see its own javadoc for why this is a
 * keyword/regex heuristic, not real natural-language understanding, and
 * why that's a deliberate scope choice rather than a limitation to fix.
 */
class NaturalLanguageSceneParserTest {

    private final PendulumConfig fallback = PendulumConfig.defaultConfig();

    @Test
    void parsesLinkCount() {
        PendulumConfig result = NaturalLanguageSceneParser.parse("5 links, low gravity", fallback);
        assertEquals(5, result.getN());
    }

    @Test
    void clampsLinkCountToMaxN() {
        PendulumConfig result = NaturalLanguageSceneParser.parse("999 pendulums", fallback, 20);
        assertEquals(20, result.getN());
    }

    @Test
    void unrecognizedTextFallsBackToFallbackEntirely() {
        PendulumConfig result = NaturalLanguageSceneParser.parse("blah blah nonsense text", fallback);
        assertEquals(fallback.getN(), result.getN());
        assertEquals(fallback.getGravity(), result.getGravity(), 1e-9);
    }

    @Test
    void parsesNamedGravityPresets() {
        assertEquals(1.62, NaturalLanguageSceneParser.parse("moon gravity", fallback).getGravity(), 1e-9);
        assertEquals(3.71, NaturalLanguageSceneParser.parse("mars gravity", fallback).getGravity(), 1e-9);
        assertEquals(24.79, NaturalLanguageSceneParser.parse("jupiter gravity", fallback).getGravity(), 1e-9);
    }

    @Test
    void parsesNumericGravity() {
        PendulumConfig result = NaturalLanguageSceneParser.parse("3 links, gravity of 15", fallback);
        assertEquals(15.0, result.getGravity(), 1e-9);
    }

    @Test
    void parsesNamedAngles() {
        assertEquals(Math.PI / 2.0, NaturalLanguageSceneParser.parse("2 links, horizontal", fallback).getInitAngle(0), 1e-9);
        assertEquals(0.0, NaturalLanguageSceneParser.parse("2 links, vertical", fallback).getInitAngle(0), 1e-9);
    }

    @Test
    void parsesNumericDegreeAngle() {
        PendulumConfig result = NaturalLanguageSceneParser.parse("3 links, angle 45 degrees", fallback);
        assertEquals(Math.toRadians(45), result.getInitAngle(0), 1e-9);
        // Uniform across every link — the parser applies one angle to the whole chain.
        for (int i = 0; i < result.getN(); i++) {
            assertEquals(Math.toRadians(45), result.getInitAngle(i), 1e-9);
        }
    }

    @Test
    void heavyFirstLinkIncreasesItsMassRelativeToOthers() {
        PendulumConfig result = NaturalLanguageSceneParser.parse("3 links, heavy first link", fallback);
        assertTrue(result.getMass(0) > result.getMass(1),
                "The first link's mass should be scaled up relative to the others");
    }

    @Test
    void resultIsAlwaysAValidConfig() {
        // Whatever comes out must still satisfy PendulumConfig's own
        // validation — a malformed parse must never reach the physics engine.
        PendulumConfig result = NaturalLanguageSceneParser.parse(
                "7 links, heavy first link, light last link, gravity of 4.5, angle 30 degrees", fallback);
        PhysicsEngine engine = new PhysicsEngine(result); // throws if PendulumConfig's own constructor didn't already reject something invalid
        assertTrue(PendulumTestSupport.isFiniteState(engine.getState()));
    }
}
