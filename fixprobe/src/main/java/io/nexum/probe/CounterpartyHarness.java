package io.nexum.probe;

import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;
import quickfix.field.MsgType;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One FIX endpoint the harness puts up to talk to a system under test.
 *
 * <p>A system that routes orders sits between two counterparties: clients send
 * it orders, and it sends those orders on to a market. Testing it means
 * standing on both sides — sending requests in as a client, and answering as
 * the market — which is what this provides one instance at a time.
 *
 * <p>Everything is per instance. The simulators this grew out of kept their
 * session in static fields, which is fine for one in-process pair and wrong
 * here: a harness needs a client endpoint and a market endpoint running at once,
 * pointed at different hosts, without either one's state reaching the other.
 *
 * <p>Nothing is answered automatically. A conformance test asserts what a
 * system does with a particular message, so the reply has to be the one the
 * test chose rather than whatever a matching engine decided; every message this
 * sends comes from an explicit call. What arrives is recorded for the test to
 * read back.
 */
public final class CounterpartyHarness implements Application {

    /** Which side of a connection this endpoint takes. */
    public enum Role {
        /** Dial out to the system under test. A client does this. */
        INITIATOR,
        /** Listen for the system under test to dial in. A market does this. */
        ACCEPTOR
    }

    /** One message that crossed the wire, as evidence for a test. */
    public record Traffic(String direction, String msgType, String raw, long at) {}

    private final String name;
    private final Role role;
    private final List<Traffic> traffic = new CopyOnWriteArrayList<>();
    private final Map<String, SessionID> sessions = new ConcurrentHashMap<>();

    private quickfix.Connector connector;

    /**
     * @param name what this endpoint is called in tool output and errors, so a
     *     failure names the side it happened on rather than a session id the
     *     caller never chose
     */
    public CounterpartyHarness(String name, Role role) {
        this.name = name;
        this.role = role;
    }

    /**
     * Bring the endpoint up.
     *
     * @param host where the system under test listens; ignored for an acceptor,
     *     which listens rather than dials
     * @param port the port to dial or to listen on
     * @param senderCompId who this endpoint claims to be
     * @param targetCompId who it expects to be talking to
     */
    public synchronized void start(
            String host, int port, String senderCompId, String targetCompId) throws Exception {

        if (connector != null) {
            throw new IllegalStateException(name + " is already running");
        }
        String settings = role == Role.INITIATOR
                ? initiatorSettings(host, port, senderCompId, targetCompId)
                : acceptorSettings(port, senderCompId, targetCompId);

        SessionSettings parsed = new SessionSettings(
                new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8)));
        connector = role == Role.INITIATOR
                ? new SocketInitiator(this, new MemoryStoreFactory(), parsed, new DefaultMessageFactory())
                : new SocketAcceptor(this, new MemoryStoreFactory(), parsed, new DefaultMessageFactory());
        connector.start();
    }

    /** Take the endpoint down, so a test can restart it against another target. */
    public synchronized void stop() {
        if (connector != null) {
            connector.stop(true);
            connector = null;
        }
        sessions.clear();
    }

    /** Whether the endpoint has a session that is logged on. */
    public boolean isLoggedOn() {
        return sessions.values().stream()
                .map(Session::lookupSession)
                .anyMatch(session -> session != null && session.isLoggedOn());
    }

    /** Send a message the caller built, on this endpoint's session. */
    public void send(Message message) {
        SessionID target = sessions.values().stream().findFirst().orElseThrow(
                () -> new IllegalStateException(name + " has no session; is it connected?"));
        try {
            if (!Session.sendToTarget(message, target)) {
                throw new IllegalStateException(name + " could not send; the session is not logged on");
            }
        } catch (quickfix.SessionNotFound absent) {
            throw new IllegalStateException(name + " has no session " + target, absent);
        }
    }

    /** Everything that crossed this endpoint, oldest first. */
    public List<Traffic> traffic() {
        return List.copyOf(traffic);
    }

    /** Messages of one type that arrived, so a test can assert on what it was sent. */
    public List<Traffic> received(String msgType) {
        return traffic.stream()
                .filter(entry -> "in".equals(entry.direction()) && entry.msgType().equals(msgType))
                .toList();
    }

    /** Forget the recorded traffic, so one scenario does not read another's. */
    public void clearTraffic() {
        traffic.clear();
    }

    /** What this endpoint is called. */
    public String name() {
        return name;
    }

    /** Which side it takes. */
    public Role role() {
        return role;
    }

    // ------------------------------------------------------------------

    private static String initiatorSettings(
            String host, int port, String senderCompId, String targetCompId) {
        return """
                [default]
                ConnectionType=initiator
                SocketConnectHost=%s
                SocketConnectPort=%d
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                ReconnectInterval=2
                UseDataDictionary=N
                ResetOnLogon=Y

                [session]
                BeginString=FIX.4.4
                SenderCompID=%s
                TargetCompID=%s
                """.formatted(host, port, senderCompId, targetCompId);
    }

    private static String acceptorSettings(int port, String senderCompId, String targetCompId) {
        return """
                [default]
                ConnectionType=acceptor
                SocketAcceptPort=%d
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                UseDataDictionary=N
                ResetOnLogon=Y

                [session]
                BeginString=FIX.4.4
                SenderCompID=%s
                TargetCompID=%s
                """.formatted(port, senderCompId, targetCompId);
    }

    private void record(String direction, Message message) {
        String msgType;
        try {
            msgType = message.getHeader().getString(MsgType.FIELD);
        } catch (FieldNotFound missing) {
            // Only reachable for a message the engine built without a type,
            // which nothing else here reads; recording it unlabelled keeps the
            // evidence rather than dropping it.
            msgType = "?";
        }
        traffic.add(new Traffic(direction, msgType, message.toString(), System.currentTimeMillis()));
    }

    @Override
    public void fromApp(Message message, SessionID sessionID) {
        record("in", message);
    }

    @Override
    public void toApp(Message message, SessionID sessionID) {
        record("out", message);
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID) {
        record("in", message);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        record("out", message);
    }

    @Override
    public void onCreate(SessionID sessionID) {
        sessions.put(sessionID.toString(), sessionID);
    }

    @Override
    public void onLogon(SessionID sessionID) {
        sessions.put(sessionID.toString(), sessionID);
    }

    @Override
    public void onLogout(SessionID sessionID) {}
}
