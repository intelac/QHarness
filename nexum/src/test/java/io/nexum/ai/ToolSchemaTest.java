package io.nexum.ai;

import io.nexum.probe.HarnessRig;
import io.nexum.probe.HarnessTools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a tool's schema says what a caller must supply.
 *
 * <p>A model picks a tool from its schema and nothing else. A parameter with no
 * usable default that is nonetheless optional invites a call that omits it —
 * and the failure surfaces at execution, where the model has to work out from a
 * message what the schema should have told it before it called.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ToolSchemaTest {

    private static List<AiTool> harnessTools() {
        return new HarnessTools(new HarnessRig()).tools();
    }

    @Test
    @DisplayName("a choice with no default is required")
    void aChoiceWithNoDefaultIsRequired() {
        // Each of these decides what the call does — which side to connect,
        // which report to send, which request a refusal answers. None has a
        // defensible default, so a schema that lets a model omit one is a
        // schema that lets it make a call nobody can carry out.
        List<String> mustBeRequired = List.of("side", "execType", "ordStatus", "responseTo");
        List<String> optional = new ArrayList<>();

        for (AiTool tool : harnessTools()) {
            tool.parameters().forEach((name, parameter) -> {
                if (mustBeRequired.contains(name) && !parameter.required()) {
                    optional.add(tool.name() + "." + name);
                }
            });
        }

        assertTrue(optional.isEmpty(),
                "these decide what the call does and must be required: " + optional);
    }

    @Test
    @DisplayName("every choice still offers the values it accepts")
    void everyChoiceOffersItsValues() {
        List<String> valueless = new ArrayList<>();

        for (AiTool tool : harnessTools()) {
            tool.parameters().forEach((name, parameter) -> {
                // "side" is deliberately absent: an endpoint is named by the
                // scenario now, so there is no closed set to offer. The rest
                // are FIX enumerations, where a value outside the set is not a
                // thing a venue can mean.
                if (List.of("execType", "ordStatus", "responseTo", "dials").contains(name)
                        && parameter.allowedValues().isEmpty()) {
                    valueless.add(tool.name() + "." + name);
                }
            });
        }

        assertTrue(valueless.isEmpty(),
                "a choice a model cannot see the options for is a guess: " + valueless);
    }

    @Test
    @DisplayName("connecting says which side has to come up first")
    void connectExplainsTheOrder() {
        AiTool connect = harnessTools().stream()
                .filter(tool -> tool.name().equals("harness_connect"))
                .findFirst().orElseThrow();

        // The system under test dials out to the market side, so a client
        // brought up first has nothing to reach and the scenario stalls with
        // both sides looking merely "not logged on yet".
        String description = connect.description().toLowerCase();
        assertTrue(description.contains("market side first")
                        || description.contains("start the market"),
                "a model has no way to learn the order except from here: "
                        + connect.description());
    }

    @Test
    @DisplayName("answering as the market says where the id comes from")
    void executionExplainsWhereTheIdComesFrom() {
        AiTool execution = harnessTools().stream()
                .filter(tool -> tool.name().equals("harness_send_execution"))
                .findFirst().orElseThrow();

        // The id belongs to the system under test, not to the client, and the
        // only way to learn it is to read the traffic first.
        assertTrue(execution.description().contains("harness_traffic"),
                "it must name the tool that supplies the id: " + execution.description());
    }

    @Test
    @DisplayName("no tool asks for something it does not describe")
    void everyParameterIsDescribed() {
        List<String> undescribed = new ArrayList<>();

        for (AiTool tool : harnessTools()) {
            tool.parameters().forEach((name, parameter) -> {
                if (parameter.description() == null || parameter.description().isBlank()) {
                    undescribed.add(tool.name() + "." + name);
                }
            });
        }

        assertTrue(undescribed.isEmpty(), "undescribed parameters: " + undescribed);
    }

    @Test
    @DisplayName("a tool that reads nothing declares no parameters")
    void readOnlyToolsStayEmpty() {
        for (AiTool tool : harnessTools()) {
            if (tool.name().equals("harness_status")
                    || tool.name().equals("harness_clear_traffic")) {
                assertFalse(tool.parameters().containsKey("side"),
                        tool.name() + " acts on both sides and should ask for nothing");
            }
        }
    }
}
