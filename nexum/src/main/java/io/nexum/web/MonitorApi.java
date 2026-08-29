package io.nexum.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.nexum.core.Context;
import io.nexum.core.Plugin;
import io.nexum.message.FixDictionary;
import io.nexum.monitor.Anomaly;
import io.nexum.monitor.MonitorPlugin;
import io.nexum.monitor.OrderMonitor;
import io.nexum.monitor.OrderSnapshot;
import io.nexum.order.OrderHistory;
import io.nexum.transport.Transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A read-only view of the book over HTTP, for a grid to render.
 *
 * <p>Two shapes, deliberately: the list returns one flat row per order, which is
 * what a data grid wants and what keeps a thousand-row refresh cheap; the detail
 * of an order is a second call, made when a row is selected. Returning every
 * order's full history in the list would move megabytes to render a screenful.
 *
 * <p>Read-only on purpose. Acting on an order from a browser needs
 * authentication and an audit trail this does not have, and adding a POST here
 * later is a smaller mistake than shipping one now.
 */
public final class MonitorApi implements Plugin {

    private final int port;

    /**
     * The port actually bound, which differs from the requested one when 0 was
     * asked for. Volatile because it is written on the loading thread and read
     * by whoever wants to reach the server.
     */
    private volatile int boundPort;
    private final Path journalDirectory;

    /** Anomalies as they arrive, so the screen can show what is outstanding. */
    private final Map<String, Anomaly> outstanding = new ConcurrentHashMap<>();

    public MonitorApi(int port, Path journalDirectory) {
        this.port = port;
        this.journalDirectory = journalDirectory;
    }

    @Override
    public String name() {
        return "monitor-api";
    }

    @Override
    public List<String> inject() {
        return List.of("monitor", "transport");
    }

    /**
     * The port the monitor is listening on.
     *
     * @return the bound port, which is the requested one unless 0 was asked
     *     for and the OS chose; 0 before the plugin has been applied
     */
    public int port() {
        return boundPort;
    }

    @Override
    public void apply(Context ctx) {
        OrderMonitor monitor = ctx.get("monitor");
        Transport transport = ctx.get("transport");
        OrderHistory history =
                journalDirectory == null ? null : new OrderHistory(journalDirectory);

        ctx.onEvent(MonitorPlugin.ANOMALY, (Anomaly anomaly) ->
                outstanding.put(anomaly.key(), anomaly));
        ctx.onEvent(MonitorPlugin.ANOMALY_CLEARED, (String key) ->
                outstanding.remove(key));

        ctx.effect(() -> {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "cannot open the monitor API on port " + port, failure);
            }

            boundPort = server.getAddress().getPort();

            server.createContext("/", exchange -> serve(exchange, page()));
            server.createContext("/vendor/", MonitorApi::vendor);
            server.createContext("/api/orders", exchange ->
                    json(exchange, orders(monitor)));
            server.createContext("/api/order", exchange ->
                    json(exchange, orderDetail(exchange, monitor, history)));
            // Live session state, for a page or an agent that would otherwise
            // poll. A subscriber names the sessions it cares about, or none
            // for all of them.
            SessionStream stream = new SessionStream(ctx, transport);
            server.createContext("/api/sessions/stream", exchange -> {
                try {
                    stream.subscribe(exchange, query(exchange, "sessions"));
                } catch (IOException gone) {
                    exchange.close();
                }
            });

            server.createContext("/api/sessions", exchange ->
                    json(exchange, sessions(transport)));
            server.createContext("/api/anomalies", exchange ->
                    json(exchange, anomalies()));

            // A small pool: this serves a handful of operators, and every handler
            // reads in-memory state.
            server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "monitor-api");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return () -> {
                stream.closeAll();
                server.stop(0);
            };
        });
    }

    // ------------------------------------------------------------------
    // Payloads
    // ------------------------------------------------------------------

    /** One flat row per order — what a grid binds to directly. */
    private Object orders(OrderMonitor monitor) {
        long now = System.currentTimeMillis();
        List<Object> rows = new ArrayList<>();

        for (OrderSnapshot order : monitor.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", order.orderId());
            row.put("clientClOrdId", order.clientClOrdId());
            row.put("ourClOrdId", order.ourClOrdId());
            row.put("venueOrderId", order.venueOrderId());
            row.put("client", order.clientId());
            row.put("destination", order.destinationId());
            row.put("symbol", order.symbol());
            row.put("side", order.side());
            row.put("orderQty", order.orderQty());
            row.put("cumQty", order.cumQty());
            row.put("leavesQty", order.leavesQty());
            // The enum name classifies (working, pending, bad); the label is
            // what a person reads. Sending only one would make the display
            // either uncolourable or unreadable.
            row.put("state", order.state().name());
            row.put("stateLabel", order.state().label());
            row.put("working", order.state().isWorking());
            row.put("terminal", order.isTerminal());
            row.put("reports", order.reportCount());
            row.put("createdAt", order.createdAt());
            row.put("lastReportAt", order.lastReportAt());
            // Precomputed here rather than in the browser: the screen should not
            // have to know what counts as stale.
            row.put("silentSeconds", order.silentFor(now) / 1000);
            row.put("attention", hasAnomaly(order.orderId()));
            rows.add(row);
        }
        return Map.of("orders", rows, "asOf", now);
    }

    /** Everything about one order, fetched when a row is selected. */
    private Object orderDetail(
            HttpExchange exchange, OrderMonitor monitor, OrderHistory history) {

        String orderId = query(exchange, "id");
        if (orderId == null) {
            return Map.of("error", "give an order id as ?id=");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        monitor.byOrderId(orderId).ifPresent(order -> {
            detail.put("orderId", order.orderId());
            detail.put("state", order.state().name());
            detail.put("stateLabel", order.state().label());
            detail.put("client", order.clientId());
            detail.put("session", order.sessionId());
            detail.put("destination", order.destinationId());
            detail.put("symbol", order.symbol());
            detail.put("orderQty", order.orderQty());
            detail.put("cumQty", order.cumQty());
            // The three identifiers, side by side: this is the question support
            // asks most, and joining them by hand across logs is the tedious way
            // to answer it.
            detail.put("identifiers", Map.of(
                    "client", String.valueOf(order.clientClOrdId()),
                    "ours", String.valueOf(order.ourClOrdId()),
                    "venue", String.valueOf(order.venueOrderId())));
        });

        List<Object> events = new ArrayList<>();
        if (history != null) {
            // Transitions only: the venue's id arriving and a request being
            // answered are bookkeeping, and they break the sequence someone is
            // reading. Both are still in the journal.
            OrderHistory.Page page = history.transitionsOf(orderId, 200);
            detail.put("eventsTotal", page.total());
            detail.put("eventsOmitted", page.omitted());
            for (OrderHistory.Entry entry : page.entries()) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("at", entry.at());
                event.put("type", entry.type());
                event.put("summary", entry.summary());
                // The halves separately, so the display can tell an event from
                // a state without the reader having to.
                event.put("event", entry.event());
                event.put("state", entry.state());
                event.put("fields", entry.fields());
                // The message this event was recorded from, tag by tag, with
                // what each tag is called and what its value means. A raw
                // report is a list of numbers; this is the same thing read.
                event.put("message", decode(entry.fields()));
                // The wire reference is what turns "it went to Filled" into the
                // exact message that said so.
                event.put("wire", entry.wire() == null ? null : entry.wire().toString());
                events.add(event);
            }
        }
        detail.put("events", events);
        detail.put("anomalies", anomaliesFor(orderId));
        return detail;
    }

    /**
     * The FIX message an entry was recorded from, decoded.
     *
     * <p>Journal fields prefixed {@code m.} are the message the venue sent,
     * {@code c.} what the client sent, {@code d.} what went to the venue.
     * Each becomes a row: the tag, its name, its raw value, and what that value
     * means when the tag's values are codes.
     */
    private static List<Object> decode(Map<String, String> fields) {
        Map<String, List<Object>> byPrefix = new LinkedHashMap<>();

        fields.forEach((key, value) -> {
            int dot = key.indexOf('.');
            if (dot != 1) {
                return;
            }
            String prefix = switch (key.charAt(0)) {
                case 'm' -> "venue";
                case 'c' -> "client";
                case 'd' -> "destination";
                // A reply to the client. Same speaker as `d.` — this system —
                // but going the other way, which is what a screen laying the
                // conversation out left to right needs to know.
                case 'r' -> "reply";
                default -> null;
            };
            if (prefix == null) {
                return;
            }

            int tag;
            try {
                tag = Integer.parseInt(key.substring(dot + 1));
            } catch (NumberFormatException notATag) {
                return;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tag", tag);
            // A tag with no name is still shown. One nobody recognises is
            // exactly the one worth looking at.
            row.put("name", FixDictionary.name(tag).orElse(null));
            row.put("value", value);
            row.put("meaning", FixDictionary.meaning(tag, value).orElse(null));
            row.put("session", FixDictionary.isSession(tag));

            byPrefix.computeIfAbsent(prefix, key2 -> new ArrayList<>()).add(row);
        });

        List<Object> views = new ArrayList<>();
        byPrefix.forEach((view, rows) -> {
            rows.sort(Comparator.comparingInt(row -> (Integer) ((Map<?, ?>) row).get("tag")));
            views.add(Map.of("view", view, "fields", rows));
        });
        return views;
    }

    private Object sessions(Transport transport) {
        List<Object> rows = new ArrayList<>();
        for (String sessionId : transport.sessions()) {
            Transport.SessionStatus status = transport.status(sessionId);
            rows.add(Map.of(
                    "sessionId", status.sessionId(),
                    "loggedOn", status.loggedOn(),
                    "nextSenderSeqNum", status.nextSenderSeqNum(),
                    "nextTargetSeqNum", status.nextTargetSeqNum(),
                    "beginString", status.beginString()));
        }
        return Map.of("sessions", rows);
    }

    private Object anomalies() {
        List<Object> rows = new ArrayList<>();
        outstanding.values().forEach(anomaly -> rows.add(Map.of(
                "rule", anomaly.rule(),
                "severity", anomaly.severity().name(),
                "orderId", anomaly.orderId(),
                "summary", anomaly.summary(),
                "detectedAt", anomaly.detectedAt(),
                "evidence", anomaly.evidence())));
        return Map.of("anomalies", rows);
    }

    private List<Object> anomaliesFor(String orderId) {
        List<Object> rows = new ArrayList<>();
        outstanding.values().stream()
                .filter(anomaly -> orderId.equals(anomaly.orderId()))
                .forEach(anomaly -> rows.add(Map.of(
                        "rule", anomaly.rule(),
                        "severity", anomaly.severity().name(),
                        "summary", anomaly.summary())));
        return rows;
    }

    private boolean hasAnomaly(String orderId) {
        return outstanding.values().stream()
                .anyMatch(anomaly -> orderId.equals(anomaly.orderId()));
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private static void json(HttpExchange exchange, Object payload) throws IOException {
        byte[] body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        // Read from a page served somewhere else — a harness UI on another
        // port, a dashboard. These endpoints are read-only and the server is
        // bound to an interface a deployment chose, so what may read them is
        // already decided by where it listens rather than by this header.
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Serve a bundled asset out of the jar.
     *
     * <p>These are the grid's stylesheets and script, kept locally because the
     * network this runs in does not reach a CDN reliably, and a monitor that
     * renders a blank page during an incident is worse than useless.
     */
    private static void vendor(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);

        // Only the files shipped, by exact name. Anything derived from the
        // request that reaches the filesystem is a way out of this directory.
        if (!VENDOR.containsKey(name)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] body;
        try (var in = MonitorApi.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            body = in.readAllBytes();
        }

        exchange.getResponseHeaders().add("Content-Type", VENDOR.get(name));
        // Versioned with the build; a released jar's assets never change.
        exchange.getResponseHeaders().add("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** What may be served from /vendor/, and as what. */
    private static final java.util.Map<String, String> VENDOR = java.util.Map.of(
            "ag-grid.css", "text/css",
            "ag-theme-quartz.css", "text/css",
            "ag-grid-community.min.js", "application/javascript");

    private static void serve(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String query(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(key)) {
                return java.net.URLDecoder.decode(
                        pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String page() {
        return WebPage.HTML;
    }
}
