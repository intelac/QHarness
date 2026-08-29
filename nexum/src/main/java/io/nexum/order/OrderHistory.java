package io.nexum.order;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What happened to one order, read back out of the journal.
 *
 * <p>Replay rebuilds state and discards the steps; answering "why is this order
 * in that state" needs the steps themselves. Both read the same segments, but
 * this one keeps the sequence and the wire references, which is what turns a
 * status into an explanation.
 *
 * <p>Scans rather than indexes. An order's history is asked for one order at a
 * time, by someone looking at a screen, and a scan over the segments that could
 * hold it costs milliseconds — an index would cost a write on every event to
 * save that.
 */
public final class OrderHistory {

    private static final char SEPARATOR = '\t';

    private final JournalSegments segments;

    public OrderHistory(Path directory) {
        this.segments = new JournalSegments(directory);
    }

    /**
     * One recorded step in an order's life.
     *
     * @param wire where the message that caused it sits in a session log, or
     *     null for steps this system took on its own
     */
    public record Entry(
            long at,
            String type,
            Map<String, String> fields,
            OrderEvent.WireRef wire) {

        public String field(String key) {
            return fields.get(key);
        }

        /**
         * One line, always the same shape: the event, then the state it left
         * the order in.
         *
         * <pre>
         *   ACCEPTED_FROM_CLIENT   →  PENDING_NEW
         *   ACK                    →  NEW
         *   PARTIAL_FILL  300      →  PARTIALLY_FILLED
         *   FILL  900              →  FILLED
         *   CANCEL_PENDING         →  PENDING_CANCEL
         *   CANCELLED              →  CANCELED
         * </pre>
         *
         * <p>Both values are already on the event — the state machine decided
         * them and the journal recorded them. They are shown as they are rather
         * than translated into prose: a phrase book has to be kept in step with
         * the state machine, and the entry it is missing is the one that reads
         * as nonsense on the day something unusual happens.
         *
         * <p>Rows that changed no state carry no arrow, which is how they are
         * told apart at a glance.
         */
        public String summary() {
            String state = stateLabel();
            String event = eventName();
            String quantity = quantity();

            String left = quantity.isEmpty() ? event : event + "  " + quantity;
            return state == null ? left : left + "  \u2192  " + state;
        }

        /**
         * True when this entry moved the order from one state to another.
         *
         * <p>The journal records more than transitions — the venue's own id
         * arriving, a request being answered — because recovery needs them.
         * A history read by a person does not: those rows sit between the
         * transitions and break the sequence they are trying to follow.
         *
         * <p>A request being answered is the clearest case. The report that
         * answered it moved the state a moment earlier, so the answer is the
         * same event told twice.
         */
        public boolean isTransition() {
            return switch (type) {
                case "venue-id", "request-answered" -> false;
                // A refusal changes nothing, which is exactly why it has to be
                // shown: a history that lists only what moved the order shows
                // the client's request arriving and nothing following, and that
                // is the shape of a message that was dropped.
                case "request-refused" -> true;
                default -> stateOrImplied() != null;
            };
        }

        private String stateOrImplied() {
            String state = fields.get("state");
            return state != null ? state : impliedState();
        }

        /** The state this entry left the order in, as someone reads it. */
        private String stateLabel() {
            String state = stateOrImplied();
            if (state == null) {
                return null;
            }
            try {
                return OrderState.valueOf(state).label();
            } catch (IllegalArgumentException unknown) {
                // A journal written by a newer version; shown as recorded
                // rather than dropped.
                return state;
            }
        }

        /**
         * Where an entry leaves the order when the journal did not record it.
         *
         * <p>These are the steps this system took itself, before any venue
         * answer: creating an order and sending a request. The state machine
         * has no other move to make at those points, so the state was never a
         * decision worth recording — but a row without one reads as a gap in
         * the sequence.
         */
        private String impliedState() {
            return switch (type) {
                case "created" -> OrderState.PENDING_NEW.name();
                case "request" -> "CANCEL".equals(fields.get("kind"))
                        ? OrderState.PENDING_CANCEL.name()
                        : OrderState.PENDING_REPLACE.name();
                default -> null;
            };
        }

        /**
         * The event alone, for a display that colours the two halves apart.
         *
         * <p>Seven event names are also state names. Reading them in one string
         * leaves it to the reader to work out where the event stops — which is
         * exactly the work a display should be doing.
         */
        public String event() {
            String event = eventName();
            String quantity = quantity();
            return quantity.isEmpty() ? event : event + "  " + quantity;
        }

        /** The state alone, or null when this entry changed none. */
        public String state() {
            return stateLabel();
        }

        /** The event, named the way FIX names it. */
        private String eventName() {
            OrderEventType event = eventType();
            if (event != null) {
                return event.label();
            }

            String cause = fields.get("cause");
            if (cause != null) {
                // A journal written by a newer version; shown as recorded.
                return cause;
            }

            return switch (type) {
                case "request-answered" -> "true".equals(fields.get("accepted"))
                        ? "request accepted"
                        : "request refused";
                // Named for what was refused, because that is what the reader
                // is looking for: a client asking why its cancel went
                // unanswered is scanning for the word cancel.
                case "request-refused" -> "replace".equals(fields.get("kind"))
                        ? "replace refused: " + reasonOrBlank()
                        : "cancel refused: " + reasonOrBlank();
                case "venue-id" -> "venue id " + fields.get("venueOrderId");
                default -> type;
            };
        }

        private String reasonOrBlank() {
            String why = fields.get("why");
            return why == null ? "no reason recorded" : why;
        }

        /**
         * The event this entry recorded.
         *
         * <p>A report carries its own; the steps this system takes before any
         * venue answer do not, because the journal's type already says which
         * they were.
         */
        private OrderEventType eventType() {
            String cause = fields.get("cause");
            if (cause != null) {
                try {
                    return OrderEventType.valueOf(cause);
                } catch (IllegalArgumentException unknown) {
                    return null;
                }
            }

            return switch (type) {
                case "created" -> OrderEventType.ACCEPTED_FROM_CLIENT;
                case "request" -> "CANCEL".equals(fields.get("kind"))
                        ? OrderEventType.CANCEL_PENDING
                        : OrderEventType.REPLACE_PENDING;
                default -> null;
            };
        }

        /**
         * What traded, when anything did.
         *
         * <p>An acknowledgement and a cancel confirmation both carry a quantity
         * of zero, and printing it puts a "0" beside every event that never
         * traded — noise in the column where a reader is looking for fills.
         */
        private String quantity() {
            String last = fields.get("lastQty");
            String shown = last != null ? last : fields.get("cumQty");
            if (shown == null) {
                return "";
            }
            String trimmed = trim(shown);
            return "0".equals(trimmed) ? "" : trimmed;
        }

        /** 300 rather than 300.0 — a column of decimals is noise. */
        private static String trim(String number) {
            try {
                return new java.math.BigDecimal(number)
                        .stripTrailingZeros().toPlainString();
            } catch (NumberFormatException notANumber) {
                return number;
            }
        }
    }

    /**
     * The most recent recorded steps for one order, oldest first within the
     * page.
     *
     * <p>Every segment is read before trimming. Stopping early would return the
     * <em>oldest</em> entries, which is the opposite of what someone looking at
     * an order wants: the question is almost always what just happened.
     *
     * @param limit most entries to return; a long-lived order accumulates
     *     thousands of reports and a screen shows a page of them
     */
    public Page of(String orderId, int limit) {
        List<Entry> entries = new ArrayList<>();
        for (Path segment : segments.allSegments()) {
            readInto(segment, orderId, entries);
        }
        if (entries.size() <= limit) {
            return new Page(List.copyOf(entries), entries.size(), false);
        }
        return new Page(
                List.copyOf(entries.subList(entries.size() - limit, entries.size())),
                entries.size(),
                true);
    }

    public Page of(String orderId) {
        return of(orderId, 500);
    }

    /**
     * The order's state transitions, and nothing else.
     *
     * <p>What someone following an order wants to read: one row per move, each
     * saying what happened and where it left the order. The bookkeeping the
     * journal also holds is still there for anything that needs it — this is a
     * view of the same history, not a different one.
     */
    public Page transitionsOf(String orderId, int limit) {
        Page all = of(orderId, Integer.MAX_VALUE);
        List<Entry> moves = all.entries().stream()
                .filter(Entry::isTransition)
                .toList();

        if (moves.size() <= limit) {
            return new Page(moves, moves.size(), false);
        }
        return new Page(
                List.copyOf(moves.subList(moves.size() - limit, moves.size())),
                moves.size(),
                true);
    }

    /**
     * A page of history, and what was left out.
     *
     * @param total how many entries exist in all
     * @param truncated true when older entries were not returned, so a caller
     *     shows that rather than presenting a partial history as complete
     */
    public record Page(List<Entry> entries, int total, boolean truncated) {

        public int shown() {
            return entries.size();
        }

        public int omitted() {
            return Math.max(0, total - entries.size());
        }
    }

    // ------------------------------------------------------------------

    private void readInto(Path segment, String orderId, List<Entry> into) {
        try (Stream<String> lines = Files.lines(segment, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                // Match before parsing: most lines in a segment belong to other
                // orders, and splitting them all would dominate the scan.
                if (!line.contains(orderId)) {
                    return;
                }
                Entry entry = parse(line, orderId);
                if (entry != null) {
                    into.add(entry);
                }
            });
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read segment " + segment, unreadable);
        }
    }

    private static Entry parse(String line, String orderId) {
        String[] parts = line.split(String.valueOf(SEPARATOR), -1);
        if (parts.length < 3) {
            return null;
        }
        if (!orderId.equals(unescape(parts[2]))) {
            // The id appeared somewhere else on the line — inside a client's own
            // identifier, most likely.
            return null;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        OrderEvent.WireRef wire = null;

        for (int i = 3; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = unescape(parts[i].substring(0, equals));
            String value = unescape(parts[i].substring(equals + 1));

            if (key.equals("wire") || key.equals("wireIn") || key.equals("wireOut")) {
                wire = OrderEvent.WireRef.parse(value);
            }
            fields.put(key, value);
        }

        long at;
        try {
            at = Long.parseLong(parts[0]);
        } catch (NumberFormatException notATimestamp) {
            return null;
        }
        return new Entry(at, parts[1], fields, wire);
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder text = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                text.append(c);
                continue;
            }
            char next = value.charAt(++i);
            text.append(switch (next) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case '\\' -> '\\';
                default -> next;
            });
        }
        return text.toString();
    }
}
