package io.nexum.transport;

import io.nexum.core.Context;
import io.nexum.core.Disposable;
import io.nexum.core.Plugin;
import io.nexum.message.FixMessage;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@code transport} service in front of however many engines are mounted.
 *
 * <p>A deployment runs several connections at once — an acceptor for clients, an
 * initiator per venue — and consumers should not have to know which engine owns
 * which session. Each engine registers its sessions here and the hub dispatches
 * by session id.
 *
 * <p>Registrations are reversible, so unloading one engine removes exactly its
 * sessions and leaves the rest connected.
 */
public final class TransportHub implements Transport {

    private final Map<String, Transport> bySession = new ConcurrentHashMap<>();

    /** Publishes the hub itself; individual engines attach to it. */
    public static final class HubPlugin implements Plugin {
        @Override
        public String name() {
            return "transport";
        }

        @Override
        public void apply(Context ctx) {
            ctx.register("transport", new TransportHub());
        }
    }

    /**
     * Attach an engine's sessions. Reversed when its plugin unloads.
     *
     * @throws IllegalStateException when a session id is already served. Two
     *     engines claiming one session is a configuration mistake, and letting
     *     the second win quietly means unloading it later takes the first one
     *     offline with it.
     */
    public Disposable attach(Set<String> sessionIds, Transport engine) {
        for (String sessionId : sessionIds) {
            Transport existing = bySession.putIfAbsent(sessionId, engine);
            if (existing != null && existing != engine) {
                // Roll back what this call already claimed, so a refused attach
                // leaves nothing half-registered.
                sessionIds.forEach(claimed -> bySession.remove(claimed, engine));
                throw new IllegalStateException(
                        "session \"" + sessionId + "\" is already served by another"
                                + " transport; disable one in configuration");
            }
        }
        return () -> sessionIds.forEach(sessionId -> bySession.remove(sessionId, engine));
    }

    @Override
    public boolean send(String sessionId, FixMessage message) {
        Transport engine = bySession.get(sessionId);
        return engine != null && engine.send(sessionId, message);
    }

    @Override
    public Set<String> sessions() {
        return new LinkedHashSet<>(bySession.keySet());
    }

    @Override
    public boolean isLoggedOn(String sessionId) {
        Transport engine = bySession.get(sessionId);
        return engine != null && engine.isLoggedOn(sessionId);
    }

    // Each of these reaches the one engine that owns the session. An unknown
    // session is false rather than an exception: a caller naming a session that
    // is not here has made an ordinary mistake, not caused a fault.

    @Override
    public boolean logon(String sessionId) {
        Transport engine = bySession.get(sessionId);
        return engine != null && engine.logon(sessionId);
    }

    @Override
    public boolean logout(String sessionId, String reason) {
        Transport engine = bySession.get(sessionId);
        return engine != null && engine.logout(sessionId, reason);
    }

    @Override
    public boolean disconnect(String sessionId, String reason) {
        Transport engine = bySession.get(sessionId);
        return engine != null && engine.disconnect(sessionId, reason);
    }

    @Override
    public boolean reset(String sessionId) {
        Transport engine = bySession.get(sessionId);
        return engine != null && engine.reset(sessionId);
    }

    @Override
    public boolean resequence(String sessionId, Integer nextSender, Integer nextTarget) {
        Transport engine = bySession.get(sessionId);
        return engine != null && engine.resequence(sessionId, nextSender, nextTarget);
    }

    @Override
    public SessionStatus status(String sessionId) {
        Transport engine = bySession.get(sessionId);
        return engine == null
                ? new SessionStatus(sessionId, false, 0, 0, "not mounted")
                : engine.status(sessionId);
    }
}
