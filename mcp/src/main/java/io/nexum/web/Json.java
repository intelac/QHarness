package io.nexum.web;

import java.util.Collection;
import java.util.Map;

/**
 * Just enough JSON to answer a browser.
 *
 * <p>Written rather than depended on, for the same reason the YAML reader was:
 * the system should not carry a library to describe its own state. Only
 * serialisation is here — nothing parses JSON, because nothing needs to.
 */
public final class Json {

    private Json() {}

    public static String write(Object value) {
        StringBuilder text = new StringBuilder();
        append(text, value);
        return text.toString();
    }

    /**
     * Parse JSON into maps, lists, strings, numbers and booleans.
     *
     * <p>Enough for JSON-RPC, which is what this exists for — the requests an
     * MCP client sends are a handful of known fields, not arbitrary documents.
     * Written here rather than pulled in with a library because this project
     * carries one dependency and adding a second for eight fields is a poor
     * trade.
     *
     * @throws IllegalArgumentException on anything malformed, naming the
     *     position — a client sending bad JSON needs to know where
     */
    public static Object read(String text) {
        Parser parser = new Parser(text);
        parser.skipSpace();
        Object value = parser.value();
        parser.skipSpace();
        if (parser.at < text.length()) {
            throw new IllegalArgumentException(
                    "trailing content at position " + parser.at);
        }
        return value;
    }

    /** A JSON object, or an empty one when the text is not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String text) {
        Object value = read(text);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static final class Parser {

        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Object value() {
            skipSpace();
            if (at >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return switch (text.charAt(at)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            expect('{');
            skipSpace();
            if (peek() == '}') {
                at++;
                return map;
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                map.put(key, value());
                skipSpace();
                char next = peek();
                at++;
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw new IllegalArgumentException(
                            "expected , or } at position " + (at - 1));
                }
            }
        }

        private java.util.List<Object> array() {
            java.util.List<Object> list = new java.util.ArrayList<>();
            expect('[');
            skipSpace();
            if (peek() == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                skipSpace();
                char next = peek();
                at++;
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw new IllegalArgumentException(
                            "expected , or ] at position " + (at - 1));
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder built = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return built.toString();
                }
                if (c != '\\') {
                    built.append(c);
                    continue;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> built.append(escaped);
                    case 'b' -> built.append('\b');
                    case 'f' -> built.append('\f');
                    case 'n' -> built.append('\n');
                    case 'r' -> built.append('\r');
                    case 't' -> built.append('\t');
                    case 'u' -> {
                        built.append((char) Integer.parseInt(
                                text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException(
                            "unknown escape \\" + escaped + " at position " + (at - 1));
                }
            }
        }

        private Object number() {
            int start = at;
            while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            String raw = text.substring(start, at);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException(
                        "unexpected character at position " + start);
            }
            // Whole numbers as Long so an id round-trips as it arrived; a JSON-RPC
            // id returned as 1.0 does not match the 1 a client sent.
            return raw.contains(".") || raw.contains("e") || raw.contains("E")
                    ? (Object) Double.parseDouble(raw)
                    : (Object) Long.parseLong(raw);
        }

        private Object bool() {
            return peek() == 't' ? literal("true", Boolean.TRUE) : literal("false", Boolean.FALSE);
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw new IllegalArgumentException(
                        "expected " + word + " at position " + at);
            }
            at += word.length();
            return value;
        }

        private char peek() {
            if (at >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return text.charAt(at);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException(
                        "expected " + c + " at position " + at);
            }
            at++;
        }

        void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }

    private static void append(StringBuilder text, Object value) {
        switch (value) {
            case null -> text.append("null");
            case String string -> quote(text, string);
            case Boolean bool -> text.append(bool);
            case Number number -> appendNumber(text, number);
            case Map<?, ?> map -> {
                text.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        text.append(',');
                    }
                    first = false;
                    quote(text, String.valueOf(entry.getKey()));
                    text.append(':');
                    append(text, entry.getValue());
                }
                text.append('}');
            }
            case Collection<?> collection -> {
                text.append('[');
                boolean first = true;
                for (Object item : collection) {
                    if (!first) {
                        text.append(',');
                    }
                    first = false;
                    append(text, item);
                }
                text.append(']');
            }
            case Enum<?> constant -> quote(text, constant.name());
            default -> quote(text, String.valueOf(value));
        }
    }

    /** JSON has no NaN or Infinity; a browser given one fails to parse the lot. */
    private static void appendNumber(StringBuilder text, Number number) {
        double asDouble = number.doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
            text.append("null");
        } else {
            text.append(number);
        }
    }

    private static void quote(StringBuilder text, String value) {
        text.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    // FIX messages carry SOH and other control characters; a raw
                    // one in a JSON string is invalid and breaks the parse.
                    if (c < 0x20) {
                        text.append(String.format("\\u%04x", (int) c));
                    } else {
                        text.append(c);
                    }
                }
            }
        }
        text.append('"');
    }
}
