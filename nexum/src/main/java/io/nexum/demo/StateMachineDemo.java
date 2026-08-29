package io.nexum.demo;

import io.nexum.order.OrderEventType;
import io.nexum.order.OrderState;
import io.nexum.order.OrderStateMachine;

/**
 * Three steps over one message: recognise what it is, decide what it means where
 * the order stands, then see what the order becomes.
 *
 * <p>The middle step is the one that cannot be skipped. The same event is a
 * transition, a duplicate, or a disagreement depending entirely on the state it
 * arrives in.
 */
public final class StateMachineDemo {

    public static void main(String[] args) {
        System.out.println("=== step 1: what is this message ===");
        recognise("150=0", null, null, "0", null);
        recognise("150=F, 151=500 left", "F", 500.0, "1", null);
        recognise("150=F, 151=0 left", "F", 0.0, "2", null);
        recognise("35=9, 434=1", null, null, null, "1");
        recognise("35=9, 434=2", null, null, null, "2");
        recognise("no 150, only 39=2", null, 0.0, "2", null);

        System.out.println("\n=== the ordinary life of an order ===");
        walk(OrderState.CREATED,
                OrderEventType.SENT_TO_VENUE,
                OrderEventType.ACK,
                OrderEventType.PARTIAL_FILL,
                OrderEventType.FILL);

        System.out.println("\n=== a cancel the venue refuses ===");
        System.out.println("  the order keeps working — the part most easily missed");
        walk(OrderState.NEW,
                OrderEventType.CANCEL_PENDING,
                OrderEventType.CANCEL_REFUSED,
                OrderEventType.PARTIAL_FILL,
                OrderEventType.FILL);

        System.out.println("\n=== a fill that beats a cancel ===");
        walk(OrderState.NEW,
                OrderEventType.CANCEL_PENDING,
                OrderEventType.FILL);

        System.out.println("\n=== a replace the venue refuses ===");
        walk(OrderState.NEW,
                OrderEventType.REPLACE_PENDING,
                OrderEventType.REPLACE_REFUSED,
                OrderEventType.CANCEL_PENDING,
                OrderEventType.CANCELLED);

        System.out.println("\n=== a send that never left ===");
        System.out.println("  ours to resolve; no counterparty ever saw it");
        walk(OrderState.CREATED, OrderEventType.SEND_FAILED);

        System.out.println("\n=== held, then working again ===");
        walk(OrderState.NEW,
                OrderEventType.SUSPENDED,
                OrderEventType.ACK,
                OrderEventType.DONE_FOR_DAY,
                OrderEventType.CANCELLED);

        System.out.println("\n=== the same event, three different meanings ===");
        one(OrderState.PENDING_CANCEL, OrderEventType.CANCEL_REFUSED, 0, 0);
        one(OrderState.NEW, OrderEventType.CANCEL_REFUSED, 0, 0);
        one(OrderState.FILLED, OrderEventType.CANCEL_REFUSED, 0, 1000);

        System.out.println("\n=== a replayed report after the order closed ===");
        one(OrderState.FILLED, OrderEventType.ACK, 0, 1000);
        one(OrderState.FILLED, OrderEventType.PARTIAL_FILL, 300, 1000);

        System.out.println("\n=== quantity decides whether a partial is news ===");
        one(OrderState.PARTIALLY_FILLED, OrderEventType.PARTIAL_FILL, 600, 300);
        one(OrderState.PARTIALLY_FILLED, OrderEventType.PARTIAL_FILL, 600, 600);

        System.out.println("\n=== states that cannot follow ===");
        one(OrderState.NEW, OrderEventType.ORDER_REJECTED, 0, 0);
        one(OrderState.CREATED, OrderEventType.PARTIAL_FILL, 100, 0);

        System.out.println("\n=== reachability, derived from the decisions themselves ===");
        for (OrderState state : OrderState.values()) {
            System.out.printf("  %-17s -> %s%n",
                    state, OrderStateMachine.reachableFrom(state));
        }
    }

    private static void recognise(
            String label, String execType, Double leaves, String ordStatus, String cxlRejTo) {

        OrderEventType event = cxlRejTo != null
                ? OrderEventType.fromCancelReject(cxlRejTo)
                : OrderEventType.fromExecutionReport(execType, ordStatus, leaves);
        System.out.printf("  %-22s -> %s%n", label, event);
    }

    /** Run an order through a sequence, showing what each event does to it. */
    private static void walk(OrderState from, OrderEventType... events) {
        OrderState state = from;
        double cumQty = 0;
        System.out.println("  start: " + state);
        for (OrderEventType event : events) {
            cumQty += event == OrderEventType.PARTIAL_FILL ? 300 : 0;
            OrderStateMachine.Decision decision =
                    OrderStateMachine.decide(state, event, cumQty, cumQty - 300);
            if (decision instanceof OrderStateMachine.Decision.Advance(var to, var because)) {
                System.out.printf("    %-18s -> %-17s  (%s)%n", event, to, because);
                state = to;
            } else {
                System.out.printf("    %-18s -> %s%n", event, describe(decision));
            }
        }
    }

    private static void one(
            OrderState current, OrderEventType event, double cumQty, double known) {

        OrderStateMachine.Decision decision =
                OrderStateMachine.decide(current, event, cumQty, known);
        System.out.printf("  %-17s + %-18s -> %s%n", current, event, describe(decision));
    }

    private static String describe(OrderStateMachine.Decision decision) {
        // Exhaustive over a sealed interface: a decision this does not handle
        // becomes a compile error rather than a silent default.
        return switch (decision) {
            case OrderStateMachine.Decision.Advance(var to, var because) ->
                    "ADVANCE to " + to + " (" + because + ")";
            case OrderStateMachine.Decision.Stale(var was, var event, var why) ->
                    "STALE, ignored: " + why;
            case OrderStateMachine.Decision.Illegal(var was, var event, var why) ->
                    "ILLEGAL, raised: " + why;
            case OrderStateMachine.Decision.Unchanged(var current, var why) ->
                    "UNCHANGED: " + why;
        };
    }
}
