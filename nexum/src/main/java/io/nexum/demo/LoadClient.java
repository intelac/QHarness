package io.nexum.demo;

import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.ClOrdID;
import quickfix.field.MsgType;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Password;
import quickfix.field.Price;
import quickfix.field.SecurityExchange;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sends orders at a running NEXUM, so a deployment can be seen working.
 *
 * <p>Exists because a monitor with no orders in it proves only that the page
 * renders. This drives real messages through a real socket into a real
 * deployment, which is the only way to see the whole path — routing,
 * identifier translation, the state machine, and what the monitor makes of it.
 *
 * <pre>
 *   java -cp nexum.jar io.nexum.demo.LoadClient &lt;host&gt; &lt;port&gt; &lt;password&gt;
 * </pre>
 *
 * <p>Unlike {@code SimClient} this sends Password(554) on Logon, because a
 * deployment on a public address requires one and refuses anything else.
 */
public final class LoadClient implements Application {

    private static volatile SessionID sessionID;
    private static volatile boolean loggedOn;
    private static String password;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: LoadClient <host> <port> <password> [orders]");
            System.exit(2);
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        password = args[2];
        int count = args.length > 3 ? Integer.parseInt(args[3]) : 6;

        SocketInitiator client = start(host, port);
        try {
            if (!awaitLogon()) {
                System.err.println("could not log on — is the password right?");
                System.exit(1);
                return;
            }
            System.out.println("logged on");
            place(count);

            // Reports come back asynchronously; leaving immediately would cut
            // the venue's answers off mid-flight.
            Thread.sleep(5000);
            System.out.println("done");
        } finally {
            client.stop(true);
        }
    }

    /** A spread of orders, so the monitor shows more than one state. */
    private static void place(int count) throws Exception {
        String stamp = String.valueOf(System.currentTimeMillis() % 100000);

        List<String[]> orders = List.of(
                new String[] {"VOD", "1000"},
                new String[] {"BP", "2500"},
                new String[] {"HSBC", "800"},
                new String[] {"BARC", "5000"},
                new String[] {"GSK", "1200"},
                new String[] {"AZN", "300"});

        for (int i = 0; i < count; i++) {
            String[] order = orders.get(i % orders.size());
            String clOrdId = "DEMO-" + stamp + "-" + (i + 1);
            sendOrder(clOrdId, order[0], Double.parseDouble(order[1]));
            System.out.println("sent " + clOrdId + " " + order[0] + " x" + order[1]);
            Thread.sleep(700);
        }

        // One cancel, so a request and its answer are visible too. It has to
        // name an order that is still live: the first order trades immediately,
        // and cancelling a filled order is refused — correctly, but it shows
        // nothing. The second rests, so there is something to act on.
        if (count >= 2) {
            Thread.sleep(1500);
            String toCancel = "DEMO-" + stamp + "-2";
            sendCancel("DEMO-" + stamp + "-CXL", toCancel, orders.get(1)[0]);
            System.out.println("cancelling " + toCancel + " (" + orders.get(1)[0] + ")");
        }
    }

    private static void sendOrder(String clOrdId, String symbol, double qty) throws Exception {
        quickfix.fix44.NewOrderSingle order = new quickfix.fix44.NewOrderSingle(
                new ClOrdID(clOrdId),
                new Side(Side.BUY),
                new TransactTime(),
                new OrdType(OrdType.LIMIT));
        order.set(new Symbol(symbol));
        order.set(new OrderQty(qty));
        order.set(new Price(150.0));
        order.set(new SecurityExchange("L"));
        order.getHeader().setString(OnBehalfOfCompID.FIELD, "FUNDX");
        Session.sendToTarget(order, sessionID);
    }

    private static void sendCancel(String clOrdId, String origClOrdId, String symbol)
            throws Exception {

        quickfix.fix44.OrderCancelRequest request = new quickfix.fix44.OrderCancelRequest(
                new OrigClOrdID(origClOrdId),
                new ClOrdID(clOrdId),
                new Side(Side.BUY),
                new TransactTime());
        request.set(new Symbol(symbol));
        request.getHeader().setString(OnBehalfOfCompID.FIELD, "FUNDX");
        Session.sendToTarget(request, sessionID);
    }

    // ------------------------------------------------------------------

    private static SocketInitiator start(String host, int port) throws Exception {
        String config = """
                [default]
                ConnectionType=initiator
                SocketConnectHost=%s
                SocketConnectPort=%d
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                ReconnectInterval=2
                UseDataDictionary=N
                ResetOnLogon=Y
                FileLogPath=/tmp/nexum-loadclient

                [session]
                BeginString=FIX.4.4
                SenderCompID=FUNDX
                TargetCompID=OMS
                """.formatted(host, port);

        SessionSettings settings = new SessionSettings(
                new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
        SocketInitiator initiator = new SocketInitiator(
                new LoadClient(), new MemoryStoreFactory(), settings, new DefaultMessageFactory());
        initiator.start();
        return initiator;
    }

    private static boolean awaitLogon() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (loggedOn) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    @Override
    public void toAdmin(Message message, SessionID id) {
        // The credential goes on the Logon and nothing else.
        try {
            if ("A".equals(message.getHeader().getString(MsgType.FIELD))) {
                message.setString(Password.FIELD, password);
            }
        } catch (FieldNotFound withoutMsgType) {
            // Not something this sends; nothing to do.
        }
    }

    @Override
    public void fromApp(Message message, SessionID id) {
        try {
            String type = message.getHeader().getString(MsgType.FIELD);
            if ("9".equals(type)) {
                System.out.println("  rejected: " + message.getString(ClOrdID.FIELD));
                return;
            }
            System.out.println("  report " + message.getString(ClOrdID.FIELD)
                    + " status=" + message.getChar(OrdStatus.FIELD));
        } catch (FieldNotFound incomplete) {
            // A report without the fields displayed here is not this tool's concern.
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
    public void fromAdmin(Message message, SessionID id) {}

    @Override
    public void toApp(Message message, SessionID id) {}
}
