package io.nexum.sim;

import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * That matching reaches a client over FIX, not merely inside the book.
 *
 * <p>{@link MatchingBookTest} covers the arithmetic. What it cannot show is
 * that a decision becomes an execution report on the wire, addressed to the
 * session the order arrived on — the step where a price move has to find an
 * order placed earlier and answer the client that sent it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MatchingVenueTest {

    private static final String SYMBOL = "MATCHME";

    private SocketAcceptor venue;
    private SocketInitiator client;
    private Recorder recorder;
    private SessionID session;

    @BeforeEach
    void bringUp() throws Exception {
        SimVenue.reset();
        SimVenue.matchOn(SYMBOL);

        int port = freePort();
        venue = SimVenue.start(port, "LSE", "OMS");

        recorder = new Recorder();
        String config = """
                [default]
                ConnectionType=initiator
                SocketConnectHost=127.0.0.1
                SocketConnectPort=%d
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                UseDataDictionary=N
                ResetOnLogon=Y
                ReconnectInterval=1

                [session]
                BeginString=FIX.4.4
                SenderCompID=OMS
                TargetCompID=LSE
                """.formatted(port);
        client = new SocketInitiator(recorder, new MemoryStoreFactory(),
                new SessionSettings(new ByteArrayInputStream(
                        config.getBytes(StandardCharsets.UTF_8))),
                new DefaultMessageFactory());
        client.start();

        session = awaitLogon();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.stop(true);
        if (venue != null) venue.stop(true);
        SimVenue.reset();
    }

    @Test
    @DisplayName("an order priced away from the market rests without trading")
    void anUncrossedOrderRests() throws Exception {
        send(order("rest-1", 1000, 90));

        // The acknowledgement arrives; a trade must not.
        awaitReport("rest-1", ExecType.NEW);
        Thread.sleep(500);
        assertFalse(recorder.sawTrade("rest-1"),
                "a bid below the market should not have traded: " + recorder.reports);
    }

    @Test
    @DisplayName("an order that crosses trades on arrival")
    void aCrossedOrderTradesImmediately() throws Exception {
        send(order("cross-1", 1000, 110));

        Message trade = awaitReport("cross-1", ExecType.TRADE);
        assertTrue(trade.getDouble(CumQty.FIELD) > 0);
        assertTrue(trade.getDouble(LeavesQty.FIELD) > 0,
                "a slice trades, so the rest must still be working");
    }

    @Test
    @DisplayName("a price move fills the order that was resting")
    void aPriceMoveFillsARestingOrder() throws Exception {
        send(order("wake-1", 1000, 95));
        awaitReport("wake-1", ExecType.NEW);
        assertFalse(recorder.sawTrade("wake-1"));

        // The market comes to the order — this is the path a scripted venue
        // could not exercise at all.
        SimVenue.running().reprice(SYMBOL, 94);

        Message trade = awaitReport("wake-1", ExecType.TRADE);
        assertEquals(94.0, trade.getDouble(quickfix.field.LastPx.FIELD), 1e-9,
                "it trades at the market, not at its own limit");
    }

    @Test
    @DisplayName("repeated moves fill the order and cumQty never passes the order quantity")
    void repeatedMovesFillTheOrder() throws Exception {
        send(order("fill-1", 1000, 110));

        for (int move = 0; move < 6; move++) {
            SimVenue.running().reprice(SYMBOL, 100);
            Thread.sleep(120);
        }

        Message last = awaitReport("fill-1", ExecType.TRADE);
        List<Message> trades = recorder.tradesFor("fill-1");

        double running = 0;
        for (Message trade : trades) {
            running += trade.getDouble(quickfix.field.LastQty.FIELD);
            assertEquals(running, trade.getDouble(CumQty.FIELD), 1e-9,
                    "cumQty must equal the fills that produced it");
            assertTrue(trade.getDouble(CumQty.FIELD) <= 1000,
                    "cumQty passed the order quantity: " + trade.getDouble(CumQty.FIELD));
        }
        assertEquals(1000.0, last.getDouble(CumQty.FIELD), 1e-9, "the order finishes filled");
        assertEquals(0.0, last.getDouble(LeavesQty.FIELD), 1e-9);
    }

    @Test
    @DisplayName("cancelling a matched order reports what already traded")
    void aCancelKeepsTheTradedQuantity() throws Exception {
        send(order("cancel-1", 1000, 110));
        Message trade = awaitReport("cancel-1", ExecType.TRADE);
        double traded = trade.getDouble(CumQty.FIELD);

        quickfix.fix44.OrderCancelRequest cancel = new quickfix.fix44.OrderCancelRequest(
                new OrigClOrdID("cancel-1"), new ClOrdID("cancel-1-c"),
                new Side(Side.BUY), new TransactTime());
        cancel.set(new Symbol(SYMBOL));
        cancel.set(new OrderQty(1000));
        send(cancel);

        Message cancelled = awaitReport("cancel-1-c", ExecType.CANCELED);
        assertEquals(traded, cancelled.getDouble(CumQty.FIELD), 1e-9,
                "a cancel stops the rest; it does not undo what traded");
    }

    @Test
    @DisplayName("an amendment that crosses trades under its new client id")
    void anAmendmentCanCross() throws Exception {
        send(order("amend-1", 1000, 90));
        awaitReport("amend-1", ExecType.NEW);

        quickfix.fix44.OrderCancelReplaceRequest replace =
                new quickfix.fix44.OrderCancelReplaceRequest(
                        new OrigClOrdID("amend-1"), new ClOrdID("amend-2"),
                        new Side(Side.BUY), new TransactTime(), new OrdType(OrdType.LIMIT));
        replace.set(new Symbol(SYMBOL));
        replace.set(new OrderQty(1000));
        replace.set(new Price(110));
        send(replace);

        awaitReport("amend-2", ExecType.REPLACED);
        Message trade = awaitReport("amend-2", ExecType.TRADE);
        assertTrue(trade.getDouble(CumQty.FIELD) > 0, "the repriced order should trade");
    }

    // ------------------------------------------------------------------

    private quickfix.fix44.NewOrderSingle order(String clOrdId, double qty, double price) {
        quickfix.fix44.NewOrderSingle order = new quickfix.fix44.NewOrderSingle(
                new ClOrdID(clOrdId), new Side(Side.BUY), new TransactTime(),
                new OrdType(OrdType.LIMIT));
        order.set(new Symbol(SYMBOL));
        order.set(new OrderQty(qty));
        order.set(new Price(price));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        return order;
    }

    private void send(Message message) throws Exception {
        Session.sendToTarget(message, session);
    }

    private SessionID awaitLogon() throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (recorder.session != null && Session.lookupSession(recorder.session) != null
                    && Session.lookupSession(recorder.session).isLoggedOn()) {
                return recorder.session;
            }
            Thread.sleep(50);
        }
        return fail("the client never logged on");
    }

    /** Wait for one report about an order, so a test never races the wire. */
    private Message awaitReport(String clOrdId, char execType) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            Message found = recorder.latest(clOrdId, execType);
            if (found != null) {
                return found;
            }
            Thread.sleep(50);
        }
        return fail("no " + execType + " report for " + clOrdId + " arrived; saw "
                + recorder.summary());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Collects what the venue sent back. */
    private static final class Recorder implements Application {

        private final List<Message> reports = new CopyOnWriteArrayList<>();
        private volatile SessionID session;

        Message latest(String clOrdId, char execType) {
            Message found = null;
            for (Message report : reports) {
                try {
                    if (clOrdId.equals(report.getString(ClOrdID.FIELD))
                            && report.getChar(ExecType.FIELD) == execType) {
                        found = report;
                    }
                } catch (FieldNotFound ignored) {
                    // A report without those fields is not one this asks about;
                    // nothing else in this class reads it.
                }
            }
            return found;
        }

        List<Message> tradesFor(String clOrdId) {
            List<Message> trades = new java.util.ArrayList<>();
            for (Message report : reports) {
                try {
                    if (clOrdId.equals(report.getString(ClOrdID.FIELD))
                            && report.getChar(ExecType.FIELD) == ExecType.TRADE) {
                        trades.add(report);
                    }
                } catch (FieldNotFound ignored) {
                    // As above: a message without both fields is not a trade report.
                }
            }
            return trades;
        }

        boolean sawTrade(String clOrdId) {
            return !tradesFor(clOrdId).isEmpty();
        }

        String summary() {
            StringBuilder text = new StringBuilder();
            for (Message report : reports) {
                try {
                    text.append(report.getString(ClOrdID.FIELD))
                            .append('/').append(report.getChar(ExecType.FIELD)).append(' ');
                } catch (FieldNotFound ignored) {
                    // Only used to explain a failure; an unreadable one is skipped.
                }
            }
            return text.toString();
        }

        @Override
        public void fromApp(Message message, SessionID sessionID) {
            try {
                if (MsgType.EXECUTION_REPORT.equals(message.getHeader().getString(MsgType.FIELD))) {
                    reports.add(message);
                }
            } catch (FieldNotFound missing) {
                throw new IllegalStateException("a message arrived without a type", missing);
            }
        }

        @Override
        public void onLogon(SessionID sessionID) {
            session = sessionID;
        }

        @Override
        public void onCreate(SessionID sessionID) {
            session = sessionID;
        }

        @Override
        public void onLogout(SessionID sessionID) {}

        @Override
        public void toAdmin(Message message, SessionID sessionID) {}

        @Override
        public void fromAdmin(Message message, SessionID sessionID) {}

        @Override
        public void toApp(Message message, SessionID sessionID) {}
    }
}
