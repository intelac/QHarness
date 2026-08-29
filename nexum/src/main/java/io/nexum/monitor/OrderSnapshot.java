package io.nexum.monitor;

import io.nexum.message.FixTags;
import io.nexum.order.Order;
import io.nexum.order.OrderState;

/**
 * An order as monitoring sees it: identity, routing, current state, and the
 * timings that make staleness visible.
 *
 * <p>Separate from {@link Order} because monitoring needs things the order
 * itself does not carry — when it was last heard about, how many reports it has
 * drawn — and does not need the full field views. Keeping them apart means a new
 * monitoring need does not change the type on the order path.
 */
public record OrderSnapshot(
        String orderId,
        String sessionId,
        String clientId,
        String destinationId,
        String clientClOrdId,
        String ourClOrdId,
        String venueOrderId,
        String symbol,
        String side,
        OrderState state,
        double orderQty,
        double cumQty,
        long createdAt,
        long lastReportAt,
        int reportCount) {


    public static OrderSnapshot of(Order order, long now) {
        return new OrderSnapshot(
                order.orderId(),
                order.sessionId(),
                order.clientId(),
                order.destinationId(),
                order.client().clOrdId(),
                order.destination().clOrdId(),
                order.destination().orderId(),
                order.client().field(FixTags.SYMBOL),
                order.client().field(FixTags.SIDE),
                order.state(),
                number(order.client().field(FixTags.ORDER_QTY)),
                0,
                now,
                now,
                0);
    }

    public OrderSnapshot withReport(OrderState newState, double newCumQty, long at) {
        return withReport(newState, newCumQty, 0, at);
    }

    /** @param newOrderQty the order's quantity now, or 0 to keep the current one */
    public OrderSnapshot withReport(
            OrderState newState, double newCumQty, double newOrderQty, long at) {
        return new OrderSnapshot(
                orderId, sessionId, clientId, destinationId, clientClOrdId, ourClOrdId,
                venueOrderId, symbol, side, newState,
                newOrderQty > 0 ? newOrderQty : orderQty, newCumQty,
                createdAt, at, reportCount + 1);
    }

    public OrderSnapshot withVenueOrderId(String id) {
        return new OrderSnapshot(
                orderId, sessionId, clientId, destinationId, clientClOrdId, ourClOrdId,
                id, symbol, side, state, orderQty, cumQty,
                createdAt, lastReportAt, reportCount);
    }

    public double leavesQty() {
        return Math.max(0, orderQty - cumQty);
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    /** How long since the venue last said anything about this order. */
    public long silentFor(long now) {
        return now - lastReportAt;
    }

    /** True while the order is live and has never been acknowledged. */
    public boolean awaitingAck() {
        return state == OrderState.PENDING_NEW;
    }

    private static double number(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
