package io.nexum.demo;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.routing.RoutingEvents;
import io.nexum.order.OutboundEvent;
import io.nexum.order.OrderEvents;
import io.nexum.order.Order;
import io.nexum.order.OrderCache;
import io.nexum.sim.SimClient;
import io.nexum.sim.SimVenue;
import io.nexum.transport.Transport;

import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Proves what actually survives a restart, with both stores on: FIX sequence
 * numbers and in-flight orders.
 *
 * <p>Run with {@code first} to place an order and stop, then with
 * {@code second} to come back up. Two processes, because a restart that never
 * leaves the JVM proves nothing about what was on disk.
 */
public final class RestartDemo {

    static final int CLIENT_PORT = 19891;
    static final int VENUE_PORT = 19892;
    static final Path DATA = Path.of("target/restart");

    private static final String CONFIG = """
            orders:
              journal: target/restart/orders.journal
              sync: true

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
                role: acceptor
                port: %d
                logPath: target/restart
                persistent: true
                resetOnLogon: false

              - id: OMS->LSE
                version: FIX.4.4
                role: initiator
                host: 127.0.0.1
                port: %d
                logPath: target/restart
                persistent: true
                resetOnLogon: false

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->LSE
                fingerprint: any
            """.formatted(CLIENT_PORT, VENUE_PORT);

    public static void main(String[] args) throws Exception {
        String phase = args.length > 0 ? args[0] : "first";
        if ("first".equals(phase)) {
            deleteTree(DATA);
        }

        System.out.println("=== " + phase + " run ===");
        SocketAcceptor venue = SimVenue.start(VENUE_PORT, "LSE", "OMS");

        Context ctx = new Context();
        ctx.on(OrderEvents.REPLAYED, (OrderEvents.Replayed r) ->
                System.out.println("  replayed " + r.recovered() + " orders from journal"));

        PluginLoader loader = Bootstrap.from(CONFIG).start(ctx);
        Transport transport = ctx.get("transport");
        OrderCache cache = ctx.get("orders");

        for (int i = 0; i < 100 && !transport.isLoggedOn("OMS->LSE"); i++) {
            Thread.sleep(100);
        }
        Transport.SessionStatus status = transport.status("OMS->LSE");
        System.out.println("  venue session: loggedOn=" + status.loggedOn()
                + "  nextSender=" + status.nextSenderSeqNum()
                + "  nextTarget=" + status.nextTargetSeqNum());

        SocketInitiator client = SimClient.start(CLIENT_PORT, "FUNDX", "OMS");
        for (int i = 0; i < 100 && !SimClient.isLoggedOn(); i++) {
            Thread.sleep(100);
        }

        if ("first".equals(phase)) {
            System.out.println("\n  placing an order, then stopping abruptly");
            ctx.on(OrderEvents.CREATED, (Order order) ->
                    System.out.println("    created " + order.orderId()
                            + "  client=" + order.client().clOrdId()
                            + "  ours=" + order.destination().clOrdId()));
            SimClient.sendOrder("FUNDX-R1", "VOD", 1000, "L");
            Thread.sleep(2000);

            System.out.println("\n  on disk now:");
            listFiles(DATA);
            System.out.println("\n  sequence numbers: "
                    + transport.status("OMS->LSE").nextSenderSeqNum() + " (sender)");

            // No unload: this is a crash, not a shutdown. Whatever is on disk is
            // all the next run will have.
            System.out.println("\n  (stopping without an orderly shutdown)");
            System.exit(0);
        }

        System.out.println("\n  recovered orders: " + cache.size());
        for (Order order : cache.byOrderId("ORD-1").stream().toList()) {
            System.out.println("    " + order.orderId()
                    + "  state=" + order.state()
                    + "  client=" + order.client().clOrdId()
                    + "  ours=" + order.destination().clOrdId()
                    + "  venue=" + order.destination().orderId());
        }

        System.out.println("\n  a report for the pre-restart order resolves:");
        cache.resolve("SIM-1", "OUR-ORD-1").ifPresentOrElse(
                found -> System.out.println("    -> " + found.orderId()
                        + ", client sees " + found.client().clOrdId()),
                () -> System.out.println("    -> NOT FOUND"));

        loader.unloadAll();
        client.stop();
        venue.stop();
        System.exit(0);
    }

    private static void listFiles(Path root) throws Exception {
        if (!Files.exists(root)) {
            System.out.println("    (nothing)");
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        try {
                            System.out.println("    " + root.relativize(path)
                                    + "  (" + Files.size(path) + " bytes)");
                        } catch (Exception ignored) {
                            System.out.println("    " + path);
                        }
                    });
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (Exception ignored) {
                    // best effort; a leftover file only affects this demo
                }
            });
        }
    }
}
