package io.nexum.routing;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.message.FixMessage;
import io.nexum.order.ManagedOrder;
import io.nexum.order.OrderBook;
import io.nexum.order.OrderCache;
import io.nexum.order.OrderEvents;
import io.nexum.order.OrderState;
import io.nexum.transport.RecordingTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole path, from a client message to what leaves and what is recorded.
 *
 * <p>The per-order tests cover what an order concludes; this covers what the
 * system does with those conclusions — routing, identifier translation, and the
 * report that goes back. Run over a recording transport rather than sockets, so
 * a full lifecycle takes microseconds and a venue's timing can be chosen.
 */
class OrderFlowTest {

    private static final int CL_ORD_ID = 11;
    private static final int ORIG_CL_ORD_ID = 41;
    private static final int ORDER_ID = 37;
    private static final int SYMBOL = 55;
    private static final int SIDE = 54;
    private static final int ORDER_QTY = 38;
    private static final int PRICE = 44;
    private static final int ORD_STATUS = 39;
    private static final int EXEC_TYPE = 150;
    private static final int LEAVES_QTY = 151;
    private static final int CUM_QTY = 14;
    private static final int ON_BEHALF_OF = 115;
    private static final int CXL_REJ_RESPONSE_TO = 434;

    private static final String CLIENT_SESSION = "OMS->FUNDX";
    private static final String VENUE_SESSION = "OMS->LSE";

    private static final String CONFIG = """
            monitor:
              enabled: false

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
              - id: OMS->LSE
                version: FIX.4.4

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->LSE
                fingerprint: any
            """;

    private Context ctx;
    private PluginLoader loader;
    private RecordingTransport transport;
    private OrderBook book;
    private OrderCache cache;
    private final List<String> seen = new ArrayList<>();

    @BeforeEach
    void start() {
        ctx = new Context();
        transport = new RecordingTransport(CLIENT_SESSION, VENUE_SESSION);
        loader = Bootstrap.from(CONFIG).with(transport).start(ctx);
        book = ctx.get("book");
        cache = ctx.get("orders");

        // Subscribed through the declared keys: the compiler now binds the name
        // and the payload type, so a rename cannot leave this watching an event
        // nobody publishes.
        watch(OrderEvents.STATE_CHANGED);
        watch(OrderEvents.QUANTITY_CHANGED);
        watch(OrderEvents.REPORT_IGNORED);
        watch(OrderEvents.DISAGREEMENT);
        watch(OrderEvents.REQUEST_UNKNOWN);
        watch(OrderEvents.REPORT_UNMATCHED);
        watch(OrderEvents.REQUEST_ANSWERED);
        watch(RoutingEvents.RULE_UNMATCHED);
    }

    /** Record every occurrence of one declared event, whatever its payload. */
    private <T> void watch(io.nexum.core.EventKey<T> key) {
        ctx.on(key, (T payload) -> seen.add(key.name()));
    }

    @AfterEach
    void stop() {
        loader.unloadAll();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a new order")
    class NewOrders {

        @Test
        void reachesTheVenueUnderOurOwnIdentifier() {
            send(newOrder("FX-1", "VOD", 1000));

            RecordingTransport.Sent outbound = transport.to(VENUE_SESSION).get(0);
            assertEquals("D", outbound.message().msgType());
            assertNotEquals("FX-1", outbound.field(CL_ORD_ID),
                    "the venue must see our identifier, not the client's");
            assertTrue(outbound.field(CL_ORD_ID).length() <= 20,
                    "ClOrdID is commonly capped around twenty characters");
            assertEquals("VOD", outbound.field(SYMBOL));
        }

        @Test
        void isIdentifiedByDaySessionAndClientClOrdId() {
            send(newOrder("FX-1", "VOD", 1000));

            ManagedOrder order = onlyOrder();
            assertTrue(order.orderId().endsWith(":" + CLIENT_SESSION + ":FX-1"),
                    "the identity should read day:session:clOrdId but was " + order.orderId());
            assertEquals(OrderState.PENDING_NEW, order.state());
        }

        @Test
        void anUnroutableClientIsNotSentAnywhere() {
            FixMessage order = newOrder("FX-1", "VOD", 1000).set(ON_BEHALF_OF, "STRANGER");
            send(order);

            assertEquals(0, transport.to(VENUE_SESSION).size());
            assertTrue(sawEvent(RoutingEvents.RULE_UNMATCHED));
        }

        @Test
        void aRefusedSendClosesTheOrderLocally() {
            transport.takeDown(VENUE_SESSION);
            send(newOrder("FX-1", "VOD", 1000));

            // Nothing reached a venue, so this is ours to resolve — the
            // distinction between chasing our own send path and calling a
            // broker. NOT_SENT says exactly that; REJECTED would claim a venue
            // looked at the order and refused it.
            assertEquals(OrderState.NOT_SENT, onlyOrder().state());
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("execution reports")
    class Reports {

        @Test
        void anAckPutsTheOrderOnTheMarketAndReachesTheClient() {
            send(newOrder("FX-1", "VOD", 1000));
            String ourId = venueClOrdId();
            transport.clear();

            deliverFromVenue(execReport(ourId, "0", "0", 0, 1000, "LSE-1"));

            assertEquals(OrderState.NEW, onlyOrder().state());
            RecordingTransport.Sent toClient = transport.to(CLIENT_SESSION).get(0);
            assertEquals("FX-1", toClient.field(CL_ORD_ID),
                    "the client must see the identifier it chose");
        }

        @Test
        void partialsAccumulateAndTheOrderFills() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);

            deliverFromVenue(execReport(ourId, "F", "1", 400, 600, "LSE-1"));
            assertEquals(400, onlyOrder().cumQty());
            assertEquals(OrderState.PARTIALLY_FILLED, onlyOrder().state());

            deliverFromVenue(execReport(ourId, "F", "2", 1000, 0, "LSE-1"));
            assertEquals(1000, onlyOrder().cumQty());
            assertEquals(OrderState.FILLED, onlyOrder().state());
        }

        @Test
        void aReplayedReportDoesNotUndoAFill() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            deliverFromVenue(execReport(ourId, "F", "2", 1000, 0, "LSE-1"));

            // A resend replaying the ack after the order closed.
            deliverFromVenue(execReport(ourId, "0", "0", 0, 1000, "LSE-1"));

            assertEquals(OrderState.FILLED, onlyOrder().state(),
                    "a late ack must not pull a filled order back to working");
            assertTrue(sawEvent(OrderEvents.REPORT_IGNORED));
        }

        @Test
        void aReportForAnUnknownOrderIsSurfacedNotDropped() {
            deliverFromVenue(execReport("NEVER-SENT", "0", "0", 0, 100, "LSE-9"));

            assertTrue(sawEvent(OrderEvents.REPORT_UNMATCHED),
                    "an unmatched report is a real condition, not a parse failure");
        }

        @Test
        void theVenueOrderIdBecomesTheIndexOnceKnown() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);

            // Later reports resolve by OrderID(37), which is stable for the
            // order's life while ClOrdID changes on every amendment.
            assertTrue(cache.byVenueOrderId("LSE-1").isPresent());
            assertEquals(cache.byOurClOrdId(ourId).orElseThrow().orderId(),
                    cache.byVenueOrderId("LSE-1").orElseThrow().orderId());
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("market events that end the day without ending the order")
    class MarketEvents {

        @Test
        void doneForDayLeavesTheOrderRecoverableTomorrow() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            deliverFromVenue(execReport(ourId, "F", "1", 300, 700, "LSE-1"));

            deliverFromVenue(execReport(ourId, "3", "3", 300, 700, "LSE-1"));

            ManagedOrder order = onlyOrder();
            assertEquals(OrderState.DONE_FOR_DAY, order.state());
            assertFalse(order.state().isTerminal(),
                    "a multi-day order that finished today can trade tomorrow;"
                            + " closing it would drop it while it is still live at the venue");
            assertEquals(300, order.cumQty(), "the day's fills are kept");
        }

        @Test
        void anOrderResumesTradingAfterDoneForDay() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            deliverFromVenue(execReport(ourId, "3", "3", 0, 1000, "LSE-1"));

            deliverFromVenue(execReport(ourId, "0", "0", 0, 1000, "LSE-1"));

            assertEquals(OrderState.NEW, onlyOrder().state());
        }

        @Test
        void aSuspendedOrderIsHeldNotEnded() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);

            deliverFromVenue(execReport(ourId, "9", "9", 0, 1000, "LSE-1"));

            ManagedOrder order = onlyOrder();
            assertEquals(OrderState.SUSPENDED, order.state());
            assertFalse(order.state().isTerminal(), "a halt may lift");
        }

        @Test
        void aSuspendedOrderCanStillBeCancelled() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            deliverFromVenue(execReport(ourId, "9", "9", 0, 1000, "LSE-1"));
            transport.clear();

            send(cancelRequest("FX-1-CXL", "FX-1"));

            assertEquals(1, transport.to(VENUE_SESSION).size(),
                    "a held order is still the venue's to cancel");
            assertEquals(OrderState.PENDING_CANCEL, onlyOrder().state());
        }

        @Test
        void anExpiredOrderIsFinished() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            deliverFromVenue(execReport(ourId, "F", "1", 200, 800, "LSE-1"));

            deliverFromVenue(execReport(ourId, "C", "C", 200, 0, "LSE-1"));

            ManagedOrder order = onlyOrder();
            assertEquals(OrderState.EXPIRED, order.state());
            assertTrue(order.state().isTerminal());
            assertEquals(200, order.cumQty(), "what traded before it expired is kept");
        }

        @Test
        void aVenueRejectionEndsAnOrderThatNeverWorked() {
            send(newOrder("FX-1", "VOD", 1000));
            String ourId = venueClOrdId();

            deliverFromVenue(execReport(ourId, "8", "8", 0, 0, null));

            assertEquals(OrderState.REJECTED, onlyOrder().state());
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("cancel and replace")
    class Amendments {

        @Test
        void aCancelReachesTheVenueQuotingWhatItKnows() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            transport.clear();

            send(cancelRequest("FX-1-CXL", "FX-1"));

            RecordingTransport.Sent outbound = transport.to(VENUE_SESSION).get(0);
            assertEquals("F", outbound.message().msgType());
            assertEquals(ourId, outbound.field(ORIG_CL_ORD_ID),
                    "the venue is told the identifier it was given");
            assertEquals(OrderState.PENDING_CANCEL, onlyOrder().state());
        }

        @Test
        void aRefusedCancelLeavesTheOrderWorking() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            send(cancelRequest("FX-1-CXL", "FX-1"));
            String cancelId = transport.lastOfType("F").orElseThrow().field(CL_ORD_ID);

            deliverFromVenue(cancelReject(cancelId, ourId, "1"));

            ManagedOrder order = onlyOrder();
            assertEquals(OrderState.CANCEL_REJECTED, order.state());
            assertTrue(order.state().isWorking(),
                    "\"cancel rejected\" reads like an ending and is the opposite");
        }

        @Test
        void aFillDuringACancelKeepsTheRequestOutstanding() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            send(cancelRequest("FX-1-CXL", "FX-1"));

            deliverFromVenue(execReport(ourId, "F", "1", 400, 600, "LSE-1"));

            ManagedOrder order = onlyOrder();
            assertEquals(OrderState.PENDING_CANCEL, order.state(),
                    "a fill does not answer a cancel");
            assertEquals(400, order.cumQty());
            assertTrue(order.pending().isPresent());
        }

        @Test
        void aReplaceBeforeTheVenueHasAckedIsRefusedInWriting() {
            // The order is still pending new — the venue has never seen it, so
            // there is nothing there to amend. That was a return statement:
            // the request was neither forwarded nor answered, and a client
            // cannot tell that from one still being worked on.
            send(newOrder("FX-1", "VOD", 1000));
            transport.clear();

            send(replaceRequest("FX-1-AMD", "FX-1", 1500, "155.00"));

            RecordingTransport.Sent reject = transport.to(CLIENT_SESSION).get(0);
            assertEquals("9", reject.message().msgType(),
                    "a refused amendment is answered with a cancel reject");
            assertEquals("FX-1-AMD", reject.field(CL_ORD_ID),
                    "the reject names the request, which is what the client waits on");
            assertEquals("FX-1", reject.field(ORIG_CL_ORD_ID));
            assertEquals("2", reject.field(CXL_REJ_RESPONSE_TO),
                    "2 says it answers a replace; 1 would claim a cancel was refused");

            // Nothing went out to the venue: the point is that it could not.
            assertTrue(transport.to(VENUE_SESSION).isEmpty(),
                    "a refused request must not also be forwarded");
        }

        @Test
        void aSecondCancelWhileOneIsOutstandingIsRefusedInWriting() {
            // An order awaiting an ack can be cancelled — the cancel says "do
            // not send it" — so the refusal to provoke here is the second one:
            // there is already a cancel in flight, and this system does not
            // hold two.
            placeAndAck("FX-1", "VOD", 1000);
            send(cancelRequest("FX-1-CXL", "FX-1"));
            transport.clear();

            send(cancelRequest("FX-1-CXL2", "FX-1"));

            RecordingTransport.Sent reject = transport.to(CLIENT_SESSION).get(0);
            assertEquals("9", reject.message().msgType());
            assertEquals("FX-1-CXL2", reject.field(CL_ORD_ID),
                    "the reject names the request that was refused, not the one in flight");
            assertEquals("1", reject.field(CXL_REJ_RESPONSE_TO),
                    "1 says it answers a cancel");
            assertTrue(transport.to(VENUE_SESSION).isEmpty(),
                    "a refused request must not also be forwarded");
        }

        @Test
        void aRefusalSaysWhatTheOrderIsNow() {
            // A client has just been told what its order is not. OrdStatus(39)
            // is where it learns what the order still is — without it the
            // reject leaves the order's state to be guessed.
            send(newOrder("FX-1", "VOD", 1000));
            transport.clear();

            send(replaceRequest("FX-1-AMD", "FX-1", 1500, "155.00"));

            RecordingTransport.Sent reject = transport.to(CLIENT_SESSION).get(0);
            assertEquals(OrderState.PENDING_NEW.fixOrdStatus(), reject.field(ORD_STATUS),
                    "the order is still pending new, and the reject says so");
        }

        @Test
        void anAcceptedReplaceCarriesItsNewQuantity() {
            // The identifier advanced on an accepted replace and the terms did
            // not, so an order amended from 1000 to 1500 and filled in full was
            // reported as 1500 done of 1000 — a sum that says the record is
            // wrong without saying which half of it to believe.
            placeAndAck("FX-1", "VOD", 1000);
            send(replaceRequest("FX-1-AMD", "FX-1", 1500, "155.00"));
            String replaceId = transport.lastOfType("G").orElseThrow().field(CL_ORD_ID);

            deliverFromVenue(execReport(replaceId, "5", "5", 0, 1500, "LSE-1"));

            assertEquals("1500", onlyOrder().clientView().field(ORDER_QTY),
                    "the order is for what the venue agreed to, not what it was");
        }

        @Test
        void aCancelConfirmationNamesTheCancelAndWhatItCancelled() {
            // Which identifier belongs in which field is easy to get backwards,
            // and both readings sound right: the report is about the order, so
            // ClOrdID could name the order — but FIX answers a request with the
            // request's own id, and says which order it was about in
            // OrigClOrdID. A client matching on 11 is waiting for the cancel it
            // sent, not for the order it sent earlier.
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            send(cancelRequest("FX-1-CXL", "FX-1"));
            String cancelId = transport.lastOfType("F").orElseThrow().field(CL_ORD_ID);
            transport.clear();

            deliverFromVenue(execReport(cancelId, "4", "4", 300, 0, "LSE-1"));

            RecordingTransport.Sent toClient = transport.to(CLIENT_SESSION).get(0);
            assertEquals("FX-1-CXL", toClient.field(CL_ORD_ID),
                    "the confirmation answers the cancel, so it carries the cancel's id");
            assertEquals("FX-1", toClient.field(ORIG_CL_ORD_ID),
                    "and says which order was cancelled");

            // Both are the client's own identifiers. The venue saw neither.
            assertNotEquals(ourId, toClient.field(CL_ORD_ID));
            assertNotEquals(cancelId, toClient.field(CL_ORD_ID));
        }

        @Test
        void aCancelledOrderKeepsWhatItTraded() {
            // Cancelling stops the rest; it does not undo what was done. An
            // order reported as cancelled with nothing traded, after 300 had
            // traded, tells its client a position vanished.
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            deliverFromVenue(execReport(ourId, "F", "1", 300, 700, "LSE-1"));
            send(cancelRequest("FX-1-CXL", "FX-1"));
            String cancelId = transport.lastOfType("F").orElseThrow().field(CL_ORD_ID);
            transport.clear();

            deliverFromVenue(execReport(cancelId, "4", "4", 300, 0, "LSE-1"));

            assertEquals(OrderState.CANCELED, onlyOrder().state());
            assertEquals(300, onlyOrder().cumQty(),
                    "the 300 that traded still traded");
            assertEquals("300", transport.to(CLIENT_SESSION).get(0).field(CUM_QTY),
                    "and the client is told so");
        }

        @Test
        void anAcceptedReplaceMakesTheNewTermsCurrent() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            send(replaceRequest("FX-1-AMD", "FX-1", 1500, "155.00"));
            String replaceId = transport.lastOfType("G").orElseThrow().field(CL_ORD_ID);

            deliverFromVenue(execReport(replaceId, "5", "5", 0, 1500, "LSE-1"));

            ManagedOrder order = onlyOrder();
            assertEquals(OrderState.REPLACED, order.state());
            assertTrue(order.state().isWorking(), "a replaced order is live on new terms");
            assertEquals("155.00", order.destinationView().orElseThrow().field(PRICE));
        }

        @Test
        void aCancelAfterAReplaceQuotesTheIdentifierTheVenueNowKnows() {
            placeAndAck("FX-1", "VOD", 1000);
            send(replaceRequest("FX-1-AMD", "FX-1", 1500, "155.00"));
            String replaceId = transport.lastOfType("G").orElseThrow().field(CL_ORD_ID);
            deliverFromVenue(execReport(replaceId, "5", "5", 0, 1500, "LSE-1"));
            transport.clear();

            // The client may quote either identifier; both name the same order.
            send(cancelRequest("FX-1-CXL", "FX-1-AMD"));

            RecordingTransport.Sent outbound = transport.to(VENUE_SESSION).get(0);
            assertEquals(replaceId, outbound.field(ORIG_CL_ORD_ID),
                    "quoting the replaced identifier would be rejected as unknown");
        }

        @Test
        void anAmendmentForAnUnknownOrderIsRefusedWithoutInventingOne() {
            send(cancelRequest("CXL-1", "NEVER-PLACED"));

            assertEquals(0, transport.to(VENUE_SESSION).size());
            assertTrue(sawEvent(OrderEvents.REQUEST_UNKNOWN),
                    "building an identity from OrigClOrdID would make a typo resolve"
                            + " to an order that was never placed");
        }

        @Test
        void aCancelForATerminalOrderNeverReachesTheVenue() {
            String ourId = placeAndAck("FX-1", "VOD", 1000);
            deliverFromVenue(execReport(ourId, "F", "2", 1000, 0, "LSE-1"));
            transport.clear();

            send(cancelRequest("FX-1-CXL", "FX-1"));

            assertEquals(0, transport.to(VENUE_SESSION).size(),
                    "there is nothing left to cancel");
        }
    }

    // ------------------------------------------------------------------
    // Driving
    // ------------------------------------------------------------------

    private void send(FixMessage message) {
        transport.deliver(CLIENT_SESSION, message);
    }

    private void deliverFromVenue(FixMessage message) {
        transport.deliver(VENUE_SESSION, message);
    }

    /** Place an order, acknowledge it, and return the identifier the venue saw. */
    private String placeAndAck(String clOrdId, String symbol, double qty) {
        send(newOrder(clOrdId, symbol, qty));
        String ourId = venueClOrdId();
        deliverFromVenue(execReport(ourId, "0", "0", 0, qty, "LSE-1"));
        return ourId;
    }

    private String venueClOrdId() {
        return transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);
    }

    private ManagedOrder onlyOrder() {
        List<ManagedOrder> all = List.copyOf(book.all());
        assertEquals(1, all.size(), "expected exactly one order but found " + all.size());
        return all.get(0);
    }

    private boolean sawEvent(io.nexum.core.EventKey<?> key) {
        return seen.contains(key.name());
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    private static FixMessage newOrder(String clOrdId, String symbol, double qty) {
        return FixMessage.of("D", Map.of(
                CL_ORD_ID, clOrdId,
                ON_BEHALF_OF, "FUNDX",
                SYMBOL, symbol,
                SIDE, "1",
                ORDER_QTY, String.valueOf((long) qty),
                PRICE, "150.00"));
    }

    private static FixMessage cancelRequest(String clOrdId, String origClOrdId) {
        return FixMessage.of("F", Map.of(
                CL_ORD_ID, clOrdId,
                ORIG_CL_ORD_ID, origClOrdId,
                ON_BEHALF_OF, "FUNDX",
                SYMBOL, "VOD",
                SIDE, "1"));
    }

    private static FixMessage replaceRequest(
            String clOrdId, String origClOrdId, double newQty, String newPrice) {

        return FixMessage.of("G", Map.of(
                CL_ORD_ID, clOrdId,
                ORIG_CL_ORD_ID, origClOrdId,
                ON_BEHALF_OF, "FUNDX",
                SYMBOL, "VOD",
                SIDE, "1",
                ORDER_QTY, String.valueOf((long) newQty),
                PRICE, newPrice));
    }

    /**
     * @param execType ExecType(150), which is what the recognition reads
     * @param ordStatus OrdStatus(39), present because venues send it, and
     *     deliberately allowed to disagree in some tests
     */
    private static FixMessage execReport(
            String clOrdId, String execType, String ordStatus,
            double cumQty, double leavesQty, String venueOrderId) {

        Map<Integer, String> fields = new LinkedHashMap<>();
        fields.put(CL_ORD_ID, clOrdId);
        fields.put(EXEC_TYPE, execType);
        fields.put(ORD_STATUS, ordStatus);
        fields.put(CUM_QTY, String.valueOf((long) cumQty));
        fields.put(LEAVES_QTY, String.valueOf((long) leavesQty));
        fields.put(SYMBOL, "VOD");
        fields.put(SIDE, "1");
        if (venueOrderId != null) {
            fields.put(ORDER_ID, venueOrderId);
        }
        return FixMessage.of("8", fields);
    }

    private static FixMessage cancelReject(
            String clOrdId, String origClOrdId, String responseTo) {

        return FixMessage.of("9", Map.of(
                CL_ORD_ID, clOrdId,
                ORIG_CL_ORD_ID, origClOrdId,
                CXL_REJ_RESPONSE_TO, responseTo,
                // A reject echoes the order's status rather than reporting the
                // refusal, which is why 434 is what distinguishes it.
                ORD_STATUS, "0"));
    }
}
