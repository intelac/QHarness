package io.nexum.sim;

import quickfix.Acceptor;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A counterparty to talk to. Accepts orders and answers with execution reports:
 * an ack, then a partial fill, then the remainder.
 *
 * <p>Exists because testing a FIX client otherwise means booking time with a
 * broker's UAT environment. Everything here runs in-process and needs no
 * credentials, so the whole path can be exercised from a unit test or CI.
 */
public final class SimVenue implements Application {

    private final AtomicLong sequence = new AtomicLong(1);
    private final boolean rejectMode;

    /**
     * Symbols the venue accepts and then says nothing about, so the monitoring
     * rules have a real stuck order to find rather than a simulated one.
     */
    private static final java.util.Set<String> SILENT_SYMBOLS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void goSilentOn(String symbol) {
        SILENT_SYMBOLS.add(symbol);
    }

    /**
     * Symbols the venue prices and matches rather than scripts.
     *
     * <p>The other behaviours answer a symbol the same way every time, which is
     * what a test asserting one exact sequence needs. A matched symbol instead
     * trades when its limit crosses the market and rests when it does not, so
     * an order has to be priced to fill and a price move is what completes it.
     */
    private static final java.util.Set<String> MATCHED_SYMBOLS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The matched symbols' book, shared by every session this venue serves. */
    private static final MatchingBook MATCHING = new MatchingBook();

    /** Where each matched order came from, so a price move can report back to it. */
    private static final java.util.Map<String, SessionID> ORIGIN =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void matchOn(String symbol) {
        MATCHED_SYMBOLS.add(symbol);
    }

    /** The price a matched symbol is trading at. */
    public static double marketPrice(String symbol) {
        return MATCHING.price(symbol);
    }

    /** Symbols that rest on the book instead of trading immediately. */
    private static final java.util.Set<String> RESTING_SYMBOLS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void restOn(String symbol) {
        RESTING_SYMBOLS.add(symbol);
    }

    /**
     * Symbols that trade part of the order and leave the rest working.
     *
     * <p>The state most worth exercising and the one this simulator could not
     * reach: a symbol either traded out completely or never traded at all, so
     * an amendment against a partially filled order — where cumQty is already
     * non-zero and the remainder is still live — had nothing to act on.
     */
    private static final java.util.Set<String> PARTIAL_SYMBOLS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void partialOn(String symbol) {
        PARTIAL_SYMBOLS.add(symbol);
    }

    /**
     * Symbols the venue refuses outright.
     *
     * <p>A rejection is a terminal state like any other and belongs on the
     * monitor beside the fills — an order the venue would not take is exactly
     * what someone watching needs to see. Per symbol rather than the global
     * reject mode, so one run can show a rejection next to orders that worked.
     */
    private static final java.util.Set<String> REJECT_SYMBOLS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void rejectOn(String symbol) {
        REJECT_SYMBOLS.add(symbol);
    }

    /** Forget every configured behaviour, so one scenario cannot leak into the next. */
    public static void reset() {
        SILENT_SYMBOLS.clear();
        RESTING_SYMBOLS.clear();
        PARTIAL_SYMBOLS.clear();
        REJECT_SYMBOLS.clear();
        REFUSE_CANCEL.clear();
        MATCHED_SYMBOLS.clear();
        ORIGIN.clear();
    }

    public SimVenue(boolean rejectMode) {
        this.rejectMode = rejectMode;
    }

    public static SocketAcceptor start(int port, String senderCompId, String targetCompId)
            throws Exception {
        String config = """
                [default]
                ConnectionType=acceptor
                SocketAcceptPort=%d
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                UseDataDictionary=N
                ResetOnLogon=Y

                [session]
                BeginString=FIX.4.4
                SenderCompID=%s
                TargetCompID=%s
                """.formatted(port, senderCompId, targetCompId);

        SessionSettings settings = new SessionSettings(
                new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
        SimVenue venue = new SimVenue(false);
        SocketAcceptor acceptor = new SocketAcceptor(
                venue, new MemoryStoreFactory(), settings,
                new DefaultMessageFactory());
        acceptor.start();
        RUNNING.set(venue);
        return acceptor;
    }

    /**
     * The venue behind the acceptor {@link #start} put up.
     *
     * <p>Reporting a trade means sending on a session, which only an instance
     * can do, and the acceptor owns the instance it was built with. A price
     * feed needs that same one.
     */
    public static SimVenue running() {
        return RUNNING.get();
    }

    private static final java.util.concurrent.atomic.AtomicReference<SimVenue> RUNNING =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Orders the venue is working, so a cancel or replace has something to act on. */
    private final java.util.Map<String, Working> book =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Symbols whose cancels the venue refuses, so a refusal can be exercised. */
    private static final java.util.Set<String> REFUSE_CANCEL =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void refuseCancelsOn(String symbol) {
        REFUSE_CANCEL.add(symbol);
    }

    /**
     * @param cumQty what has already traded. A cancel confirmation has to carry
     *     it: cancelling stops the rest, it does not undo what was done, and a
     *     venue reporting zero here would be telling the client its position
     *     vanished.
     */
    private record Working(String orderId, String symbol, char side, double qty,
                           double price, double cumQty) {}

    @Override
    public void fromApp(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            switch (msgType) {
                case MsgType.ORDER_SINGLE -> onNewOrder(message, sessionID);
                case MsgType.ORDER_CANCEL_REQUEST -> onCancel(message, sessionID);
                case MsgType.ORDER_CANCEL_REPLACE_REQUEST -> onReplace(message, sessionID);
                default -> System.out.println("[sim] ignoring 35=" + msgType);
            }
        } catch (FieldNotFound missing) {
            System.err.println("[sim] malformed message: " + missing.getMessage());
        }
    }

    private void onCancel(Message request, SessionID sessionID) throws FieldNotFound {
        String clOrdId = request.getString(ClOrdID.FIELD);
        String origClOrdId = request.getString(OrigClOrdID.FIELD);

        // A matched order lives in the matching book rather than in `book`, and
        // cancelling it has to go through the same lock the price thread holds:
        // between deciding to cancel and saying so, a crossing move could
        // otherwise trade quantity this report has already called retired.
        java.util.Optional<MatchingBook.Resting> matched = MATCHING.cancel(origClOrdId);
        if (matched.isPresent()) {
            MatchingBook.Resting cancelled = matched.get();
            ORIGIN.remove(origClOrdId);
            System.out.println("[sim] cancelling matched " + origClOrdId
                    + ", " + cancelled.cumQty() + " already traded");
            Message cancelReport = report(cancelled.orderId(), clOrdId, cancelled.symbol(),
                    cancelled.side(), cancelled.orderQty(), ExecType.CANCELED, OrdStatus.CANCELED,
                    0, cancelled.cumQty(), 0, cancelled.limitPrice());
            cancelReport.setString(OrigClOrdID.FIELD, origClOrdId);
            send(sessionID, cancelReport);
            return;
        }

        Working order = book.get(origClOrdId);

        if (order == null) {
            System.out.println("[sim] cancel for unknown " + origClOrdId + " — rejecting");
            send(sessionID, cancelReject(clOrdId, origClOrdId, "1", "unknown order"));
            return;
        }
        if (REFUSE_CANCEL.contains(order.symbol())) {
            // A refused cancel leaves the order working, which is the case most
            // often mishandled downstream.
            System.out.println("[sim] refusing to cancel " + origClOrdId);
            send(sessionID, cancelReject(clOrdId, origClOrdId, "1", "too late to cancel"));
            return;
        }

        System.out.println("[sim] cancelling " + origClOrdId);
        book.remove(origClOrdId);
        // What already traded survives the cancel.
        Message report = report(order.orderId(), clOrdId, order.symbol(), order.side(),
                order.qty(), ExecType.CANCELED, OrdStatus.CANCELED,
                0, order.cumQty(), 0, order.price());
        report.setString(OrigClOrdID.FIELD, origClOrdId);
        send(sessionID, report);
    }

    private void onReplace(Message request, SessionID sessionID) throws FieldNotFound {
        String clOrdId = request.getString(ClOrdID.FIELD);
        String origClOrdId = request.getString(OrigClOrdID.FIELD);

        java.util.Optional<MatchingBook.Resting> matched = MATCHING.find(origClOrdId);
        if (matched.isPresent()) {
            MatchingBook.Resting original = matched.get();
            double amendedQty = request.isSetField(OrderQty.FIELD)
                    ? request.getDouble(OrderQty.FIELD) : original.orderQty();
            double amendedPrice = request.isSetField(Price.FIELD)
                    ? request.getDouble(Price.FIELD) : original.limitPrice();

            // The replace mints a new client id, so what the venue reports on
            // from here answers to that one.
            ORIGIN.remove(origClOrdId);
            ORIGIN.put(clOrdId, sessionID);
            java.util.List<MatchingBook.Trade> caused = MATCHING
                    .replace(origClOrdId, clOrdId, amendedQty, amendedPrice)
                    .orElseThrow();

            Message replaced = report(original.orderId(), clOrdId, original.symbol(),
                    original.side(), amendedQty, ExecType.REPLACED, OrdStatus.REPLACED,
                    0, original.cumQty(), amendedQty - original.cumQty(), amendedPrice);
            replaced.setString(OrigClOrdID.FIELD, origClOrdId);
            send(sessionID, replaced);

            // An amendment that makes the order marketable trades at once, and
            // those reports follow the confirmation rather than replacing it.
            for (MatchingBook.Trade trade : caused) {
                reportTrade(trade);
            }
            return;
        }

        Working order = book.get(origClOrdId);

        if (order == null) {
            send(sessionID, cancelReject(clOrdId, origClOrdId, "2", "unknown order"));
            return;
        }

        double newQty = request.isSetField(OrderQty.FIELD)
                ? request.getDouble(OrderQty.FIELD) : order.qty();
        double newPrice = request.isSetField(Price.FIELD)
                ? request.getDouble(Price.FIELD) : order.price();

        System.out.println("[sim] replacing " + origClOrdId
                + " qty=" + newQty + " px=" + newPrice);
        book.remove(origClOrdId);
        book.put(clOrdId, new Working(order.orderId(), order.symbol(), order.side(),
                newQty, newPrice, order.cumQty()));

        Message report = report(order.orderId(), clOrdId, order.symbol(), order.side(),
                newQty, ExecType.REPLACED, OrdStatus.REPLACED,
                0, order.cumQty(), newQty - order.cumQty(), newPrice);
        report.setString(OrigClOrdID.FIELD, origClOrdId);
        send(sessionID, report);
    }

    private Message cancelReject(
            String clOrdId, String origClOrdId, String responseTo, String reason) {

        quickfix.fix44.OrderCancelReject reject = new quickfix.fix44.OrderCancelReject(
                new OrderID("SIM-REJ"),
                new ClOrdID(clOrdId),
                new OrigClOrdID(origClOrdId),
                // The order's own status, which is why reading 39 here tells you
                // nothing about the refusal — 434 is what distinguishes it.
                new OrdStatus(OrdStatus.NEW),
                new CxlRejResponseTo(responseTo.charAt(0)));
        reject.setString(58, reason);
        return reject;
    }

    private void onNewOrder(Message message, SessionID sessionID) throws FieldNotFound {
        {
            String clOrdId = message.getString(ClOrdID.FIELD);
            String symbol = message.getString(Symbol.FIELD);
            char side = message.getChar(Side.FIELD);
            double qty = message.getDouble(OrderQty.FIELD);
            double price = message.isSetField(Price.FIELD)
                    ? message.getDouble(Price.FIELD)
                    : 100.0;
            String orderId = "SIM-" + sequence.getAndIncrement();

            System.out.println("[sim] order " + clOrdId + " " + symbol + " qty=" + qty);

            if (REJECT_SYMBOLS.contains(symbol)) {
                System.out.println("[sim] rejecting " + clOrdId + " " + symbol);
                send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                        ExecType.REJECTED, OrdStatus.REJECTED, 0, 0, 0, price));
                return;
            }

            if (MATCHED_SYMBOLS.contains(symbol)) {
                ORIGIN.put(clOrdId, sessionID);
                // Acknowledged before anything trades: the client has to learn
                // the venue's order id even when the order rests untouched.
                send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                        ExecType.NEW, OrdStatus.NEW, 0, 0, qty, price));
                for (MatchingBook.Trade trade : MATCHING.place(clOrdId, orderId, symbol, side, qty, price)) {
                    reportTrade(trade);
                }
                return;
            }

            if (SILENT_SYMBOLS.contains(symbol)) {
                System.out.println("[sim] accepting " + clOrdId + " and saying nothing");
                book.put(clOrdId, new Working(orderId, symbol, side, qty, price, 0));
                return;
            }

            // Part traded, the rest left working — an amendment then acts on an
            // order that already has a position.
            if (PARTIAL_SYMBOLS.contains(symbol)) {
                double done = Math.floor(qty / 3);
                System.out.println("[sim] " + clOrdId + " partially filled " + done
                        + ", " + (qty - done) + " still working");
                book.put(clOrdId, new Working(orderId, symbol, side, qty, price, done));
                send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                        ExecType.NEW, OrdStatus.NEW, 0, 0, qty, price));
                send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                        ExecType.TRADE, OrdStatus.PARTIALLY_FILLED,
                        done, done, qty - done, price));
                return;
            }

            // Orders that rest rather than trade, so a cancel or replace has
            // something live to act on.
            if (RESTING_SYMBOLS.contains(symbol)) {
                System.out.println("[sim] " + clOrdId + " resting on the book");
                book.put(clOrdId, new Working(orderId, symbol, side, qty, price, 0));
                send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                        ExecType.NEW, OrdStatus.NEW, 0, 0, qty, price));
                return;
            }

            if (rejectMode) {
                send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                        ExecType.REJECTED, OrdStatus.REJECTED, 0, 0, qty, price));
                return;
            }

            // ack, then half, then the rest — the ordinary three-report life of
            // an order, so the return path is exercised properly.
            send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                    ExecType.NEW, OrdStatus.NEW, 0, 0, qty, price));

            double half = Math.floor(qty / 2);
            send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                    ExecType.TRADE, OrdStatus.PARTIALLY_FILLED, half, half, qty - half, price));

            send(sessionID, report(orderId, clOrdId, symbol, side, qty,
                    ExecType.TRADE, OrdStatus.FILLED, qty - half, qty, 0, price));

        }
    }

    /**
     * Turn one of the book's decisions into an execution report.
     *
     * <p>The order's origin is remembered when it is placed, because a price
     * move reports on orders that arrived earlier and possibly on another
     * session; a completed order gives its entry up, since nothing further can
     * be reported about it.
     */
    private void reportTrade(MatchingBook.Trade trade) {
        SessionID origin = trade.complete()
                ? ORIGIN.remove(trade.clOrdId())
                : ORIGIN.get(trade.clOrdId());
        if (origin == null) {
            return;
        }
        System.out.println("[sim] " + trade.clOrdId() + " traded " + trade.lastQty()
                + " at " + trade.price() + ", cum=" + trade.cumQty()
                + ", leaves=" + trade.leavesQty());
        send(origin, report(trade.orderId(), trade.clOrdId(), trade.symbol(), trade.side(),
                trade.orderQty(),
                ExecType.TRADE,
                trade.complete() ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED,
                trade.lastQty(), trade.cumQty(), trade.leavesQty(), trade.price()));
    }

    /**
     * Move a matched symbol's price and report whatever it trades.
     *
     * <p>This is the seam a price feed drives. It takes an instance because
     * only an instance can send on a session, and every acceptor connection is
     * served by the same one.
     */
    public void reprice(String symbol, double price) {
        for (MatchingBook.Trade trade : MATCHING.reprice(symbol, price)) {
            reportTrade(trade);
        }
    }

    private Message report(
            String orderId, String clOrdId, String symbol, char side, double orderQty,
            char execType, char ordStatus, double lastQty, double cumQty, double leavesQty,
            double price) {

        quickfix.fix44.ExecutionReport report = new quickfix.fix44.ExecutionReport(
                new OrderID(orderId),
                new ExecID("EXEC-" + sequence.getAndIncrement()),
                new ExecType(execType),
                new OrdStatus(ordStatus),
                new Side(side),
                new LeavesQty(leavesQty),
                new CumQty(cumQty),
                new AvgPx(price));
        report.set(new ClOrdID(clOrdId));
        report.set(new Symbol(symbol));
        report.set(new OrderQty(orderQty));
        if (lastQty > 0) {
            report.set(new LastQty(lastQty));
            report.set(new LastPx(price));
        }
        return report;
    }

    private void send(SessionID sessionID, Message message) {
        try {
            Session.sendToTarget(message, sessionID);
        } catch (Exception failure) {
            System.err.println("[sim] cannot send: " + failure.getMessage());
        }
    }

    @Override
    public void onCreate(SessionID sessionID) {}

    @Override
    public void onLogon(SessionID sessionID) {
        System.out.println("[sim] logon from " + sessionID.getTargetCompID());
    }

    @Override
    public void onLogout(SessionID sessionID) {
        System.out.println("[sim] logout");
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {}

    @Override
    public void fromAdmin(Message message, SessionID sessionID) {}

    @Override
    public void toApp(Message message, SessionID sessionID) {}
}
