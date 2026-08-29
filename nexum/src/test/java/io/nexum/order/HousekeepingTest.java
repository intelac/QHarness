package io.nexum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What gets released, and what is deliberately kept.
 *
 * <p>Both maps here grew for the life of the process. A leak like that is
 * invisible in a test that places three orders and shows up after the system has
 * been up long enough to matter — which is why the release is asserted rather
 * than assumed.
 */
class HousekeepingTest {

    private static final long RETAIN = Duration.ofMinutes(30).toMillis();
    private static final int CL_ORD_ID = 11;

    @Test
    @DisplayName("a finished order is released once its retention window passes")
    void settledOrdersAreReleased() {
        OrderBook book = new OrderBook();
        ManagedOrder order = fill(book, "FX-1");

        long wellAfter = order.lastEventAt() + RETAIN + 1;
        List<String> dropped = book.evictSettled(wellAfter, RETAIN);

        assertEquals(1, dropped.size());
        assertEquals(0, book.size());
    }

    @Test
    @DisplayName("a finished order is kept while its window is open")
    void recentlySettledOrdersAreKept() {
        OrderBook book = new OrderBook();
        ManagedOrder order = fill(book, "FX-1");

        // The last report is rarely the last message: a correction, a bust or a
        // resend follows, and an order dropped too eagerly makes those look
        // like reports for something that never existed.
        List<String> dropped = book.evictSettled(order.lastEventAt() + 1_000, RETAIN);

        assertTrue(dropped.isEmpty());
        assertEquals(1, book.size());
    }

    @Test
    @DisplayName("a working order is never released, however old")
    void workingOrdersAreNeverReleased() {
        OrderBook book = new OrderBook();
        OrderId id = OrderId.of(java.time.LocalDate.now(), "OMS->FUNDX", "FX-1");
        ManagedOrder order = book.open(id, clientOrder("FX-1", 1));
        order.on(new InboundEvent.SentToVenue(2, "OMS->LSE", "O0000001"));
        order.on(ack(3));

        List<String> dropped = book.evictSettled(
                System.currentTimeMillis() + Duration.ofDays(7).toMillis(), RETAIN);

        assertTrue(dropped.isEmpty(), "a live order is not housekeeping's to remove");
        assertEquals(1, book.size());
    }

    @Test
    @DisplayName("releasing an order releases every identifier it answered to")
    void identifiersAreReleasedWithTheOrder() {
        OrderIdResolver resolver = new OrderIdResolver(ZoneOffset.UTC);
        OrderId placed = resolver.forNewOrder(
                io.nexum.message.FixMessage.of("D", Map.of(CL_ORD_ID, "FX-1")),
                "OMS->FUNDX");
        // A replace introduces a further identifier for the same order.
        resolver.alsoKnownAs(placed, "OMS->FUNDX", "FX-1-AMD");
        resolver.alsoKnownAs(placed, "OMS->FUNDX", "FX-1-AMD-2");
        assertEquals(3, resolver.knownAliases());

        int released = resolver.forget(placed);

        assertEquals(3, released);
        assertEquals(0, resolver.knownAliases(),
                "every ClOrdID the client used has to go, or the map grows for the"
                        + " life of the process");
    }

    @Test
    @DisplayName("releasing one order leaves another's identifiers alone")
    void releasingOneOrderDoesNotTouchAnother() {
        OrderIdResolver resolver = new OrderIdResolver(ZoneOffset.UTC);
        OrderId first = resolver.forNewOrder(
                io.nexum.message.FixMessage.of("D", Map.of(CL_ORD_ID, "FX-1")),
                "OMS->FUNDX");
        OrderId second = resolver.forNewOrder(
                io.nexum.message.FixMessage.of("D", Map.of(CL_ORD_ID, "FX-2")),
                "OMS->FUNDX");

        resolver.forget(first);

        assertEquals(1, resolver.knownAliases());
        assertTrue(resolver.forAmendment(
                io.nexum.message.FixMessage.of("F", Map.of(41, "FX-2")),
                "OMS->FUNDX").isPresent());
        assertFalse(resolver.forAmendment(
                io.nexum.message.FixMessage.of("F", Map.of(41, "FX-1")),
                "OMS->FUNDX").isPresent());
    }

    // ------------------------------------------------------------------

    private static ManagedOrder fill(OrderBook book, String clOrdId) {
        OrderId id = OrderId.of(java.time.LocalDate.now(), "OMS->FUNDX", clOrdId);
        ManagedOrder order = book.open(id, clientOrder(clOrdId, 1));
        order.on(new InboundEvent.SentToVenue(2, "OMS->LSE", "O0000001"));
        order.on(ack(3));
        order.on(new InboundEvent.VenueReport(
                4, OrderEventType.FILL, 1000, 0.0, "LSE-1", "O0000001", Map.of()));
        return order;
    }

    private static InboundEvent.ClientOrder clientOrder(String clOrdId, long at) {
        return new InboundEvent.ClientOrder(
                at, "OMS->FUNDX", "FUND_X", clOrdId,
                Map.of(CL_ORD_ID, clOrdId, 55, "VOD", 38, "1000"));
    }

    private static InboundEvent.VenueReport ack(long at) {
        return new InboundEvent.VenueReport(
                at, OrderEventType.ACK, 0, 1000.0, "LSE-1", "O0000001", Map.of());
    }
}
