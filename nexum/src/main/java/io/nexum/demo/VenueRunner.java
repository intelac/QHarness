package io.nexum.demo;

import io.nexum.sim.SimVenue;

import quickfix.SocketAcceptor;

import java.util.concurrent.CountDownLatch;

/**
 * Runs a simulated venue on its own, for a deployment that has no real one yet.
 *
 * <p>Without something at the other end every order stops at PENDING_NEW, and
 * a monitor full of orders waiting for an acknowledgement looks like a system
 * that is stuck rather than one that is working.
 *
 * <pre>
 *   java -cp nexum.jar io.nexum.demo.VenueRunner &lt;port&gt; [resting-symbols...]
 * </pre>
 *
 * <p>Symbols named on the command line rest on the book instead of trading
 * immediately, so a cancel or a replace has something live to act on.
 */
public final class VenueRunner {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9881;

        // Each symbol carries how it should behave, so one venue can show a
        // fill, a resting order, a partial and a rejection side by side.
        java.util.List<String> matched = new java.util.ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String[] parts = args[i].split(":", 2);
            String symbol = parts[0];
            String behaviour = parts.length > 1 ? parts[1] : "rest";

            switch (behaviour) {
                case "rest" -> SimVenue.restOn(symbol);
                case "match" -> {
                    SimVenue.matchOn(symbol);
                    matched.add(symbol);
                }
                case "partial" -> SimVenue.partialOn(symbol);
                case "reject" -> SimVenue.rejectOn(symbol);
                case "silent" -> SimVenue.goSilentOn(symbol);
                case "refuse-cancel" -> {
                    SimVenue.restOn(symbol);
                    SimVenue.refuseCancelsOn(symbol);
                }
                default -> {
                    System.err.println("unknown behaviour \"" + behaviour + "\" for "
                            + symbol + "; known: match, rest, partial, reject,"
                            + " silent, refuse-cancel");
                    System.exit(2);
                    return;
                }
            }
            System.out.println(symbol + ": " + behaviour);
        }

        SocketAcceptor venue = SimVenue.start(port, "LSE", "OMS");
        System.out.println("venue simulator listening on " + port);

        if (!matched.isEmpty()) {
            startPriceFeed(matched);
        }

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            venue.stop(true);
            stopped.countDown();
        }, "venue-shutdown"));

        stopped.await();
    }

    /**
     * Walk the matched symbols' prices, so resting orders trade over time.
     *
     * <p>A venue whose price never moves fills only what was marketable on
     * arrival; an order placed away from the market would rest for ever and the
     * fill path would never be exercised past its first report. The walk is
     * small and frequent rather than large and rare, so an order fills over
     * several reports the way a real one does.
     */
    private static void startPriceFeed(java.util.List<String> symbols) {
        // Seeded, so a run that shows a problem can be repeated exactly.
        java.util.Random walk = new java.util.Random(20260826L);
        Thread feed = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
                SimVenue venue = SimVenue.running();
                if (venue == null) {
                    continue;
                }
                for (String symbol : symbols) {
                    double current = SimVenue.marketPrice(symbol);
                    // Within a percent a step, so a limit a little away from the
                    // market is reached in a few steps rather than at once.
                    double next = Math.max(1, current * (1 + (walk.nextDouble() - 0.5) / 50));
                    venue.reprice(symbol, Math.round(next * 100) / 100.0);
                }
            }
        }, "venue-price-feed");
        feed.setDaemon(true);
        feed.start();
        System.out.println("price feed walking " + symbols);
    }
}
