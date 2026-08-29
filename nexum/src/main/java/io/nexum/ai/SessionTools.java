package io.nexum.ai;

import io.nexum.transport.Transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running the FIX sessions themselves.
 *
 * <p>Without these an agent can be handed order tools and still be unable to do
 * anything: an order goes nowhere while the session is down, and the reason it
 * is down — never logged on, sequence numbers disagreeing after a restart, a
 * counterparty that reset its side — is invisible. These are the operations a
 * person performs on an ordinary day, so they are the ones an agent needs to be
 * able to see and perform too.
 *
 * <p>Resetting sequence numbers is the one to be careful with, and it is marked
 * as such: a reset on one side alone leaves the two disagreeing about every
 * message from then on. The tool says so where the model will read it.
 */
public final class SessionTools {

    private final Transport transport;

    public SessionTools(Transport transport) {
        this.transport = transport;
    }

    public List<AiTool> all() {
        return List.of(
                new ListSessions(), new SessionStatusTool(),
                new Logon(), new Logout(), new Disconnect(),
                new ResetSequence(), new SetSequence());
    }

    // ------------------------------------------------------------------
    // Looking
    // ------------------------------------------------------------------

    final class ListSessions implements AiTool {

        @Override
        public String name() {
            return "list_sessions";
        }

        @Override
        public String description() {
            return "Every FIX session this system is configured with: whether each"
                    + " is logged on, and how it is wired — acceptor or initiator,"
                    + " and the port. Start here when an order is not reaching a"
                    + " venue, because a session that is down explains it, and when"
                    + " a counterparty has to be pointed at one, because the port"
                    + " and role are what it needs.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of();
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            List<Object> rows = new ArrayList<>();
            StringBuilder text = new StringBuilder();

            for (String sessionId : transport.sessions()) {
                Transport.SessionStatus status = transport.status(sessionId);
                rows.add(describe(status));
                text.append(line(status)).append('\n');
            }

            if (rows.isEmpty()) {
                return Result.of("no sessions are configured");
            }
            return Result.of(text.toString().trim(), Map.of("sessions", rows));
        }
    }

    final class SessionStatusTool implements AiTool {

        @Override
        public String name() {
            return "session_status";
        }

        @Override
        public String description() {
            return "One session in detail: whether it is logged on, and the next"
                    + " sequence number each side expects. Sequence numbers that"
                    + " disagree with the counterparty's are why a session logs on"
                    + " and immediately drops.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of("sessionId",
                    Parameter.required("string", "e.g. OMS->LSE, from list_sessions"));
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String sessionId = text(arguments, "sessionId");
            if (!transport.sessions().contains(sessionId)) {
                return Result.failed("no session " + sessionId
                        + "; list_sessions shows which exist");
            }
            Transport.SessionStatus status = transport.status(sessionId);
            return Result.of(line(status), describe(status));
        }
    }

    // ------------------------------------------------------------------
    // Acting
    // ------------------------------------------------------------------

    final class Logon implements AiTool {

        @Override
        public String name() {
            return "logon_session";
        }

        @Override
        public String description() {
            return "Bring a session up. The logon completes a moment later, so"
                    + " check session_status to see whether the counterparty"
                    + " accepted it.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of("sessionId", Parameter.required("string", "Which session"));
        }

        @Override
        public Effect effect() {
            // Reaches a counterparty, so it is gated like an order.
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String sessionId = text(arguments, "sessionId");
            return transport.logon(sessionId)
                    ? Result.of("asked " + sessionId + " to log on;"
                            + " check session_status for whether it completed")
                    : Result.failed("no session " + sessionId);
        }
    }

    final class Logout implements AiTool {

        @Override
        public String name() {
            return "logout_session";
        }

        @Override
        public String description() {
            return "Log a session out cleanly, telling the counterparty why. Both"
                    + " sides end up agreeing about sequence numbers, which a"
                    + " session that simply disappears does not.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> p = new LinkedHashMap<>();
            p.put("sessionId", Parameter.required("string", "Which session"));
            p.put("reason", Parameter.optional("string",
                    "Sent in Text(58), so the counterparty's log says what happened"));
            return p;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String sessionId = text(arguments, "sessionId");
            String reason = text(arguments, "reason");
            return transport.logout(sessionId, reason)
                    ? Result.of("logged " + sessionId + " out")
                    : Result.failed("no session " + sessionId);
        }
    }

    final class Disconnect implements AiTool {

        @Override
        public String name() {
            return "disconnect_session";
        }

        @Override
        public String description() {
            return "Drop a session's connection without logging out. For one that"
                    + " has stopped responding, where a logout would wait for an"
                    + " answer that is not coming. Prefer logout_session.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> p = new LinkedHashMap<>();
            p.put("sessionId", Parameter.required("string", "Which session"));
            p.put("reason", Parameter.optional("string", "For this system's own log"));
            return p;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String sessionId = text(arguments, "sessionId");
            return transport.disconnect(sessionId, text(arguments, "reason"))
                    ? Result.of("dropped " + sessionId)
                    : Result.failed("no session " + sessionId);
        }
    }

    final class ResetSequence implements AiTool {

        @Override
        public String name() {
            return "reset_session_sequence";
        }

        @Override
        public String description() {
            return "Reset a session's sequence numbers to 1 and clear its message"
                    + " store — what end of day means to a FIX session, and what a"
                    + " counterparty asks for when the two sides have lost track of"
                    + " each other. THE COUNTERPARTY MUST RESET AT THE SAME TIME:"
                    + " resetting one side alone leaves the two disagreeing about"
                    + " every message from then on. Confirm with a person first.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of("sessionId", Parameter.required("string", "Which session"));
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String sessionId = text(arguments, "sessionId");
            if (!transport.reset(sessionId)) {
                return Result.failed("no session " + sessionId);
            }

            // Deliberately no numbers. A reset logs the session out and brings
            // it back, and the sequence numbers do not land until that
            // completes — reading them here returns what they were a moment
            // before, which reads as the reset having failed.
            return Result.of(
                    sessionId + " reset. The session logs out and back on to"
                            + " complete it; call session_status in a few seconds"
                            + " to see the new sequence numbers.",
                    Map.of("sessionId", sessionId, "resetRequested", true));
        }
    }

    final class SetSequence implements AiTool {

        @Override
        public String name() {
            return "set_session_sequence";
        }

        @Override
        public String description() {
            return "Set the next sequence number this side will send, or expect to"
                    + " receive. The targeted alternative to a full reset: a"
                    + " counterparty that has skipped a message asks for a specific"
                    + " number rather than for everything to start again.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> p = new LinkedHashMap<>();
            p.put("sessionId", Parameter.required("string", "Which session"));
            p.put("nextSender", Parameter.optional("number",
                    "Next MsgSeqNum this side will send. Omit to leave alone."));
            p.put("nextTarget", Parameter.optional("number",
                    "Next MsgSeqNum this side expects. Omit to leave alone."));
            return p;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String sessionId = text(arguments, "sessionId");
            Integer sender = whole(arguments.get("nextSender"));
            Integer target = whole(arguments.get("nextTarget"));

            if (sender == null && target == null) {
                return Result.failed("give nextSender, nextTarget, or both");
            }
            if (!transport.resequence(sessionId, sender, target)) {
                return Result.failed(
                        "could not resequence " + sessionId
                                + "; the session may not exist or the store refused the write");
            }

            Transport.SessionStatus after = transport.status(sessionId);
            return Result.of(
                    sessionId + " resequenced; next sender " + after.nextSenderSeqNum()
                            + ", next target " + after.nextTargetSeqNum(),
                    describe(after));
        }
    }

    // ------------------------------------------------------------------

    private static String line(Transport.SessionStatus status) {
        return "%s  %s  %s  next sender %d, next target %d  [%s]".formatted(
                status.sessionId(),
                status.loggedOn() ? "logged on" : "DOWN",
                wiring(status),
                status.nextSenderSeqNum(),
                status.nextTargetSeqNum(),
                status.beginString());
    }

    /**
     * How a counterparty reaches this session.
     *
     * <p>An acceptor waits on a port and an initiator dials one, and which of
     * the two decides what the other side must do to connect. Stating it here
     * is what keeps that answer out of a configuration file: a reader who has
     * to open one to find a port has been sent somewhere that may not match
     * what is actually running.
     */
    private static String wiring(Transport.SessionStatus status) {
        if (status.port() == 0) {
            return status.role();
        }
        return "initiator".equals(status.role()) && status.host() != null
                ? "initiator to %s:%d".formatted(status.host(), status.port())
                : "%s on %d".formatted(status.role(), status.port());
    }

    private static Map<String, Object> describe(Transport.SessionStatus status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", status.sessionId());
        data.put("loggedOn", status.loggedOn());
        data.put("nextSenderSeqNum", status.nextSenderSeqNum());
        data.put("nextTargetSeqNum", status.nextTargetSeqNum());
        data.put("beginString", status.beginString());
        data.put("role", status.role());
        if (status.port() != 0) {
            data.put("port", status.port());
        }
        if (status.host() != null) {
            data.put("host", status.host());
        }
        return data;
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** A sequence number, which is a whole number or nothing. */
    private static Integer whole(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
