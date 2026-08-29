package io.nexum.order;

/**
 * An order as the system holds it: three views plus the routing that produced
 * them.
 *
 * <p>Nothing about an order is a pass-through. The client's identifiers stay in
 * {@link #client()}, ours live in {@link #internal()}, and what actually went on
 * the wire is in {@link #destination()}. A report arriving in venue terms is
 * resolved against the destination view and answered in client terms.
 */
public record Order(
        String orderId,
        String sessionId,
        String clientId,
        String destinationId,
        OrderView client,
        OrderView internal,
        OrderView destination,
        OrderState state,
        double cumQty,
        PendingRequest pending) {

    /** Convenience for construction before any fill or amendment. */
    public Order(
            String orderId, String sessionId, String clientId, String destinationId,
            OrderView client, OrderView internal, OrderView destination, OrderState state) {
        this(orderId, sessionId, clientId, destinationId,
                client, internal, destination, state, 0, null);
    }

    public Order withState(OrderState newState) {
        return new Order(orderId, sessionId, clientId, destinationId,
                client, internal, destination, newState, cumQty, pending);
    }

    /** Record a cancel or replace as outstanding. */
    public Order withPending(PendingRequest request) {
        return new Order(orderId, sessionId, clientId, destinationId,
                client, internal, destination, state, cumQty, request);
    }

    /** The venue has answered; nothing is outstanding any more. */
    public Order withoutPending() {
        return new Order(orderId, sessionId, clientId, destinationId,
                client, internal, destination, state, cumQty, null);
    }


    /**
     * Apply an accepted replace: the requested terms become the order's own.
     *
     * <p>Only at this point. While the request was outstanding the order still
     * worked on its original terms, and treating the requested ones as current
     * would have shown an exposure that did not exist.
     */
    public Order withReplaceApplied() {
        if (pending == null || !pending.isReplace()) {
            return withoutPending();
        }
        OrderView amended = destination;
        for (var entry : pending.requestedTerms().entrySet()) {
            amended = amended.withField(entry.getKey(), entry.getValue());
        }
        return new Order(orderId, sessionId, clientId, destinationId,
                client, internal, amended, state, cumQty, null);
    }

    /** True while a cancel or replace is awaiting an answer. */
    public boolean hasPending() {
        return pending != null;
    }

    /**
     * Apply a report's outcome. Quantity travels with the state because the two
     * are decided together: a partial fill that does not change the state still
     * changes the position.
     */
    public Order withReport(OrderState newState, double newCumQty) {
        return new Order(orderId, sessionId, clientId, destinationId,
                client, internal, destination, newState, Math.max(cumQty, newCumQty), pending);
    }

    /** Record the venue's OrderID(37) the first time it appears. */
    public Order withVenueOrderId(String venueOrderId) {
        return new Order(orderId, sessionId, clientId, destinationId,
                client, internal, destination.withOrderId(venueOrderId), state, cumQty, pending);
    }
}
