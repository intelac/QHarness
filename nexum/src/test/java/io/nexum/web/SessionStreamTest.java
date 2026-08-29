package io.nexum.web;

import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.transport.RecordingTransport;
import io.nexum.transport.TransportEvents;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * That a subscriber is told what its sessions are doing, and only its sessions.
 *
 * <p>Filtering that quietly sends everything is the failure worth catching: it
 * looks correct from one subscriber's view and only shows up as noise once
 * something else connects.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SessionStreamTest {

    private static final String VENUE = "OMS->LSE";
    private static final String CLIENT = "OMS->FUNDX";

    private Context ctx;
    private PluginLoader loader;
    private MonitorApi api;

    @BeforeEach
    void start() {
        ctx = new Context();
        loader = new PluginLoader(ctx);
        ctx.register("transport", new RecordingTransport(VENUE, CLIENT));
        ctx.register("monitor", new io.nexum.monitor.OrderMonitor(60_000));

        api = new MonitorApi(0, null);
        loader.load(List.of(api));
    }

    @AfterEach
    void stop() {
        loader.unloadAll();
    }

    @Test
    @DisplayName("a subscriber is sent the current state before anything happens")
    void theCurrentStateArrivesFirst() throws Exception {
        // A page opening during a quiet spell would otherwise show nothing at
        // all until a session happened to change — which, when everything is
        // healthy, is never.
        try (Subscription live = subscribe("")) {
            String snapshot = live.nextFrame();

            assertTrue(snapshot.contains("event: snapshot"), snapshot);
            assertTrue(snapshot.contains(VENUE), snapshot);
            assertTrue(snapshot.contains(CLIENT), snapshot);
            assertTrue(snapshot.contains("nextSenderSeqNum"), snapshot);
        }
    }

    @Test
    @DisplayName("a change reaches a subscriber without it asking")
    void aChangeIsPushed() throws Exception {
        try (Subscription live = subscribe("")) {
            live.nextFrame();

            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    VENUE, TransportEvents.Kind.LOGOUT, "end of day"));

            String event = live.nextFrame();
            assertTrue(event.contains("event: session"), event);
            assertTrue(event.contains("LOGOUT"), event);
            assertTrue(event.contains("end of day"), event);
        }
    }

    @Test
    @DisplayName("a subscriber that named one session is not woken by another")
    void filteringIsRespected() throws Exception {
        try (Subscription live = subscribe(VENUE)) {
            String snapshot = live.nextFrame();

            // The snapshot is filtered too: a subscriber watching one session
            // should not learn the others exist.
            assertTrue(snapshot.contains(VENUE), snapshot);
            assertFalse(snapshot.contains(CLIENT), snapshot);

            // Something happening elsewhere must not arrive.
            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    CLIENT, TransportEvents.Kind.LOGOUT, "not this one"));

            // Then something on the watched session, which must.
            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    VENUE, TransportEvents.Kind.LOGON, "this one"));

            String event = live.nextFrame();
            assertTrue(event.contains("this one"), event);
            assertFalse(event.contains("not this one"),
                    "the other session's event should never have been sent: " + event);
        }
    }

    @Test
    @DisplayName("an event carries the state it left the session in")
    void eventsCarryTheResultingState() throws Exception {
        try (Subscription live = subscribe("")) {
            live.nextFrame();

            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    VENUE, TransportEvents.Kind.LOGON, "logged on"));

            // A subscriber reacting to a logon wants the sequence numbers that
            // came with it; asking for them separately races the next event.
            String event = live.nextFrame();
            assertTrue(event.contains("nextSenderSeqNum"), event);
            assertTrue(event.contains("loggedOn"), event);
        }
    }

    @Test
    @DisplayName("a logout says the session is down, not what the engine still thinks")
    void aLogoutReportsTheSessionDown() throws Exception {
        try (Subscription live = subscribe("")) {
            live.nextFrame();

            // The engine announces a logout while the connection is still
            // open. Reading its snapshot here reported a session that had just
            // gone down as logged on — the one field a subscriber watches.
            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    VENUE, TransportEvents.Kind.LOGOUT, "end of day"));

            String event = live.nextFrame();
            assertTrue(event.contains("\"loggedOn\":false"),
                    "a logout must report the session down: " + event);
        }
    }

    @Test
    @DisplayName("a lost connection also says the session is down")
    void aLostConnectionReportsTheSessionDown() throws Exception {
        try (Subscription live = subscribe("")) {
            live.nextFrame();

            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    VENUE, TransportEvents.Kind.CONNECTION_LOST, "socket closed"));

            assertTrue(live.nextFrame().contains("\"loggedOn\":false"));
        }
    }

    @Test
    @DisplayName("several subscribers each hear their own sessions")
    void subscribersAreIndependent() throws Exception {
        try (Subscription venue = subscribe(VENUE);
             Subscription client = subscribe(CLIENT)) {

            venue.nextFrame();
            client.nextFrame();

            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    VENUE, TransportEvents.Kind.LOGOUT, "venue only"));

            assertTrue(venue.nextFrame().contains("venue only"));

            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    CLIENT, TransportEvents.Kind.LOGOUT, "client only"));

            String forClient = client.nextFrame();
            assertTrue(forClient.contains("client only"), forClient);
            assertFalse(forClient.contains("venue only"),
                    "the venue's event should not have reached the client watcher");
        }
    }

    @Test
    @DisplayName("a subscriber that disconnects is dropped")
    void aGoneSubscriberIsReleased() throws Exception {
        Subscription live = subscribe("");
        live.nextFrame();
        live.close();

        // Writing to a closed connection is the only notification SSE gives
        // that a subscriber left, so the drop happens on the next event.
        for (int attempt = 0; attempt < 20; attempt++) {
            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    VENUE, TransportEvents.Kind.LOGOUT, "probe " + attempt));
            Thread.sleep(50);
        }

        // Nothing to assert on the wire — the point is that emitting to a dead
        // subscriber neither throws nor blocks the ones still listening.
        try (Subscription fresh = subscribe("")) {
            assertTrue(fresh.nextFrame().contains("event: snapshot"));
        }
    }

    @Test
    @DisplayName("a session created later reaches a subscriber as a complete row")
    void aLateSessionArrivesComplete() throws Exception {
        try (Subscription live = subscribe("")) {
            live.nextFrame();

            // What a runtime-added session announces. A watcher that refuses an
            // incomplete row — because rendering undefined sequence numbers is
            // worse than rendering nothing — drops a frame without these, so
            // the session never appears until something else happens to it.
            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    VENUE, TransportEvents.Kind.CREATED, "session created"));

            String event = live.nextFrame();
            assertTrue(event.contains("nextSenderSeqNum"),
                    "a creation must carry the state a watcher renders: " + event);
            assertTrue(event.contains("loggedOn"), event);
        }
    }

    @Test
    @DisplayName("a message moving the sequence numbers reaches a subscriber")
    void trafficRefreshesTheSequenceNumbers() throws Exception {
        try (Subscription live = subscribe("")) {
            live.nextFrame();

            // Sequence numbers move on every message, not only when a session
            // logs on or off — and a watcher showing the numbers from the last
            // logon is showing a session that has been busy for hours as though
            // nothing had happened.
            ctx.emit(TransportEvents.WIRE, new TransportEvents.Wire(
                    VENUE, TransportEvents.Direction.OUT, "8=FIX.4.4\u000135=D\u0001"));

            String event = live.nextFrame();
            assertTrue(event.contains("nextSenderSeqNum"),
                    "traffic must refresh what a watcher shows: " + event);
            assertTrue(event.contains(VENUE), event);
        }
    }

    // ------------------------------------------------------------------

    private Subscription subscribe(String sessions) throws Exception {
        String url = "http://127.0.0.1:" + api.port() + "/api/sessions/stream"
                + (sessions.isEmpty() ? "" : "?sessions=" + java.net.URLEncoder.encode(
                        sessions, java.nio.charset.StandardCharsets.UTF_8));

        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.connect();
        return new Subscription(connection);
    }

    /** One open stream, read frame by frame. */
    private static final class Subscription implements AutoCloseable {

        private final HttpURLConnection connection;
        private final BufferedReader reader;

        Subscription(HttpURLConnection connection) throws Exception {
            this.connection = connection;
            this.reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8));
        }

        /** Read up to the blank line that ends one SSE frame. */
        String nextFrame() throws Exception {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!lines.isEmpty()) {
                        return String.join("\n", lines);
                    }
                    continue;
                }
                lines.add(line);
            }
            fail("the stream ended before a frame arrived");
            return null;
        }

        @Override
        public void close() {
            connection.disconnect();
        }
    }
}
