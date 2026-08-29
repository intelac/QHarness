package io.nexum.web;

import com.sun.net.httpserver.HttpExchange;

import io.nexum.core.Context;
import io.nexum.transport.Transport;
import io.nexum.transport.TransportEvents;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells subscribers what the FIX sessions are doing, as it happens.
 *
 * <p>A page that polls for session state is either stale or wasteful, and the
 * thing it is watching for — a session dropping — is exactly the moment the
 * gap matters. The engine already announces every logon, logout, disconnection
 * and resequence; this carries those announcements out.
 *
 * <p>A subscriber says which sessions it cares about. A sidebar watching a
 * whole desk asks for all of them; something following one counterparty asks
 * for that one and is not woken by the others.
 *
 * <p>Every subscriber is sent the current state on connect, before any event.
 * Without it a page opens blank and stays blank until something happens to a
 * session — which, when everything is healthy, is never.
 */
public final class SessionStream {

    /** One connected subscriber and what it asked to hear about. */
    private record Subscriber(HttpExchange exchange, Set<String> sessions) {

        /** Empty means everything: a subscriber that named nothing wants the lot. */
        boolean wants(String sessionId) {
            return sessions.isEmpty() || sessions.contains(sessionId);
        }
    }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    private final Transport transport;

    public SessionStream(Context ctx, Transport transport) {
        this.transport = transport;

        ctx.onEvent(TransportEvents.SESSION, (TransportEvents.SessionEvent event) ->
                announce(event));

        // Sequence numbers move on every message, and only lifecycle events
        // were announced — so a watcher showed the numbers from the last logon
        // and a session that had been busy for hours looked untouched. Traffic
        // carries no state of its own, so what it triggers is a fresh reading
        // of the session it crossed.
        ctx.onEvent(TransportEvents.WIRE, (TransportEvents.Wire wire) ->
                refresh(wire.sessionId()));
    }

    /**
     * Accept a subscriber.
     *
     * <p>The request stays open for as long as the subscriber is listening, so
     * this returns having handed the exchange over rather than having answered
     * it.
     *
     * @param wanted comma-separated session ids, or empty for all of them
     */
    public void subscribe(HttpExchange exchange, String wanted) throws IOException {
        Set<String> sessions = parse(wanted);

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        // Without this a proxy buffers the stream and the events arrive in a
        // batch when the connection closes, which is the opposite of the point.
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("X-Accel-Buffering", "no");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);

        Subscriber subscriber = new Subscriber(exchange, sessions);
        subscribers.add(subscriber);

        // The current state first. A page that opened during a quiet spell
        // would otherwise show nothing at all until a session happened to
        // change — which, when everything is healthy, is never.
        try {
            send(subscriber, "snapshot", snapshot(sessions));
        } catch (IOException gone) {
            subscribers.remove(subscriber);
            throw gone;
        }
    }

    /** How many subscribers are listening, for a status page or a test. */
    public int subscriberCount() {
        return subscribers.size();
    }

    /** Stop every subscriber, for an orderly shutdown. */
    public void closeAll() {
        for (Subscriber subscriber : subscribers) {
            subscriber.exchange().close();
        }
        subscribers.clear();
    }

    // ------------------------------------------------------------------

    /**
     * Send a subscriber the session as it now stands.
     *
     * <p>Unlike {@link #announce}, this carries no event: nothing happened to
     * the session's lifecycle, its numbers merely moved. Subscribers that
     * replace a row from what arrives treat it the same way.
     */
    private void refresh(String sessionId) {
        Transport.SessionStatus status = statusOf(sessionId);
        if (status == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("kind", "TRAFFIC");
        payload.put("detail", "sequence numbers moved");
        payload.put("at", System.currentTimeMillis());
        payload.put("loggedOn", status.loggedOn());
        payload.put("nextSenderSeqNum", status.nextSenderSeqNum());
        payload.put("nextTargetSeqNum", status.nextTargetSeqNum());
        payload.put("beginString", status.beginString());
        broadcast(sessionId, payload);
    }

    private void announce(TransportEvents.SessionEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", event.sessionId());
        payload.put("kind", event.kind().name());
        payload.put("detail", event.detail());
        payload.put("at", System.currentTimeMillis());

        // The state as well as the change. A subscriber reacting to a logon
        // wants the sequence numbers that came with it, and asking for them
        // separately races the next event.
        Transport.SessionStatus status = statusOf(event.sessionId());
        if (status != null) {
            // Whether it is up comes from the event, not from the snapshot.
            // The engine announces a logout while the connection is still
            // open, so reading it here reports a session that just went down
            // as still logged on — the one field a subscriber is watching.
            payload.put("loggedOn", loggedOnAfter(event, status));
            payload.put("nextSenderSeqNum", status.nextSenderSeqNum());
            payload.put("nextTargetSeqNum", status.nextTargetSeqNum());
            payload.put("beginString", status.beginString());
        }

        broadcast(event.sessionId(), payload);
    }

    /** Send one payload to every subscriber that asked about this session. */
    private void broadcast(String sessionId, Map<String, Object> payload) {
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.wants(sessionId)) {
                continue;
            }
            try {
                send(subscriber, "session", payload);
            } catch (IOException gone) {
                // The subscriber went away. Dropping it here is the only
                // notification a closed SSE connection gives.
                subscribers.remove(subscriber);
                subscriber.exchange().close();
            }
        }
    }

    private Map<String, Object> snapshot(Set<String> wanted) {
        List<Object> rows = new ArrayList<>();
        for (String sessionId : transport.sessions()) {
            if (!wanted.isEmpty() && !wanted.contains(sessionId)) {
                continue;
            }
            Transport.SessionStatus status = transport.status(sessionId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", status.sessionId());
            row.put("loggedOn", status.loggedOn());
            row.put("nextSenderSeqNum", status.nextSenderSeqNum());
            row.put("nextTargetSeqNum", status.nextTargetSeqNum());
            row.put("beginString", status.beginString());
            rows.add(row);
        }
        return Map.of("sessions", rows, "at", System.currentTimeMillis());
    }

    /**
     * Whether the session is up once this event has taken effect.
     *
     * <p>Sequence numbers can be read as they are — they have already moved by
     * the time the event is announced. Connectedness cannot: the engine
     * announces a logout before the socket closes.
     */
    private static boolean loggedOnAfter(
            TransportEvents.SessionEvent event, Transport.SessionStatus status) {

        return switch (event.kind()) {
            case LOGOUT, CONNECTION_LOST, LOGON_REFUSED -> false;
            case LOGON -> true;
            // CREATED and SEQUENCE_RESET say nothing about connectedness, so
            // the engine's own answer is the right one.
            default -> status.loggedOn();
        };
    }

    private Transport.SessionStatus statusOf(String sessionId) {
        try {
            return transport.sessions().contains(sessionId)
                    ? transport.status(sessionId)
                    : null;
        } catch (RuntimeException unavailable) {
            // A session announcing its own creation may not be answerable yet.
            // The event still carries what happened, which is the point.
            return null;
        }
    }

    private static void send(Subscriber subscriber, String event, Object data)
            throws IOException {

        String frame = "event: " + event + "\ndata: " + Json.write(data) + "\n\n";
        OutputStream out = subscriber.exchange().getResponseBody();

        // Synchronised on the stream: events arrive on the engine's session
        // threads, and two frames interleaved mid-write are two corrupt frames
        // rather than one late one.
        synchronized (out) {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** Which sessions a subscriber named, if any. */
    private static Set<String> parse(String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return Set.of();
        }
        Set<String> sessions = new java.util.LinkedHashSet<>();
        for (String part : wanted.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sessions.add(trimmed);
            }
        }
        return sessions;
    }
}
