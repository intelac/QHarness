package io.nexum.sim;

import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.MsgType;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.SecurityExchange;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * A customer sending orders in, so the inbound path can be exercised without a
 * real buy-side connection.
 *
 * <p>Deliberately naive: it sends what a client sends and leaves out what a
 * client leaves out, which is what gives the enrichment plugins something to do.
 */
public final class SimClient implements Application {

    private static volatile SessionID sessionID;
    private static volatile boolean loggedOn;

    public static SocketInitiator start(int port, String senderCompId, String targetCompId)
            throws Exception {
        String config = """
                [default]
                ConnectionType=initiator
                SocketConnectHost=127.0.0.1
                SocketConnectPort=%d
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                ReconnectInterval=2
                UseDataDictionary=N
                ResetOnLogon=Y
                FileLogPath=target/fixlogs

                [session]
                BeginString=FIX.4.4
                SenderCompID=%s
                TargetCompID=%s
                """.formatted(port, senderCompId, targetCompId);

        SessionSettings settings = new SessionSettings(
                new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
        SocketInitiator initiator = new SocketInitiator(
                new SimClient(), new MemoryStoreFactory(), settings, new DefaultMessageFactory());
        initiator.start();
        return initiator;
    }

    public static boolean isLoggedOn() {
        return loggedOn;
    }

    /** Send an order the way a client would: no HandlInst, no TransactTime, no Currency. */
    public static void sendOrder(String clOrdId, String symbol, double qty, String exchange) {
        sendOrderAs("FUNDX", clOrdId, symbol, qty, exchange);
    }

    public static void sendOrderAs(
            String onBehalfOf, String clOrdId, String symbol, double qty, String exchange) {

        quickfix.fix44.NewOrderSingle order = new quickfix.fix44.NewOrderSingle(
                new ClOrdID(clOrdId),
                new Side(Side.BUY),
                new TransactTime(),
                new OrdType(OrdType.LIMIT));
        order.set(new Symbol(symbol));
        order.set(new OrderQty(qty));
        order.set(new Price(150.0));
        order.set(new SecurityExchange(exchange));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        order.getHeader().setString(OnBehalfOfCompID.FIELD, onBehalfOf);

        try {
            Session.sendToTarget(order, sessionID);
            System.out.println("[client] sent " + clOrdId + " " + symbol + " qty=" + qty
                    + " onBehalfOf=" + onBehalfOf);
        } catch (Exception failure) {
            System.err.println("[client] cannot send: " + failure.getMessage());
        }
    }

    /** Ask to cancel an order the client placed earlier. */
    public static void sendCancel(String clOrdId, String origClOrdId, String symbol) {
        quickfix.fix44.OrderCancelRequest request = new quickfix.fix44.OrderCancelRequest(
                new quickfix.field.OrigClOrdID(origClOrdId),
                new ClOrdID(clOrdId),
                new Side(Side.BUY),
                new TransactTime());
        request.set(new Symbol(symbol));
        request.getHeader().setString(OnBehalfOfCompID.FIELD, "FUNDX");
        dispatch(request, "cancel " + origClOrdId);
    }

    /** Ask to change an order's price or quantity. */
    public static void sendReplace(
            String clOrdId, String origClOrdId, String symbol, double newQty, double newPrice) {

        quickfix.fix44.OrderCancelReplaceRequest request =
                new quickfix.fix44.OrderCancelReplaceRequest(
                        new quickfix.field.OrigClOrdID(origClOrdId),
                        new ClOrdID(clOrdId),
                        new Side(Side.BUY),
                        new TransactTime(),
                        new OrdType(OrdType.LIMIT));
        request.set(new Symbol(symbol));
        request.set(new OrderQty(newQty));
        request.set(new Price(newPrice));
        request.getHeader().setString(OnBehalfOfCompID.FIELD, "FUNDX");
        dispatch(request, "replace " + origClOrdId
                + " qty=" + newQty + " px=" + newPrice);
    }

    private static void dispatch(Message message, String what) {
        try {
            Session.sendToTarget(message, sessionID);
            System.out.println("[client] " + what);
        } catch (Exception failure) {
            System.err.println("[client] cannot send: " + failure.getMessage());
        }
    }

    @Override
    public void fromApp(Message message, SessionID id) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            if (!"8".equals(msgType) && !"9".equals(msgType)) {
                return;
            }
            String type = message.getHeader().getString(MsgType.FIELD);
            if ("9".equals(type)) {
                System.out.println("[client] REJECTED "
                        + message.getString(ClOrdID.FIELD)
                        + "  (434=" + message.getChar(434) + ")"
                        + "  the order is still working");
                return;
            }
            System.out.println("[client] report for " + message.getString(ClOrdID.FIELD)
                    + "  status=" + message.getChar(OrdStatus.FIELD));
        } catch (FieldNotFound ignored) {
            // a report without the fields we display is not our concern here
        }
    }

    @Override
    public void onCreate(SessionID id) {
        sessionID = id;
    }

    @Override
    public void onLogon(SessionID id) {
        sessionID = id;
        loggedOn = true;
    }

    @Override
    public void onLogout(SessionID id) {
        loggedOn = false;
    }

    @Override
    public void toAdmin(Message message, SessionID id) {}

    @Override
    public void fromAdmin(Message message, SessionID id) {}

    @Override
    public void toApp(Message message, SessionID id) {}
}
