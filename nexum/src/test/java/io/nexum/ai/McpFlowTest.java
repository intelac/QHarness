package io.nexum.ai;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.message.FixMessage;
import io.nexum.transport.RecordingTransport;
import io.nexum.web.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an agent can drive an order through MCP and be told what happened.
 *
 * <p>The whole point of the interface: place, watch it reach the venue, read
 * the state back, act on it. Driven through the protocol rather than by calling
 * the tools directly — a tool that works and a tool a client can reach are
 * different claims.
 */
class McpFlowTest {

    private static final String CLIENT_SESSION = "OMS->FUNDX";
    private static final String VENUE_SESSION = "OMS->LSE";

    private static final String CONFIG = """
            monitor:
              enabled: false

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
              - id: OMS->LSE
                version: FIX.4.4

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->LSE
                fingerprint: any
            """;

    private Context ctx;
    private PluginLoader loader;
    private RecordingTransport transport;
    private McpServer mcp;
    private ToolRegistry registry;

    @BeforeEach
    void start() {
        ctx = new Context();
        transport = new RecordingTransport(CLIENT_SESSION, VENUE_SESSION);
        loader = Bootstrap.from(CONFIG).with(transport).start(ctx);

        OrderWatch watch = new OrderWatch(ctx);
        registry = new ToolRegistry();
        // The agent is a client, recognised the same way any other is.
        new OrderTools(ctx, ctx.get("orders"), watch, null, CLIENT_SESSION,
                Map.of(115, "FUNDX"))
                .all().forEach(registry::register);
        registry.register(new ParseFixTool());

        // The agent acts as this destination, which is what the gate checks.
        registry.unlock("test", ToolRegistry.Unlock.granted(
                "a test", List.of(VENUE_SESSION), 100, java.time.Duration.ofMinutes(10)));

        mcp = new McpServer(registry, "nexum", "test", VENUE_SESSION);
    }

    @AfterEach
    void stop() {
        loader.unloadAll();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a client can discover what this offers")
    void toolsAreDiscoverable() {
        Map<String, Object> reply = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """);

        List<?> tools = (List<?>) result(reply).get("tools");
        List<String> names = tools.stream()
                .map(t -> String.valueOf(((Map<?, ?>) t).get("name")))
                .toList();

        assertTrue(names.contains("place_order"), names.toString());
        assertTrue(names.contains("cancel_order"));
        assertTrue(names.contains("get_order"));
        assertTrue(names.contains("parse_fix"));

        // A client decides whether to ask a person first from this, so it has
        // to be declared rather than guessed from the name.
        Map<?, ?> place = (Map<?, ?>) tools.stream()
                .filter(t -> "place_order".equals(((Map<?, ?>) t).get("name")))
                .findFirst().orElseThrow();
        Map<?, ?> annotations = (Map<?, ?>) place.get("annotations");
        assertEquals(Boolean.FALSE, annotations.get("readOnlyHint"));
        assertEquals(Boolean.TRUE, annotations.get("destructiveHint"));
    }

    @Test
    @DisplayName("an agent places an order and is told the venue took it")
    void placingWaitsForTheVenue() throws Exception {
        // The venue answers while the tool is still waiting, which is the
        // shape this exists to handle.
        answerNextOrderWith("0", "0", 0, 1000);

        Map<String, Object> reply = call("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"place_order",
                  "arguments":{"symbol":"VOD","side":"buy","quantity":1000,"price":150,
                               "timeoutMillis":4000}}}
                """);

        Map<String, Object> outcome = result(reply);
        assertEquals(Boolean.FALSE, outcome.get("isError"), text(outcome));

        Map<?, ?> data = (Map<?, ?>) outcome.get("structuredContent");
        assertNotNull(data, "the agent needs the identifier to act again: " + text(outcome));
        assertEquals("NEW", data.get("state"),
                "the tool should have waited for the acknowledgement, but returned "
                        + text(outcome));

        // Reached the venue as a real order, not by a back channel.
        assertEquals(1, transport.ofType("D").size());
    }

    @Test
    @DisplayName("an order is found by any identifier it answers to")
    void anyIdentifierFindsTheOrder() throws Exception {
        answerNextOrderWith("0", "0", 0, 1000);

        Map<?, ?> data = (Map<?, ?>) result(call("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{
                  "name":"place_order",
                  "arguments":{"symbol":"VOD","side":"buy","quantity":1000,"price":150,
                               "timeoutMillis":4000}}}
                """)).get("structuredContent");
        String minted = String.valueOf(data.get("orderId"));
        // The ClOrdID this system chose on the client's behalf — the last part
        // of the minted identity, and what a client's own messages would carry.
        String clientClOrdId = minted.substring(minted.lastIndexOf(':') + 1);

        // What this system put on the wire. An agent reading the venue side
        // holds this one and nothing else — it is the only identifier that
        // message carries, and asking for the order by it used to be told no
        // such order existed while the order sat in the cache.
        String onTheWire = transport.ofType("D").get(0).field(11);
        assertNotEquals(minted, onTheWire, "the wire id is not the minted one");

        for (String identifier : List.of(minted, onTheWire, clientClOrdId)) {
            Map<String, Object> outcome = result(call("""
                    {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{
                      "name":"get_order","arguments":{"orderId":"%s"}}}
                    """.formatted(identifier)));
            assertEquals(Boolean.FALSE, outcome.get("isError"),
                    identifier + " should find the order: " + text(outcome));
        }
    }

    @Test
    @DisplayName("an identifier that finds nothing says which ones are accepted")
    void anUnknownIdentifierSaysWhatWouldWork() {
        Map<String, Object> outcome = result(call("""
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{
                  "name":"get_order","arguments":{"orderId":"NOT-AN-ORDER"}}}
                """));

        assertEquals(Boolean.TRUE, outcome.get("isError"));
        String said = text(outcome);
        // "No order X" is true of an id never used and of one whose order
        // settled half an hour ago, and the two call for different things.
        assertTrue(said.contains("ClOrdID"),
                "the refusal should name the identifiers that work: " + said);
        assertTrue(said.contains("list_orders"),
                "and where to find one: " + said);
    }

    @Test
    @DisplayName("an order can be read back and then cancelled")
    void anAgentCanActOnWhatItPlaced() throws Exception {
        answerNextOrderWith("0", "0", 0, 1000);

        Map<?, ?> placed = (Map<?, ?>) result(call("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"place_order",
                  "arguments":{"symbol":"BP","side":"buy","quantity":2500,"price":150,
                               "timeoutMillis":4000}}}
                """)).get("structuredContent");

        String orderId = String.valueOf(placed.get("orderId"));

        // Read it back.
        Map<String, Object> got = result(call("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"get_order","arguments":{"orderId":"%s"}}}
                """.formatted(orderId)));
        assertEquals(Boolean.FALSE, got.get("isError"), text(got));
        assertTrue(text(got).contains("BP"), text(got));

        // Then cancel it, and be told it was cancelled.
        answerNextCancelWith(orderId);

        Map<String, Object> cancelled = result(call("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
                  "name":"cancel_order",
                  "arguments":{"orderId":"%s","timeoutMillis":4000}}}
                """.formatted(orderId)));

        assertEquals(Boolean.FALSE, cancelled.get("isError"), text(cancelled));
        Map<?, ?> after = (Map<?, ?>) cancelled.get("structuredContent");
        assertEquals("CANCELED", after.get("state"), text(cancelled));
    }

    @Test
    @DisplayName("a silent venue is reported, not treated as a failure")
    void aSilentVenueIsReportedHonestly() {
        // Nothing answers. The order went out and is pending — a real
        // situation, and one the agent has to be able to see.
        Map<String, Object> outcome = result(call("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
                  "name":"place_order",
                  "arguments":{"symbol":"VOD","side":"buy","quantity":100,
                               "timeoutMillis":300}}}
                """));

        assertEquals(Boolean.FALSE, outcome.get("isError"),
                "a pending order is not an error: " + text(outcome));
        assertTrue(text(outcome).contains("has not answered"), text(outcome));
    }

    @Test
    @DisplayName("an acting call without an unlock is refused")
    void actingIsGated() {
        registry.lockAll();

        Map<String, Object> outcome = result(call("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
                  "name":"place_order",
                  "arguments":{"symbol":"VOD","side":"buy","quantity":100}}}
                """));

        assertEquals(Boolean.TRUE, outcome.get("isError"), text(outcome));
        assertTrue(text(outcome).contains("unlock"), text(outcome));
        assertEquals(0, transport.ofType("D").size(),
                "a refused call must not reach the venue");
    }

    @Test
    @DisplayName("reading a message needs no unlock")
    void readingIsAlwaysAvailable() {
        registry.lockAll();

        Map<String, Object> outcome = result(call("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{
                  "name":"parse_fix",
                  "arguments":{"message":"8=FIX.4.4|35=8|39=1|150=F|14=300|"}}}
                """));

        assertEquals(Boolean.FALSE, outcome.get("isError"), text(outcome));
        String read = text(outcome);
        assertTrue(read.contains("OrdStatus"), read);
        assertTrue(read.contains("PartiallyFilled"), read);
        assertTrue(read.contains("Trade"), read);
    }

    @Test
    @DisplayName("a notification is not answered")
    void notificationsGetNoReply() {
        // Answering one is a protocol error the client complains about.
        assertEquals(null, mcp.handle("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """));
    }

    @Test
    @DisplayName("malformed input is refused rather than guessed at")
    void malformedInputIsRefused() {
        Map<String, Object> reply = Json.readObject(mcp.handle("{not json"));
        assertNotNull(reply.get("error"));
        assertEquals(-32700L, ((Map<?, ?>) reply.get("error")).get("code"));
    }

    // ------------------------------------------------------------------

    private Map<String, Object> call(String request) {
        return Json.readObject(mcp.handle(request));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> reply) {
        assertFalse(reply.containsKey("error"), String.valueOf(reply.get("error")));
        return (Map<String, Object>) reply.get("result");
    }

    private static String text(Map<String, Object> outcome) {
        List<?> content = (List<?>) outcome.get("content");
        return String.valueOf(((Map<?, ?>) content.get(0)).get("text"));
    }

    /** Answer the next order to reach the venue, on another thread. */
    private void answerNextOrderWith(
            String execType, String ordStatus, double cumQty, double leavesQty) {

        new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                var sent = transport.lastOfType("D");
                if (sent.isPresent()) {
                    transport.deliver(VENUE_SESSION, FixMessage.of("8", Map.of(
                            11, sent.get().field(11),
                            37, "SIM-1",
                            150, execType,
                            39, ordStatus,
                            14, String.valueOf((long) cumQty),
                            151, String.valueOf((long) leavesQty))));
                    return;
                }
                sleep(10);
            }
        }, "venue").start();
    }

    private void answerNextCancelWith(String orderId) {
        new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                var sent = transport.lastOfType("F");
                if (sent.isPresent()) {
                    transport.deliver(VENUE_SESSION, FixMessage.of("8", Map.of(
                            11, sent.get().field(11),
                            37, "SIM-1",
                            150, "4",
                            39, "4",
                            14, "0",
                            151, "0")));
                    return;
                }
                sleep(10);
            }
        }, "venue-cancel").start();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
