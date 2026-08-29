package io.nexum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One order, two session threads.
 *
 * <p>This is the ordinary case, not an edge one: a client's cancel arrives on
 * the client connection while the venue's fill arrives on the venue connection,
 * and the engine calls back on a thread per session. Nothing serialises them
 * except the order itself.
 *
 * <p>Repeated rather than run once, because a race that only sometimes loses is
 * still a race — a single green run proves very little.
 */
class ManagedOrderConcurrencyTest {

    private static final int SYMBOL = 55;
    private static final int PRICE = 44;

    @RepeatedTest(20)
    @DisplayName("a cancel and a fill arriving together leave a coherent order")
    void cancelAndFillTogether() throws Exception {
        ManagedOrder order = working();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            threads.submit(() -> {
                await(start);
                order.on(new InboundEvent.CancelRequested(10, "CXL-1", "OUR-1", "CLIENT-CXL"));
            });
            threads.submit(() -> {
                await(start);
                order.on(new InboundEvent.VenueReport(
                        10, OrderEventType.PARTIAL_FILL, 400, 600.0, "LSE-1", "OUR-1", Map.of()));
            });
            start.countDown();
            threads.shutdown();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }

        // Whichever won, the result has to be one of the two coherent outcomes
        // — never a state from one event with a quantity from the other.
        OrderState state = order.state();
        assertTrue(
                state == OrderState.PENDING_CANCEL || state == OrderState.PARTIALLY_FILLED,
                "ended in " + state + ", which neither ordering produces");
        assertEquals(400, order.cumQty(),
                "the fill must be counted exactly once whichever order they arrived in");
    }

    @RepeatedTest(20)
    @DisplayName("concurrent partial fills count each quantity once")
    void concurrentPartials() throws Exception {
        ManagedOrder order = working();
        int reports = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(reports);

        try (ExecutorService threads = Executors.newFixedThreadPool(4)) {
            for (int i = 1; i <= reports; i++) {
                double cumQty = i * 50.0;
                threads.submit(() -> {
                    await(start);
                    try {
                        order.on(new InboundEvent.VenueReport(
                                10, OrderEventType.PARTIAL_FILL, cumQty,
                                1000 - cumQty, "LSE-1", "OUR-1", Map.of()));
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            threads.shutdown();
        }

        // CumQty is cumulative, so the highest wins regardless of arrival order.
        // A lost update would leave it short; a double-count would overshoot.
        assertEquals(1000, order.cumQty());
        assertEquals(OrderState.PARTIALLY_FILLED, order.state());
    }

    @RepeatedTest(20)
    @DisplayName("a snapshot is never a mixture of two events")
    void snapshotsAreCoherent() throws Exception {
        ManagedOrder order = working();
        AtomicReference<String> broken = new AtomicReference<>();
        AtomicInteger taken = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            threads.submit(() -> {
                await(start);
                for (int i = 1; i <= 200; i++) {
                    order.on(new InboundEvent.VenueReport(
                            10, OrderEventType.PARTIAL_FILL, i * 5.0,
                            1000 - i * 5.0, "LSE-1", "OUR-1", Map.of()));
                }
            });
            threads.submit(() -> {
                await(start);
                for (int i = 0; i < 500; i++) {
                    Order snapshot = order.snapshot();
                    taken.incrementAndGet();
                    // A quantity above zero with no state to match it would mean
                    // a snapshot caught the order half-updated.
                    if (snapshot.cumQty() > 0
                            && snapshot.state() == OrderState.NEW) {
                        broken.compareAndSet(null,
                                "cumQty " + snapshot.cumQty() + " while state was NEW");
                    }
                }
            });
            start.countDown();
            threads.shutdown();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertTrue(taken.get() > 0, "no snapshots were taken");
        assertEquals(null, broken.get(), "a torn snapshot was observed: " + broken.get());
    }

    @Test
    @DisplayName("different orders do not contend with one another")
    void independentOrdersRunInParallel() throws Exception {
        int count = 50;
        ManagedOrder[] orders = new ManagedOrder[count];
        for (int i = 0; i < count; i++) {
            orders[i] = working();
        }

        CountDownLatch done = new CountDownLatch(count);
        try (ExecutorService threads = Executors.newFixedThreadPool(8)) {
            for (ManagedOrder order : orders) {
                threads.submit(() -> {
                    try {
                        for (int i = 1; i <= 100; i++) {
                            order.on(new InboundEvent.VenueReport(
                                    10, OrderEventType.PARTIAL_FILL, i * 10.0,
                                    1000 - i * 10.0, "LSE-1", "OUR-1", Map.of()));
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS),
                    "orders blocked each other; the lock is too coarse");
            threads.shutdown();
        }

        for (ManagedOrder order : orders) {
            assertEquals(1000, order.cumQty());
        }
    }

    @RepeatedTest(10)
    @DisplayName("a fill and a replace request together keep the order coherent")
    void replaceAndFillTogether() throws Exception {
        ManagedOrder order = working();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            threads.submit(() -> {
                await(start);
                order.on(new InboundEvent.ReplaceRequested(10, "AMD-1", "OUR-1", "CLIENT-RPL", Map.of(PRICE, "155.00")));
            });
            threads.submit(() -> {
                await(start);
                order.on(new InboundEvent.VenueReport(
                        10, OrderEventType.FILL, 1000, 0.0, "LSE-1", "OUR-1", Map.of()));
            });
            start.countDown();
            threads.shutdown();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
        }

        OrderState state = order.state();
        assertNotNull(state);
        if (state == OrderState.FILLED) {
            // A filled order has nothing left to replace, so no request may be
            // left hanging for a reply that will never come.
            assertTrue(order.pending().isEmpty(),
                    "a filled order kept a replace outstanding");
        } else {
            assertEquals(OrderState.PENDING_REPLACE, state);
        }
    }

    // ------------------------------------------------------------------

    private static ManagedOrder working() {
        ManagedOrder order = ManagedOrder.accept("ORD-1", new InboundEvent.ClientOrder(
                1, "OMS->FUNDX", "FUND_X", "CLIENT-1",
                Map.of(SYMBOL, "VOD", PRICE, "150.00", 38, "1000")));
        order.on(new InboundEvent.SentToVenue(2, "OMS->LSE", "OUR-1"));
        order.on(new InboundEvent.VenueReport(
                3, OrderEventType.ACK, 0, 1000.0, "LSE-1", "OUR-1", Map.of()));
        return order;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
