package io.nexum.ai;

import io.nexum.core.Context;
import io.nexum.order.OrderEvents;
import io.nexum.order.OrderState;
import io.nexum.order.OutboundEvent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Waits for an order to reach a state worth reporting.
 *
 * <p>FIX is asynchronous: a send returns while the order is still PENDING_NEW,
 * and what the caller actually wants to know — did the venue take it — arrives
 * later on a different thread. A caller left to poll has to guess how long to
 * wait, and a model doing the guessing burns a turn on each attempt.
 *
 * <p>So a caller registers interest before sending, and is woken when the order
 * settles into something it can act on. The registration happens first on
 * purpose: a venue that answers in under a millisecond would otherwise deliver
 * its answer before anyone was listening.
 */
public final class OrderWatch {

    /** Who is waiting, and for what. */
    private record Waiter(Predicate<OrderState> until, CompletableFuture<OrderState> arrival) {}

    private final Map<String, Waiter> waiting = new ConcurrentHashMap<>();

    public OrderWatch(Context ctx) {
        // Both, because an order can settle either by changing state or by
        // having a request answered, and a waiter that listened to only one
        // would hang through the other.
        ctx.on(OrderEvents.STATE_CHANGED,
                (OutboundEvent.StateChanged changed) -> settle(changed.orderId(), changed.to()));

        ctx.on(OrderEvents.QUANTITY_CHANGED,
                (OutboundEvent.QuantityChanged changed) ->
                        settle(changed.orderId(), changed.state()));
    }

    /**
     * Watch an order, before whatever will move it has been sent.
     *
     * @param until what the caller is waiting for. Called on the thread that
     *     delivered the event, so it must not block.
     * @return a handle to wait on, which must be closed even when the wait
     *     times out — an abandoned registration leaks and, worse, would catch
     *     the answer meant for a later call on the same order.
     */
    public Watch watch(String orderId, Predicate<OrderState> until) {
        CompletableFuture<OrderState> arrival = new CompletableFuture<>();
        waiting.put(orderId, new Waiter(until, arrival));
        return new Watch(orderId, arrival);
    }

    /** A registered interest in one order. */
    public final class Watch implements AutoCloseable {

        private final String orderId;
        private final CompletableFuture<OrderState> arrival;

        private Watch(String orderId, CompletableFuture<OrderState> arrival) {
            this.orderId = orderId;
            this.arrival = arrival;
        }

        /**
         * Wait for the state, or give up.
         *
         * @return the state reached, or null when nothing arrived in time. A
         *     timeout is not an error: an order the venue has not answered yet
         *     is a real and reportable situation, and saying so beats throwing
         *     at a caller who then cannot see the order at all.
         */
        public OrderState await(long millis) {
            try {
                return arrival.get(millis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillWaiting) {
                return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } catch (java.util.concurrent.ExecutionException impossible) {
                // Nothing completes this exceptionally.
                return null;
            }
        }

        @Override
        public void close() {
            waiting.remove(orderId, waiting.get(orderId));
        }
    }

    private void settle(String orderId, OrderState state) {
        Waiter waiter = waiting.get(orderId);
        if (waiter == null) {
            return;
        }
        // Tested before completing so a waiter interested in a later state is
        // not woken by an earlier one it has to ignore.
        if (waiter.until().test(state)) {
            waiting.remove(orderId);
            waiter.arrival().complete(state);
        }
    }

    /** How many orders are being watched, for a test or a status page. */
    public int outstanding() {
        return waiting.size();
    }
}
