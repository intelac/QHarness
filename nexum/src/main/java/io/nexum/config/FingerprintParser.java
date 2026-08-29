package io.nexum.config;

import io.nexum.core.Fingerprint;

import java.util.Map;

/**
 * Builds a {@link Fingerprint} from configuration.
 *
 * <p>Each entry is a tag and a test. A bare value means equality; an operator
 * prefix selects anything else. Keeping the operator inside the value keeps a
 * rule to one line per condition, which is how they read in a rules-of-engagement
 * document:
 *
 * <pre>
 *   fingerprint:
 *     207: L              # equals
 *     38: "&gt; 100000"       # numeric comparison
 *     40: "!= 1"          # not equal
 *     55: "like *.L"      # wildcard
 *     48: "~ [A-Z]{4}"    # regular expression
 *     15: "in USD,EUR"    # membership
 *     44: exists          # presence
 * </pre>
 *
 * <p>{@code fingerprint: any} matches everything and belongs on the last rule.
 */
public final class FingerprintParser {

    private FingerprintParser() {}

    public static Fingerprint parse(Config config, String key) {
        // "fingerprint: any" arrives as a scalar rather than a section.
        String scalar = config.string(key);
        if (scalar != null && !scalar.startsWith("{")) {
            if ("any".equalsIgnoreCase(scalar.trim())) {
                return Fingerprint.any();
            }
            throw new IllegalStateException(
                    "\"" + key + ": " + scalar + "\" is not understood; use \"any\" or a tag map");
        }

        Config section = config.section(key);
        if (section.raw().isEmpty()) {
            return Fingerprint.any();
        }

        Fingerprint.Builder builder = Fingerprint.of();
        for (Map.Entry<String, Object> entry : section.raw().entrySet()) {
            int tag = parseTag(entry.getKey());
            apply(builder, tag, String.valueOf(entry.getValue()).trim());
        }
        return builder.build();
    }

    private static void apply(Fingerprint.Builder builder, int tag, String test) {
        if (test.equalsIgnoreCase("exists")) {
            builder.exists(tag);
        } else if (test.equalsIgnoreCase("absent")) {
            builder.absent(tag);
        } else if (test.startsWith(">=")) {
            builder.gte(tag, rest(test, 2));
        } else if (test.startsWith("<=")) {
            builder.lte(tag, rest(test, 2));
        } else if (test.startsWith("!=")) {
            builder.ne(tag, rest(test, 2));
        } else if (test.startsWith(">")) {
            builder.gt(tag, rest(test, 1));
        } else if (test.startsWith("<")) {
            builder.lt(tag, rest(test, 1));
        } else if (test.startsWith("~")) {
            builder.regex(tag, rest(test, 1));
        } else if (test.startsWith("like ")) {
            builder.like(tag, rest(test, 5));
        } else if (test.startsWith("in ")) {
            builder.in(tag, splitList(rest(test, 3)));
        } else {
            builder.eq(tag, test);
        }
    }

    private static String rest(String text, int from) {
        return text.substring(from).trim();
    }

    private static String[] splitList(String text) {
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static int parseTag(String key) {
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException notATag) {
            throw new IllegalStateException(
                    "fingerprint keys are tag numbers; \"" + key + "\" is not one");
        }
    }
}
