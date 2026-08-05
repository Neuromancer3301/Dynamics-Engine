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

    private final String s;
    private int pos;

    MiniJson(String s) {
        this.s = s;
        this.pos = 0;
    }

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

    private Boolean parseBoolean() {
        if (peek() == 't') { expectLiteral("true"); return Boolean.TRUE; }
        expectLiteral("false");
        return Boolean.FALSE;
    }

    private void expectLiteral(String literal) {
        if (pos + literal.length() > s.length() || !s.startsWith(literal, pos))
            throw new IllegalArgumentException("Expected '" + literal + "' at position " + pos);
        pos += literal.length();
    }

    private void expect(char c) {
        skipWs();
        char actual = next();
        if (actual != c)
            throw new IllegalArgumentException("Expected '" + c + "' but found '" + actual + "' at position " + (pos - 1));
    }

    private char next() {
        if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of input");
        return s.charAt(pos++);
    }

    private char peek() {
        if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of input");
        return s.charAt(pos);
    }

    private void skipWs() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }
}
