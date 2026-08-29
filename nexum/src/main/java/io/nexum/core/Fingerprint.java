package io.nexum.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A content test used to route a message: session to client, and client to
 * destination. Identity is not carried by the transport — it is recognised from
 * the fields on the message itself.
 *
 * <p>Conditions across tags are ANDed. {@link #or} builds an alternative when a
 * rule genuinely needs one; keeping OR at the top level rather than nesting
 * arbitrary boolean trees keeps rules readable in configuration and keeps a
 * failed match explainable.
 *
 * <p>Rules are evaluated in declaration order and the first match wins, so
 * specific rules belong above general ones.
 *
 * <pre>{@code
 * // large London orders go to the dark pool
 * Fingerprint.of()
 *     .eq(207, "L")
 *     .gt(38, "100000")
 *     .ne(40, "1")            // not a market order
 *     .build();
 * }</pre>
 */
public final class Fingerprint {

    /** Alternatives; the fingerprint matches when any one of them matches. */
    private final List<List<Condition>> alternatives;

    private Fingerprint(List<List<Condition>> alternatives) {
        this.alternatives = alternatives;
    }

    public static Builder of() {
        return new Builder();
    }

    /** Matches every message. Use as the final catch-all rule. */
    public static Fingerprint any() {
        return new Fingerprint(List.of(List.of()));
    }

    // ------------------------------------------------------------------

    public static final class Builder {
        private final List<List<Condition>> alternatives = new ArrayList<>();
        private List<Condition> current = new ArrayList<>();

        private Builder add(Condition condition) {
            current.add(condition);
            return this;
        }

        public Builder eq(int tag, String value) {
            return add(new Condition.Equals(tag, value));
        }

        public Builder ne(int tag, String value) {
            return add(new Condition.NotEquals(tag, value));
        }

        /** Leading and/or trailing {@code *}. */
        public Builder like(int tag, String pattern) {
            return add(new Condition.Wildcard(tag, pattern));
        }

        /** Full regular expression, anchored — the whole value must match. */
        public Builder regex(int tag, String regex) {
            return add(Condition.Matches.of(tag, regex));
        }

        public Builder gt(int tag, String bound) {
            return add(new Condition.GreaterThan(tag, new BigDecimal(bound), false));
        }

        public Builder gte(int tag, String bound) {
            return add(new Condition.GreaterThan(tag, new BigDecimal(bound), true));
        }

        public Builder lt(int tag, String bound) {
            return add(new Condition.LessThan(tag, new BigDecimal(bound), false));
        }

        public Builder lte(int tag, String bound) {
            return add(new Condition.LessThan(tag, new BigDecimal(bound), true));
        }

        public Builder in(int tag, String... values) {
            return add(new Condition.In(tag, Set.of(values)));
        }

        public Builder exists(int tag) {
            return add(new Condition.Exists(tag, true));
        }

        public Builder absent(int tag) {
            return add(new Condition.Exists(tag, false));
        }

        /** Start a fresh alternative; the rule matches if any alternative does. */
        public Builder or() {
            alternatives.add(List.copyOf(current));
            current = new ArrayList<>();
            return this;
        }

        public Fingerprint build() {
            List<List<Condition>> all = new ArrayList<>(alternatives);
            all.add(List.copyOf(current));
            return new Fingerprint(List.copyOf(all));
        }
    }

    // ------------------------------------------------------------------

    /**
     * @param fields tag to value view of the message under test
     */
    public boolean matches(Map<Integer, String> fields) {
        for (List<Condition> alternative : alternatives) {
            if (allHold(alternative, fields)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allHold(List<Condition> conditions, Map<Integer, String> fields) {
        for (Condition condition : conditions) {
            if (!condition.test(fields.get(condition.tag()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The first condition that failed, for every alternative.
     *
     * <p>"Why did this order not route where I expected" is a routine question;
     * answering it from the rule itself beats reconstructing it from a log.
     */
    public List<String> explainFailure(Map<Integer, String> fields) {
        List<String> reasons = new ArrayList<>();
        for (List<Condition> alternative : alternatives) {
            for (Condition condition : alternative) {
                String actual = fields.get(condition.tag());
                if (!condition.test(actual)) {
                    reasons.add(condition.describe() + " (actual: "
                            + (actual == null ? "absent" : actual) + ")");
                    break;
                }
            }
        }
        return reasons;
    }

    @Override
    public String toString() {
        if (alternatives.size() == 1 && alternatives.get(0).isEmpty()) {
            return "any";
        }
        return alternatives.stream()
                .map(alternative -> alternative.stream()
                        .map(Condition::describe)
                        .collect(Collectors.joining(" AND ")))
                .collect(Collectors.joining(" OR "));
    }
}
