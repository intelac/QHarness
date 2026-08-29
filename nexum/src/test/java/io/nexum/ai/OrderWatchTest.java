package io.nexum.ai;

import io.nexum.core.Context;
import io.nexum.order.OrderEvents;
import io.nexum.order.OrderEventType;
import io.nexum.order.OrderState;
import io.nexum.order.OutboundEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a caller can wait for an order without polling.
 *
 * <p>The ordering is the whole point: a venue answering in under a millisecond
 * would deliver its answer before a caller that registered afterwards was
 * listening, and the caller would then wait out its timeout for something that
 * had already happened.
 */
class OrderWatchTest {

    @Test
    @DisplayName("a state that arrives after the wait began wakes the caller")
    void anArrivingStateWakesTheCaller() {
        Context ctx = new Context();
        OrderWatch watch = new OrderWatch(ctx);

        try (OrderWatch.Watch waiting = watch.watch("ORD-1", state -> state == OrderState.NEW)) {
            ctx.emit(OrderEvents.STATE_CHANGED, new OutboundEvent.StateChanged(
                    "ORD-1", 1, OrderState.PENDING_NEW, OrderState.NEW,
                    OrderEventType.ACK, "acked", 0));

            assertEquals(OrderState.NEW, waiting.await(2000));
        }
    }

    @Test
    @DisplayName("a state the caller is not waiting for does not wake it")
    void anUninterestingStateIsIgnored() {
        Context ctx = new Context();
        OrderWatch watch = new OrderWatch(ctx);

        try (OrderWatch.Watch waiting =
                     watch.watch("ORD-2", OrderState::isTerminal)) {

            // Working, not terminal. Waking here would hand the caller a state
            // it explicitly said it was not waiting for.
            ctx.emit(OrderEvents.STATE_CHANGED, new OutboundEvent.StateChanged(
                    "ORD-2", 1, OrderState.PENDING_NEW, OrderState.NEW,
                    OrderEventType.ACK, "acked", 0));

            assertNull(waiting.await(150), "NEW is not terminal");

            ctx.emit(OrderEvents.STATE_CHANGED, new OutboundEvent.StateChanged(
                    "ORD-2", 2, OrderState.NEW, OrderState.FILLED,
                    OrderEventType.FILL, "filled", 1000));

            assertEquals(OrderState.FILLED, waiting.await(2000));
        }
    }

    @Test
    @DisplayName("a partial that moves quantity but not state still wakes a caller waiting on it")
    void aQuantityChangeCanSettleAWait() {
        Context ctx = new Context();
        OrderWatch watch = new OrderWatch(ctx);

        // A fill arriving while a cancel is outstanding leaves the state alone
        // and moves the position. A caller watching for the working state has
        // to see it, or it waits out its timeout through real activity.
        try (OrderWatch.Watch waiting =
                     watch.watch("ORD-3", state -> state == OrderState.PENDING_CANCEL)) {

            ctx.emit(OrderEvents.QUANTITY_CHANGED, new OutboundEvent.QuantityChanged(
                    "ORD-3", 1, OrderState.PENDING_CANCEL, 0, 300));

            assertEquals(OrderState.PENDING_CANCEL, waiting.await(2000));
        }
    }

    @Test
    @DisplayName("nothing arriving is reported as nothing, not as a failure")
    void aSilentVenueTimesOutQuietly() {
        Context ctx = new Context();
        OrderWatch watch = new OrderWatch(ctx);

        try (OrderWatch.Watch waiting = watch.watch("ORD-4", state -> true)) {
            // An order the venue has not answered is a real situation. Throwing
            // here would leave the caller unable to see the order at all.
            assertNull(waiting.await(120));
        }
    }

    @Test
    @DisplayName("a closed watch leaves nothing behind")
    void closingReleasesTheRegistration() {
        Context ctx = new Context();
        OrderWatch watch = new OrderWatch(ctx);

        OrderWatch.Watch waiting = watch.watch("ORD-5", state -> true);
        assertEquals(1, watch.outstanding());

        waiting.close();
        assertEquals(0, watch.outstanding(),
                "an abandoned registration would catch the answer meant for a later call");
    }

    @Test
    @DisplayName("an answer that arrives while the caller is already blocked reaches it")
    void anAnswerDuringTheWaitReachesTheCaller() throws Exception {
        Context ctx = new Context();
        OrderWatch watch = new OrderWatch(ctx);

        try (OrderWatch.Watch waiting = watch.watch("ORD-6", state -> state == OrderState.NEW)) {
            // The realistic shape: the caller is blocked, and the venue's
            // answer arrives on the session thread.
            CompletableFuture<OrderState> got =
                    CompletableFuture.supplyAsync(() -> waiting.await(3000));

            Thread.sleep(80);
            ctx.emit(OrderEvents.STATE_CHANGED, new OutboundEvent.StateChanged(
                    "ORD-6", 1, OrderState.PENDING_NEW, OrderState.NEW,
                    OrderEventType.ACK, "acked", 0));

            assertEquals(OrderState.NEW, got.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("two orders in flight do not answer for each other")
    void watchesAreIndependent() {
        Context ctx = new Context();
        OrderWatch watch = new OrderWatch(ctx);

        try (OrderWatch.Watch first = watch.watch("ORD-A", state -> true);
             OrderWatch.Watch second = watch.watch("ORD-B", state -> true)) {

            ctx.emit(OrderEvents.STATE_CHANGED, new OutboundEvent.StateChanged(
                    "ORD-A", 1, OrderState.PENDING_NEW, OrderState.NEW,
                    OrderEventType.ACK, "acked", 0));

            assertEquals(OrderState.NEW, first.await(2000));
            assertNull(second.await(120), "ORD-B was never answered");
        }
    }
}
