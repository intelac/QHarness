package io.nexum.demo;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.sim.SimClient;
import io.nexum.sim.SimVenue;
import io.nexum.transport.Transport;

import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

/**
 * The monitor screen with a live book behind it.
 *
 * <p>Places a spread of orders — filled, working, and one the venue accepts and
 * then says nothing about — so the grid has something to show and the anomaly
 * rules have something to find.
 *
 * <p>Runs until interrupted. Open http://localhost:8181.
 */
public final class WebDemo {

    static final int CLIENT_PORT = 19921;
    static final int VENUE_PORT = 19922;
    static final int WEB_PORT = 8181;

    private static final String CONFIG = """
            web:
              port: %d

            orders:
              journal: target/web/orders
              sync: true

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
                role: acceptor
                port: %d
                logPath: target/web/logs
                persistent: false

              - id: OMS->LSE
                version: FIX.4.4
                role: initiator
                host: 127.0.0.1
                port: %d
                logPath: target/web/logs
                persistent: false

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->LSE
                fingerprint: any
            """.formatted(WEB_PORT, CLIENT_PORT, VENUE_PORT);

    public static void main(String[] args) throws Exception {
        SimVenue.goSilentOn("STUCK");
        SocketAcceptor venue = SimVenue.start(VENUE_PORT, "LSE", "OMS");

        Context ctx = new Context();
        PluginLoader loader = Bootstrap.from(CONFIG).start(ctx);

        Transport transport = ctx.get("transport");
        for (int i = 0; i < 100 && !transport.isLoggedOn("OMS->LSE"); i++) {
            Thread.sleep(100);
        }
        SocketInitiator client = SimClient.start(CLIENT_PORT, "FUNDX", "OMS");
        for (int i = 0; i < 100 && !SimClient.isLoggedOn(); i++) {
            Thread.sleep(100);
        }

        System.out.println("\n  monitor on http://localhost:" + WEB_PORT + "\n");

        String[] symbols = {"VOD", "IBM", "MSFT", "AAPL", "BARC", "HSBA", "TSLA", "GOOG"};
        for (int i = 0; i < symbols.length; i++) {
            SimClient.sendOrder("FUNDX-" + (i + 1), symbols[i], (i + 1) * 250, "L");
            Thread.sleep(120);
        }

        // One the venue takes and never reports on, so the stuck-pending rule
        // has something real to find.
        SimClient.sendOrder("FUNDX-STUCK", "STUCK", 750, "L");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            loader.unloadAll();
            client.stop();
            venue.stop();
        }));

        System.out.println("  " + symbols.length + " orders placed, one left unacknowledged");
        System.out.println("  ctrl-c to stop\n");
        Thread.currentThread().join();
    }
}
