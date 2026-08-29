package io.nexum.transport;

import io.nexum.core.Context;
import io.nexum.core.Disposable;
import io.nexum.core.PluginLoader;
import io.nexum.message.DialectRegistry;
import io.nexum.message.FixVersion;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds and removes FIX sessions while the engine is running.
 *
 * <p>Adding a counterparty otherwise means editing the configuration and
 * restarting, which drops every other session with it. Nothing new was needed
 * to avoid that — one plugin already owns one session and its connector starts
 * inside an effect — but the two steps a session needs have to happen in order,
 * which is what this owns.
 *
 * <p>Sessions added here live until the process ends. They are deliberately not
 * written back to the configuration file: the deployment's own sessions are
 * described there and a process that rewrites its own configuration makes the
 * file no longer the record of what someone chose. A session that should
 * survive a restart belongs in the file, added by whoever runs the deployment.
 */
public final class SessionManager {

    /** What a session added at runtime was created from, so it can be described. */
    public record Added(String sessionId, String role, String host, int port, String version) {}

    private final Context ctx;
    private final PluginLoader loader;
    private final Map<String, Added> added = new ConcurrentHashMap<>();
    private final Map<String, Disposable> dialects = new ConcurrentHashMap<>();

    public SessionManager(Context ctx, PluginLoader loader) {
        this.ctx = ctx;
        this.loader = loader;
    }

    /**
     * Bring a session up.
     *
     * <p>Two steps in one call, because the order matters and getting it wrong
     * fails obscurely: the dialect is declared first, since the transport asks
     * the registry which version a session speaks as it starts, and a session
     * without one refuses to load.
     *
     * @param sessionId reads SENDER-&gt;TARGET, the same vocabulary the
     *     configuration file uses
     * @param host where to connect; ignored for an acceptor, which listens
     * @throws IllegalArgumentException when the id is malformed, the version is
     *     unknown, or a session by that name already exists
     */
    public Added add(String sessionId, String role, String host, int port, String version) {
        if (sessionId.indexOf("->") < 0) {
            throw new IllegalArgumentException(
                    "session id \"" + sessionId + "\" must read SENDER->TARGET");
        }
        Transport transport = ctx.get("transport");
        if (transport.sessions().contains(sessionId)) {
            throw new IllegalArgumentException(
                    "session \"" + sessionId + "\" already exists");
        }
        boolean acceptor = "acceptor".equalsIgnoreCase(role);
        FixVersion parsed = version(version);

        DialectRegistry registry = ctx.get("dialects");
        Disposable dialect = registry.declareSession(sessionId, parsed);
        try {
            loader.load(List.of(plugin(sessionId, acceptor, host, port, parsed)));
        } catch (RuntimeException failure) {
            // The dialect was declared for a session that then failed to start,
            // and leaving it behind would make a later attempt on the same name
            // look like it succeeded partially.
            dialect.dispose();
            throw failure;
        }

        dialects.put(sessionId, dialect);
        Added record = new Added(sessionId, acceptor ? "acceptor" : "initiator",
                acceptor ? null : host, port, parsed.name());
        added.put(sessionId, record);
        return record;
    }

    /**
     * Take a session down and forget it.
     *
     * @return true when it was one this manager added; false leaves a
     *     configured session alone, since removing one would make the running
     *     engine disagree with the file that describes it
     */
    public boolean remove(String sessionId) {
        if (!added.containsKey(sessionId)) {
            return false;
        }
        loader.unload("transport-" + sessionId);
        Disposable dialect = dialects.remove(sessionId);
        if (dialect != null) {
            dialect.dispose();
        }
        added.remove(sessionId);
        return true;
    }

    /** The sessions this manager added, which a configured one is never among. */
    public Map<String, Added> added() {
        return Map.copyOf(added);
    }

    /** Whether a session was added at runtime rather than configured. */
    public boolean isAdded(String sessionId) {
        return added.containsKey(sessionId);
    }

    // ------------------------------------------------------------------

    private static FixVersion version(String name) {
        for (FixVersion candidate : FixVersion.values()) {
            if (candidate.name().equalsIgnoreCase(name)
                    || candidate.beginString().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown FIX version \"" + name + "\"");
    }

    /**
     * One session's plugin.
     *
     * <p>Sequence numbers are kept in memory: a session added at runtime is not
     * in the configuration, so after a restart there is nothing to resume and a
     * store on disk would only leave numbers behind for a session that no
     * longer exists.
     */
    private static QuickFixPlugin plugin(
            String sessionId, boolean acceptor, String host, int port, FixVersion version) {

        int arrow = sessionId.indexOf("->");
        StringBuilder text = new StringBuilder();
        text.append("[default]\n")
                .append("ConnectionType=").append(acceptor ? "acceptor" : "initiator").append('\n')
                .append("StartTime=00:00:00\n")
                .append("EndTime=00:00:00\n")
                .append("HeartBtInt=30\n")
                .append("ReconnectInterval=5\n")
                .append("ResetOnLogon=Y\n")
                .append("UseDataDictionary=N\n")
                .append("FileLogPath=logs/").append(sessionId.replace("->", "-to-")).append('\n');

        if (acceptor) {
            text.append("SocketAcceptPort=").append(port).append('\n');
        } else {
            text.append("SocketConnectHost=").append(host == null ? "127.0.0.1" : host).append('\n')
                    .append("SocketConnectPort=").append(port).append('\n');
        }

        text.append("\n[session]\n")
                .append("BeginString=").append(version.beginString()).append('\n')
                .append("SenderCompID=").append(sessionId, 0, arrow).append('\n')
                .append("TargetCompID=").append(sessionId.substring(arrow + 2)).append('\n');

        ByteArrayInputStream config =
                new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8));
        return acceptor
                ? QuickFixPlugin.acceptor(sessionId, config, false)
                : QuickFixPlugin.initiator(sessionId, config, false);
    }
}
