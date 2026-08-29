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
import quickfix.field.CxlRejResponseTo;
import quickfix.field.CumQty;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Drives named order scenarios at a running NEXUM.
 *
 * <p>One order proves the path is open. These prove it behaves — a fill after
 * an amendment, a cancel that arrives too late, a replace against an order that
 * already has a position. Each scenario says what it expects and reports
 * whether that is what happened, so this is a check rather than a demonstration.
 *
 * <pre>
 *   java -cp nexum.jar io.nexum.demo.Scenarios &lt;host&gt; &lt;port&gt; &lt;password&gt; [name...]
 * </pre>
 *
 * <p>With no names every scenario runs. The symbols are chosen to match how the
 * venue simulator is configured — see {@code VenueRunner}.
 */
public final class Scenarios implements Application {

    /** Symbols the venue simulator must be started with, and what each does. */
    public static final String TRADES = "VOD";      // fills immediately
    public static final String RESTS = "BP";        // sits on the book
    public static final String PARTIAL = "GLEN";    // part fills, rest works
    public static final String REFUSES_CANCEL = "RIO";
    public static final String REJECTED = "TSCO";   // the venue refuses it

    private static volatile SessionID sessionID;
    private static volatile boolean loggedOn;
    private static String password;

    /** Reports seen per ClOrdID, in arrival order. */
    private static final Map<String, List<Report>> received = new ConcurrentHashMap<>();

    /** One message the venue sent back, reduced to what a scenario checks. */
    record Report(String msgType, String clOrdId, String ordStatus,
                  double cumQty, String cxlRejResponseTo) {}

    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: Scenarios <host> <port> <password> [name...]");
            System.err.println();
            System.err.println("scenarios: " + String.join(", ", ALL.keySet()));
            System.exit(2);
            return;
        }

        password = args[2];
        List<String> wanted = args.length > 3
                ? List.of(args).subList(3, args.length)
                : List.copyOf(ALL.keySet());

        SocketInitiator client = start(args[0], Integer.parseInt(args[1]));
        try {
            if (!awaitLogon()) {
                System.err.println("could not log on — check the password");
                System.exit(1);
                return;
            }

            int failed = 0;
            for (String name : wanted) {
                Scenario scenario = ALL.get(name);
                if (scenario == null) {
                    System.out.println("? " + name + " — no such scenario");
                    failed++;
                    continue;
                }
                failed += run(name, scenario) ? 0 : 1;
            }

            System.out.println();
            System.out.println(failed == 0
                    ? "all " + wanted.size() + " scenarios behaved as expected"
                    : failed + " of " + wanted.size() + " did not");
            System.exit(failed == 0 ? 0 : 1);
        } finally {
            client.stop(true);
        }
    }

    private static boolean run(String name, Scenario scenario) {
        String tag = name + "-" + (System.currentTimeMillis() % 100000);
        try {
            String outcome = scenario.play(new Orders(tag));
            if (outcome == null) {
                System.out.println("PASS  " + name);
                return true;
            }
            System.out.println("FAIL  " + name + " — " + outcome);
            return false;
        } catch (Exception failure) {
            System.out.println("FAIL  " + name + " — " + failure);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // The scenarios
    // ------------------------------------------------------------------

    interface Scenario {
        /** @return null when it behaved, or what went wrong */
        String play(Orders o) throws Exception;
    }

    private static final Map<String, Scenario> ALL = new LinkedHashMap<>();

    static {
        ALL.put("new-fill", o -> {
            String id = o.send(TRADES, 1000);
            return o.expect(id, "2", 1000, "an order on a trading symbol should fill");
        });

        ALL.put("new-rest", o -> {
            String id = o.send(RESTS, 2500);
            return o.expect(id, "0", 0, "an order on a resting symbol should be acknowledged"
                    + " and stay working");
        });

        ALL.put("new-partial", o -> {
            String id = o.send(PARTIAL, 900);
            // A third trades; the rest stays live. cumQty is what matters —
            // OrdStatus alone would not distinguish this from a full fill.
            return o.expect(id, "1", 300, "a partial should report what actually traded");
        });

        ALL.put("new-rejected", o -> {
            String id = o.send(REJECTED, 1000);
            // A rejection is terminal, and belongs on the monitor beside the
            // fills — an order the venue would not take is exactly what
            // someone watching needs to see.
            return o.expect(id, "8", 0, "an order the venue refuses should come back rejected");
        });

        ALL.put("cancel-rejected-order", o -> {
            String id = o.send(REJECTED, 1000);
            String problem = o.expect(id, "8", 0, "the order should be rejected first");
            if (problem != null) {
                return problem;
            }
            // Rejected is terminal: there is nothing left to cancel, and the
            // request must not reach the venue.
            String cancel = o.cancel(id, REJECTED);
            o.settle();
            return o.reportsFor(cancel).isEmpty()
                    ? null
                    : "a cancel against a rejected order should not reach the venue";
        });

        ALL.put("cancel-resting", o -> {
            String id = o.send(RESTS, 2500);
            String problem = o.expect(id, "0", 0, "the order should be working first");
            if (problem != null) {
                return problem;
            }
            String cancel = o.cancel(id, RESTS);
            return o.expect(cancel, "4", 0,
                    "a cancel against a working order should be confirmed");
        });

        ALL.put("cancel-after-partial", o -> {
            String id = o.send(PARTIAL, 900);
            String problem = o.expect(id, "1", 300, "it should be partially filled first");
            if (problem != null) {
                return problem;
            }
            String cancel = o.cancel(id, PARTIAL);
            // The quantity already traded survives the cancel: cancelling
            // leaves what was done, it does not undo it.
            return o.expect(cancel, "4", 300,
                    "a cancel must keep the quantity already filled");
        });

        ALL.put("cancel-too-late", o -> {
            String id = o.send(TRADES, 1000);
            String problem = o.expect(id, "2", 1000, "the order should fill first");
            if (problem != null) {
                return problem;
            }
            // Nothing left to cancel. The order refuses it and nothing reaches
            // the venue, so no report comes back at all.
            String cancel = o.cancel(id, TRADES);
            o.settle();
            return o.reportsFor(cancel).isEmpty()
                    ? null
                    : "a cancel against a filled order should not reach the venue, but "
                            + o.reportsFor(cancel).size() + " report(s) came back";
        });

        ALL.put("cancel-refused", o -> {
            String id = o.send(REFUSES_CANCEL, 1000);
            String problem = o.expect(id, "0", 0, "the order should be working first");
            if (problem != null) {
                return problem;
            }
            String cancel = o.cancel(id, REFUSES_CANCEL);
            o.settle();

            List<Report> answers = o.reportsFor(cancel);
            if (answers.isEmpty()) {
                return "the refusal never came back";
            }
            Report reject = answers.get(0);
            if (!"9".equals(reject.msgType())) {
                return "expected a Cancel Reject (35=9) but got 35=" + reject.msgType();
            }
            // 434=1 is what says this refuses a cancel rather than an order —
            // OrdStatus on a Cancel Reject still reads New.
            return "1".equals(reject.cxlRejResponseTo())
                    ? null
                    : "expected CxlRejResponseTo(434)=1 but got " + reject.cxlRejResponseTo();
        });

        ALL.put("amend-resting", o -> {
            String id = o.send(RESTS, 2500);
            String problem = o.expect(id, "0", 0, "the order should be working first");
            if (problem != null) {
                return problem;
            }
            String amend = o.replace(id, RESTS, 4000, 155.0);
            return o.expect(amend, "5", 0,
                    "a replace against a working order should be accepted");
        });

        ALL.put("amend-after-partial", o -> {
            String id = o.send(PARTIAL, 900);
            String problem = o.expect(id, "1", 300, "it should be partially filled first");
            if (problem != null) {
                return problem;
            }
            // Amending an order that already has a position: the filled
            // quantity is not something the amendment may undo.
            String amend = o.replace(id, PARTIAL, 1500, 149.0);
            o.settle();
            List<Report> answers = o.reportsFor(amend);
            if (answers.isEmpty()) {
                return "the replace was never answered";
            }
            return "5".equals(answers.get(0).ordStatus())
                    ? null
                    : "expected REPLACED (39=5) but got 39=" + answers.get(0).ordStatus();
        });

        ALL.put("amend-then-cancel", o -> {
            String id = o.send(RESTS, 2500);
            String problem = o.expect(id, "0", 0, "the order should be working first");
            if (problem != null) {
                return problem;
            }
            String amend = o.replace(id, RESTS, 4000, 155.0);
            problem = o.expect(amend, "5", 0, "the replace should be accepted");
            if (problem != null) {
                return problem;
            }
            // Cancelling after a replace exercises identifier bookkeeping: the
            // order now answers to the replace's ClOrdID at the venue, and a
            // cancel quoting the original must still find it.
            String cancel = o.cancel(id, RESTS);
            return o.expect(cancel, "4", 0,
                    "a cancel quoting the original id must still reach the order");
        });

        ALL.put("amend-unknown", o -> {
            // Never sent, so nothing was ever minted for it. This must be
            // refused rather than resolved to something plausible.
            String amend = o.replaceUnknown("NEVER-SENT-" + o.tag, RESTS);
            o.settle();
            return o.reportsFor(amend).isEmpty()
                    ? null
                    : "an amendment for an unknown order should not reach the venue";
        });
    }

    // ------------------------------------------------------------------
    // Sending, and waiting for what comes back
    // ------------------------------------------------------------------

    /** The messages one scenario sends, named so they cannot collide. */
    static final class Orders {

        private final String tag;
        private int n;

        Orders(String tag) {
            this.tag = tag;
        }

        String send(String symbol, double qty) throws Exception {
            String clOrdId = tag + "-" + (++n);
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
            return clOrdId;
        }

        String cancel(String origClOrdId, String symbol) throws Exception {
            String clOrdId = tag + "-cxl-" + (++n);
            quickfix.fix44.OrderCancelRequest request = new quickfix.fix44.OrderCancelRequest(
                    new OrigClOrdID(origClOrdId),
                    new ClOrdID(clOrdId),
                    new Side(Side.BUY),
                    new TransactTime());
            request.set(new Symbol(symbol));
            request.getHeader().setString(OnBehalfOfCompID.FIELD, "FUNDX");
            Session.sendToTarget(request, sessionID);
            return clOrdId;
        }

        String replace(String origClOrdId, String symbol, double qty, double price)
                throws Exception {
            String clOrdId = tag + "-rpl-" + (++n);
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
            Session.sendToTarget(request, sessionID);
            return clOrdId;
        }

        String replaceUnknown(String origClOrdId, String symbol) throws Exception {
            return replace(origClOrdId, symbol, 100, 150.0);
        }

        /**
         * Wait for a report on this identifier that matches, and say what was
         * seen instead when none does.
         */
        String expect(String clOrdId, String ordStatus, double cumQty, String why)
                throws InterruptedException {

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                for (Report report : reportsFor(clOrdId)) {
                    if (ordStatus.equals(report.ordStatus())
                            && Math.abs(report.cumQty() - cumQty) < 0.001) {
                        return null;
                    }
                }
                Thread.sleep(50);
            }

            List<String> seen = reportsFor(clOrdId).stream()
                    .map(r -> "39=" + r.ordStatus() + "/cum=" + (long) r.cumQty())
                    .toList();
            return why + " (wanted 39=" + ordStatus + "/cum=" + (long) cumQty
                    + ", saw " + (seen.isEmpty() ? "nothing" : seen) + ")";
        }

        /** Give anything in flight time to arrive, for a scenario asserting silence. */
        void settle() throws InterruptedException {
            Thread.sleep(2500);
        }

        List<Report> reportsFor(String clOrdId) {
            return List.copyOf(received.getOrDefault(clOrdId, List.of()));
        }
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
                FileLogPath=/tmp/nexum-scenarios

                [session]
                BeginString=FIX.4.4
                SenderCompID=FUNDX
                TargetCompID=OMS
                """.formatted(host, port);

        SessionSettings settings = new SessionSettings(
                new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
        SocketInitiator initiator = new SocketInitiator(
                new Scenarios(), new MemoryStoreFactory(), settings, new DefaultMessageFactory());
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
        try {
            if ("A".equals(message.getHeader().getString(MsgType.FIELD))
                    && password != null && !password.isEmpty()) {
                message.setString(Password.FIELD, password);
            }
        } catch (FieldNotFound withoutMsgType) {
            // Not something this sends.
        }
    }

    @Override
    public void fromApp(Message message, SessionID id) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            if (!"8".equals(msgType) && !"9".equals(msgType)) {
                return;
            }
            String clOrdId = message.getString(ClOrdID.FIELD);
            Report report = new Report(
                    msgType,
                    clOrdId,
                    message.isSetField(OrdStatus.FIELD)
                            ? String.valueOf(message.getChar(OrdStatus.FIELD)) : null,
                    message.isSetField(CumQty.FIELD) ? message.getDouble(CumQty.FIELD) : 0,
                    message.isSetField(CxlRejResponseTo.FIELD)
                            ? String.valueOf(message.getChar(CxlRejResponseTo.FIELD)) : null);

            received.computeIfAbsent(clOrdId, key -> new ArrayList<>()).add(report);
        } catch (FieldNotFound incomplete) {
            // A report without a ClOrdID is not something a scenario can match.
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
