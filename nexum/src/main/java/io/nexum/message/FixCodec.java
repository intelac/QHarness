package io.nexum.message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns wire bytes into a {@link FixMessage} and back, using a {@link Dialect}
 * to recognise repeating groups.
 *
 * <p>The dialect is not optional. A tag sequence carries no structure of its
 * own: {@code 375=BRK1|437=100|375=BRK2} is two entries or one malformed entry
 * depending entirely on what the counterparty declared. Parsing without the
 * template silently flattens groups and loses entry boundaries.
 */
public final class FixCodec {

    public static final char SOH = '';

    private final Dialect dialect;

    public FixCodec(Dialect dialect) {
        this.dialect = dialect;
    }

    public Dialect dialect() {
        return dialect;
    }

    private volatile List<String> lastWarnings = List.of();

    /**
     * Structural complaints from the most recent parse — a group the dialect
     * could not read to its declared length, and so on. Empty when the message
     * matched the dialect exactly.
     */
    public List<String> lastWarnings() {
        return lastWarnings;
    }

    // ------------------------------------------------------------------
    // Parse
    // ------------------------------------------------------------------

    /** Accepts SOH, {@code |} or {@code ^} as the separator, as log files vary. */
    public FixMessage parse(String wire) {
        List<String[]> pairs = split(wire);

        String msgType = null;
        for (String[] pair : pairs) {
            if ("35".equals(pair[0])) {
                msgType = pair[1];
                break;
            }
        }
        if (msgType == null) {
            throw new IllegalArgumentException("message has no MsgType(35)");
        }

        Map<Integer, GroupTemplate> templates = dialect.groupsFor(msgType);
        Map<Integer, String> fields = new LinkedHashMap<>();
        Map<Integer, List<FixMessage.Group>> groups = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        int index = 0;
        while (index < pairs.size()) {
            String[] pair = pairs.get(index);
            int tag = Integer.parseInt(pair[0]);
            GroupTemplate template = templates.get(tag);

            if (template == null) {
                // MsgType is carried by the message itself, not as an ordinary
                // field, so encoding cannot emit it twice.
                if (tag != 35) {
                    fields.put(tag, pair[1]);
                }
                index++;
                continue;
            }

            // A group counter: consume the declared number of entries.
            int declared = parseCount(pair[1], tag);
            Parsed parsed = parseGroup(pairs, index + 1, template, declared);
            groups.put(tag, parsed.entries());
            if (parsed.shortOf() >= 0 || parsed.overRan()) {
                warnings.add(describeMismatch(tag, parsed, dialect.name()));
            }
            warnings.addAll(nestedProblems);
            nestedProblems.clear();
            index = parsed.nextIndex();
        }

        FixMessage message = FixMessage.of(msgType, fields);
        for (Map.Entry<Integer, List<FixMessage.Group>> entry : groups.entrySet()) {
            message = message.setGroup(entry.getKey(), entry.getValue());
        }
        lastWarnings = List.copyOf(warnings);
        return message;
    }

    /**
     * @param shortOf the declared count when fewer entries were found, else -1.
     *     A short group is the ordinary sign of an undeclared dialect deviation.
     * @param overRan true when more entries were present than declared, which
     *     means the counter and the body disagree
     */
    private record Parsed(
            List<FixMessage.Group> entries, int nextIndex, int shortOf, boolean overRan) {}

    /** Advance past every tag the group owns, so nothing after it is misfiled. */
    private static int skipToEndOfGroup(
            List<String[]> pairs, int from, GroupTemplate template) {

        int index = from;
        while (index < pairs.size()) {
            int tag = Integer.parseInt(pairs.get(index)[0]);
            if (tag != template.delimiterTag() && !template.owns(tag)) {
                break;
            }
            index++;
        }
        return index;
    }

    /**
     * Read {@code declared} entries starting at {@code from}. A new entry begins
     * at each delimiter tag; the group ends at the first tag it does not own, or
     * once the declared count is reached.
     */
    private Parsed parseGroup(
            List<String[]> pairs, int from, GroupTemplate template, int declared) {
        String dialectName = dialectName();

        List<FixMessage.Group> entries = new ArrayList<>();
        Map<Integer, String> current = null;
        Map<Integer, List<FixMessage.Group>> currentNested = new LinkedHashMap<>();

        int index = from;
        while (index < pairs.size() && entries.size() < declared) {
            int tag = Integer.parseInt(pairs.get(index)[0]);
            String value = pairs.get(index)[1];

            if (tag == template.delimiterTag()) {
                if (current != null) {
                    entries.add(new FixMessage.Group(current, currentNested));
                    currentNested = new LinkedHashMap<>();
                }
                current = new LinkedHashMap<>();
                current.put(tag, value);
                index++;
                continue;
            }

            if (current == null || !template.owns(tag)) {
                break; // outside the group
            }

            GroupTemplate child = template.nested().get(tag);
            if (child != null) {
                Parsed inner = parseGroup(pairs, index + 1, child, parseCount(value, tag));
                currentNested.put(tag, inner.entries());
                // Carried up rather than discarded: the same corruption inside a
                // nested group was completely silent, leaving a caller with
                // no warnings while fields leaked into the message body.
                if (inner.shortOf() >= 0 || inner.overRan()) {
                    nestedProblems.add(describeMismatch(
                            tag, inner, dialectName));
                }
                index = inner.nextIndex();
                continue;
            }

            current.put(tag, value);
            index++;
        }

        if (current != null) {
            entries.add(new FixMessage.Group(current, currentNested));
        }
        // An over-run leaves a partial entry buffered and the cursor mid-entry,
        // so the fields after it would be filed as top-level — a fill's
        // ContraTradeQty migrating out of its group into the message body, and
        // re-encoding producing a structurally different message. The entries
        // are trimmed to what was declared and the cursor advanced past the
        // rest, so nothing leaks upward.
        int overRun = entries.size() - declared;
        if (overRun > 0) {
            entries = new ArrayList<>(entries.subList(0, declared));
            index = skipToEndOfGroup(pairs, from, template);
        }
        int mismatch = entries.size() != declared ? declared : -1;
        return new Parsed(entries, index, mismatch, overRun > 0);
    }

    /** Collected while parsing nested groups, drained onto the parse's warnings. */
    private final List<String> nestedProblems = new ArrayList<>();

    private String dialectName() {
        return dialect.name();
    }

    /**
     * Say which way a group disagreed with its counter.
     *
     * <p>Reported the right way round: an over-run and a short read need
     * different investigation, and wording one as the other sends whoever picks
     * it up looking in the wrong place.
     */
    private static String describeMismatch(int tag, Parsed parsed, String dialectName) {
        int found = parsed.entries().size();
        if (parsed.overRan()) {
            return "group " + tag + " carried more entries than its counter declared;"
                    + " dialect \"" + dialectName + "\" trimmed it to " + found
                    + " and skipped the rest";
        }
        return "group " + tag + " declared " + parsed.shortOf()
                + " entries, dialect \"" + dialectName + "\" could read " + found
                + " — the wire carries a tag this dialect does not declare";
    }

    private static int parseCount(String value, int tag) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notNumeric) {
            throw new IllegalArgumentException(
                    "group counter " + tag + " is not a number: " + value);
        }
    }

    private static List<String[]> split(String wire) {
        List<String[]> pairs = new ArrayList<>();
        for (String token : wire.split("[" + SOH + "|^]")) {
            if (token.isEmpty()) {
                continue;
            }
            int equals = token.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            pairs.add(new String[] {token.substring(0, equals), token.substring(equals + 1)});
        }
        return pairs;
    }

    // ------------------------------------------------------------------
    // Encode
    // ------------------------------------------------------------------

    /**
     * Render back to the wire. Group counters are derived from the entry count,
     * never taken from a field, so a rewritten group cannot disagree with its
     * own counter.
     */
    public String encode(FixMessage message, char separator) {
        StringBuilder text = new StringBuilder();
        append(text, 35, message.msgType(), separator);
        message.storedFields().forEach((tag, value) -> append(text, tag, value, separator));

        Map<Integer, GroupTemplate> templates = dialect.groupsFor(message.msgType());
        message.allGroups().forEach((counter, entries) -> {
            append(text, counter, String.valueOf(entries.size()), separator);
            GroupTemplate template = templates.get(counter);
            for (FixMessage.Group entry : entries) {
                encodeEntry(text, entry, template, separator);
            }
        });
        return text.toString();
    }

    public String encode(FixMessage message) {
        return encode(message, SOH);
    }

    private void encodeEntry(
            StringBuilder text, FixMessage.Group entry, GroupTemplate template, char separator) {

        // Delimiter first — a group entry is only recognisable if it leads with it.
        if (template != null) {
            String delimiter = entry.get(template.delimiterTag());
            if (delimiter != null) {
                append(text, template.delimiterTag(), delimiter, separator);
            }
        }
        entry.fields().forEach((tag, value) -> {
            if (template == null || tag != template.delimiterTag()) {
                append(text, tag, value, separator);
            }
        });
        entry.nested().forEach((counter, nestedEntries) -> {
            append(text, counter, String.valueOf(nestedEntries.size()), separator);
            GroupTemplate child = template == null ? null : template.nested().get(counter);
            for (FixMessage.Group nested : nestedEntries) {
                encodeEntry(text, nested, child, separator);
            }
        });
    }

    private static void append(StringBuilder text, int tag, String value, char separator) {
        text.append(tag).append('=').append(value).append(separator);
    }
}
