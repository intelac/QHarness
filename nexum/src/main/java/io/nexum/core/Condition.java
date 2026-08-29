package io.nexum.core;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One test against one tag. Conditions combine into a {@link Fingerprint}.
 *
 * <p>Numeric comparisons parse both sides as {@link BigDecimal} so quantities
 * and prices compare by value rather than by string. A tag that is absent, or
 * present but not numeric, fails a numeric comparison rather than throwing —
 * routing must not blow up on a malformed inbound message.
 */
public sealed interface Condition {

    int tag();

    /** @param actual the tag's value on the message, or null when absent */
    boolean test(String actual);

    /** Human-readable form, used in config dumps and routing traces. */
    String describe();

    // ------------------------------------------------------------------

    record Equals(int tag, String value) implements Condition {
        public boolean test(String actual) {
            return value.equals(actual);
        }

        public String describe() {
            return tag + " = " + value;
        }
    }

    record NotEquals(int tag, String value) implements Condition {
        /** An absent tag counts as "not equal" — it is certainly not this value. */
        public boolean test(String actual) {
            return !value.equals(actual);
        }

        public String describe() {
            return tag + " != " + value;
        }
    }

    /** Leading and/or trailing {@code *}; the common venue-suffix case. */
    record Wildcard(int tag, String pattern) implements Condition {
        public boolean test(String actual) {
            if (actual == null) {
                return false;
            }
            boolean openStart = pattern.startsWith("*");
            boolean openEnd = pattern.endsWith("*");
            if (openStart && openEnd && pattern.length() > 1) {
                return actual.contains(pattern.substring(1, pattern.length() - 1));
            }
            if (openStart) {
                return actual.endsWith(pattern.substring(1));
            }
            if (openEnd) {
                return actual.startsWith(pattern.substring(0, pattern.length() - 1));
            }
            return pattern.equals(actual);
        }

        public String describe() {
            return tag + " like " + pattern;
        }
    }

    record Matches(int tag, Pattern pattern) implements Condition {
        public static Matches of(int tag, String regex) {
            return new Matches(tag, Pattern.compile(regex));
        }

        public boolean test(String actual) {
            return actual != null && pattern.matcher(actual).matches();
        }

        public String describe() {
            return tag + " ~ /" + pattern.pattern() + "/";
        }
    }

    record GreaterThan(int tag, BigDecimal bound, boolean orEqual) implements Condition {
        public boolean test(String actual) {
            BigDecimal parsed = number(actual);
            if (parsed == null) {
                return false;
            }
            int cmp = parsed.compareTo(bound);
            return orEqual ? cmp >= 0 : cmp > 0;
        }

        public String describe() {
            return tag + (orEqual ? " >= " : " > ") + bound;
        }
    }

    record LessThan(int tag, BigDecimal bound, boolean orEqual) implements Condition {
        public boolean test(String actual) {
            BigDecimal parsed = number(actual);
            if (parsed == null) {
                return false;
            }
            int cmp = parsed.compareTo(bound);
            return orEqual ? cmp <= 0 : cmp < 0;
        }

        public String describe() {
            return tag + (orEqual ? " <= " : " < ") + bound;
        }
    }

    record In(int tag, Set<String> values) implements Condition {
        public In {
            values = Set.copyOf(values);
        }

        public boolean test(String actual) {
            return actual != null && values.contains(actual);
        }

        public String describe() {
            return tag + " in " + new LinkedHashSet<>(values);
        }
    }

    record Exists(int tag, boolean required) implements Condition {
        public boolean test(String actual) {
            return required == (actual != null);
        }

        public String describe() {
            return tag + (required ? " exists" : " absent");
        }
    }

    /** Null when the value is missing or not a number, so comparisons just fail. */
    private static BigDecimal number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }
}
