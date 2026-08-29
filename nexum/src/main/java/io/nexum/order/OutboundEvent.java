package io.nexum.order;

import java.util.Map;

/**
 * What an order concluded from something it was told.
 *
 * <p>Returned rather than performed, so the order stays a value: it decides, and
 * the caller journals, forwards or raises. That separation is what makes the
 * decisions testable without a transport, a journal or a clock.
 */
public sealed interface OutboundEvent {

    String orderId();

    long at();

    /** The order moved. */
    /**
     * @param orderQty what the order is for as of this change. It is not always
     *     what it was created for — an accepted amendment changes it — and a
     *     projection that keeps the original reports an order filled beyond its
     *     own size.
     */
    record StateChanged(
            String orderId,
            long at,
            OrderState from,
            OrderState to,
            OrderEventType cause,
            String because,
            double cumQty,
            double orderQty) implements OutboundEvent {

        /** For a change that does not carry the order's terms. */
        public StateChanged(
                String orderId, long at, OrderState from, OrderState to,
                OrderEventType cause, String because, double cumQty) {
            this(orderId, at, from, to, cause, because, cumQty, 0);
        }
    }

    /** Quantity moved without the state changing — a partial while a cancel is awaited. */
    record QuantityChanged(
            String orderId, long at, OrderState state, double from, double to)
            implements OutboundEvent {}

    /** The venue supplied its own OrderID(37) for the first time. */
    record VenueIdLearned(String orderId, long at, String venueOrderId)
            implements OutboundEvent {}

    /** A cancel or replace is now outstanding. */
    record RequestOutstanding(String orderId, long at, PendingRequest request)
            implements OutboundEvent {}

    /** The venue answered an outstanding request. */
    record RequestAnswered(
            String orderId, long at, PendingRequest request, boolean accepted)
            implements OutboundEvent {}

    /** Replaced terms took effect. */
    record TermsAmended(String orderId, long at, Map<Integer, String> newTerms)
            implements OutboundEvent {}

    /**
     * Recognised but describing a point already passed. Ordinary during a
     * resend; worth counting, because a run of them means reports are arriving
     * out of order.
     */
    record Ignored(String orderId, long at, OrderEventType cause, String why)
            implements OutboundEvent {}

    /**
     * The venue and this system disagree. Nothing was applied, and someone needs
     * to look.
     */
    record Disagreement(
            String orderId, long at, OrderState state, OrderEventType cause, String why)
            implements OutboundEvent {}

    /**
     * The report should now be forwarded to the client, under these identifiers.
     *
     * @param clientClOrdId what ClOrdID(11) should say. For an ordinary report
     *     this is the order's own; for one answering a cancel or replace it is
     *     what the client called that request, which is what the client is
     *     waiting to hear about.
     * @param origClOrdId what OrigClOrdID(41) should say, or null when the
     *     report does not answer a request. FIX pairs the two: 11 names the
     *     request, 41 names the order it was against.
     */
    record ForwardToClient(
            String orderId, long at, String clientClOrdId, String origClOrdId)
            implements OutboundEvent {}
}
