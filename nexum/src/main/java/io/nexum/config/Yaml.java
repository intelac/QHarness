package io.nexum.config;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A YAML reader covering what configuration needs: nested maps, lists of maps,
 * scalars, comments.
 *
 * <p>Written rather than pulled in so the system carries no dependency for
 * reading its own configuration. Anchors, multi-line scalars, flow syntax and
 * tags are not supported — a configuration that needs them is describing
 * behaviour that belongs in a plugin.
 */
public final class Yaml {

    private Yaml() {}

    public static Config parse(InputStream input) {
        List<Line> lines = read(input);
        Cursor cursor = new Cursor(lines);
        Object parsed = parseBlock(cursor, 0);
        return new Config(parsed instanceof Map<?, ?> map ? castMap(map) : Map.of());
    }

    public static Config parse(String text) {
        return parse(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private record Line(int indent, String content, int number) {}

    private static final class Cursor {
        private final List<Line> lines;
        private int position;

        Cursor(List<Line> lines) {
            this.lines = lines;
        }

        Line peek() {
            return position < lines.size() ? lines.get(position) : null;
        }

        Line next() {
            return lines.get(position++);
        }

        boolean done() {
            return position >= lines.size();
        }
    }

    private static Object parseBlock(Cursor cursor, int indent) {
        Line first = cursor.peek();
        if (first == null || first.indent() < indent) {
            return Map.of();
        }
        return first.content().startsWith("- ") || first.content().equals("-")
                ? parseList(cursor, indent)
                : parseMap(cursor, indent);
    }

    private static Map<String, Object> parseMap(Cursor cursor, int indent) {
        Map<String, Object> result = new LinkedHashMap<>();
        while (!cursor.done()) {
            Line line = cursor.peek();
            if (line.indent() < indent || line.content().startsWith("-")) {
                break;
            }
            cursor.next();

            int colon = colonAt(line.content());
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "line " + line.number() + ": expected \"key: value\" but found \""
                                + line.content() + "\"");
            }
            String key = line.content().substring(0, colon).trim();
            String inline = line.content().substring(colon + 1).trim();

            if (!inline.isEmpty()) {
                result.put(key, scalar(inline));
                continue;
            }
            Line child = cursor.peek();
            if (child == null) {
                result.put(key, Map.of());
                continue;
            }
            // A block sequence may sit at the parent key's own indentation —
            // the common YAML style, and the one every editor produces:
            //
            //   sessions:
            //   - id: A->B
            //
            // Requiring deeper indentation parsed that as an empty map, and the
            // failure surfaced later as "no sessions configured", naming the
            // wrong cause.
            boolean nested = child.indent() > line.indent();
            boolean siblingList = child.indent() == line.indent()
                    && child.content().startsWith("-");
            result.put(key, nested || siblingList
                    ? parseBlock(cursor, child.indent())
                    : Map.of());
        }
        return result;
    }

    private static List<Object> parseList(Cursor cursor, int indent) {
        List<Object> result = new ArrayList<>();
        while (!cursor.done()) {
            Line line = cursor.peek();
            if (line.indent() < indent || !line.content().startsWith("-")) {
                break;
            }
            cursor.next();

            String rest = line.content().length() > 1 ? line.content().substring(2).trim() : "";
            if (rest.isEmpty()) {
                Line child = cursor.peek();
                result.add(child != null && child.indent() > line.indent()
                        ? parseBlock(cursor, child.indent())
                        : Map.of());
                continue;
            }

            int colon = colonAt(rest);
            if (colon < 0) {
                result.add(scalar(rest));
                continue;
            }
            // "- key: value" opens a map whose remaining keys line up with
            // where that first key started. Derived from the text rather than
            // assumed to be two spaces past the dash: any other spacing after
            // the dash would push continuation keys out of the list item and
            // into the enclosing map.
            Map<String, Object> entry = new LinkedHashMap<>();
            String key = rest.substring(0, colon).trim();
            String inline = rest.substring(colon + 1).trim();
            int entryIndent = line.indent() + (line.content().length() - rest.length());

            if (inline.isEmpty()) {
                Line child = cursor.peek();
                entry.put(key, child != null && child.indent() > entryIndent
                        ? parseBlock(cursor, child.indent())
                        : Map.of());
            } else {
                entry.put(key, scalar(inline));
            }
            while (!cursor.done()) {
                Line following = cursor.peek();
                if (following.indent() != entryIndent || following.content().startsWith("-")) {
                    break;
                }
                Map<String, Object> more = parseMap(cursor, entryIndent);
                entry.putAll(more);
            }
            result.add(entry);
        }
        return result;
    }

    /** First colon that separates a key from a value, ignoring quoted text. */
    private static int colonAt(String text) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == quote) {
                    quoted = false;
                }
            } else if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
            } else if (c == ':' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static Object scalar(String raw) {
        String text = raw.trim();
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                        || (text.startsWith("'") && text.endsWith("'")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static List<Line> read(InputStream input) {
        List<Line> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String raw;
            int number = 0;
            while ((raw = reader.readLine()) != null) {
                number++;
                String withoutComment = stripComment(raw);
                if (withoutComment.isBlank()) {
                    continue;
                }
                int indent = 0;
                while (indent < withoutComment.length() && withoutComment.charAt(indent) == ' ') {
                    indent++;
                }
                lines.add(new Line(indent, withoutComment.trim(), number));
            }
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot read configuration", failure);
        }
        return lines;
    }

    /** Drops trailing comments while leaving a {@code #} inside quotes alone. */
    private static String stripComment(String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == quote) {
                    quoted = false;
                }
            } else if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
            } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
