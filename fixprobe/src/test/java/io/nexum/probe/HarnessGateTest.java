package io.nexum.probe;

import io.nexum.ai.AiTool;
import io.nexum.ai.ToolRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That permitting the harness and permitting trading stay separate.
 *
 * <p>The harness reaches a system under test, not the deployment's venue. If
 * one grant covered both, letting someone run a conformance test would also let
 * the agent send real orders — and the reverse, so a deployment that wants an
 * agent to trade would silently gain a harness pointed at another firm's
 * system. Both directions are checked here because both are one-line mistakes.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HarnessGateTest {

    private static final String VENUE = "OMS->LSE";

    /** A stand-in for an ordinary order tool: acts, and reaches the venue. */
    private static final class VenueTool implements AiTool {

        @Override
        public String name() {
            return "place_order_stub";
        }

        @Override
        public String description() {
            return "stands in for an order tool";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of();
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            return Result.of("sent");
        }
    }

    private static ToolRegistry registryWithBoth() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new VenueTool());
        new HarnessTools(new HarnessRig()).tools().forEach(registry::register);
        return registry;
    }

    @Test
    @DisplayName("unlocking the harness does not permit trading")
    void aHarnessGrantDoesNotPermitTrading() {
        ToolRegistry registry = registryWithBoth();
        registry.unlock("harness", ToolRegistry.Unlock.granted(
                "a test", List.of(HarnessTools.DESTINATION), 10, Duration.ofMinutes(5)));

        AiTool.Result traded = registry.call("place_order_stub", Map.of(), VENUE);

        assertFalse(traded.ok(), "the venue must stay locked: " + traded.content());
        assertTrue(traded.content().contains(VENUE), traded.content());
    }

    @Test
    @DisplayName("unlocking trading does not permit the harness")
    void aTradingGrantDoesNotPermitTheHarness() {
        ToolRegistry registry = registryWithBoth();
        registry.unlock("configuration", ToolRegistry.Unlock.granted(
                "a deployment", List.of(VENUE), 10, Duration.ofMinutes(5)));

        // The call names the venue, as the server does for every call; the
        // tool's own destination is what it is judged on.
        AiTool.Result connected = registry.call("harness_connect", Map.of(
                "side", "client", "port", 1, "senderCompId", "A", "targetCompId", "B"), VENUE);

        assertFalse(connected.ok(), "the harness must stay locked: " + connected.content());
        assertTrue(connected.content().contains(HarnessTools.DESTINATION), connected.content());
    }

    @Test
    @DisplayName("its own grant permits the harness")
    void aHarnessGrantPermitsTheHarness() {
        ToolRegistry registry = registryWithBoth();
        registry.unlock("harness", ToolRegistry.Unlock.granted(
                "a test", List.of(HarnessTools.DESTINATION), 10, Duration.ofMinutes(5)));

        // It reaches the tool, which then fails on its own terms — nothing is
        // listening on port 1 — rather than being refused by the gate.
        AiTool.Result result = registry.call("harness_send_order", Map.of(
                "clOrdId", "x", "symbol", "BP", "side", "buy", "quantity", 1), VENUE);

        assertFalse(result.ok());
        assertTrue(result.content().contains("not started"),
                "it should have reached the tool: " + result.content());
    }

    @Test
    @DisplayName("reading the harness needs no grant at all")
    void readingNeedsNoGrant() {
        ToolRegistry registry = registryWithBoth();

        AiTool.Result status = registry.call("harness_status", Map.of(), null);

        assertTrue(status.ok(), status.content());
        assertTrue(status.content().contains("not started"), status.content());
    }

    @Test
    @DisplayName("with nothing unlocked the acting tools are not even offered")
    void lockedToolsStayInvisible() {
        ToolRegistry registry = registryWithBoth();

        List<String> visible = registry.visible().stream().map(AiTool::name).toList();

        assertTrue(visible.contains("harness_status"), "reads stay visible: " + visible);
        assertFalse(visible.contains("harness_send_order"),
                "acting tools should be withheld while nothing is unlocked: " + visible);
    }
}
