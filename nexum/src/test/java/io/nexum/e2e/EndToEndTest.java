package io.nexum.e2e;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.order.Order;
import io.nexum.order.OrderCache;
import io.nexum.order.OrderState;
import io.nexum.sim.SimVenue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.SecurityExchange;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The whole system, over real sockets.
 *
 * <p>Every other test here runs on a recording transport, which is fast and
 * lets a venue's timing be chosen — but it is a stand-in, and a stand-in can
 * disagree with the real thing. It did: the outbound layer walk lived inside
 * {@code QuickFixTransport} and the recording transport never ran it, so a
 * plugin mounted on a destination silently never fired. No test caught that,
 * because no test ran both.
 *
 * <p>So this one runs two real QuickFIX/J engines on real ports, logs them on,
 * and drives a client order through to a venue and back. It is slower than the
 * rest of the suite by orders of magnitude, which is the price of the only
 * evidence that the assembled system works.
 *
 * <pre>
 *   RecordingClient --FIX/socket--&gt; [ NEXUM ] --FIX/socket--&gt; SimVenue
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EndToEndTest {

    private static final int CL_ORD_ID = 11;
    private static final int ORDER_ID = 37;
    private static final int ORD_STATUS = 39;
    private static final int EXEC_TYPE = 150;
    private static final int CUM_QTY = 14;
    private static final int SYMBOL = 55;
    private static final int CURRENCY = 15;
    private static final int ORDER_QTY = 38;
    private static final int PRICE = 44;
    private static final int ORIG_CL_ORD_ID = 41;
    private static final int CXL_REJ_RESPONSE_TO = 434;

    private static final String CLIENT_SESSION = "OMS->FUNDX";
    private static final String VENUE_SESSION = "OMS->LSE";

    /** Symbols that trade immediately, and ones that rest so they can be amended. */
    private static final String TRADES = "VOD";
    private static final String RESTS = "BP";
    private static final String CANCEL_REFUSED = "RIO";

    private Context ctx;
    private PluginLoader loader;
    private OrderCache cache;

    /** Raw messages that reached the venue's socket, as evidence of what was sent. */
    private final List<String> toVenue = new java.util.concurrent.CopyOnWriteArrayList<>();

    private SocketAcceptor venue;
    private SocketInitiator client;
    private RecordingClient recorder;

    @BeforeAll
    void bringTheSystemUp() throws Exception {
        int clientPort = freePort();
        int venuePort = freePort();

        // The venue must be listening before our initiator tries to reach it.
        // Both must rest: a symbol that trades immediately has no live order
        // for a cancel to act on, so a "refused cancel" would be refused for
        // the wrong reason.
        SimVenue.restOn(RESTS);
        SimVenue.restOn(CANCEL_REFUSED);
        SimVenue.refuseCancelsOn(CANCEL_REFUSED);
        venue = SimVenue.start(venuePort, "LSE", "OMS");

        ctx = new Context();
        // A fresh journal each run. Sharing one across runs replays the
        // previous run's orders — and with them their OrderID(37) index, which
        // a restarted simulator immediately reissues from SIM-1. The report for
        // one order then resolves to another run's.
        java.nio.file.Path journalDir = java.nio.file.Files.createTempDirectory("nexum-e2e");
        journalDir.toFile().deleteOnExit();
        loader = Bootstrap.from(
                config(clientPort, venuePort, journalDir.toString())).start(ctx);
        cache = ctx.get("orders");

        ctx.onEvent(io.nexum.transport.TransportEvents.WIRE,
                (io.nexum.transport.TransportEvents.Wire wire) -> {
                    if (VENUE_SESSION.equals(wire.sessionId())
                            && wire.direction()
                                == io.nexum.transport.TransportEvents.Direction.OUT) {
                        toVenue.add(wire.raw());
                    }
                });

        recorder = new RecordingClient();
        client = RecordingClient.start(recorder, clientPort, "FUNDX", "OMS");

        await("both sessions to log on", () -> recorder.isLoggedOn()
                && ctx.<io.nexum.transport.Transport>get("transport").isLoggedOn(VENUE_SESSION));
    }

    @AfterAll
    void takeItDown() {
        if (client != null) {
            client.stop(true);
        }
        if (loader != null) {
            loader.unloadAll();
        }
        if (venue != null) {
            venue.stop(true);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("an order that trades")
    class Trading {

        @Test
        void reachesTheVenueAndComesBackFilled() throws Exception {
            String clOrdId = "E2E-TRADE-1";
            sendOrder(clOrdId, TRADES, 1000);

            // The venue acks, half-fills, then fills. The client should see all
            // three, under its own identifier.
            await("three reports for " + clOrdId,
                    () -> recorder.forClOrdId(clOrdId).size() >= 3);

            List<RecordingClient.Received> reports = recorder.forClOrdId(clOrdId);
            assertEquals(List.of("0", "1", "2"),
                    reports.stream().map(r -> r.field(ORD_STATUS)).toList(),
                    "the client should have seen new, partially filled, then filled");

            assertEquals("1000", reports.get(2).field(CUM_QTY),
                    "the final report should account for the whole order");
        }

        @Test
        void endsUpFilledInOurOwnBook() throws Exception {
            String clOrdId = "E2E-TRADE-2";
            sendOrder(clOrdId, TRADES, 400);

            await("the order to settle",
                    () -> find(clOrdId).map(o -> o.state() == OrderState.FILLED).orElse(false));

            Order order = find(clOrdId).orElseThrow();
            assertEquals(400.0, order.cumQty(), 0.001);
            assertNotNull(order.destination().clOrdId());
            assertNotEquals(clOrdId, order.destination().clOrdId(),
                    "the venue should have seen our identifier, not the client's");
        }

        @Test
        void neverShowsTheClientOurIdentifiers() throws Exception {
            String clOrdId = "E2E-TRADE-3";
            sendOrder(clOrdId, TRADES, 200);

            await("a report", () -> !recorder.forClOrdId(clOrdId).isEmpty());

            // Every report naming this order must carry the client's own
            // ClOrdID. Ours going out is a leak the client cannot act on.
            for (RecordingClient.Received report : recorder.forClOrdId(clOrdId)) {
                assertEquals(clOrdId, report.field(CL_ORD_ID));
            }
        }

        @Test
        void learnsTheVenuesOrderId() throws Exception {
            String clOrdId = "E2E-TRADE-4";
            sendOrder(clOrdId, TRADES, 600);

            await("the venue id",
                    () -> find(clOrdId).map(o -> o.destination().orderId() != null).orElse(false));

            assertTrue(find(clOrdId).orElseThrow().destination().orderId().startsWith("SIM-"),
                    "OrderID(37) is what a later report is matched on");
        }
    }

    @Nested
    @DisplayName("an order that rests")
    class Amending {

        @Test
        void canBeCancelled() throws Exception {
            String clOrdId = "E2E-REST-1";
            sendOrder(clOrdId, RESTS, 1000);
            await("the ack", () -> stateOf(clOrdId) == OrderState.NEW);

            sendCancel("E2E-CXL-1", clOrdId, RESTS);

            await("the cancel to be confirmed",
                    () -> stateOf(clOrdId) == OrderState.CANCELED);

            // FIX pairs the two: ClOrdID(11) names the request the client sent,
            // OrigClOrdID(41) names the order it was against. A client matching
            // a cancel confirmation looks for the id it sent the cancel with.
            await("the confirmation to reach the client",
                    () -> !recorder.forClOrdId("E2E-CXL-1").isEmpty());

            RecordingClient.Received confirmation = recorder.forClOrdId("E2E-CXL-1").get(0);
            assertEquals("4", confirmation.field(ORD_STATUS));
            assertEquals(clOrdId, confirmation.field(ORIG_CL_ORD_ID),
                    "41 should name the order the cancel was against");

            // An ordinary report is not answering a request, so it carries the
            // order's own identifier and no 41 at all.
            RecordingClient.Received ack = recorder.forClOrdId(clOrdId).get(0);
            assertNull(ack.field(ORIG_CL_ORD_ID),
                    "an ack answers no request and should not carry 41");
        }

        @Test
        void canBeReplaced() throws Exception {
            String clOrdId = "E2E-REST-2";
            sendOrder(clOrdId, RESTS, 1000);
            await("the ack", () -> stateOf(clOrdId) == OrderState.NEW);

            sendReplace("E2E-RPL-1", clOrdId, RESTS, 2000, 155.0);

            await("the replace to be applied",
                    () -> find(clOrdId)
                            .map(o -> "2000".equals(o.destination().field(ORDER_QTY)))
                            .orElse(false));

            Order order = find(clOrdId).orElseThrow();
            assertEquals("155", stripTrailingZeros(order.destination().field(PRICE)),
                    "the amended price should be the order's own terms now");
        }

        @Test
        void survivesARefusedCancel() throws Exception {
            String clOrdId = "E2E-REST-3";
            sendOrder(clOrdId, CANCEL_REFUSED, 1000);
            await("the ack", () -> stateOf(clOrdId) == OrderState.NEW);

            sendCancel("E2E-CXL-2", clOrdId, CANCEL_REFUSED);

            // A refused cancel leaves the order working — the whole point of
            // CxlRejResponseTo(434) is that this is not a rejected order.
            await("the refusal", () -> !recorder.ofType("9").isEmpty());

            RecordingClient.Received reject = recorder.ofType("9").get(0);
            assertEquals("1", reject.field(CXL_REJ_RESPONSE_TO),
                    "434=1 says this refuses a cancel, not an order");

            // CANCEL_REJECTED is itself a working state — the order carries the
            // refusal without ceasing to be live. Asserting the name would tie
            // this to a spelling; what matters is that it is still working and
            // nothing is outstanding.
            OrderState after = stateOf(clOrdId);
            assertTrue(after.isWorking(),
                    "a refused cancel must leave the order working, but it was " + after);
            assertFalse(after.awaitsRequestAnswer(),
                    "the refusal answered the request, so nothing is outstanding");
        }
    }

    @Nested
    @DisplayName("the layers")
    class Layers {

        @Test
        void canEnrichWhatGoesToTheVenue() throws Exception {
            // A destination plugin mounted at runtime, the way a real one would
            // be. Over sockets this matters: the outbound layer walk used to
            // live inside the transport, which never ran the destination chain.
            var mounted = ctx.onGate(
                    io.nexum.transport.TransportEvents.MESSAGE_OUTBOUND,
                    io.nexum.core.Scope.destination(VENUE_SESSION),
                    (io.nexum.core.Events.Gate<io.nexum.transport.TransportEvents.InFlight>)
                            (flight, next) -> next.apply(flight.with(
                                    flight.message().set(CURRENCY, "USD"))));

            try {
                String clOrdId = "E2E-LAYER-1";
                sendOrder(clOrdId, TRADES, 100);

                await("the order to settle",
                        () -> find(clOrdId).map(o -> o.state().isTerminal()).orElse(false));

                // Read from the venue's log rather than our stored view: the
                // destination view is a snapshot of the inbound chain, taken
                // before the outbound gate runs, so it cannot witness this.
                // What reached the socket can.
                assertTrue(venueSawField(CURRENCY, "USD"),
                        "a destination plugin must reach the wire");
            } finally {
                mounted.dispose();
            }
        }

        @Test
        void keepThreeSeparateViewsOfTheOrder() throws Exception {
            String clOrdId = "E2E-LAYER-2";
            sendOrder(clOrdId, TRADES, 300);

            await("the order", () -> find(clOrdId).isPresent());

            Order order = find(clOrdId).orElseThrow();
            assertEquals(clOrdId, order.client().clOrdId(),
                    "the client's view keeps what the client sent");
            assertNotEquals(clOrdId, order.destination().clOrdId(),
                    "the destination's view keeps what the venue saw");
        }
    }

    // ------------------------------------------------------------------
    // Driving
    // ------------------------------------------------------------------

    private void sendOrder(String clOrdId, String symbol, double qty) throws Exception {
        quickfix.fix44.NewOrderSingle order = new quickfix.fix44.NewOrderSingle(
                new ClOrdID(clOrdId),
                new Side(Side.BUY),
                new TransactTime(),
                new OrdType(OrdType.LIMIT));
        order.set(new Symbol(symbol));
        order.set(new OrderQty(qty));
        order.set(new Price(150.0));
        order.set(new SecurityExchange("L"));
        order.set(new HandlInst(
                HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        order.getHeader().setString(OnBehalfOfCompID.FIELD, "FUNDX");
        dispatch(order);
    }

    private void sendCancel(String clOrdId, String origClOrdId, String symbol) throws Exception {
        quickfix.fix44.OrderCancelRequest request = new quickfix.fix44.OrderCancelRequest(
                new OrigClOrdID(origClOrdId),
                new ClOrdID(clOrdId),
                new Side(Side.BUY),
                new TransactTime());
        request.set(new Symbol(symbol));
        request.getHeader().setString(OnBehalfOfCompID.FIELD, "FUNDX");
        dispatch(request);
    }

    private void sendReplace(
            String clOrdId, String origClOrdId, String symbol, double qty, double price)
            throws Exception {

        quickfix.fix44.OrderCancelReplaceRequest request =
                new quickfix.fix44.OrderCancelReplaceRequest(
                        new OrigClOrdID(origClOrdId),
                        new ClOrdID(clOrdId),
                        new Side(Side.BUY),
                        new TransactTime(),
                        new OrdType(OrdType.LIMIT));
        request.set(new Symbol(symbol));
        request.set(new OrderQty(qty));
        request.set(new Price(price));
        request.getHeader().setString(OnBehalfOfCompID.FIELD, "FUNDX");
        dispatch(request);
    }

    private void dispatch(Message message) throws Exception {
        SessionID id = recorder.session();
        assertTrue(Session.sendToTarget(message, id), "the client could not send");
    }

    // ------------------------------------------------------------------
    // Looking at what happened
    // ------------------------------------------------------------------

    /**
     * Our order for a client's ClOrdID.
     *
     * <p>Through the cache's own lookup rather than a scan of {@code active()},
     * which excludes terminal orders — polling that for a FILLED order waits
     * for a state that removes the order from the collection being polled.
     */
    private Optional<Order> find(String clientClOrdId) {
        return cache.byClientClOrdId(clientClOrdId);
    }

    /** Whether any message that reached the venue carried this field. */
    private boolean venueSawField(int tag, String value) {
        String needle = "\u0001" + tag + "=" + value + "\u0001";
        return toVenue.stream().anyMatch(raw -> raw.contains(needle));
    }

    private OrderState stateOf(String clientClOrdId) {
        return find(clientClOrdId).map(Order::state).orElse(null);
    }

    /**
     * Wait for something to become true.
     *
     * <p>Real sockets mean nothing is synchronous, and a fixed sleep is either
     * slow or flaky depending on the machine. Failing names what was being
     * waited for, so a timeout says which step did not happen.
     */
    private static void await(String what, BooleanSupplier condition)
            throws InterruptedException {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for " + what);
    }

    private static String stripTrailingZeros(String number) {
        if (number == null) {
            return null;
        }
        return new java.math.BigDecimal(number).stripTrailingZeros().toPlainString();
    }

    /** A port the OS says is free, so parallel builds do not collide. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String config(int clientPort, int venuePort, String journalDir) {
        return """
            orders:
              journal: %s
              sync: true

            monitor:
              enabled: false

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
                role: acceptor
                port: %d
                logPath: target/e2e/logs
                persistent: false

              - id: OMS->LSE
                version: FIX.4.4
                role: initiator
                host: 127.0.0.1
                port: %d
                logPath: target/e2e/logs
                persistent: false

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->LSE
                fingerprint: any
            """.formatted(journalDir, clientPort, venuePort);
    }
}
