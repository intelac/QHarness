package io.nexum.ai;

import io.nexum.message.FixDictionary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a raw FIX message back to whoever pasted it.
 *
 * <p>A counterparty quoting a problem quotes the message, and the message is a
 * list of numbers. Turning it into names and meanings is the first thing anyone
 * does by hand, every time, with a reference open beside them.
 *
 * <p>Touches nothing: the text is examined and described, never sent.
 */
public final class ParseFixTool implements AiTool {

    /**
     * Every separator a message arrives with.
     *
     * <p>The real SOH, and the two things people substitute when pasting a
     * message somewhere that eats control characters. A tool that only
     * accepted SOH would refuse most of what is actually pasted into it.
     */
    private static final String SEPARATORS = "[|^]";

    @Override
    public String name() {
        return "parse_fix";
    }

    @Override
    public String description() {
        return "Read a raw FIX message. Give it the text a counterparty quoted —"
                + " fields separated by SOH, | or ^ — and it returns each tag with"
                + " its name and, where the value is a code, what that code means."
                + " Nothing is sent anywhere.";
    }

    @Override
    public Map<String, Parameter> parameters() {
        Map<String, Parameter> p = new LinkedHashMap<>();
        p.put("message", Parameter.required("string",
                "The raw message, e.g. 8=FIX.4.4|35=8|39=1|150=F|14=300|"));
        return p;
    }

    @Override
    public Effect effect() {
        return Effect.READ_ONLY;
    }

    @Override
    public Result call(Map<String, Object> arguments) {
        Object raw = arguments.get("message");
        if (raw == null) {
            return Result.failed("give it a message to read");
        }

        String[] fields = String.valueOf(raw).split(SEPARATORS);

        List<Object> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int unreadable = 0;

        for (String field : fields) {
            int equals = field.indexOf('=');
            if (equals <= 0) {
                if (!field.isBlank()) {
                    unreadable++;
                }
                continue;
            }

            int tag;
            try {
                tag = Integer.parseInt(field.substring(0, equals).trim());
            } catch (NumberFormatException notATag) {
                unreadable++;
                continue;
            }

            String value = field.substring(equals + 1);
            String tagName = FixDictionary.name(tag).orElse(null);
            String meaning = FixDictionary.meaning(tag, value).orElse(null);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tag", tag);
            row.put("name", tagName);
            row.put("value", value);
            row.put("meaning", meaning);
            row.put("session", FixDictionary.isSession(tag));
            rows.add(row);

            text.append(tag)
                    .append("  ")
                    // A tag with no name is still shown. One nobody recognises
                    // is exactly the one worth asking a counterparty about.
                    .append(tagName == null ? "(unknown)" : tagName)
                    .append("  ")
                    .append(value);
            if (meaning != null) {
                text.append("  — ").append(meaning);
            }
            text.append('\n');
        }

        if (rows.isEmpty()) {
            return Result.failed("nothing in that looked like tag=value");
        }

        String summary = text.toString().trim();
        if (unreadable > 0) {
            summary += "\n(" + unreadable
                    + " fragment(s) were not tag=value and were skipped)";
        }
        return Result.of(summary, Map.of("fields", rows));
    }
}
