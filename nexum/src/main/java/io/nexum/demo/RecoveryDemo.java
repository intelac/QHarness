package io.nexum.demo;

import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.routing.RoutingEvents;
import io.nexum.order.OutboundEvent;
import io.nexum.order.OrderEvents;
import io.nexum.order.InMemoryOrderCache;
import io.nexum.order.Order;
import io.nexum.order.OrderCache;
import io.nexum.order.OrderCachePlugin;
import io.nexum.order.OrderState;
import io.nexum.order.OrderView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What survives a restart.
 *
 * <p>An order the venue knows about but this system has forgotten is
 * unrecoverable — its reports arrive carrying identifiers that resolve to
 * nothing. The journal exists to make that impossible, and this shows the
 * resolution working after the process that placed the order is gone.
 */
public final class RecoveryDemo {

    static final int CL_ORD_ID = 11;
    static final int SYMBOL = 55;
    static final int ORDER_QTY = 38;

    public static void main(String[] args) throws Exception {
        // A directory of dated segments, not a single file.
        Path journal = Path.of("target/demo/orders");
        deleteTree(journal);

        System.out.println("=== first run: place two orders ===");
        Context first = new Context();
        PluginLoader firstLoader = new PluginLoader(first);
        firstLoader.load(List.of(new OrderCachePlugin(journal, true)));

        OrderCache cache = first.get("orders");
        cache.put(order("ORD-1", "FUNDX-1", "OUR-1", "VOD", "1000"));
        cache.indexOutbound("OUR-1", "ORD-1");
        cache.put(order("ORD-2", "FUNDX-2", "OUR-2", "IBM", "500"));
        cache.indexOutbound("OUR-2", "ORD-2");

        // ORD-1 gets an ack carrying the venue's own id, then a partial fill.
        cache.indexVenueOrderId("LSE-9981", "ORD-1");
        cache.update(cache.byOrderId("ORD-1").orElseThrow()
                .withVenueOrderId("LSE-9981")
                .withState(OrderState.PARTIALLY_FILLED));

        System.out.println("  cached : " + cache.size() + " orders, "
                + cache.active().size() + " active");
        System.out.println("  journal: " + countLines(journal) + " lines across "
                + segmentCount(journal) + " segment(s)");

        // Simulate a crash: the process ends without any orderly shutdown.
        firstLoader.unloadAll();

        System.out.println("\n=== journal on disk ===");
        forEachLine(journal, line -> System.out.println("  " + line.replace('\t', '|')));

        System.out.println("\n=== second run: nothing in memory, everything replayed ===");
        Context second = new Context();
        PluginLoader secondLoader = new PluginLoader(second);
        second.on(OrderEvents.REPLAYED, (OrderEvents.Replayed r) -> {
            System.out.println("  replayed " + r.recovered() + " orders from journal");
            if (!r.skipped().isEmpty()) {
                System.out.println("  skipped: " + r.skipped());
            }
        });
        secondLoader.load(List.of(new OrderCachePlugin(journal, true)));

        OrderCache recovered = second.get("orders");
        System.out.println("  cached : " + recovered.size() + " orders, "
                + recovered.active().size() + " active");

        System.out.println("\n=== a report arrives for an order placed before the restart ===");
        System.out.println("  by OrderID(37)=LSE-9981:");
        recovered.resolve("LSE-9981", null).ifPresentOrElse(
                found -> System.out.println("    -> " + found.orderId()
                        + "  state=" + found.state()
                        + "  client sees " + found.client().clOrdId()
                        + "  symbol=" + found.client().field(SYMBOL)),
                () -> System.out.println("    -> NOT FOUND"));

        System.out.println("  by ClOrdID(11)=OUR-2, which never got a 37:");
        recovered.resolve(null, "OUR-2").ifPresentOrElse(
                found -> System.out.println("    -> " + found.orderId()
                        + "  state=" + found.state()
                        + "  client sees " + found.client().clOrdId()),
                () -> System.out.println("    -> NOT FOUND"));

        System.out.println("\n=== a truncated journal still starts ===");
        Path damaged = Path.of("target/demo/damaged");
        deleteTree(damaged);
        Files.createDirectories(damaged);
        List<String> lines = new java.util.ArrayList<>();
        forEachLine(journal, lines::add);
        lines.add("this line is not a record");
        lines.add(String.valueOf(System.currentTimeMillis()));   // cut short by a crash
        Files.write(damaged.resolve("orders-2026-08-24.journal"), lines);

        Context third = new Context();
        third.on(OrderEvents.REPLAYED, (OrderEvents.Replayed r) -> {
            r.skipped().forEach(reason -> System.out.println("  skipped " + reason));
            System.out.println("  still recovered " + r.recovered() + " orders");
        });
        new PluginLoader(third).load(List.of(new OrderCachePlugin(damaged, true)));

        secondLoader.unloadAll();
        System.out.println("\ndone");
    }

    private static long countLines(Path directory) throws Exception {
        long[] total = {0};
        forEachLine(directory, line -> total[0]++);
        return total[0];
    }

    private static long segmentCount(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".journal"))
                    .count();
        }
    }

    private static void forEachLine(Path directory, java.util.function.Consumer<String> action)
            throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path segment : paths.sorted().toList()) {
                if (segment.getFileName().toString().endsWith(".journal")) {
                    Files.readAllLines(segment).forEach(action);
                }
            }
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

    private static Order order(
            String orderId, String clientClOrdId, String ourClOrdId, String symbol, String qty) {

        Map<Integer, String> clientFields = Map.of(
                CL_ORD_ID, clientClOrdId, SYMBOL, symbol, ORDER_QTY, qty);
        Map<Integer, String> destFields = Map.of(
                CL_ORD_ID, ourClOrdId, SYMBOL, symbol + ".L", ORDER_QTY, qty);

        return new Order(
                orderId,
                "OMS->FUNDX",
                "FUND_X",
                "OMS->LSE",
                OrderView.of(clientClOrdId, clientFields),
                OrderView.of(orderId, Map.of()),
                OrderView.of(ourClOrdId, destFields),
                OrderState.PENDING_NEW);
    }
}
