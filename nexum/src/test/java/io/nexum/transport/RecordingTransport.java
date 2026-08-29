package io.nexum.transport;

import io.nexum.core.Context;
import io.nexum.core.Plugin;
import io.nexum.core.Scope;
import io.nexum.message.FixMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A transport that keeps what was sent instead of putting it on a socket.
 *
 * <p>Exists because a test of the order flow should not need two engines, two
 * ports and a wait for logon. That it can stand in at all is the point of
 * keeping the transport behind an interface: nothing above it knows which
 * provider it is talking to.
 *
 * <p>Also lets a test deliver a report at a chosen moment, which a real venue
 * cannot be asked to do.
 */
public final class RecordingTransport implements Transport, Plugin {

    /** One message the system tried to send. */
    public record Sent(String sessionId, FixMessage message) {

        public String field(int tag) {
            return message.get(tag);
        }
    }

    private final List<Sent> sent = new ArrayList<>();
    private final Set<String> sessions = new LinkedHashSet<>();
    private final Set<String> down = new LinkedHashSet<>();
    private final AtomicInteger seqNum = new AtomicInteger(1);

    private Context ctx;

    public RecordingTransport(String... sessionIds) {
        this.sessions.addAll(List.of(sessionIds));
    }

    @Override
    public String name() {
        return "transport";
    }

    @Override
    public void apply(Context context) {
        this.ctx = context;
        context.register("transport", this);
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    @Override
    public boolean send(String sessionId, FixMessage message) {
        if (down.contains(sessionId)) {
            // A refused send is how the system learns an order never left, and
            // a test needs to be able to produce one.
            return false;
        }
        synchronized (sent) {
            sent.add(new Sent(sessionId, message));
        }
        return true;
    }

    @Override
    public Set<String> sessions() {
        return Set.copyOf(sessions);
    }

    @Override
    public boolean isLoggedOn(String sessionId) {
        return sessions.contains(sessionId) && !down.contains(sessionId);
    }

    @Override
    public SessionStatus status(String sessionId) {
        return new SessionStatus(
                sessionId, isLoggedOn(sessionId), seqNum.get(), seqNum.get(), "FIX.4.4");
    }

    // ------------------------------------------------------------------
    // Controlling a session
    //
    // Recorded rather than performed: there is no engine here to log on. A
    // test asserting that a tool asked for a reset needs to see the request,
    // and one asserting an order still went out needs the session to behave.
    // ------------------------------------------------------------------

    /** One control action a caller asked for. */
    public record Control(String action, String sessionId, String detail) {}

    private final List<Control> controls = new ArrayList<>();

    @Override
    public boolean logon(String sessionId) {
        return control("logon", sessionId, null, () -> down.remove(sessionId));
    }

    @Override
    public boolean logout(String sessionId, String reason) {
        return control("logout", sessionId, reason, () -> down.add(sessionId));
    }

    @Override
    public boolean disconnect(String sessionId, String reason) {
        return control("disconnect", sessionId, reason, () -> down.add(sessionId));
    }

    @Override
    public boolean reset(String sessionId) {
        return control("reset", sessionId, null, () -> seqNum.set(1));
    }

    @Override
    public boolean resequence(String sessionId, Integer nextSender, Integer nextTarget) {
        return control("resequence", sessionId,
                "sender=" + nextSender + " target=" + nextTarget,
                () -> {
                    if (nextSender != null) {
                        seqNum.set(nextSender);
                    }
                });
    }

    private boolean control(
            String action, String sessionId, String detail, Runnable effect) {

        if (!sessions.contains(sessionId)) {
            return false;
        }
        effect.run();
        synchronized (controls) {
            controls.add(new Control(action, sessionId, detail));
        }
        return true;
    }

    /** What was asked of the sessions, in order. */
    public List<Control> controls() {
        synchronized (controls) {
            return List.copyOf(controls);
        }
    }

    // ------------------------------------------------------------------
    // Driving a test
    // ------------------------------------------------------------------

    /** Deliver a message as though a counterparty had sent it. */
    public void deliver(String sessionId, FixMessage message) {
        TransportEvents.InFlight arrival = TransportEvents.InFlight.inbound(
                message, sessionId, seqNum.getAndIncrement());

        TransportEvents.InFlight afterSession = ctx.waterfall(
                TransportEvents.MESSAGE_INBOUND,
                Scope.session(sessionId),
                arrival,
                flight -> flight);

        if (afterSession.rejected()) {
            return;
        }
        ctx.emit(TransportEvents.MESSAGE_INBOUND + "/accepted", afterSession);
    }

    /** Make sends on a session fail, as a disconnected one would. */
    public void takeDown(String sessionId) {
        down.add(sessionId);
    }

    public void bringUp(String sessionId) {
        down.remove(sessionId);
    }

    // ------------------------------------------------------------------
    // What was sent
    // ------------------------------------------------------------------

    public List<Sent> all() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    public List<Sent> to(String sessionId) {
        return all().stream().filter(s -> s.sessionId().equals(sessionId)).toList();
    }

    public List<Sent> ofType(String msgType) {
        return all().stream().filter(s -> s.message().msgType().equals(msgType)).toList();
    }

    public Optional<Sent> lastOfType(String msgType) {
        List<Sent> matching = ofType(msgType);
        return matching.isEmpty()
                ? Optional.empty()
                : Optional.of(matching.get(matching.size() - 1));
    }

    public int count() {
        return all().size();
    }

    public void clear() {
        synchronized (sent) {
            sent.clear();
        }
    }
}
