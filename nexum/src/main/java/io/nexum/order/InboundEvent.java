package io.nexum.order;

import java.util.Map;

/**
 * Something that happened, offered to an order.
 *
 * <p>The order is told what occurred, not what to become. Deciding the state is
 * the order's own work — a caller that passes in a state has already made the
 * decision the state machine exists to make, and two callers will eventually
 * make it differently.
 */
public sealed interface InboundEvent {

    long at();

    /** A client order arriving, before anything has been sent. */
    record ClientOrder(
            long at,
            String sessionId,
            String clientId,
            String clientClOrdId,
            Map<Integer, String> fields) implements InboundEvent {}

    /** We put the order on the wire. */
    record SentToVenue(long at, String destinationId, String ourClOrdId)
            implements InboundEvent {}

    /** The send failed; no counterparty saw it. */
    record SendFailed(long at, String reason) implements InboundEvent {}

    /**
     * A cancel request going out.
     *
     * @param clientClOrdId what the client called this request, so the
     *     confirmation can be echoed back under it
     */
    record CancelRequested(
            long at, String clOrdId, String origClOrdId, String clientClOrdId)
            implements InboundEvent {}

    /** A replace request going out, carrying the terms it asks for. */
    record ReplaceRequested(
            long at, String clOrdId, String origClOrdId, String clientClOrdId,
            Map<Integer, String> requestedTerms)
            implements InboundEvent {}

    /**
     * An execution report or a cancel reject from the venue.
     *
     * @param type what the message was recognised as
     * @param cumQty CumQty(14)
     * @param leavesQty LeavesQty(151), null when the venue omits it
     * @param venueOrderId OrderID(37), null until the venue supplies one
     */
    record VenueReport(
            long at,
            OrderEventType type,
            double cumQty,
            Double leavesQty,
            String venueOrderId,
            String ourClOrdId,
            Map<Integer, String> fields) implements InboundEvent {

        public String field(int tag) {
            return fields.get(tag);
        }
    }
}
