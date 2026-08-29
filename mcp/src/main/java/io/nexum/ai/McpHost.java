package io.nexum.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * An {@link McpServer} put on a port.
 *
 * <p>Serving MCP over HTTP is the same work wherever the tools come from —
 * POST carries a request, GET is declined because this server never initiates,
 * a notification is answered 202 with no body. Two copies of that would be two
 * places for the protocol to be got subtly wrong, and the one nobody is
 * looking at is the one that drifts.
 */
public final class McpHost implements AutoCloseable {

    private final HttpServer server;
    private final int port;

    /**
     * @param bind the address to listen on; 127.0.0.1 keeps it off the network
     * @param port the port, or 0 to let the system choose — {@link #port()}
     *     then says which, so a test does not have to guess a free one
     */
    public McpHost(McpServer mcp, String bind, int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot open the MCP endpoint on " + bind + ":" + port, failure);
        }
        this.port = server.getAddress().getPort();

        server.createContext("/mcp", exchange -> serve(exchange, mcp));
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "mcp");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    /** The port actually bound, which differs from the one asked for when it was 0. */
    public int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop(0);
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
