package io.nexum.ai;

import io.nexum.web.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speaks MCP, so an agent can reach this system's tools.
 *
 * <p>Only the method set a tool-using client needs: initialize, list the tools,
 * call one. Resources, prompts and sampling are not implemented and are
 * declined rather than faked — a client told a capability exists and then
 * finding it empty is worse served than one told plainly it is absent.
 *
 * <p>This is the protocol alone. What the tools do lives in {@link OrderTools},
 * and whether a call is permitted lives in {@link ToolRegistry}: the same gate
 * applies whether a call arrives here, over a socket, or from a test.
 */
public final class McpServer {

    /** The revision this speaks. Clients send their own and take the lower. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final ToolRegistry registry;
    private final String serverName;
    private final String version;

    /**
     * Where acting calls are considered to reach.
     *
     * <p>The registry refuses a SENDS_TO_VENUE call that names no destination,
     * so an agent's calls are attributed to one rather than being exempt.
     */
    private final String destination;

    public McpServer(
            ToolRegistry registry, String serverName, String version, String destination) {

        this.registry = registry;
        this.serverName = serverName;
        this.version = version;
        this.destination = destination;
    }

    /**
     * Handle one JSON-RPC message.
     *
     * @return the response to send back, or null for a notification — which
     *     takes no reply, and answering one is a protocol error the client
     *     will complain about
     */
    public String handle(String body) {
        Map<String, Object> request;
        try {
            request = Json.readObject(body);
        } catch (IllegalArgumentException malformed) {
            return error(null, -32700, "parse error: " + malformed.getMessage());
        }

        Object id = request.get("id");
        String method = String.valueOf(request.get("method"));

        // A notification has no id and expects no reply.
        boolean notification = !request.containsKey("id");

        try {
            return switch (method) {
                case "initialize" -> notification ? null : result(id, initialize(request));
                case "tools/list" -> notification ? null : result(id, listTools());
                case "tools/call" -> notification ? null : result(id, callTool(request));

                // Sent after initialize; acknowledged by saying nothing.
                case "notifications/initialized" -> null;

                case "ping" -> notification ? null : result(id, Map.of());

                default -> notification
                        ? null
                        : error(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException failure) {
            // A tool that throws must not take the connection down with it.
            return notification ? null : error(id, -32603, "internal error: " + failure);
        }
    }

    // ------------------------------------------------------------------

    private Map<String, Object> initialize(Map<String, Object> request) {
        Object asked = ((Map<?, ?>) request.getOrDefault("params", Map.of()))
                .get("protocolVersion");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", serverName);
        info.put("version", version);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        // Tools only, and listChanged because unloading a plugin removes them.
        capabilities.put("tools", Map.of("listChanged", true));

        Map<String, Object> response = new LinkedHashMap<>();
        // Echo what the client asked for when it is one this understands; a
        // client offered a version it did not ask for has to decide whether to
        // proceed, and the answer is usually to disconnect.
        response.put("protocolVersion",
                asked == null ? PROTOCOL_VERSION : String.valueOf(asked));
        response.put("capabilities", capabilities);
        response.put("serverInfo", info);
        return response;
    }

    private Map<String, Object> listTools() {
        List<Object> tools = new ArrayList<>();
        for (AiTool tool : registry.visible()) {
            tools.add(describe(tool));
        }
        return Map.of("tools", tools);
    }

    /** One tool, in the shape a model's client turns into a function signature. */
    private static Map<String, Object> describe(AiTool tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();

        tool.parameters().forEach((name, parameter) -> {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", parameter.type());
            schema.put("description", parameter.description());
            if (!parameter.allowedValues().isEmpty()) {
                schema.put("enum", parameter.allowedValues());
            }
            properties.put(name, schema);
            if (parameter.required()) {
                required.add(name);
            }
        });

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("properties", properties);
        input.put("required", required);

        Map<String, Object> described = new LinkedHashMap<>();
        described.put("name", tool.name());
        described.put("description", tool.description());
        described.put("inputSchema", input);

        // What a call does, so a client can decide whether to ask a person
        // first. Declared, not inferred from the name.
        described.put("annotations", Map.of(
                "readOnlyHint", tool.effect() == AiTool.Effect.READ_ONLY,
                "destructiveHint", tool.effect() == AiTool.Effect.SENDS_TO_VENUE));

        return described;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> request) {
        Map<String, Object> params =
                (Map<String, Object>) request.getOrDefault("params", Map.of());

        String name = String.valueOf(params.get("name"));
        Map<String, Object> arguments =
                (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        AiTool.Result outcome = registry.call(name, arguments, destination);

        List<Object> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", outcome.content()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        // MCP reports a tool's own failure in the result rather than as a
        // protocol error: the model is meant to read it and try something else.
        response.put("isError", !outcome.ok());

        if (!outcome.data().isEmpty()) {
            response.put("structuredContent", outcome.data());
        }
        if (outcome.truncated()) {
            response.put("_meta", Map.of("truncated", true, "more", outcome.more()));
        }
        return response;
    }

    // ------------------------------------------------------------------

    private static String result(Object id, Object payload) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", payload);
        return Json.write(response);
    }

    private static String error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return Json.write(response);
    }
}
