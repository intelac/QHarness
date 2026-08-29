package io.nexum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order as the thing that decides: events in, events out, state held.
 *
 * <p>No transport, no journal, no wall clock — every time here is supplied, so
 * an entire order lifecycle runs in microseconds and the assertions are about
 * decisions rather than timing.
 */
class ManagedOrderTest {

    private static final int CL_ORD_ID = 11;
    private static final int SYMBOL = 55;
    private static final int PRICE = 44;
    private static final int ORDER_QTY = 38;

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("an order opens knowing nothing has been sent")
    class Opening {

        @Test
        void startsAsOurs() {
            ManagedOrder order = accepted();

            assertEquals(OrderState.CREATED, order.state());
            assertTrue(order.state().isOurs(),
                    "a created order is ours to resolve: the venue has not seen it");
            assertEquals(0, order.cumQty());
            assertTrue(order.pending().isEmpty());
        }

        @Test
        void keepsTheClientsOwnIdentifiers() {
            ManagedOrder order = accepted();

            assertEquals("CLIENT-1", order.clientView().clOrdId());
            assertEquals("VOD", order.clientView().field(SYMBOL));
            assertTrue(order.destinationView().isEmpty(),
                    "nothing has been sent, so there is no outbound view yet");
        }

        @Test
        void refusesASecondClientOrder() {
            ManagedOrder order = accepted();

            List<OutboundEvent> out = order.on(new InboundEvent.ClientOrder(
                    2, "S", "C", "CLIENT-2", Map.of()));

            assertTrue(hasA(out, OutboundEvent.Disagreement.class));
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("sending assigns our own identifiers")
    class Sending {

        @Test
        void movesToPendingAndMintsOurClOrdId() {
            ManagedOrder order = accepted();

            List<OutboundEvent> out = order.on(
                    new InboundEvent.SentToVenue(2, "OMS->LSE", "OUR-1"));

            assertEquals(OrderState.PENDING_NEW, order.state());
            assertEquals("OUR-1", order.destinationView().orElseThrow().clOrdId());
            assertEquals("CLIENT-1", order.clientView().clOrdId(),
                    "the client's own identifier is not overwritten");
            assertTrue(hasA(out, OutboundEvent.StateChanged.class));
        }

        @Test
        void aFailedSendClosesItLocally() {
            ManagedOrder order = accepted();

            // NOT_SENT rather than REJECTED: no venue saw this order, and the
            // two call for opposite responses — a refusal is about the order,
            // this is about us.
            order.on(new InboundEvent.SendFailed(2, "no route to host"));

            assertEquals(OrderState.NOT_SENT, order.state());
        }

        @Test
        void sendingTwiceIsRecognisedAsARepeat() {
            ManagedOrder order = sent();

            List<OutboundEvent> out = order.on(
                    new InboundEvent.SentToVenue(3, "OMS->LSE", "OUR-1"));

            assertEquals(OrderState.PENDING_NEW, order.state());
            assertTrue(hasA(out, OutboundEvent.Ignored.class));
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("reports from the venue")
    class Reports {

        @Test
        void anAckPutsItOnTheMarket() {
            ManagedOrder order = sent();

            List<OutboundEvent> out = order.on(ack(3, "LSE-1"));

            assertEquals(OrderState.NEW, order.state());
            assertTrue(order.state().isWorking());
            assertTrue(hasA(out, OutboundEvent.VenueIdLearned.class));
            assertTrue(hasA(out, OutboundEvent.ForwardToClient.class),
                    "every report reaches the client, whatever it did to the state");
        }

        @Test
        void theVenueIdIsLearnedOnceAndKept() {
            ManagedOrder order = working();

            List<OutboundEvent> again = order.on(
                    new InboundEvent.VenueReport(
                            5, OrderEventType.PARTIAL_FILL, 300, 700.0, "LSE-1", "OUR-1", Map.of()));

            assertFalse(hasA(again, OutboundEvent.VenueIdLearned.class),
                    "the venue id is news only the first time");
        }

        @Test
        void partialsAccumulate() {
            ManagedOrder order = working();

            order.on(partial(5, 300, 700));
            assertEquals(300, order.cumQty());

            order.on(partial(6, 800, 200));
            assertEquals(800, order.cumQty());
            assertEquals(OrderState.PARTIALLY_FILLED, order.state());
        }

        @Test
        void aRepeatedPartialIsIgnoredAndTheQuantityHolds() {
            ManagedOrder order = working();
            order.on(partial(5, 300, 700));

            List<OutboundEvent> out = order.on(partial(6, 300, 700));

            assertEquals(300, order.cumQty(), "a replayed partial must not double-count");
            assertTrue(hasA(out, OutboundEvent.Ignored.class));
        }

        @Test
        void aFillEndsIt() {
            ManagedOrder order = working();

            order.on(new InboundEvent.VenueReport(
                    5, OrderEventType.FILL, 1000, 0.0, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.FILLED, order.state());
            assertTrue(order.state().isTerminal());
            assertEquals(1000, order.cumQty());
        }

        @Test
        void aReplayedAckAfterTheFillChangesNothing() {
            ManagedOrder order = working();
            order.on(new InboundEvent.VenueReport(
                    5, OrderEventType.FILL, 1000, 0.0, "LSE-1", "OUR-1", Map.of()));

            List<OutboundEvent> out = order.on(ack(6, "LSE-1"));

            assertEquals(OrderState.FILLED, order.state(),
                    "a late ack must not pull a filled order back to working");
            assertTrue(hasA(out, OutboundEvent.Ignored.class));
        }

        @Test
        void anImpossibleReportIsRaisedAndAppliedToNothing() {
            ManagedOrder order = working();

            List<OutboundEvent> out = order.on(new InboundEvent.VenueReport(
                    5, OrderEventType.ORDER_REJECTED, 0, null, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.NEW, order.state(),
                    "an order already working cannot be rejected");
            assertTrue(hasA(out, OutboundEvent.Disagreement.class));
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a cancel in flight")
    class Cancels {

        @Test
        void theRequestIsHeldUntilAnswered() {
            ManagedOrder order = working();

            List<OutboundEvent> out = order.on(
                    new InboundEvent.CancelRequested(5, "CXL-1", "OUR-1", "CLIENT-CXL"));

            assertEquals(OrderState.PENDING_CANCEL, order.state());
            assertTrue(order.pending().isPresent());
            assertTrue(order.pending().get().isCancel());
            assertTrue(hasA(out, OutboundEvent.RequestOutstanding.class));
        }

        @Test
        void aFillDuringACancelMovesQuantityAndKeepsTheRequest() {
            ManagedOrder order = cancelling();

            List<OutboundEvent> out = order.on(partial(6, 400, 600));

            assertEquals(OrderState.PENDING_CANCEL, order.state(),
                    "a fill does not answer a cancel; forgetting it would lose a reply"
                            + " the venue is still going to send");
            assertEquals(400, order.cumQty());
            assertTrue(order.pending().isPresent());
            assertTrue(hasA(out, OutboundEvent.QuantityChanged.class));
        }

        @Test
        void aCancelCannotUndoWhatAlreadyTraded() {
            ManagedOrder order = cancelling();
            order.on(partial(6, 300, 600));

            // Venues do report zero here. Taking it at face value would tell a
            // desk its position vanished when the order was cancelled — the
            // cancel stops the remainder, it does not unwind the fills.
            order.on(new InboundEvent.VenueReport(
                    7, OrderEventType.CANCELLED, 0, 0.0, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.CANCELED, order.state());
            assertEquals(300, order.cumQty(),
                    "the quantity already filled must survive the cancel");
        }

        @Test
        void anAcceptedCancelClearsTheRequest() {
            ManagedOrder order = cancelling();

            List<OutboundEvent> out = order.on(new InboundEvent.VenueReport(
                    6, OrderEventType.CANCELLED, 0, 0.0, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.CANCELED, order.state());
            assertTrue(order.pending().isEmpty());
            assertTrue(answered(out, true));
        }

        @Test
        void aRefusedCancelClearsTheRequestButLeavesItWorking() {
            ManagedOrder order = cancelling();

            List<OutboundEvent> out = order.on(new InboundEvent.VenueReport(
                    6, OrderEventType.CANCEL_REFUSED, 0, null, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.CANCEL_REJECTED, order.state());
            assertTrue(order.state().isWorking(),
                    "\"cancel rejected\" reads like an ending and is the opposite");
            assertTrue(order.pending().isEmpty());
            assertTrue(answered(out, false));
        }

        @Test
        void aFillThatBeatsTheCancelDropsTheRequest() {
            ManagedOrder order = cancelling();

            List<OutboundEvent> out = order.on(new InboundEvent.VenueReport(
                    6, OrderEventType.FILL, 1000, 0.0, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.FILLED, order.state());
            assertTrue(order.pending().isEmpty(),
                    "nothing is left for the venue to cancel");
            assertTrue(answered(out, false));
        }

        @Test
        void theOrderCanBeCancelledAgainAfterARefusal() {
            ManagedOrder order = cancelling();
            order.on(new InboundEvent.VenueReport(
                    6, OrderEventType.CANCEL_REFUSED, 0, null, "LSE-1", "OUR-1", Map.of()));

            order.on(new InboundEvent.CancelRequested(7, "CXL-2", "OUR-1", "CLIENT-CXL"));

            assertEquals(OrderState.PENDING_CANCEL, order.state());
            assertEquals("CXL-2", order.pending().orElseThrow().clOrdId());
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a replace in flight")
    class Replaces {

        @Test
        void requestedTermsAreNotInForceUntilAccepted() {
            ManagedOrder order = amending();

            assertEquals("150.00", order.destinationView().orElseThrow().field(PRICE),
                    "showing the requested price before the venue agrees would report"
                            + " an exposure that does not exist");
            assertEquals("155.00", order.pending().orElseThrow().requested(PRICE));
        }

        @Test
        void acceptanceMakesTheRequestedTermsCurrent() {
            ManagedOrder order = amending();

            List<OutboundEvent> out = order.on(new InboundEvent.VenueReport(
                    7, OrderEventType.REPLACED, 0, null, "LSE-1", "OUR-1", Map.of()));

            assertEquals("155.00", order.destinationView().orElseThrow().field(PRICE));
            assertEquals("1500", order.destinationView().orElseThrow().field(ORDER_QTY));
            assertTrue(hasA(out, OutboundEvent.TermsAmended.class));
            assertTrue(order.pending().isEmpty());
        }

        @Test
        void refusalLeavesTheOriginalTermsAlone() {
            ManagedOrder order = amending();

            List<OutboundEvent> out = order.on(new InboundEvent.VenueReport(
                    7, OrderEventType.REPLACE_REFUSED, 0, null, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.REPLACE_REJECTED, order.state());
            assertEquals("150.00", order.destinationView().orElseThrow().field(PRICE));
            assertFalse(hasA(out, OutboundEvent.TermsAmended.class));
            assertTrue(answered(out, false));
        }

        @Test
        void aFillDuringAReplaceKeepsTheRequest() {
            ManagedOrder order = amending();

            order.on(partial(7, 400, 600));

            assertEquals(OrderState.PENDING_REPLACE, order.state());
            assertTrue(order.pending().isPresent());
            assertEquals(400, order.cumQty());
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a whole lifecycle, one event at a time")
    class Lifecycle {

        @Test
        void clientOrderThroughToFill() {
            ManagedOrder order = accepted();
            assertEquals(OrderState.CREATED, order.state());

            order.on(new InboundEvent.SentToVenue(2, "OMS->LSE", "OUR-1"));
            assertEquals(OrderState.PENDING_NEW, order.state());

            order.on(ack(3, "LSE-1"));
            assertEquals(OrderState.NEW, order.state());

            order.on(partial(4, 300, 700));
            assertEquals(OrderState.PARTIALLY_FILLED, order.state());

            order.on(partial(5, 700, 300));
            assertEquals(700, order.cumQty());

            order.on(new InboundEvent.VenueReport(
                    6, OrderEventType.FILL, 1000, 0.0, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.FILLED, order.state());
            assertEquals(1000, order.cumQty());
            assertEquals(4, order.reportCount());
        }

        @Test
        void snapshotCarriesEverythingNeededToRestore() {
            ManagedOrder order = cancelling();
            order.on(partial(6, 400, 600));

            Order stored = order.snapshot();
            ManagedOrder restored = ManagedOrder.restore(stored, 7);

            assertEquals(order.state(), restored.state());
            assertEquals(order.cumQty(), restored.cumQty());
            assertEquals(order.pending().orElseThrow().clOrdId(),
                    restored.pending().orElseThrow().clOrdId(),
                    "an outstanding cancel must survive a restart, or its reply"
                            + " arrives against an order that has forgotten it");
        }

        @Test
        void aRestoredOrderCarriesOn() {
            ManagedOrder restored = ManagedOrder.restore(cancelling().snapshot(), 10);

            restored.on(new InboundEvent.VenueReport(
                    11, OrderEventType.CANCELLED, 0, 0.0, "LSE-1", "OUR-1", Map.of()));

            assertEquals(OrderState.CANCELED, restored.state());
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ManagedOrder accepted() {
        return ManagedOrder.accept("ORD-1", new InboundEvent.ClientOrder(
                1, "OMS->FUNDX", "FUND_X", "CLIENT-1",
                Map.of(CL_ORD_ID, "CLIENT-1", SYMBOL, "VOD",
                        PRICE, "150.00", ORDER_QTY, "1000")));
    }

    private static ManagedOrder sent() {
        ManagedOrder order = accepted();
        order.on(new InboundEvent.SentToVenue(2, "OMS->LSE", "OUR-1"));
        return order;
    }

    private static ManagedOrder working() {
        ManagedOrder order = sent();
        order.on(ack(3, "LSE-1"));
        return order;
    }

    private static ManagedOrder cancelling() {
        ManagedOrder order = working();
        order.on(new InboundEvent.CancelRequested(5, "CXL-1", "OUR-1", "CLIENT-CXL"));
        return order;
    }

    private static ManagedOrder amending() {
        ManagedOrder order = working();
        order.on(new InboundEvent.ReplaceRequested(6, "AMD-1", "OUR-1", "CLIENT-RPL", Map.of(PRICE, "155.00", ORDER_QTY, "1500")));
        return order;
    }

    private static InboundEvent.VenueReport ack(long at, String venueOrderId) {
        return new InboundEvent.VenueReport(
                at, OrderEventType.ACK, 0, null, venueOrderId, "OUR-1", Map.of());
    }

    private static InboundEvent.VenueReport partial(long at, double cumQty, double leaves) {
        return new InboundEvent.VenueReport(
                at, OrderEventType.PARTIAL_FILL, cumQty, leaves, "LSE-1", "OUR-1", Map.of());
    }

    private static boolean hasA(List<OutboundEvent> events, Class<?> type) {
        return events.stream().anyMatch(type::isInstance);
    }

    private static boolean answered(List<OutboundEvent> events, boolean accepted) {
        return events.stream()
                .filter(OutboundEvent.RequestAnswered.class::isInstance)
                .map(OutboundEvent.RequestAnswered.class::cast)
                .anyMatch(event -> event.accepted() == accepted);
    }
}
