package io.nexum.e2e;

import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.MsgType;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A FIX client that keeps what it was sent.
 *
 * <p>The demo's client prints reports, which is enough to read but not to
 * assert on. This one records them, so an end-to-end test can state what the
 * client should have received and fail when it did not.
 *
 * <p>Deliberately its own class rather than a change to {@code SimClient}: the
 * demo is for reading and this is for asserting, and making one serve both ends
 * with a class that does neither well.
 */
public final class RecordingClient implements Application {

    /** One message this client received, flattened for assertions. */
    public record Received(String msgType, Map<Integer, String> fields) {

        public String field(int tag) {
            return fields.get(tag);
        }
    }

    private final List<Received> received = new ArrayList<>();
    private final Map<String, SessionID> sessions = new ConcurrentHashMap<>();

    private volatile boolean loggedOn;

    public static SocketInitiator start(
            RecordingClient application, int port, String senderCompId, String targetCompId)
            throws Exception {

        String config = """
                [default]
                ConnectionType=initiator
                SocketConnectHost=127.0.0.1
                SocketConnectPort=%d
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                ReconnectInterval=1
                UseDataDictionary=N
                ResetOnLogon=Y
                FileLogPath=target/e2e-logs

                [session]
                BeginString=FIX.4.4
                SenderCompID=%s
                TargetCompID=%s
                """.formatted(port, senderCompId, targetCompId);

        SessionSettings settings = new SessionSettings(
                new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
        SocketInitiator initiator = new SocketInitiator(
                application, new MemoryStoreFactory(), settings, new DefaultMessageFactory());
        initiator.start();
        return initiator;
    }

    public boolean isLoggedOn() {
        return loggedOn;
    }

    public SessionID session() {
        return sessions.values().stream().findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // What came back
    // ------------------------------------------------------------------

    public List<Received> all() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    public List<Received> ofType(String msgType) {
        return all().stream().filter(r -> r.msgType().equals(msgType)).toList();
    }

    /** Reports naming one ClOrdID(11), in the order they arrived. */
    public List<Received> forClOrdId(String clOrdId) {
        return all().stream()
                .filter(r -> clOrdId.equals(r.field(11)))
                .toList();
    }

    public void clear() {
        synchronized (received) {
            received.clear();
        }
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    @Override
    public void fromApp(Message message, SessionID id) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            Map<Integer, String> fields = new java.util.LinkedHashMap<>();
            message.iterator().forEachRemaining(
                    field -> fields.put(field.getTag(), field.getObject().toString()));
            synchronized (received) {
                received.add(new Received(msgType, fields));
            }
        } catch (FieldNotFound withoutMsgType) {
            // Not something a counterparty can send; nothing to record.
        }
    }

    @Override
    public void onCreate(SessionID id) {
        sessions.put(id.toString(), id);
    }

    @Override
    public void onLogon(SessionID id) {
        sessions.put(id.toString(), id);
        loggedOn = true;
    }

    @Override
    public void onLogout(SessionID id) {
        loggedOn = false;
    }

    @Override
    public void toAdmin(Message message, SessionID id) {}

    @Override
    public void fromAdmin(Message message, SessionID id) {}

    @Override
    public void toApp(Message message, SessionID id) {}
}
