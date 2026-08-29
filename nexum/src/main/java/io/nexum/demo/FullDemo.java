package io.nexum.demo;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.Events;
import io.nexum.core.Plugin;
import io.nexum.core.PluginLoader;
import io.nexum.routing.RoutingEvents;
import io.nexum.order.OutboundEvent;
import io.nexum.order.OrderEvents;
import io.nexum.core.Scope;
import io.nexum.message.FixMessage;
import io.nexum.order.Order;
import io.nexum.order.OrderCache;
import io.nexum.sim.SimClient;
import io.nexum.sim.SimVenue;
import io.nexum.transport.Transport;
import io.nexum.transport.TransportEvents;

import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The whole path, driven by configuration: a client order arrives on one
 * session, crosses four layers, leaves on another under our own identifiers, and
 * the venue's reports come back translated into the client's terms.
 *
 * <pre>
 *   SimClient --FIX--&gt; [ our system ] --FIX--&gt; SimVenue
 *                        session
 *                        client      (fingerprint)
 *                        routing
 *                        destination (fingerprint)
 * </pre>
 */
public final class FullDemo {

    static final int CLIENT_PORT = 19881;
    static final int VENUE_PORT = 19882;

    static final int CL_ORD_ID = 11;
    static final int SYMBOL = 55;
    static final int ORDER_QTY = 38;
    static final int HANDL_INST = 21;
    static final int TRANSACT_TIME = 60;
    static final int CURRENCY = 15;
    static final int ON_BEHALF_OF = 115;
    static final int SECURITY_EXCHANGE = 207;

    private static final String CONFIG = """
            orders:
              journal: target/demo/full.journal
              sync: true

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
                role: acceptor
                port: %d
                logPath: target/demo/logs
                persistent: false

              - id: OMS->LSE
                version: FIX.4.4
                role: initiator
                host: 127.0.0.1
                port: %d
                logPath: target/demo/logs
                persistent: false

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

              - id: FUND_Y
                fingerprint:
                  115: FUNDY

            routes:
              - destination: OMS->LSE
                fingerprint:
                  207: L

              - destination: OMS->LSE
                fingerprint: any
            """.formatted(CLIENT_PORT, VENUE_PORT);

    /** SESSION layer: what this counterparty always omits. */
    static final class SessionStamp implements Plugin {
        public String name() {
            return "session-stamp";
        }

        public void apply(Context ctx) {
            ctx.onGate(TransportEvents.MESSAGE_OUTBOUND, Scope.destination("OMS->LSE"),
                    (Events.Gate<TransportEvents.InFlight>) (flight, next) -> {
                        if (!"D".equals(flight.message().msgType())) {
                            return next.apply(flight);
                        }
                        System.out.println("    [dest:LSE] stamping 21, 60");
                        return next.apply(flight.with(flight.message()
                                .set(HANDL_INST, "1")
                                .set(TRANSACT_TIME, utcNow())));
                    });
        }
    }

    /** CLIENT layer: rules for this customer alone. */
    static final class ClientDefaults implements Plugin {
        public String name() {
            return "client-fundx";
        }

        public void apply(Context ctx) {
            ctx.onGate(TransportEvents.MESSAGE_INBOUND, Scope.client("FUND_X"),
                    (Events.Gate<TransportEvents.InFlight>) (flight, next) -> {
                        System.out.println("    [client:FUND_X] defaulting currency");
                        return next.apply(flight.with(flight.message().set(CURRENCY, "USD")));
                    });
        }
    }

    /** ROUTING layer: has decision authority, so it may refuse. */
    static final class SizeLimit implements Plugin {
        public String name() {
            return "size-limit";
        }

        public void apply(Context ctx) {
            ctx.onGate(TransportEvents.MESSAGE_INBOUND, Scope.routing(),
                    (Events.Gate<TransportEvents.InFlight>) (flight, next) -> {
                        String qty = flight.message().get(ORDER_QTY);
                        if (qty != null && Double.parseDouble(qty) > 100000) {
                            System.out.println("    [routing] REJECT size " + qty);
                            return flight.reject("order exceeds size limit");
                        }
                        System.out.println("    [routing] size ok");
                        return next.apply(flight);
                    });
        }
    }

    public static void main(String[] args) throws Exception {
        SocketAcceptor venue = SimVenue.start(VENUE_PORT, "LSE", "OMS");
        System.out.println("venue simulator on " + VENUE_PORT);

        Context ctx = new Context();
        PluginLoader loader = Bootstrap.from(CONFIG)
                .with(new SessionStamp(), new ClientDefaults(), new SizeLimit())
                .start(ctx);

        System.out.println("loaded: " + loader.loadedNames() + "\n");

        CountDownLatch filled = new CountDownLatch(1);
        watch(ctx, filled);

        Transport transport = ctx.get("transport");
        for (int i = 0; i < 100 && !transport.isLoggedOn("OMS->LSE"); i++) {
            Thread.sleep(100);
        }
        System.out.println("venue session logged on: " + transport.isLoggedOn("OMS->LSE"));

        SocketInitiator client = SimClient.start(CLIENT_PORT, "FUNDX", "OMS");
        for (int i = 0; i < 100 && !SimClient.isLoggedOn(); i++) {
            Thread.sleep(100);
        }
        System.out.println("client session logged on: " + SimClient.isLoggedOn() + "\n");

        System.out.println("=== FUND_X sends an order ===");
        SimClient.sendOrder("FUNDX-ORD-1", "VOD", 1000, "L");
        boolean settled = filled.await(15, TimeUnit.SECONDS);
        System.out.println("\nfilled: " + settled);

        System.out.println("\n=== an order the routing layer refuses ===");
        SimClient.sendOrder("FUNDX-ORD-2", "VOD", 500000, "L");
        Thread.sleep(1200);

        System.out.println("\n=== an unrecognised client ===");
        SimClient.sendOrderAs("UNKNOWN", "FUNDZ-ORD-1", "IBM", 100, "N");
        Thread.sleep(1200);

        OrderCache cache = ctx.get("orders");
        System.out.println("\ncache: " + cache.size() + " orders, "
                + cache.active().size() + " still active");
        for (Order order : cache.active()) {
            System.out.println("  " + order.orderId() + " " + order.state());
        }

        loader.unloadAll();
        client.stop();
        venue.stop();
        System.out.println("\nstopped");
        System.exit(0);
    }

    private static void watch(Context ctx, CountDownLatch filled) {
        ctx.on(OrderEvents.CREATED, (Order order) ->
                System.out.println("  [cache] " + order.orderId()
                        + "  client=" + order.client().clOrdId()
                        + "  ours=" + order.destination().clOrdId()
                        + "  -> " + order.destinationId()));

        ctx.on(OrderEvents.STATE_CHANGED, (OutboundEvent.StateChanged changed) -> {
            System.out.println("  [report] " + changed.orderId()
                    + "  " + changed.from() + " -> " + changed.to()
                    + "  cum=" + changed.cumQty());
            if (changed.to() == io.nexum.order.OrderState.FILLED) {
                filled.countDown();
            }
        });

        ctx.on(RoutingEvents.RULE_UNMATCHED, (RoutingEvents.Unmatched miss) ->
                System.out.println("  [unrouted] at " + miss.stage() + ": " + miss.reasons()));

        ctx.on(OrderEvents.REPORT_UNMATCHED, (OrderEvents.UnmatchedReport miss) ->
                System.out.println("  [unmatched report] " + miss));
    }

    private static String utcNow() {
        return java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HH:mm:ss.SSS")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
    }
}
