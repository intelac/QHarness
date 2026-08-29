package io.nexum.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.nexum.core.Context;
import io.nexum.core.Plugin;
import io.nexum.order.OrderCache;
import io.nexum.order.OrderHistory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Exposes this system's tools to an agent, over MCP.
 *
 * <p>Streamable HTTP rather than stdio, because this process is long-lived and
 * holds the FIX sessions: a stdio server is spawned per client, and each one
 * would want its own engine. Over HTTP the agent connects to the system that is
 * already running and already knows what every order is doing.
 *
 * <p>Bound to loopback by default. The tools place and cancel orders, so the
 * question of who can reach them is not one to answer with a default.
 */
public final class McpPlugin implements Plugin {

    private final int port;
    private final String bind;
    private final Path journalDirectory;
    private final String clientSession;
    private final String destination;
    private final Map<Integer, String> identity;

    /**
     * How many acting calls the agent may make, and for how long.
     *
     * <p>Zero leaves the acting tools registered but invisible, which is the
     * safe default and a poor one to leave implicit: a model that cannot see
     * place_order does not know it could ask for it. A deployment that wants an
     * agent to trade says so here.
     */
    private final int maxCalls;
    private final java.time.Duration validFor;

    /**
     * Whether the conformance harness is offered, and for how many calls.
     *
     * <p>Its own switch rather than the one above: the harness talks to a
     * system under test, so permitting it must not also permit the agent to
     * trade on the deployment's venue, and permitting trading must not offer a
     * harness pointed at somebody else's system. Zero leaves it out entirely.
     */
    private final int harnessCalls;

    private volatile int boundPort;

    /**
     * @param identity fields identifying the agent as a client. Without them
     *     its orders match no client and are dropped at the first layer.
     */
    public McpPlugin(
            int port, String bind, Path journalDirectory,
            String clientSession, String destination, Map<Integer, String> identity,
            int maxCalls, java.time.Duration validFor, int harnessCalls) {

        this.port = port;
        this.bind = bind;
        this.journalDirectory = journalDirectory;
        this.clientSession = clientSession;
        this.destination = destination;
        this.identity = Map.copyOf(identity);
        this.maxCalls = maxCalls;
        this.validFor = validFor;
        this.harnessCalls = harnessCalls;
    }

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public List<String> inject() {
        return List.of("orders", "transport");
    }

    @Override
    public List<String> provides() {
        return List.of("mcp", "tools");
    }

    /** The port actually bound, which differs from the requested one when 0 was asked for. */
    public int port() {
        return boundPort;
    }

    @Override
    public void apply(Context ctx) {
        OrderCache cache = ctx.get("orders");
        OrderHistory history =
                journalDirectory == null ? null : new OrderHistory(journalDirectory);

        // Registered before anything can be sent: a venue answering in under a
        // millisecond would otherwise arrive before anyone was listening.
        OrderWatch watch = new OrderWatch(ctx);

        ToolRegistry registry = new ToolRegistry();
        // What a tool reaches when it names no destination, so offering an
        // acting tool can be judged against the grant that would permit it.
        registry.servingVenue(destination);
        OrderTools tools =
                new OrderTools(ctx, cache, watch, history, clientSession, identity);
        tools.all().forEach(registry::register);

        // Without these an agent handed order tools can still do nothing: an
        // order goes nowhere while the session is down, and why it is down is
        // invisible.
        new SessionTools(ctx.<io.nexum.transport.Transport>get("transport"))
                .all().forEach(registry::register);

        registry.register(new ParseFixTool());

        // Where an order would go, which nothing else exposes: routing is
        // decided from message content, so an agent holding only order and
        // session tools can discover it in one way — by sending an order and
        // seeing where it lands. A composition without a router routes nothing
        // and has nothing to explain.
        io.nexum.routing.Router router = ctx.get("router");
        if (router != null) {
            new RoutingTools(router).all().forEach(registry::register);
        }

        // Bringing a counterparty up while the engine runs, when the loader is
        // reachable; a composition that built its own does not offer it rather
        // than managing plugins it does not own.
        io.nexum.core.PluginLoader loader = ctx.get("loader");
        if (loader != null) {
            new SessionAdminTools(
                    new io.nexum.transport.SessionManager(ctx, loader),
                    ctx.get("transport")).all().forEach(registry::register);
        }

        // The conformance harness is its own process now — fixprobe. It tests a
        // system that may not be this one, so being reachable only by running
        // this engine made a spare configuration and a placeholder session the
        // price of using it. `harnessCalls` is accepted and ignored so a
        // deployment that still declares it starts rather than refusing.

        if (maxCalls > 0) {
            // Recorded as a grant like any other, so the audit shows who
            // permitted what rather than the permission being invisible.
            registry.unlock("configuration", ToolRegistry.Unlock.granted(
                    "the deployment's configuration",
                    List.of(destination), maxCalls, validFor));
        }

        ctx.register("tools", registry);

        McpServer mcp = new McpServer(registry, "nexum", "0.1.0", destination);

        ctx.effect(() -> {
            HttpServer server;
            try {
                server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "cannot open the MCP endpoint on " + bind + ":" + port, failure);
            }
            boundPort = server.getAddress().getPort();

            server.createContext("/mcp", exchange -> serve(exchange, mcp));
            server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "mcp");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();

            System.out.println("mcp on " + bind + ":" + boundPort + "/mcp");
            return () -> server.stop(0);
        });
    }

    // ------------------------------------------------------------------

    private static void serve(HttpExchange exchange, McpServer mcp) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                // GET opens a server-to-client stream in Streamable HTTP. This
                // server never initiates, so declining is honest; a client that
                // needs it will fall back to POST-only.
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply = mcp.handle(body);

            if (reply == null) {
                // A notification. 202 with no body is what the spec asks for.
                exchange.sendResponseHeaders(202, -1);
                return;
            }

            byte[] payload = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } finally {
            exchange.close();
        }
    }
}
