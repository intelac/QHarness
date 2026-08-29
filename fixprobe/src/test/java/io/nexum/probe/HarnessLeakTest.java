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
 * That enabling the harness does not expose the tools that trade.
 *
 * <p>Written after it did. A live deployment configured with the harness switch
 * alone, and no permission to trade, listed place_order among its tools and
 * accepted a call to it that reached the venue. Two separate faults produce
 * that, and both are checked here: whether a tool is offered, and whether a
 * call to it is permitted.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HarnessLeakTest {

    private static final String VENUE = "OMS->LSE";

    /** Stands in for an order tool: acts, and names no destination of its own. */
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
            return Result.of("reached the venue");
        }
    }

    /** A deployment that enabled the harness and nothing else. */
    private static ToolRegistry harnessOnly() {
        ToolRegistry registry = new ToolRegistry();
        registry.servingVenue(VENUE);
        registry.register(new VenueTool());
        new HarnessTools(new HarnessRig()).tools().forEach(registry::register);
        registry.unlock("harness", ToolRegistry.Unlock.granted(
                "the deployment's configuration",
                List.of(HarnessTools.DESTINATION), 500, Duration.ofMinutes(60)));
        return registry;
    }

    @Test
    @DisplayName("a harness-only deployment does not offer the tools that trade")
    void tradingToolsAreNotOffered() {
        List<String> visible = harnessOnly().visible().stream().map(AiTool::name).toList();

        assertTrue(visible.contains("harness_send_order"),
                "the harness was enabled, so its tools belong: " + visible);
        assertFalse(visible.contains("place_order_stub"),
                "nothing permits trading, so an order tool must not be offered: " + visible);
    }

    @Test
    @DisplayName("a harness-only deployment refuses a call that would trade")
    void tradingCallsAreRefused() {
        AiTool.Result traded = harnessOnly().call("place_order_stub", Map.of(), VENUE);

        assertFalse(traded.ok(), "the venue must stay locked: " + traded.content());
    }
}
