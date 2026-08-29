package io.nexum.demo;

import io.nexum.order.Order;
import io.nexum.order.OrderEventType;
import io.nexum.order.OrderState;
import io.nexum.order.OrderStateMachine;
import io.nexum.order.OrderView;
import io.nexum.order.PendingRequest;

import java.util.Map;

/**
 * Cancels and replaces in flight: what an order does while one is outstanding,
 * and what the venue's answer does to it.
 *
 * <p>The awkward part is that a pending request and a fill are independent. An
 * order keeps trading while a cancel is on its way, and the cancel still has to
 * be answered afterwards — a system that lets the fill overwrite the pending
 * state forgets a request the venue is about to reply to.
 */
public final class PendingDemo {

    static final int PRICE = 44;
    static final int ORDER_QTY = 38;
    static final int SYMBOL = 55;

    public static void main(String[] args) {
        System.out.println("=== a cancel, answered ===");
        walk(order(), new Step[] {
                step(OrderEventType.CANCEL_PENDING, 0,
                        PendingRequest.cancel("CXL-1", "OUR-1", null, now())),
                step(OrderEventType.CANCELLED, 0, null),
        });

        System.out.println("\n=== a cancel the venue refuses ===");
        System.out.println("  the order is still working — this reads like an ending and is not");
        walk(order(), new Step[] {
                step(OrderEventType.CANCEL_PENDING, 0,
                        PendingRequest.cancel("CXL-2", "OUR-1", null, now())),
                step(OrderEventType.CANCEL_REFUSED, 0, null),
                step(OrderEventType.PARTIAL_FILL, 300, null),
        });

        System.out.println("\n=== a fill arrives while the cancel is in flight ===");
        System.out.println("  the quantity moves; the cancel is still awaited");
        walk(order(), new Step[] {
                step(OrderEventType.CANCEL_PENDING, 0,
                        PendingRequest.cancel("CXL-3", "OUR-1", null, now())),
                step(OrderEventType.PARTIAL_FILL, 400, null),
                step(OrderEventType.CANCELLED, 400, null),
        });

        System.out.println("\n=== a fill beats the cancel outright ===");
        walk(order(), new Step[] {
                step(OrderEventType.CANCEL_PENDING, 0,
                        PendingRequest.cancel("CXL-4", "OUR-1", null, now())),
                step(OrderEventType.FILL, 1000, null),
        });

        System.out.println("\n=== a replace, and what the order is worth meanwhile ===");
        Order live = order().withState(OrderState.NEW);
        System.out.println("  original terms : price=" + live.destination().field(PRICE)
                + " qty=" + live.destination().field(ORDER_QTY));

        PendingRequest amend = PendingRequest.replace("AMD-1", "OUR-1", null, now(), Map.of(PRICE, "155.00", ORDER_QTY, "1500"));
        live = live.withPending(amend);

        OrderStateMachine.Decision pendingReplace = OrderStateMachine.decide(
                live.state(), OrderEventType.REPLACE_PENDING, 0, 0, live.pending());
        live = advance(live, pendingReplace);

        System.out.println("  requested      : price=" + amend.requested(PRICE)
                + " qty=" + amend.requested(ORDER_QTY));
        System.out.println("  still in force : price=" + live.destination().field(PRICE)
                + " qty=" + live.destination().field(ORDER_QTY)
                + "   <- not the requested terms, because the venue has not agreed");
        System.out.println("  state          : " + live.state());

        System.out.println("\n  the venue accepts:");
        live = advance(live, OrderStateMachine.decide(
                live.state(), OrderEventType.REPLACED, 0, 0, live.pending()));
        live = live.withReplaceApplied();
        System.out.println("    in force now : price=" + live.destination().field(PRICE)
                + " qty=" + live.destination().field(ORDER_QTY));
        System.out.println("    outstanding  : " + (live.hasPending() ? live.pending() : "none"));

        System.out.println("\n=== a replace the venue refuses ===");
        Order refused = order().withState(OrderState.NEW).withPending(
                PendingRequest.replace("AMD-2", "OUR-1", null, now(), Map.of(PRICE, "160.00")));
        refused = advance(refused, OrderStateMachine.decide(
                refused.state(), OrderEventType.REPLACE_PENDING, 0, 0, refused.pending()));
        refused = advance(refused, OrderStateMachine.decide(
                refused.state(), OrderEventType.REPLACE_REFUSED, 0, 0, refused.pending()));
        refused = refused.withoutPending();
        System.out.println("  state          : " + refused.state());
        System.out.println("  price in force : " + refused.destination().field(PRICE)
                + "   <- unchanged, as it should be");

        System.out.println("\n=== how long has a request been outstanding ===");
        PendingRequest old = PendingRequest.cancel("CXL-9", "OUR-1", null, now() - 45_000);
        System.out.println("  " + old.kind() + " " + old.clOrdId()
                + " outstanding for " + old.outstandingFor(now()) / 1000 + "s"
                + "   <- what a stuck-amend rule watches");
    }

    // ------------------------------------------------------------------

    private record Step(OrderEventType event, double cumQty, PendingRequest pending) {}

    private static Step step(OrderEventType event, double cumQty, PendingRequest pending) {
        return new Step(event, cumQty, pending);
    }

    private static void walk(Order start, Step[] steps) {
        Order order = start.withState(OrderState.NEW);
        System.out.println("  start: " + order.state());

        for (Step step : steps) {
            if (step.pending() != null) {
                order = order.withPending(step.pending());
            }
            OrderStateMachine.Decision decision = OrderStateMachine.decide(
                    order.state(), step.event(), step.cumQty(), order.cumQty(), order.pending());

            String outstanding = order.hasPending()
                    ? "  [" + order.pending().kind() + " outstanding]"
                    : "";

            if (decision instanceof OrderStateMachine.Decision.Advance(var to, var because)) {
                order = order.withReport(to, step.cumQty());
                // The venue's answer to a request clears it; a fill does not.
                if (answersRequest(step.event())) {
                    order = order.withoutPending();
                }
                System.out.printf("    %-16s -> %-17s cum=%-6.0f (%s)%s%n",
                        step.event(), to, order.cumQty(), because, outstanding);
            } else {
                System.out.printf("    %-16s -> %s%s%n", step.event(), decision, outstanding);
            }
        }
    }

    private static boolean answersRequest(OrderEventType event) {
        return event == OrderEventType.CANCELLED
                || event == OrderEventType.CANCEL_REFUSED
                || event == OrderEventType.REPLACED
                || event == OrderEventType.REPLACE_REFUSED
                || event == OrderEventType.FILL;
    }

    private static Order advance(Order order, OrderStateMachine.Decision decision) {
        return decision instanceof OrderStateMachine.Decision.Advance(var to, var ignored)
                ? order.withState(to)
                : order;
    }

    private static Order order() {
        Map<Integer, String> fields = Map.of(
                SYMBOL, "VOD", PRICE, "150.00", ORDER_QTY, "1000");
        return new Order(
                "ORD-1", "OMS->FUNDX", "FUND_X", "OMS->LSE",
                OrderView.of("CLIENT-1", fields),
                OrderView.of("ORD-1", Map.of()),
                OrderView.of("OUR-1", fields),
                OrderState.CREATED);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
