package io.nexum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Which ClOrdID the client is told after its order has been amended.
 *
 * <p>The order's own identity does not move: it is the day, the session and the
 * ClOrdID it arrived with, derived rather than allocated, and every index in
 * the cache leads back to it. What the client is *told* is a separate question,
 * and FIX 4.4 answers it: a report names the most recent ClOrdID, so once an
 * amendment is accepted, later reports carry the amended one. A client tracking
 * its order by the id it last sent otherwise sees fills for an id it has
 * already replaced.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ReplacedIdentityTest {

    private static final int SYMBOL = 55;
    private static final int PRICE = 44;
    private static final int QUANTITY = 38;

    /** An order that is on the market, ready to be amended. */
    private static ManagedOrder working() {
        ManagedOrder order = ManagedOrder.accept("ORD-1", new InboundEvent.ClientOrder(
                1, "OMS->FUNDX", "FUND_X", "CLIENT-1",
                Map.of(SYMBOL, "VOD", PRICE, "150.00", QUANTITY, "1000")));
        order.on(new InboundEvent.SentToVenue(2, "OMS->LSE", "OUR-1"));
        order.on(new InboundEvent.VenueReport(
                3, OrderEventType.ACK, 0, 1000.0, "LSE-1", "OUR-1", Map.of()));
        return order;
    }

    /** Amend it, and have the venue confirm. */
    private static ManagedOrder amended() {
        ManagedOrder order = working();
        order.on(new InboundEvent.ReplaceRequested(
                4, "OUR-2", "OUR-1", "CLIENT-2",
                Map.of(QUANTITY, "800", PRICE, "151.00")));
        order.on(new InboundEvent.VenueReport(
                5, OrderEventType.REPLACED, 0, 800.0, "LSE-1", "OUR-2", Map.of()));
        return order;
    }

    private static OutboundEvent.ForwardToClient lastForward(List<OutboundEvent> events) {
        OutboundEvent.ForwardToClient found = null;
        for (OutboundEvent event : events) {
            if (event instanceof OutboundEvent.ForwardToClient forward) {
                found = forward;
            }
        }
        return found;
    }

    @Test
    @DisplayName("the confirmation answers under the id the amendment was sent with")
    void theConfirmationAnswersTheRequest() {
        ManagedOrder order = working();
        order.on(new InboundEvent.ReplaceRequested(
                4, "OUR-2", "OUR-1", "CLIENT-2",
                Map.of(QUANTITY, "800", PRICE, "151.00")));

        List<OutboundEvent> confirmed = order.on(new InboundEvent.VenueReport(
                5, OrderEventType.REPLACED, 0, 800.0, "LSE-1", "OUR-2", Map.of()));

        OutboundEvent.ForwardToClient forward = lastForward(confirmed);
        assertNotNull(forward, "the confirmation should reach the client");
        // A client matching a confirmation looks for the id it sent the
        // amendment with.
        assertEquals("CLIENT-2", forward.clientClOrdId());
        assertEquals("CLIENT-1", forward.origClOrdId(),
                "41 names the order the amendment replaced");
    }

    @Test
    @DisplayName("a fill after an amendment names the amended id")
    void aLaterFillNamesTheAmendedId() {
        ManagedOrder order = amended();

        List<OutboundEvent> filled = order.on(new InboundEvent.VenueReport(
                6, OrderEventType.PARTIAL_FILL, 300, 500.0, "LSE-1", "OUR-2", Map.of()));

        OutboundEvent.ForwardToClient forward = lastForward(filled);
        assertNotNull(forward, "the fill should reach the client");
        assertEquals("CLIENT-2", forward.clientClOrdId(),
                "FIX 4.4 has a report name the most recent ClOrdID, "
                        + "so a fill after an amendment carries the amended one");
    }

    @Test
    @DisplayName("a second amendment replaces the first, not the original")
    void aSecondAmendmentBuildsOnTheFirst() {
        ManagedOrder order = amended();

        order.on(new InboundEvent.ReplaceRequested(
                6, "OUR-3", "OUR-2", "CLIENT-3",
                Map.of(QUANTITY, "600", PRICE, "152.00")));
        List<OutboundEvent> confirmed = order.on(new InboundEvent.VenueReport(
                7, OrderEventType.REPLACED, 0, 600.0, "LSE-1", "OUR-3", Map.of()));

        OutboundEvent.ForwardToClient forward = lastForward(confirmed);
        assertNotNull(forward);
        assertEquals("CLIENT-3", forward.clientClOrdId());
        assertEquals("CLIENT-2", forward.origClOrdId(),
                "41 names what this amendment replaced, which is the first amendment");
    }

    @Test
    @DisplayName("the order's own identity does not move when it is amended")
    void theOrderIdIsUnchanged() {
        ManagedOrder order = amended();

        // The three-part id is what every index in the cache leads back to, and
        // it is derived from the order's arrival — an amendment is a later
        // event about the same order, not a different one.
        assertEquals("ORD-1", order.orderId());
    }

    @Test
    @DisplayName("a rejected amendment leaves the client on the id it had")
    void arefusedAmendmentDoesNotMoveTheId() {
        ManagedOrder order = working();
        order.on(new InboundEvent.ReplaceRequested(
                4, "OUR-2", "OUR-1", "CLIENT-2",
                Map.of(QUANTITY, "800", PRICE, "151.00")));
        order.on(new InboundEvent.VenueReport(
                5, OrderEventType.REPLACE_REFUSED, 0, 1000.0, "LSE-1", "OUR-2", Map.of()));

        List<OutboundEvent> filled = order.on(new InboundEvent.VenueReport(
                6, OrderEventType.PARTIAL_FILL, 200, 800.0, "LSE-1", "OUR-1", Map.of()));

        OutboundEvent.ForwardToClient forward = lastForward(filled);
        assertNotNull(forward);
        assertEquals("CLIENT-1", forward.clientClOrdId(),
                "an amendment the venue refused never took effect, so the client "
                        + "is still on the id it had");
    }
}
