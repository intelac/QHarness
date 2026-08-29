package io.nexum.demo;

import io.nexum.core.Context;
import io.nexum.core.Events;
import io.nexum.core.Plugin;
import io.nexum.core.PluginLoader;
import io.nexum.core.Scope;
import io.nexum.message.DialectPlugin;
import io.nexum.message.FixMessage;
import io.nexum.message.FixVersion;
import io.nexum.sim.SimVenue;
import io.nexum.transport.QuickFixPlugin;
import io.nexum.transport.Transport;
import io.nexum.transport.TransportEvents;

import quickfix.SocketAcceptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A real FIX session against the simulator, with plugins on the chain.
 *
 * <p>Two QuickFIX/J engines talk over a socket: logon, heartbeats and sequence
 * numbers are entirely theirs. Everything above — enrichment, validation, audit
 * — is plugins, and the order goes out only after they have had it.
 */
public final class LiveDemo {

    static final int PORT = 19876;
    static final String SESSION_ID = "CLIENT->SIM";

    static final int CL_ORD_ID = 11;
    static final int SYMBOL = 55;
    static final int SIDE = 54;
    static final int ORDER_QTY = 38;
    static final int ORD_TYPE = 40;
    static final int PRICE = 44;
    static final int HANDL_INST = 21;
    static final int TRANSACT_TIME = 60;
    static final int CURRENCY = 15;
    static final int ORD_STATUS = 39;
    static final int EXEC_TYPE = 150;
    static final int CUM_QTY = 14;
    static final int ORDER_ID = 37;

    /** Stamps the fields this counterparty requires on every outbound order. */
    static final class EnrichPlugin implements Plugin {
        public String name() {
            return "enrich";
        }

        public void apply(Context ctx) {
            ctx.onGate(TransportEvents.MESSAGE_OUTBOUND, Scope.session(SESSION_ID),
                    (Events.Gate<TransportEvents.InFlight>) (flight, next) -> {
                        if (!"D".equals(flight.message().msgType())) {
                            return next.apply(flight);
                        }
                        FixMessage enriched = flight.message()
                                .set(HANDL_INST, "1")
                                .set(TRANSACT_TIME, utcNow())
                                .set(CURRENCY, "USD");
                        System.out.println("  [enrich] added 21, 60, 15");
                        return next.apply(flight.with(enriched));
                    });
        }
    }

    /** Has decision authority, so it may refuse without delegating. */
    static final class ValidatePlugin implements Plugin {
        public String name() {
            return "validate";
        }

        public void apply(Context ctx) {
            ctx.onGate(TransportEvents.MESSAGE_OUTBOUND, Scope.session(SESSION_ID),
                    (Events.Gate<TransportEvents.InFlight>) (flight, next) -> {
                        if (!"D".equals(flight.message().msgType())) {
                            return next.apply(flight);
                        }
                        String qty = flight.message().get(ORDER_QTY);
                        if (qty == null || Double.parseDouble(qty) <= 0) {
                            System.out.println("  [validate] REJECT quantity=" + qty);
                            return flight.reject("quantity must be positive");
                        }
                        System.out.println("  [validate] pass");
                        return next.apply(flight);
                    });
        }
    }

    /** Observes only, so it always delegates. */
    static final class WireLogPlugin implements Plugin {
        public String name() {
            return "wire-log";
        }

        public void apply(Context ctx) {
            ctx.onEvent(TransportEvents.WIRE, (TransportEvents.Wire wire) -> {
                String arrow = wire.direction() == TransportEvents.Direction.IN ? "<--" : "-->";
                System.out.println("  " + arrow + " " + wire.raw().replace('\001', '|'));
            });
            ctx.onEvent(TransportEvents.SESSION, (TransportEvents.SessionEvent event) ->
                    System.out.println("  [session] " + event.kind() + " " + event.detail()));
        }
    }

    public static void main(String[] args) throws Exception {
        SocketAcceptor venue = SimVenue.start(PORT, "SIM", "CLIENT");
        System.out.println("simulator listening on " + PORT + "\n");

        CountDownLatch filled = new CountDownLatch(1);

        Context ctx = new Context();
        PluginLoader loader = new PluginLoader(ctx);

        loader.load(List.of(
                new DialectPlugin(List.of(
                        new DialectPlugin.SessionDeclaration(SESSION_ID, FixVersion.FIX44))),
                new WireLogPlugin(),
                new EnrichPlugin(),
                new ValidatePlugin(),
                QuickFixPlugin.initiator("client", initiatorConfig())));

        System.out.println("loaded: " + loader.loadedNames() + "\n");

        // Execution reports arrive on the inbound chain; watch them settle.
        ctx.onEvent(TransportEvents.MESSAGE_INBOUND + "/accepted",
                (TransportEvents.InFlight flight) -> {
                    FixMessage report = flight.message();
                    if (!"8".equals(report.msgType())) {
                        return;
                    }
                    System.out.println("  [report] ClOrdID=" + report.get(CL_ORD_ID)
                            + "  OrderID(37)=" + report.get(ORDER_ID)
                            + "  OrdStatus=" + report.get(ORD_STATUS)
                            + "  CumQty=" + report.get(CUM_QTY));
                    if ("2".equals(report.get(ORD_STATUS))) {
                        filled.countDown();
                    }
                });

        Transport transport = ctx.get("transport");

        System.out.println("waiting for logon...");
        for (int i = 0; i < 100 && !transport.isLoggedOn(SESSION_ID); i++) {
            Thread.sleep(100);
        }
        System.out.println("logged on: " + transport.isLoggedOn(SESSION_ID));
        System.out.println("status   : " + transport.status(SESSION_ID) + "\n");

        System.out.println("=== an order the validator refuses ===");
        FixMessage bad = FixMessage.of("D")
                .set(CL_ORD_ID, "ORD-BAD")
                .set(SYMBOL, "IBM")
                .set(SIDE, "1")
                .set(ORDER_QTY, "0")
                .set(ORD_TYPE, "2")
                .set(PRICE, "150.00");
        System.out.println("  sent: " + transport.send(SESSION_ID, bad)
                + "   (never reached the wire)\n");

        System.out.println("=== a good order ===");
        FixMessage order = FixMessage.of("D")
                .set(CL_ORD_ID, "ORD-1")
                .set(SYMBOL, "IBM")
                .set(SIDE, "1")
                .set(ORDER_QTY, "1000")
                .set(ORD_TYPE, "2")
                .set(PRICE, "150.00");
        System.out.println("  sent: " + transport.send(SESSION_ID, order) + "\n");

        boolean settled = filled.await(15, TimeUnit.SECONDS);
        System.out.println("\nfilled: " + settled);

        System.out.println("\n=== unload the enricher, send another ===");
        loader.unload("enrich");
        transport.send(SESSION_ID, FixMessage.of("D")
                .set(CL_ORD_ID, "ORD-2")
                .set(SYMBOL, "MSFT")
                .set(SIDE, "1")
                .set(ORDER_QTY, "500")
                .set(ORD_TYPE, "1"));
        Thread.sleep(1500);
        System.out.println("  no 21/60/15 above — the plugin left nothing behind");

        loader.unloadAll();
        venue.stop();
        System.out.println("\nstopped");
        System.exit(0);
    }

    private static String utcNow() {
        return java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HH:mm:ss.SSS")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
    }

    private static ByteArrayInputStream initiatorConfig() {
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

                [session]
                BeginString=FIX.4.4
                SenderCompID=CLIENT
                TargetCompID=SIM
                """.formatted(PORT);
        return new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8));
    }
}
