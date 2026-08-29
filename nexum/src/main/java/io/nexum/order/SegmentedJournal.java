package io.nexum.order;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The order journal, written to a segment per day and replayed from the
 * checkpoint forward.
 *
 * <p>Rolls on first write after midnight UTC rather than on a timer: a system
 * that is idle overnight should not create empty segments, and one that is busy
 * rolls at its first order of the day.
 *
 * <p>Replay reads only the segments the checkpoint says are needed, then writes
 * a new checkpoint at the oldest date still holding an unfinished order. A
 * long-lived working order therefore pins its segment — which is correct, since
 * that is exactly the record needed to rebuild it.
 */
public final class SegmentedJournal implements OrderJournal {

    private final JournalSegments segments;
    private final boolean syncOnWrite;

    private LocalDate openDate;
    private BufferedWriter writer;

    public SegmentedJournal(Path directory, boolean syncOnWrite) {
        this.segments = new JournalSegments(directory);
        this.syncOnWrite = syncOnWrite;
    }

    public JournalSegments segments() {
        return segments;
    }

    /** Record one event, rolling to a new segment if the day has changed. */
    @Override
    public synchronized void append(OrderEvent event) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (writer == null || !today.equals(openDate)) {
            roll(today);
        }
        try {
            writer.write(encode(event));
            writer.newLine();
            if (syncOnWrite) {
                writer.flush();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot append to order journal", failure);
        }
    }

    private void roll(LocalDate date) {
        closeWriter();
        Path segment = segments.segmentFor(date);
        try {
            writer = Files.newBufferedWriter(
                    segment,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            openDate = date;
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot open journal segment " + segment, failure);
        }
    }

    @Override
    public synchronized void close() {
        closeWriter();
    }

    private void closeWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot close order journal", failure);
        }
        writer = null;
    }

    // ------------------------------------------------------------------
    // Replay
    // ------------------------------------------------------------------

    public record Replay(int recovered, List<String> skipped, LocalDate checkpoint) {}

    /**
     * Rebuild the cache from the segments the checkpoint says are needed, then
     * advance the checkpoint to the oldest segment still holding unfinished
     * work.
     *
     * <p>A malformed line is skipped rather than fatal: a segment truncated by a
     * crash ends mid-line, and refusing to start over it turns a recoverable
     * situation into an outage.
     */
    public static Replay replay(Path directory, OrderCache cache) {
        JournalSegments segments = new JournalSegments(directory);
        Map<String, Order> rebuilt = new LinkedHashMap<>();
        Map<String, LocalDate> firstSeen = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();

        for (Path segment : segments.segmentsToReplay()) {
            LocalDate date = JournalSegments.dateOf(segment);
            readSegment(segment, date, rebuilt, firstSeen, cache, skipped);
        }

        LocalDate oldestUnfinished = null;
        for (Map.Entry<String, Order> entry : rebuilt.entrySet()) {
            if (entry.getValue().state().isTerminal()) {
                continue;
            }
            LocalDate seen = firstSeen.get(entry.getKey());
            if (seen != null && (oldestUnfinished == null || seen.isBefore(oldestUnfinished))) {
                oldestUnfinished = seen;
            }
        }
        // Nothing outstanding: the checkpoint moves to today, and every segment
        // before it becomes archivable.
        LocalDate checkpoint = oldestUnfinished == null
                ? LocalDate.now(ZoneOffset.UTC)
                : oldestUnfinished;
        segments.writeCheckpoint(checkpoint);

        return new Replay(cache.size(), skipped, checkpoint);
    }

    private static void readSegment(
            Path segment,
            LocalDate date,
            Map<String, Order> rebuilt,
            Map<String, LocalDate> firstSeen,
            OrderCache cache,
            List<String> skipped) {

        try (var lines = Files.lines(segment, StandardCharsets.UTF_8)) {
            int[] number = {0};
            lines.forEach(line -> {
                number[0]++;
                try {
                    String orderId = apply(line, rebuilt, cache);
                    if (orderId != null && date != null) {
                        firstSeen.putIfAbsent(orderId, date);
                    }
                } catch (RuntimeException malformed) {
                    skipped.add(segment.getFileName() + " line " + number[0]
                            + ": " + malformed.getMessage());
                }
            });
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read segment " + segment, failure);
        }
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    private static final char SEPARATOR = '\t';

    private static String encode(OrderEvent event) {
        StringBuilder line = new StringBuilder();
        line.append(event.timestamp()).append(SEPARATOR)
                .append(event.type()).append(SEPARATOR)
                .append(escape(event.orderId()));
        event.fields().forEach((key, value) ->
                line.append(SEPARATOR).append(escape(key)).append('=').append(escape(value)));
        return line.toString();
    }

    /** @return the order id the line touched, or null when it changed nothing */
    private static String apply(String line, Map<String, Order> rebuilt, OrderCache cache) {
        String[] parts = line.split(String.valueOf(SEPARATOR), -1);
        if (parts.length < 3) {
            throw new IllegalArgumentException("expected at least 3 columns");
        }
        String type = parts[1];
        String orderId = unescape(parts[2]);

        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 3; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals > 0) {
                fields.put(unescape(parts[i].substring(0, equals)),
                        unescape(parts[i].substring(equals + 1)));
            }
        }

        switch (type) {
            case "created" -> {
                Order order = new Order(
                        orderId,
                        fields.get("session"),
                        fields.get("client"),
                        fields.get("destination"),
                        new OrderView(fields.get("clientClOrdId"), null, null, tagged(fields, "c.")),
                        new OrderView(orderId, null, null, Map.of()),
                        new OrderView(fields.get("ourClOrdId"), null, null, tagged(fields, "d.")),
                        OrderState.PENDING_NEW);
                rebuilt.put(orderId, order);
                cache.put(order);
                cache.indexOutbound(fields.get("ourClOrdId"), orderId);
                return orderId;
            }
            case "venue-id" -> {
                Order order = rebuilt.get(orderId);
                if (order == null) {
                    return null;
                }
                String venueOrderId = fields.get("venueOrderId");
                Order updated = order.withVenueOrderId(venueOrderId);
                rebuilt.put(orderId, updated);
                cache.update(updated);
                cache.indexVenueOrderId(venueOrderId, orderId);
                return orderId;
            }
            case "state" -> {
                Order order = rebuilt.get(orderId);
                if (order == null) {
                    return null;
                }
                // The quantity has to come back with the state. Without it a
                // partially filled order returns at zero, the staleness guard
                // that compares against what is already known stops working,
                // and a resent report is counted as a fresh fill.
                Order updated = order.withReport(
                        OrderState.valueOf(fields.get("state")),
                        number(fields.get("cumQty"), order.cumQty()));
                rebuilt.put(orderId, updated);
                cache.update(updated);
                return orderId;
            }

            // A cancel or replace that was outstanding when the process ended
            // is still outstanding: the venue is going to answer it, and an
            // order that has forgotten the request cannot make sense of the
            // answer.
            case "request" -> {
                Order order = rebuilt.get(orderId);
                if (order == null) {
                    return null;
                }
                // Written since the client identifier was added; a segment from
                // before that says "null", which reads back as absent.
                String clientClOrdId = fields.get("clientClOrdId");
                if ("null".equals(clientClOrdId)) {
                    clientClOrdId = null;
                }
                PendingRequest request = "CANCEL".equals(fields.get("kind"))
                        ? PendingRequest.cancel(
                                fields.get("clOrdId"), fields.get("origClOrdId"),
                                clientClOrdId, Long.parseLong(parts[0]))
                        : PendingRequest.replace(
                                fields.get("clOrdId"), fields.get("origClOrdId"),
                                clientClOrdId, Long.parseLong(parts[0]),
                                requestedTerms(fields));
                Order updated = order.withPending(request);
                rebuilt.put(orderId, updated);
                cache.update(updated);
                return orderId;
            }

            case "request-answered" -> {
                Order order = rebuilt.get(orderId);
                if (order == null) {
                    return null;
                }
                Order updated = order.withoutPending();
                rebuilt.put(orderId, updated);
                cache.update(updated);
                return orderId;
            }
            default -> throw new IllegalArgumentException("unknown event type \"" + type + "\"");
        }
    }

    /** A number from a recorded field, or a fallback when it is absent or malformed. */
    private static double number(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static Map<Integer, String> requestedTerms(Map<String, String> fields) {
        return tagged(fields, "r.");
    }

    private static Map<Integer, String> tagged(Map<String, String> fields, String prefix) {
        Map<Integer, String> result = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                try {
                    result.put(Integer.parseInt(key.substring(prefix.length())), value);
                } catch (NumberFormatException notATag) {
                    // a non-numeric key under a tag prefix is not a FIX field
                }
            }
        });
        return result;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unescape(String value) {
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
