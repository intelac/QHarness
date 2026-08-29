package io.nexum.demo;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.monitor.Anomaly;
import io.nexum.monitor.MonitorPlugin;
import io.nexum.monitor.OrderMonitor;
import io.nexum.monitor.OrderSnapshot;
import io.nexum.monitor.Projections;
import io.nexum.order.OrderState;
import io.nexum.sim.SimClient;
import io.nexum.sim.SimVenue;
import io.nexum.transport.Transport;

import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Monitoring over a live book: projections folded from the event stream, and
 * anomaly rules finding an order the venue accepted and then went quiet about.
 */
public final class MonitorDemo {

    static final int CLIENT_PORT = 19901;
    static final int VENUE_PORT = 19902;

    private static final String CONFIG = """
            monitor:
              enabled: false          # this demo mounts its own, with short thresholds

            orders:
              journal: target/monitor/orders
              sync: true

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
                role: acceptor
                port: %d
                logPath: target/monitor/logs
                persistent: false

              - id: OMS->LSE
                version: FIX.4.4
                role: initiator
                host: 127.0.0.1
                port: %d
                logPath: target/monitor/logs
                persistent: false

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->LSE
                fingerprint: any
            """.formatted(CLIENT_PORT, VENUE_PORT);

    public static void main(String[] args) throws Exception {
        // The venue will take orders in this symbol and never report on them.
        SimVenue.goSilentOn("STUCK");
        SocketAcceptor venue = SimVenue.start(VENUE_PORT, "LSE", "OMS");

        Context ctx = new Context();

        // A short threshold so the demo does not have to wait out a real one.
        MonitorPlugin monitorPlugin = new MonitorPlugin(
                2_000,
                TimeUnit.MINUTES.toMillis(30),
                List.of(
                        io.nexum.monitor.AnomalyRule.stuckPending(3_000),
                        io.nexum.monitor.AnomalyRule.silentPartial(3_000),
                        io.nexum.monitor.AnomalyRule.overfill()));

        PluginLoader loader = Bootstrap.from(CONFIG).with(monitorPlugin).start(ctx);
        System.out.println("loaded: " + loader.loadedNames() + "\n");

        ctx.onEvent(MonitorPlugin.ANOMALY, (Anomaly anomaly) ->
                System.out.println("  !! [" + anomaly.severity() + "] " + anomaly.rule()
                        + " on " + anomaly.orderId() + " — " + anomaly.summary()
                        + "  " + anomaly.evidence()));

        ctx.onEvent(MonitorPlugin.ANOMALY_CLEARED, (String key) ->
                System.out.println("  ok cleared: " + key));

        Transport transport = ctx.get("transport");
        for (int i = 0; i < 100 && !transport.isLoggedOn("OMS->LSE"); i++) {
            Thread.sleep(100);
        }
        SocketInitiator client = SimClient.start(CLIENT_PORT, "FUNDX", "OMS");
        for (int i = 0; i < 100 && !SimClient.isLoggedOn(); i++) {
            Thread.sleep(100);
        }
        System.out.println("both sessions up\n");

        OrderMonitor monitor = ctx.get("monitor");

        System.out.println("=== three orders that trade normally ===");
        SimClient.sendOrder("M-1", "VOD", 1000, "L");
        SimClient.sendOrder("M-2", "IBM", 500, "N");
        SimClient.sendOrder("M-3", "MSFT", 2000, "N");
        Thread.sleep(1500);

        report(monitor);

        System.out.println("\n=== an order the venue accepts and never reports on ===");
        SimClient.sendOrder("M-4", "STUCK", 750, "L");
        Thread.sleep(1000);

        System.out.println("  active orders now:");
        for (OrderSnapshot order : monitor.active()) {
            System.out.println("    " + order.orderId() + "  " + order.symbol()
                    + "  " + order.state()
                    + "  filled=" + order.cumQty() + "/" + order.orderQty());
        }

        System.out.println("\n  waiting for the rules to notice...");
        Thread.sleep(6000);

        System.out.println("\n=== the book, folded from events ===");
        report(monitor);

        System.out.println("\n=== a report that matches no order ===");
        System.out.println("  unmatched so far: " + monitor.unmatchedReports().size());

        loader.unloadAll();
        client.stop();
        venue.stop();
        System.out.println("\nstopped");
        System.exit(0);
    }

    private static void report(OrderMonitor monitor) {
        monitor.<Map<OrderState, Integer>>projection("by-state").ifPresent(counts ->
                System.out.println("  by state : " + counts));

        monitor.<Map<String, Projections.ClientTotals>>projection("by-client")
                .ifPresent(totals -> totals.forEach((clientId, client) ->
                        System.out.println("  " + clientId + " : " + client.orders()
                                + " orders, " + client.completed() + " done, "
                                + client.filled() + "/" + client.ordered() + " filled")));

        System.out.println("  active   : " + monitor.active().size());
    }
}
