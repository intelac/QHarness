package io.nexum.routing;

import io.nexum.message.FixMessage;
import io.nexum.order.ManagedOrder;
import io.nexum.order.OrderBook;
import io.nexum.order.OrderIdResolver;

import java.util.Optional;

/** Finding the order a client's amendment refers to. */
public final class OrderLookup {

    private OrderLookup() {}

    /**
     * The order a cancel or replace names.
     *
     * <p>Through the resolver, which knows every ClOrdID the client has used for
     * an order — the original, and any a replace introduced. Nothing is minted
     * here: an identifier that was never issued means no such order was placed,
     * and manufacturing an id from OrigClOrdID would make a typo resolve to a
     * plausible-looking order that is not there.
     */
    public static Optional<ManagedOrder> byClientRequest(
            OrderIdResolver ids, OrderBook book, FixMessage request, String sessionId) {
        return ids.forAmendment(request, sessionId).flatMap(book::byOrderId);
    }

    /** The identifier the venue currently knows this order by. */
    public static String ourOutboundId(ManagedOrder order, String fallback) {
        return order.destinationView().map(view -> view.clOrdId()).orElse(fallback);
    }
}
