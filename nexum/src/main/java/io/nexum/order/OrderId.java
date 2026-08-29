package io.nexum.order;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * An order's identity: the trading day, the session it arrived on, and the
 * identifier the client gave it.
 *
 * <pre>
 *   20260824:OMS-&gt;FUNDX:FUNDX-ORD-1
 * </pre>
 *
 * <p>Derived rather than allocated. A counter has to be kept somewhere and kept
 * correct across restarts; this is computable from the message that arrived, so
 * two components asked to identify the same order agree without consulting each
 * other.
 *
 * <p>Each part earns its place. The client's ClOrdID alone is unique only to
 * that client, and two clients reuse the same strings. Adding the session
 * separates them. Adding the day separates a client from itself tomorrow, since
 * ClOrdIDs are commonly reset overnight — without it, today's order would
 * resolve to yesterday's.
 *
 * <p>This identifies orders arriving <em>from clients</em>. Reports from a venue
 * carry identifiers we or the venue chose, so those resolve through the cache's
 * indexes instead.
 */
public record OrderId(LocalDate day, String sessionId, String clientClOrdId)
        implements Comparable<OrderId> {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final char SEPARATOR = ':';

    public OrderId {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("an order id needs a session");
        }
        if (clientClOrdId == null || clientClOrdId.isBlank()) {
            throw new IllegalArgumentException("an order id needs the client's ClOrdID");
        }
    }

    /**
     * Identify an order from a client message.
     *
     * @param tradingDay the business day, which a deployment decides — a venue
     *     whose day rolls at 17:00 local is not on the calendar date after that
     */
    public static OrderId of(LocalDate tradingDay, String sessionId, String clientClOrdId) {
        return new OrderId(tradingDay, sessionId, clientClOrdId);
    }

    /** Today's trading day in the given zone. */
    public static OrderId today(ZoneId zone, String sessionId, String clientClOrdId) {
        return new OrderId(LocalDate.now(zone), sessionId, clientClOrdId);
    }

    /**
     * Read one back from its rendered form.
     *
     * <p>Split from the left twice: a client's ClOrdID may itself contain a
     * colon, and splitting on every one would truncate it.
     */
    public static OrderId parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no order id");
        }
        int firstColon = text.indexOf(SEPARATOR);
        int secondColon = firstColon < 0 ? -1 : text.indexOf(SEPARATOR, firstColon + 1);
        if (firstColon < 0 || secondColon < 0) {
            throw new IllegalArgumentException(
                    "\"" + text + "\" is not day:session:clOrdId");
        }
        try {
            return new OrderId(
                    LocalDate.parse(text.substring(0, firstColon), DAY),
                    text.substring(firstColon + 1, secondColon),
                    text.substring(secondColon + 1));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "\"" + text + "\" is not a valid order id", malformed);
        }
    }

    /** True when the text looks like one of these, without throwing if not. */
    public static boolean looksLikeOne(String text) {
        try {
            parse(text);
            return true;
        } catch (RuntimeException notOne) {
            return false;
        }
    }

    @Override
    public String toString() {
        return DAY.format(day) + SEPARATOR + sessionId + SEPARATOR + clientClOrdId;
    }

    /** The same client order on a different day is a different order. */
    public OrderId onDay(LocalDate otherDay) {
        return new OrderId(otherDay, sessionId, clientClOrdId);
    }

    /** Ordering by day first, so a listing groups naturally. */
    @Override
    public int compareTo(OrderId other) {
        int byDay = day.compareTo(other.day);
        if (byDay != 0) {
            return byDay;
        }
        int bySession = sessionId.compareTo(other.sessionId);
        return bySession != 0 ? bySession : clientClOrdId.compareTo(other.clientClOrdId);
    }
}
