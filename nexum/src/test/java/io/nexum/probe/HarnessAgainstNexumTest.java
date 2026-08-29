package io.nexum.probe;

import io.nexum.ai.AiTool;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.config.Bootstrap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * That the harness can drive a real order router from both sides.
 *
 * <p>NEXUM stands in for the system under test because it has the shape the
 * harness is built for — it accepts orders from a client and sends them on to a
 * market — and because its behaviour is known, so a failure here is the
 * harness's rather than an unfamiliar system's. Pointing the same tools at
 * another router is a change of host and port.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class HarnessAgainstNexumTest {

    private Context ctx;
    private PluginLoader loader;
    private HarnessRig rig;
    private Map<String, AiTool> tools;

    @BeforeAll
    void bringUp() throws Exception {
        int clientPort = freePort();
        int marketPort = freePort();

        rig = new HarnessRig();
        tools = new java.util.HashMap<>();
        for (AiTool tool : new HarnessTools(rig).tools()) {
            tools.put(tool.name(), tool);
        }

        // The market side listens before NEXUM starts, because NEXUM dials out
        // to it and an initiator with nothing to reach simply retries.
        call("harness_connect", Map.of(
                "side", "market", "port", marketPort,
                "senderCompId", "LSE", "targetCompId", "OMS"));

        java.nio.file.Path journal = java.nio.file.Files.createTempDirectory("harness-e2e");
        journal.toFile().deleteOnExit();
        ctx = new Context();
        loader = Bootstrap.from(nexumConfig(clientPort, marketPort, journal.toString())).start(ctx);

        call("harness_connect", Map.of(
                "side", "client", "port", clientPort,
                "senderCompId", "FUNDX", "targetCompId", "OMS"));

        awaitBothSidesLoggedOn();
    }

    @AfterAll
    void tearDown() {
        if (rig != null) rig.stopAll();
        if (loader != null) loader.unloadAll();
    }

    @Test
    @DisplayName("an order sent as a client reaches the market side")
    void anOrderTravelsThrough() throws Exception {
        call("harness_clear_traffic", Map.of());
        AiTool.Result sent = call("harness_send_order", Map.of(
                "clOrdId", "H-1", "symbol", "BP", "side", "buy",
                "quantity", 1000, "price", 50.0, "onBehalfOf", "FUNDX"));
        assertTrue(sent.ok(), sent.content());

        // What the system under test forwarded is what the market side sees.
        String forwarded = awaitTraffic("market", "D");
        assertTrue(forwarded.contains("BP"), forwarded);
        assertTrue(forwarded.contains("1000"), forwarded);
    }

    @Test
    @DisplayName("a fill injected as the market reaches the client")
    void aFillTravelsBack() throws Exception {
        call("harness_clear_traffic", Map.of());
        call("harness_send_order", Map.of(
                "clOrdId", "H-2", "symbol", "VOD", "side", "buy",
                "quantity", 500, "price", 40.0, "onBehalfOf", "FUNDX"));

        String forwarded = awaitTraffic("market", "D");
        String venueClOrdId = field(forwarded, "11");

        // The reply is chosen here rather than derived: this is the point of a
        // manual harness — the system under test is handed exactly this report.
        AiTool.Result acked = call("harness_send_execution", Map.of(
                "clOrdId", venueClOrdId, "orderId", "MKT-1", "symbol", "VOD",
                "side", "buy", "orderQty", 500,
                "execType", "new", "ordStatus", "new",
                "leavesQty", 500, "price", 40.0));
        assertTrue(acked.ok(), acked.content());

        AiTool.Result filled = call("harness_send_execution", args(
                "clOrdId", venueClOrdId, "orderId", "MKT-1", "symbol", "VOD",
                "side", "buy", "orderQty", 500,
                "execType", "trade", "ordStatus", "filled",
                "lastQty", 500, "cumQty", 500, "leavesQty", 0, "price", 40.0));
        assertTrue(filled.ok(), filled.content());

        // The system under test should pass the fill back to the client.
        String backToClient = awaitTrafficContaining("client", "8", "39=2");
        assertTrue(backToClient.contains("H-2"),
                "the report reaching the client should name the client's own order: " + backToClient);
    }

    @Test
    @DisplayName("a partial fill leaves the order working")
    void aPartialFillLeavesItWorking() throws Exception {
        call("harness_clear_traffic", Map.of());
        call("harness_send_order", Map.of(
                "clOrdId", "H-3", "symbol", "SHEL", "side", "buy",
                "quantity", 800, "price", 25.0, "onBehalfOf", "FUNDX"));
        String venueClOrdId = field(awaitTraffic("market", "D"), "11");

        call("harness_send_execution", Map.of(
                "clOrdId", venueClOrdId, "orderId", "MKT-2", "symbol", "SHEL",
                "side", "buy", "orderQty", 800,
                "execType", "new", "ordStatus", "new", "leavesQty", 800, "price", 25.0));
        call("harness_send_execution", args(
                "clOrdId", venueClOrdId, "orderId", "MKT-2", "symbol", "SHEL",
                "side", "buy", "orderQty", 800,
                "execType", "trade", "ordStatus", "partially_filled",
                "lastQty", 300, "cumQty", 300, "leavesQty", 500, "price", 25.0));

        String partial = awaitTrafficContaining("client", "8", "39=1");
        assertTrue(partial.contains("14=300"), "cumQty should be 300: " + partial);
        assertTrue(partial.contains("151=500"), "leavesQty should be 500: " + partial);
    }

    @Test
    @DisplayName("a cancel refused by the market reaches the client as a refusal")
    void aRefusedCancelTravelsBack() throws Exception {
        call("harness_clear_traffic", Map.of());
        call("harness_send_order", Map.of(
                "clOrdId", "H-4", "symbol", "AZN", "side", "buy",
                "quantity", 200, "price", 90.0, "onBehalfOf", "FUNDX"));
        String venueClOrdId = field(awaitTraffic("market", "D"), "11");
        call("harness_send_execution", Map.of(
                "clOrdId", venueClOrdId, "orderId", "MKT-3", "symbol", "AZN",
                "side", "buy", "orderQty", 200,
                "execType", "new", "ordStatus", "new", "leavesQty", 200, "price", 90.0));
        awaitTraffic("client", "8");

        call("harness_send_cancel", Map.of(
                "clOrdId", "H-4-c", "origClOrdId", "H-4", "symbol", "AZN",
                "side", "buy", "quantity", 200, "onBehalfOf", "FUNDX"));
        String cancelOut = awaitTraffic("market", "F");
        String cancelClOrdId = field(cancelOut, "11");

        AiTool.Result refused = call("harness_send_cancel_reject", Map.of(
                "clOrdId", cancelClOrdId, "origClOrdId", field(cancelOut, "41"),
                "orderId", "MKT-3", "responseTo", "cancel",
                "ordStatus", "new", "reason", "too late"));
        assertTrue(refused.ok(), refused.content());

        // 434 is what says this refuses a cancel rather than a replace.
        String reject = awaitTrafficContaining("client", "9", "434=1");
        assertTrue(reject.contains("H-4"), reject);
    }

    @Test
    @DisplayName("a tool called before its side is up says so rather than throwing")
    void anUnstartedSideIsReportedPlainly() {
        HarnessRig empty = new HarnessRig();
        Map<String, AiTool> isolated = new java.util.HashMap<>();
        for (AiTool tool : new HarnessTools(empty).tools()) {
            isolated.put(tool.name(), tool);
        }
        AiTool.Result result = isolated.get("harness_send_order").call(Map.of(
                "clOrdId", "x", "symbol", "BP", "side", "buy", "quantity", 1));

        assertEquals(false, result.ok());
        assertTrue(result.content().contains("not started"), result.content());
    }

    // ------------------------------------------------------------------

    /** Arguments beyond Map.of's ten-pair limit. */
    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            arguments.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return arguments;
    }

    private AiTool.Result call(String tool, Map<String, Object> arguments) {
        AiTool found = tools.get(tool);
        if (found == null) {
            return fail("no tool named " + tool);
        }
        return found.call(arguments);
    }

    private void awaitBothSidesLoggedOn() throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            AiTool.Result status = call("harness_status", Map.of());
            if (status.content().lines().allMatch(line -> line.contains("logged on"))) {
                return;
            }
            Thread.sleep(100);
        }
        fail("the harness never logged on both sides: " + call("harness_status", Map.of()).content());
    }

    /** Wait for a message of one type on one side, so a test never races the wire. */
    private String awaitTraffic(String side, String msgType) throws Exception {
        return awaitTrafficContaining(side, msgType, null);
    }

    private String awaitTrafficContaining(String side, String msgType, String needle)
            throws Exception {

        for (int attempt = 0; attempt < 200; attempt++) {
            AiTool.Result traffic = call("harness_traffic",
                    Map.of("side", side, "msgType", msgType));
            if (traffic.ok()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows =
                        (List<Map<String, Object>>) traffic.data().get("messages");
                if (rows != null) {
                    for (Map<String, Object> row : rows) {
                        String raw = String.valueOf(row.get("raw"));
                        if (needle == null || raw.contains(needle)) {
                            return raw;
                        }
                    }
                }
            }
            Thread.sleep(50);
        }
        return fail("no " + msgType + " on the " + side + " side"
                + (needle == null ? "" : " containing " + needle)
                + "; client side saw: " + call("harness_traffic", Map.of("side", "client")).content()
                + "\n market side saw: " + call("harness_traffic", Map.of("side", "market")).content());
    }

    /** One tag's value out of a raw FIX message. */
    private static String field(String raw, String tag) {
        for (String part : raw.split("")) {
            if (part.startsWith(tag + "=")) {
                return part.substring(tag.length() + 1);
            }
        }
        return fail("no tag " + tag + " in " + raw);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String nexumConfig(int clientPort, int marketPort, String journal) {
        return """
                orders:
                  journal: %s
                  sync: true

                monitor:
                  enabled: false

                sessions:
                  - id: OMS->FUNDX
                    version: FIX.4.4
                    role: acceptor
                    port: %d
                    logPath: target/harness/logs
                    persistent: false

                  - id: OMS->LSE
                    version: FIX.4.4
                    role: initiator
                    host: 127.0.0.1
                    port: %d
                    logPath: target/harness/logs
                    persistent: false

                clients:
                  - id: FUND_X
                    fingerprint:
                      115: FUNDX

                routes:
                  - destination: OMS->LSE
                    fingerprint: any
                """.formatted(journal, clientPort, marketPort);
    }
}
