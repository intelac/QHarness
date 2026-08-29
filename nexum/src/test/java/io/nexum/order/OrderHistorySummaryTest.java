package io.nexum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a history reads as a sequence of transitions.
 *
 * <p>It did not: each kind of entry rendered itself its own way, and a state
 * change showed only where the order arrived. Reading a bare "CANCELED" left
 * open whether it had been working, held, or already part filled.
 */
class OrderHistorySummaryTest {

    private static final String ARROW = "  →  ";

    @Test
    @DisplayName("the ordinary life of an order reads as one sequence")
    void anOrdinaryLifeReadsAsASequence() {
        assertEquals("new order single" + ARROW + "pending new",
                created("PENDING_NEW").summary());

        assertEquals("execution report: new" + ARROW + "on market",
                state("ACK", "NEW", null, "0").summary());

        assertEquals("execution report: partial fill  300" + ARROW + "partial fill",
                state("PARTIAL_FILL", "PARTIALLY_FILLED", "300", "300").summary());

        assertEquals("execution report: fill  600" + ARROW + "fully filled",
                state("FILL", "FILLED", "600", "900").summary());
    }

    @Test
    @DisplayName("a created order shows where it starts")
    void aCreatedOrderShowsItsFirstState() {
        // The journal records no state for creation — there was no decision to
        // record. Showing nothing would open the history with a row that
        // reads as a gap.
        assertEquals("new order single" + ARROW + "pending new",
                created(null).summary());
    }

    @Test
    @DisplayName("a cancel reads the same way")
    void aCancelReadsTheSameWay() {
        assertEquals("order cancel request" + ARROW + "pending cancel",
                request("CANCEL", "PENDING_CANCEL").summary());

        assertEquals("execution report: cancelled" + ARROW + "cancelled",
                state("CANCELLED", "CANCELED", null, "0").summary());
    }

    @Test
    @DisplayName("an amend reads the same way")
    void anAmendReadsTheSameWay() {
        assertEquals("order cancel/replace request" + ARROW + "pending amend",
                request("REPLACE", "PENDING_REPLACE").summary());

        assertEquals("execution report: replaced" + ARROW + "amended",
                state("REPLACED", "REPLACED", null, "0").summary());
    }

    @Test
    @DisplayName("a request shows the state it puts the order into")
    void aRequestShowsItsPendingState() {
        // Like creation, sending a request is a move this system makes itself
        // and the journal records no state for it.
        assertEquals("order cancel request" + ARROW + "pending cancel",
                request("CANCEL", null).summary());
        assertEquals("order cancel/replace request" + ARROW + "pending amend",
                request("REPLACE", null).summary());
    }

    @Test
    @DisplayName("the event and the state are available apart")
    void theHalvesAreAvailableSeparately() {
        // Seven event names are also state names. A display that gets one
        // string has to guess where the event stops; these do not.
        OrderHistory.Entry fill = state("PARTIAL_FILL", "PARTIALLY_FILLED", "300", "300");

        assertEquals("execution report: partial fill  300", fill.event());
        assertEquals("partial fill", fill.state());

        // The worst pair: REPLACED is both an event and a state.
        OrderHistory.Entry replaced = state("REPLACED", "REPLACED", null, "0");
        assertEquals("execution report: replaced", replaced.event());
        assertEquals("amended", replaced.state());
    }

    @Test
    @DisplayName("bookkeeping is not a transition")
    void bookkeepingIsNotATransition() {
        // The journal holds these because recovery needs them. A history read
        // by a person does not: they sit between the transitions and break the
        // sequence someone is trying to follow.
        assertFalse(new OrderHistory.Entry(
                1, "venue-id", Map.of("venueOrderId", "SIM-5"), null).isTransition());

        // The report that answered the request moved the state a moment
        // earlier, so this is the same event told twice.
        assertFalse(new OrderHistory.Entry(
                1, "request-answered", Map.of("accepted", "false"), null).isTransition());

        assertTrue(created(null).isTransition());
        assertTrue(request("CANCEL", null).isTransition());
        assertTrue(state("ACK", "NEW", null, "0").isTransition());
    }

    @Test
    @DisplayName("an order's life reads as one transition per row")
    void theSequenceHasNoGapsAndNoNoise() {
        // The whole point, in the shape it is read: every row is a move, and
        // every move names where it left the order.
        List<OrderHistory.Entry> journal = List.of(
                created(null),
                new OrderHistory.Entry(2, "venue-id", Map.of("venueOrderId", "SIM-5"), null),
                state("ACK", "NEW", null, "0"),
                state("PARTIAL_FILL", "PARTIALLY_FILLED", "300", "300"),
                request("CANCEL", null),
                state("CANCELLED", "CANCELED", null, "300"),
                new OrderHistory.Entry(6, "request-answered", Map.of("accepted", "true"), null));

        List<String> shown = journal.stream()
                .filter(OrderHistory.Entry::isTransition)
                .map(OrderHistory.Entry::summary)
                .toList();

        assertEquals(List.of(
                "new order single" + ARROW + "pending new",
                "execution report: new" + ARROW + "on market",
                "execution report: partial fill  300" + ARROW + "partial fill",
                "order cancel request" + ARROW + "pending cancel",
                "execution report: cancelled  300" + ARROW + "cancelled"),
                shown);
    }

    @Test
    @DisplayName("quantities lose their trailing zeros")
    void quantitiesAreNotShownAsDecimals() {
        assertEquals("execution report: fill  900" + ARROW + "fully filled",
                state("FILL", "FILLED", "900.0", "900.0").summary());
    }

    @Test
    @DisplayName("an event the vocabulary has never seen still shows itself")
    void anUnknownEventStillShowsItself() {
        // The state machine can grow an event this rendering has never been
        // told about. It must still read as something, not vanish.
        assertEquals("execution report: accepted for bidding" + ARROW + "accepted for bidding",
                state("ACCEPTED_FOR_BIDDING", "ACCEPTED_FOR_BIDDING", null, null).summary());
    }

    // ------------------------------------------------------------------

    private static OrderHistory.Entry created(String state) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("client", "FUND_X");
        fields.put("destination", "OMS->LSE");
        if (state != null) {
            fields.put("state", state);
        }
        return new OrderHistory.Entry(1, "created", fields, null);
    }

    private static OrderHistory.Entry state(
            String cause, String to, String lastQty, String cumQty) {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("cause", cause);
        fields.put("state", to);
        if (lastQty != null) {
            fields.put("lastQty", lastQty);
        }
        if (cumQty != null) {
            fields.put("cumQty", cumQty);
        }
        return new OrderHistory.Entry(1, "state", fields, null);
    }

    private static OrderHistory.Entry request(String kind, String state) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", kind);
        if (state != null) {
            fields.put("state", state);
        }
        return new OrderHistory.Entry(1, "request", fields, null);
    }
}
