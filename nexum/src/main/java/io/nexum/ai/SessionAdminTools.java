package io.nexum.ai;

import io.nexum.transport.SessionManager;
import io.nexum.transport.Transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adding and removing counterparties while the engine runs.
 *
 * <p>The other session tools act on sessions that already exist. These change
 * which ones do — connecting to a venue that was not in the configuration, or
 * standing up an acceptor for a client that has just asked for one, without
 * restarting and dropping every other session.
 *
 * <p>A session added this way lives until the process ends. That is deliberate
 * rather than a shortcut: the configuration file is the record of what someone
 * chose to run, and a process that rewrites its own configuration makes it
 * stop being that.
 */
public final class SessionAdminTools {

    private final SessionManager manager;
    private final Transport transport;

    public SessionAdminTools(SessionManager manager, Transport transport) {
        this.manager = manager;
        this.transport = transport;
    }

    public List<AiTool> all() {
        return List.of(new Create(), new Remove(), new ListAdded());
    }

    // ------------------------------------------------------------------

    private final class Create implements AiTool {

        @Override
        public String name() {
            return "create_session";
        }

        @Override
        public String description() {
            return "Bring up a new FIX session while the engine runs, without restarting. "
                    + "An acceptor listens on the port for a counterparty to connect; an "
                    + "initiator connects out to host and port. The session lives until the "
                    + "process ends — it is not written to the configuration file, so a "
                    + "session that should survive a restart belongs there instead.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            parameters.put("sessionId", Parameter.required("string",
                    "Reads SENDER->TARGET, e.g. OMS->NEWVENUE"));
            parameters.put("role", Parameter.oneOf(
                    "Whether to listen or to connect out", "acceptor", "initiator"));
            parameters.put("port", Parameter.required("number",
                    "The port to listen on, or to connect to"));
            parameters.put("host", Parameter.optional("string",
                    "Where to connect; initiator only, default 127.0.0.1"));
            parameters.put("version", Parameter.optionalOneOf(
                    "Which FIX version the session speaks", "FIX44", "FIX42", "FIX50"));
            return parameters;
        }

        @Override
        public Effect effect() {
            // It opens a socket to or from a counterparty, which is the same
            // kind of act as sending to one.
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            try {
                SessionManager.Added added = manager.add(
                        text(arguments, "sessionId"),
                        text(arguments, "role"),
                        arguments.get("host") == null ? null : text(arguments, "host"),
                        (int) number(arguments, "port"),
                        arguments.get("version") == null ? "FIX44" : text(arguments, "version"));

                String where = added.host() == null
                        ? "listening on " + added.port()
                        : "connecting to " + added.host() + ":" + added.port();
                return Result.of(added.sessionId() + " is up as an " + added.role()
                                + ", " + where + "; it will not survive a restart",
                        Map.of("sessionId", added.sessionId(), "role", added.role(),
                                "port", added.port(), "version", added.version()));
            } catch (IllegalArgumentException rejected) {
                return Result.failed(rejected.getMessage());
            } catch (RuntimeException failure) {
                return Result.failed("could not start the session: " + failure.getMessage());
            }
        }
    }

    private final class Remove implements AiTool {

        @Override
        public String name() {
            return "remove_session";
        }

        @Override
        public String description() {
            return "Take down a session that create_session brought up. A session from the "
                    + "configuration file is left alone: removing one would make the running "
                    + "engine disagree with the file that describes it.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of("sessionId", Parameter.required("string",
                    "Which session, from list_added_sessions"));
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String sessionId = text(arguments, "sessionId");
            if (manager.remove(sessionId)) {
                return Result.of(sessionId + " is down and forgotten");
            }
            return Result.failed(transport.sessions().contains(sessionId)
                    ? sessionId + " comes from the configuration file, so it is not removed here"
                    : "no session called " + sessionId);
        }
    }

    private final class ListAdded implements AiTool {

        @Override
        public String name() {
            return "list_added_sessions";
        }

        @Override
        public String description() {
            return "The sessions brought up by create_session, which are the ones "
                    + "remove_session can take down. Configured sessions are in list_sessions.";
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
            Map<String, SessionManager.Added> added = manager.added();
            if (added.isEmpty()) {
                return Result.of("no sessions were added at runtime");
            }
            StringBuilder text = new StringBuilder();
            List<Object> rows = new ArrayList<>();
            added.values().forEach(session -> {
                boolean loggedOn = transport.sessions().contains(session.sessionId())
                        && transport.isLoggedOn(session.sessionId());
                text.append(session.sessionId()).append("  ").append(session.role())
                        .append("  ").append(loggedOn ? "logged on" : "not logged on")
                        .append('\n');
                rows.add(Map.of("sessionId", session.sessionId(), "role", session.role(),
                        "port", session.port(), "version", session.version(),
                        "loggedOn", loggedOn));
            });
            return Result.of(text.toString().trim(), Map.of("sessions", rows));
        }
    }

    // ------------------------------------------------------------------

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return String.valueOf(value);
    }

    private static double number(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value instanceof Number given ? given.doubleValue()
                : Double.parseDouble(String.valueOf(value));
    }
}
