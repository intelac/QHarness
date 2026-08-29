package io.nexum.ai;

import java.util.List;
import java.util.Map;

/**
 * Something an AI agent can call.
 *
 * <p>Tools are described here once and exposed through whatever protocol a
 * deployment uses — MCP, an HTTP endpoint, a local SDK. Keeping the description
 * in one place means a new surface adds a transport rather than a second
 * definition that drifts.
 *
 * <p>Every tool declares whether it changes anything. That is not documentation:
 * the registry refuses to dispatch a mutating tool unless the deployment has
 * explicitly unlocked it, and a tool that lies about itself is a bug the
 * unlock cannot compensate for.
 */
public interface AiTool {

    String name();

    /** What it does, in the terms the model will see. */
    String description();

    /** Parameters, as a JSON-schema-shaped description. */
    Map<String, Parameter> parameters();

    /** Whether calling it can change anything. */
    Effect effect();

    /**
     * The destination an acting call reaches, when it is not the deployment's
     * own venue.
     *
     * <p>The gate is scoped by destination, so a tool that reaches somewhere
     * else has to say where: a test harness talking to another firm's system
     * must not be covered by the unlock that lets the agent trade, and granting
     * it must not open that one either. Null means the deployment's venue,
     * which is what an ordinary order tool reaches.
     */
    default String destination() {
        return null;
    }

    /**
     * Run it.
     *
     * @param arguments values supplied by the caller, already checked against
     *     {@link #parameters()}
     */
    Result call(Map<String, Object> arguments);

    // ------------------------------------------------------------------

    enum Effect {
        /** Reads state. Always available. */
        READ_ONLY,

        /**
         * Changes something inside this system but touches no counterparty —
         * loading a dialect, resetting a projection.
         */
        LOCAL_CHANGE,

        /**
         * Puts a message on the wire. Gated behind an explicit unlock, and
         * never reachable by a model on its own reasoning alone.
         */
        SENDS_TO_VENUE
    }

    record Parameter(
            String type,
            String description,
            boolean required,
            List<String> allowedValues) {

        public static Parameter required(String type, String description) {
            return new Parameter(type, description, true, List.of());
        }

        public static Parameter optional(String type, String description) {
            return new Parameter(type, description, false, List.of());
        }

        /**
         * A choice the caller must make.
         *
         * <p>Required, because a choice worth listing is one with no
         * defensible default — which side to connect, which report to send.
         * A model reads the schema and nothing else, so an optional one
         * invites a call that omits it and fails where the schema should have
         * spoken. Use {@link #optionalOneOf} where a default genuinely exists.
         */
        public static Parameter oneOf(String description, String... values) {
            return new Parameter("string", description, true, List.of(values));
        }

        /** A choice with a documented default, so omitting it means something. */
        public static Parameter optionalOneOf(String description, String... values) {
            return new Parameter("string", description, false, List.of(values));
        }
    }

    /**
     * What a call produced.
     *
     * <p>{@code content} is what the model reads and is deliberately bounded —
     * a FIX session produces far more than a context window holds, so a tool
     * that could return an unbounded amount returns a summary and a way to ask
     * for more instead.
     *
     * @param truncated true when more existed than was returned, so the model is
     *     told rather than left to assume it has everything
     */
    record Result(
            boolean ok,
            String content,
            Map<String, Object> data,
            boolean truncated,
            String more) {

        public static Result of(String content) {
            return new Result(true, content, Map.of(), false, null);
        }

        public static Result of(String content, Map<String, Object> data) {
            return new Result(true, content, data, false, null);
        }

        /** @param more how to obtain the rest, in words the model can act on */
        public static Result partial(String content, Map<String, Object> data, String more) {
            return new Result(true, content, data, true, more);
        }

        public static Result failed(String why) {
            return new Result(false, why, Map.of(), false, null);
        }
    }
}
