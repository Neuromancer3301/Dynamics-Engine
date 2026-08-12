package physics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal recursive-descent JSON parser, scoped to what {@link
 * PendulumConfigIO} needs: objects, arrays, and numbers. Not a general
 * JSON library — there's no reason to take on that surface, or a
 * dependency, for five fixed fields — but a real parser rather than
 * format-specific string-splitting, so a malformed or adversarial file
 * fails with a clear error instead of silently misreading.
 */
final class MiniJson {

    // The full text being parsed, and a cursor into it. A recursive-descent
    // parser is essentially a cursor plus one method per grammar rule, each
    // of which consumes the characters it recognises and advances `pos`.
    private final String s;
    private int pos;

    MiniJson(String s) {
        this.s = s;
        this.pos = 0;
    }

    /**
     * Parses a JSON object: <code>{ "key": value, "key": value }</code>.
     * The entry point — a scenario file is always one object at the top level.
     *
     * <p>Reads the opening brace, then loops: key, colon, value, then either
     * a comma (continue) or a closing brace (done). Anything else is a
     * syntax error reported with its character position.
     */
    Map<String, Object> parseObject() {
        skipWs();
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { pos++; return result; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            Object value = parseValue();
            result.put(key, value);
            skipWs();
            char c = next();
            if (c == '}') break;
            if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at position " + (pos - 1));
        }
        return result;
    }

    /**
     * Parses any JSON value by dispatching on its first character — the
     * core of recursive descent. Because objects and arrays can contain
     * values, and this method parses values, the recursion handles
     * arbitrary nesting for free.
     */
    private Object parseValue() {
        skipWs();
        char c = peek();
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBoolean();
        if (c == 'n') { expectLiteral("null"); return null; }
        return parseNumber();
    }

    /** Parses <code>[a, b, c]</code>. Same loop shape as {@link #parseObject}, without keys. Used for the lengths/masses/angles arrays. */
    private List<Object> parseArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            list.add(parseValue());
            skipWs();
            char c = next();
            if (c == ']') break;
            if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at position " + (pos - 1));
        }
        return list;
    }

    /**
     * Parses a double-quoted string, decoding backslash escapes. An
     * unrecognised escape is rejected outright rather than silently passed
     * through — for a five-field config format, being strict is safer than
     * being permissive.
     */
    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') break;
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 't'  -> sb.append('\t');
                    case 'r'  -> sb.append('\r');
                    default   -> throw new IllegalArgumentException("Unsupported escape: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Parses a number. Scans forward over everything that could legally
     * appear in one (digits, decimal point, exponent, signs), then hands the
     * substring to {@link Double#parseDouble}. Every JSON number becomes a
     * {@code Double}; integer fields like {@code n} are narrowed later by
     * the caller.
     */
    private Double parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
            else break;
        }
        String numStr = s.substring(start, pos);
        try {
            return Double.parseDouble(numStr);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number '" + numStr + "' at position " + start);
        }
    }

    /** Parses {@code true}/{@code false}. Unused by the current schema but included so the parser handles valid JSON rather than a subset. */
    private Boolean parseBoolean() {
        if (peek() == 't') { expectLiteral("true"); return Boolean.TRUE; }
        expectLiteral("false");
        return Boolean.FALSE;
    }

    /** Consumes an exact keyword ({@code true}/{@code false}/{@code null}) or throws with its position. */
    private void expectLiteral(String literal) {
        if (pos + literal.length() > s.length() || !s.startsWith(literal, pos))
            throw new IllegalArgumentException("Expected '" + literal + "' at position " + pos);
        pos += literal.length();
    }

    /** Consumes one required punctuation character, skipping whitespace first. Throws a positional error if it isn't there — this is what turns a malformed file into a clear message instead of silent misreading. */
    private void expect(char c) {
        skipWs();
        char actual = next();
        if (actual != c)
            throw new IllegalArgumentException("Expected '" + c + "' but found '" + actual + "' at position " + (pos - 1));
    }

    /** Returns the character at the cursor AND advances past it. Throws on end-of-input, which is how a truncated file is caught. */
    private char next() {
        if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of input");
        return s.charAt(pos++);
    }

    /** Returns the character at the cursor WITHOUT advancing — lets {@link #parseValue} decide what to parse before committing to it. */
    private char peek() {
        if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of input");
        return s.charAt(pos);
    }

    /** Advances past spaces, tabs, and newlines, which JSON allows anywhere between tokens. */
    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }
}
