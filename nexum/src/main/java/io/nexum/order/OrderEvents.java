package io.nexum.order;

import io.nexum.core.EventKey;

import java.util.List;

/**
 * The events an order's progress publishes, and what travels on each.
 *
 * <p>Declared here rather than spelled out at every call site. Two things went
 * wrong with bare strings: a subscriber once watched an event nothing emitted
 * and the venue identifier stayed null until somebody happened to look, and the
 * payloads were flattened to {@code Map<String,String>} — so a state the machine
 * had carefully derived was turned back into text and parsed again downstream,
 * where a rename would fail inside a dispatch loop that swallows exceptions.
 *
 * <p>The payload types are the records the order already produces. Nothing is
 * flattened; a subscriber receives what was concluded.
 */
public final class OrderEvents {

    private OrderEvents() {}

    /** An order was accepted from a client and given its identity. */
    public static final EventKey<Order> CREATED =
            EventKey.emit("order/created", Order.class);

    /** The order moved between states. */
    public static final EventKey<OutboundEvent.StateChanged> STATE_CHANGED =
            EventKey.emit("order/state-changed", OutboundEvent.StateChanged.class);

    /**
     * Quantity moved without the state changing — a partial fill arriving while
     * a cancel or replace is still outstanding.
     */
    public static final EventKey<OutboundEvent.QuantityChanged> QUANTITY_CHANGED =
            EventKey.emit("order/quantity-changed", OutboundEvent.QuantityChanged.class);

    /** The venue supplied its own OrderID(37) for the first time. */
    public static final EventKey<OutboundEvent.VenueIdLearned> VENUE_ID_ASSIGNED =
            EventKey.emit("order/venue-id-assigned", OutboundEvent.VenueIdLearned.class);

    /** A cancel or replace is now awaiting an answer. */
    public static final EventKey<OutboundEvent.RequestOutstanding> REQUEST_SENT =
            EventKey.emit("order/request-sent", OutboundEvent.RequestOutstanding.class);

    /** The venue answered an outstanding request. */
    public static final EventKey<OutboundEvent.RequestAnswered> REQUEST_ANSWERED =
            EventKey.emit("order/request-answered", OutboundEvent.RequestAnswered.class);

    /** Replaced terms took effect. */
    public static final EventKey<OutboundEvent.TermsAmended> TERMS_AMENDED =
            EventKey.emit("order/terms-amended", OutboundEvent.TermsAmended.class);

    /**
     * A report describing a point the order has already passed.
     *
     * <p>Ordinary during a resend, and not an error — but worth counting,
     * because a run of them means reports are arriving out of order.
     */
    public static final EventKey<OutboundEvent.Ignored> REPORT_IGNORED =
            EventKey.emit("order/report-ignored", OutboundEvent.Ignored.class);

    /**
     * The venue and this system disagree about an order.
     *
     * <p>Nothing was applied and someone needs to look.
     */
    public static final EventKey<OutboundEvent.Disagreement> DISAGREEMENT =
            EventKey.emit("order/disagreement", OutboundEvent.Disagreement.class);

    // ------------------------------------------------------------------
    // Conditions with no per-order record of their own
    // ------------------------------------------------------------------

    /**
     * A report that resolved to no order.
     *
     * <p>A restart, a stale venue message, or an order this instance never
     * sent. Surfaced rather than dropped in silence.
     */
    public static final EventKey<UnmatchedReport> REPORT_UNMATCHED =
            EventKey.emit("order/report-unmatched", UnmatchedReport.class);

    public record UnmatchedReport(
            String sessionId, String venueOrderId, String clOrdId, long at) {}

    /** A cancel or replace naming an order this system never placed. */
    public static final EventKey<UnknownRequest> REQUEST_UNKNOWN =
            EventKey.emit("order/request-unknown", UnknownRequest.class);

    public record UnknownRequest(
            String sessionId, String msgType, String origClOrdId, long at) {}

    /** An order arriving without the ClOrdID that would identify it. */
    public static final EventKey<Unidentifiable> UNIDENTIFIABLE =
            EventKey.emit("order/unidentifiable", Unidentifiable.class);

    public record Unidentifiable(String sessionId, String why, long at) {}

    /** The journal was replayed at startup. */
    public static final EventKey<Replayed> REPLAYED =
            EventKey.emit("order/replayed", Replayed.class);

    /**
     * @param skipped records that could not be read — a segment truncated by a
     *     crash ends in a partial line, and what was skipped should be visible
     */
    public record Replayed(int recovered, List<String> skipped, String checkpoint) {}
}
