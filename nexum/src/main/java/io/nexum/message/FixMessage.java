package io.nexum.message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A message in flight: top-level fields plus any repeating groups, immutable.
 *
 * <p>Every mutation returns a new instance. With several gates rewriting a
 * message across four layers, a shared mutable object makes "who changed this"
 * unanswerable; returning new values lets the audit gate diff before against
 * after for free.
 *
 * <p>{@link #flatFields()} is the flat view routing matches against. Group
 * contents are deliberately excluded from it — routing decisions are made on
 * top-level fields only, which keeps a rule's meaning unambiguous and its
 * failure explainable.
 */
public final class FixMessage {

    private final String msgType;
    private final Map<Integer, String> fields;
    private final Map<Integer, List<Group>> groups;

    private FixMessage(String msgType, Map<Integer, String> fields, Map<Integer, List<Group>> groups) {
        this.msgType = msgType;
        this.fields = fields;
        this.groups = groups;
    }

    public static FixMessage of(String msgType) {
        return new FixMessage(msgType, Map.of(), Map.of());
    }

    public static FixMessage of(String msgType, Map<Integer, String> fields) {
        return new FixMessage(msgType, Map.copyOf(fields), Map.of());
    }

    public String msgType() {
        return msgType;
    }

    public String get(int tag) {
        return fields.get(tag);
    }

    public boolean has(int tag) {
        return fields.containsKey(tag);
    }

    /**
     * Top-level fields, plus MsgType under tag 35. This is the view
     * {@code Fingerprint} tests against — routing on message type is among the
     * most common rules, so 35 has to be reachable here even though the message
     * carries it separately.
     */
    public Map<Integer, String> flatFields() {
        Map<Integer, String> view = new LinkedHashMap<>();
        view.put(35, msgType);
        view.putAll(fields);
        return Map.copyOf(view);
    }

    /** Entries of one repeating group, empty when the group is absent. */
    public List<Group> groups(int counterTag) {
        return groups.getOrDefault(counterTag, List.of());
    }

    public Map<Integer, List<Group>> allGroups() {
        return groups;
    }

    /**
     * Fields as stored, without the synthetic MsgType entry. Used by the codec
     * so encoding emits tag 35 exactly once.
     */
    public Map<Integer, String> storedFields() {
        return fields;
    }

    // ------------------------------------------------------------------
    // Mutation — each returns a new message
    // ------------------------------------------------------------------

    public FixMessage set(int tag, String value) {
        Map<Integer, String> merged = new LinkedHashMap<>(fields);
        merged.put(tag, value);
        return new FixMessage(msgType, Map.copyOf(merged), groups);
    }

    public FixMessage remove(int tag) {
        if (!fields.containsKey(tag)) {
            return this;
        }
        Map<Integer, String> reduced = new LinkedHashMap<>(fields);
        reduced.remove(tag);
        return new FixMessage(msgType, Map.copyOf(reduced), groups);
    }

    /** Replace a whole group. The counter field is derived, never set by hand. */
    public FixMessage setGroup(int counterTag, List<Group> entries) {
        Map<Integer, List<Group>> merged = new LinkedHashMap<>(groups);
        if (entries.isEmpty()) {
            merged.remove(counterTag);
        } else {
            merged.put(counterTag, List.copyOf(entries));
        }
        return new FixMessage(msgType, fields, Map.copyOf(merged));
    }

    public FixMessage addGroupEntry(int counterTag, Group entry) {
        List<Group> entries = new ArrayList<>(groups(counterTag));
        entries.add(entry);
        return setGroup(counterTag, entries);
    }

    // ------------------------------------------------------------------

    /**
     * One entry of a repeating group; may itself contain nested groups.
     *
     * <p>Field order is preserved. A group entry is only recognisable on the
     * wire by leading with its delimiter tag, and {@code Map.copyOf} does not
     * keep insertion order — encoding from one would pick whatever tag the hash
     * layout happened to put first, and a counterparty would read the entries
     * apart at the wrong boundaries.
     */
    public record Group(Map<Integer, String> fields, Map<Integer, List<Group>> nested) {

        public Group {
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            nested = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nested));
        }

        /**
         * The tag this entry leads with, which is the group's delimiter.
         *
         * @throws IllegalStateException on an empty entry, which cannot be
         *     encoded because nothing would mark where it starts
         */
        public int delimiterTag() {
            if (fields.isEmpty()) {
                throw new IllegalStateException(
                        "a group entry with no fields has no delimiter and cannot be encoded");
            }
            return fields.keySet().iterator().next();
        }

        public static Group of(Map<Integer, String> fields) {
            return new Group(fields, Map.of());
        }

        public String get(int tag) {
            return fields.get(tag);
        }

        public List<Group> nested(int counterTag) {
            return nested.getOrDefault(counterTag, List.of());
        }

        public Group set(int tag, String value) {
            Map<Integer, String> merged = new LinkedHashMap<>(fields);
            merged.put(tag, value);
            return new Group(merged, nested);
        }
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("35=").append(msgType).append('|');
        fields.forEach((tag, value) -> text.append(tag).append('=').append(value).append('|'));
        groups.forEach((counter, entries) -> {
            text.append(counter).append('=').append(entries.size()).append('|');
            for (Group entry : entries) {
                entry.fields().forEach(
                        (tag, value) -> text.append(tag).append('=').append(value).append('|'));
            }
        });
        return text.toString();
    }
}
