package io.nexum.transport;

import io.nexum.core.Context;
import io.nexum.core.Scope;
import io.nexum.message.DialectRegistry;
import io.nexum.message.FixLayers;
import io.nexum.message.FixTags;
import io.nexum.message.FixMessage;

import java.io.IOException;

import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.MsgType;

import java.util.Optional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * QuickFIX/J mounted as the {@code transport} provider.
 *
 * <p>The engine keeps what it is good at — the socket, logon, heartbeats,
 * sequence numbers, resend, persistence. Everything above the session layer is
 * handed to the plugin chains: an inbound application message becomes a
 * {@link FixMessage}, crosses the session-scoped waterfall, and is published for
 * routing. Nothing here decides what an order means.
 *
 * <p>Session-level messages never reach the chains. {@code fromAdmin} and
 * {@code toAdmin} are recorded for audit and otherwise left to the engine —
 * a plugin that rewrote a Logon or a SequenceReset would break the session it
 * runs on.
 *
 * <p>Threading: QuickFIX/J calls these callbacks on its own IO threads, one at a
 * time per session. Gates therefore run on the session thread and must not
 * block; anything slow belongs on {@code emit}, which never waits.
 */
public final class QuickFixTransport implements Application, Transport {

    private final Context ctx;
    private final DialectRegistry dialects;
    /**
     * Sessions the engine has created. Concurrent because the engine adds to it
     * on its own threads while the monitor API reads it on HTTP threads, and a
     * dynamic acceptor adds sessions long after startup.
     */
    private final Set<SessionID> knownSessions =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Whether this engine answers connections or makes them.
     *
     * <p>Only an acceptor authenticates a Logon. An initiator's Logon is the
     * counterparty <em>answering</em> ours, and checking it against our own
     * password policy refuses every venue we dial — which is exactly what
     * happened the first time a destination was configured alongside a
     * password.
     */
    private final boolean acceptor;
    private final SessionSettings settings;

    public QuickFixTransport(Context ctx, DialectRegistry dialects) {
        this(ctx, dialects, true);
    }

    public QuickFixTransport(Context ctx, DialectRegistry dialects, boolean acceptor) {
        this(ctx, dialects, acceptor, null);
    }

    /**
     * @param settings what the connector was started from, so a session can say
     *     which port it is reached on. QuickFIX/J already holds this; without
     *     it the answer exists only in a configuration file, and whoever needs
     *     to connect has to open one.
     */
    public QuickFixTransport(
            Context ctx, DialectRegistry dialects, boolean acceptor, SessionSettings settings) {
        this.ctx = ctx;
        this.dialects = dialects;
        this.acceptor = acceptor;
        this.settings = settings;
    }

    // ------------------------------------------------------------------
    // Application — inbound
    // ------------------------------------------------------------------

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {

        String sessionId = idOf(sessionID);
        String raw = message.toString();
        ctx.emit(TransportEvents.WIRE,
                new TransportEvents.Wire(sessionId, TransportEvents.Direction.IN, raw));

        FixMessage parsed = dialects.codecForSession(sessionId).parse(raw);
        int seqNum = message.getHeader().isSetField(FixTags.MSG_SEQ_NUM)
                ? message.getHeader().getInt(FixTags.MSG_SEQ_NUM)
                : 0;

        TransportEvents.InFlight result = ctx.waterfall(
                TransportEvents.MESSAGE_INBOUND,
                Scope.session(sessionId),
                TransportEvents.InFlight.inbound(parsed, sessionId, seqNum),
                inFlight -> inFlight);

        if (result.rejected()) {
            // A gate refused it. The refusal is already recorded by whichever
            // gate made the call; the engine is told nothing, because a session
            // -level reject is a different decision from a business one.
            return;
        }
        ctx.emit(TransportEvents.MESSAGE_INBOUND + "/accepted", result);
    }

    /** Session-level traffic: recorded, never offered to plugin chains. */
    @Override
    public void fromAdmin(Message message, SessionID sessionID) throws RejectLogon {
        ctx.emit(TransportEvents.WIRE, new TransportEvents.Wire(
                idOf(sessionID), TransportEvents.Direction.IN, message.toString()));

        // Only when answering. An initiator receiving a Logon is being told
        // its own was accepted; refusing that against our password policy
        // disconnects every venue we dial.
        if (acceptor && isLogon(message)) {
            checkLogon(message, sessionID);
        }
    }

    private static boolean isLogon(Message message) {
        try {
            return "A".equals(message.getHeader().getString(MsgType.FIELD));
        } catch (FieldNotFound withoutMsgType) {
            return false;
        }
    }

    /**
     * Refuse a logon the policy does not allow.
     *
     * <p>CompIDs are not secrets, so an acceptor reachable from a public
     * address and checking nothing accepts orders from anyone who has read the
     * counterparty's onboarding document.
     *
     * <p>The reason is logged here and never sent: telling a caller which of
     * its guesses was wrong is how it learns to guess.
     */
    private void checkLogon(Message message, SessionID sessionID) throws RejectLogon {
        LogonPolicy policy = ctx.<LogonPolicy>find("logon-policy")
                .orElseGet(LogonPolicy::open);

        String sessionId = idOf(sessionID);
        String password = null;
        try {
            if (message.isSetField(FixTags.PASSWORD)) {
                password = message.getString(FixTags.PASSWORD);
            }
        } catch (FieldNotFound absent) {
            // Treated as no password, which the policy decides about.
        }

        String remote = remoteAddressOf(sessionID);
        Optional<String> refusal = policy.refuse(sessionId, password, remote);

        if (refusal.isPresent()) {
            // The real reason goes to our own log, where an operator can see it.
            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    sessionId, TransportEvents.Kind.LOGON_REFUSED,
                    refusal.get() + (remote == null ? "" : " (from " + remote + ")")));

            // What the caller is told is deliberately uninformative: QuickFIX/J
            // puts this in Text(58) of the Logout it sends back, and telling a
            // caller which of its guesses was wrong is how it learns to guess.
            throw new RejectLogon("logon refused");
        }
    }

    /** Where the connection came from, or null when the engine does not say. */
    private static String remoteAddressOf(SessionID sessionID) {
        Session session = Session.lookupSession(sessionID);
        return session == null ? null : session.getRemoteAddress();
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        ctx.emit(TransportEvents.WIRE, new TransportEvents.Wire(
                idOf(sessionID), TransportEvents.Direction.OUT, message.toString()));
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        ctx.emit(TransportEvents.WIRE, new TransportEvents.Wire(
                idOf(sessionID), TransportEvents.Direction.OUT, message.toString()));
    }

    // ------------------------------------------------------------------
    // Application — lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate(SessionID sessionID) {
        knownSessions.add(sessionID);
        session(sessionID, TransportEvents.Kind.CREATED, "session created");
    }

    /**
     * Announce every session again, now that they can be asked about.
     *
     * <p>A session announces its own creation while the connector is starting,
     * which is before it is within reach of the hub — so a watcher was told a
     * session existed and could learn nothing else about it, and one that
     * refuses an incomplete row would have dropped it entirely. Called once the
     * hub has attached, this carries the state the first announcement could
     * not.
     */
    public void announceExisting() {
        for (SessionID sessionID : knownSessions) {
            session(sessionID, TransportEvents.Kind.CREATED, "session created");
        }
    }

    @Override
    public void onLogon(SessionID sessionID) {
        session(sessionID, TransportEvents.Kind.LOGON, "logged on");
    }

    @Override
    public void onLogout(SessionID sessionID) {
        // A logout arriving straight after a logon is almost always a sequence
        // disagreement, and the engine reports it only as a reconnect loop.
        // Naming the numbers here turns "it will not stay connected" into a
        // question with an answer.
        Session session = Session.lookupSession(sessionID);
        String detail = "logged out";
        if (session != null) {
            detail = "logged out (next sender " + session.getExpectedSenderNum()
                    + ", next target " + session.getExpectedTargetNum()
                    + "; a repeating logon/logout usually means these disagree "
                    + "with the counterparty — reset both sides or resend)";
        }
        session(sessionID, TransportEvents.Kind.LOGOUT, detail);
    }

    private void session(SessionID sessionID, TransportEvents.Kind kind, String detail) {
        ctx.emit(TransportEvents.SESSION,
                new TransportEvents.SessionEvent(idOf(sessionID), kind, detail));
    }

    // ------------------------------------------------------------------
    // Transport — outbound
    // ------------------------------------------------------------------

    /**
     * Put a message on the wire as it stands.
     *
     * <p>The layer chains have already run — see {@code OutboundPath}, which
     * walks them above the transport because that is the first place that knows
     * which destination or client a message is for. Running them here as well
     * would fire every session plugin twice.
     */
    @Override
    public boolean send(String sessionId, FixMessage message) {
        SessionID sessionID = lookup(sessionId);
        if (sessionID == null) {
            return false;
        }

        try {
            // Fields are set individually rather than parsed from a rendered
            // string: a string without a header is not a message QuickFIX/J will
            // read back, and round-tripping through one silently drops fields.
            //
            // Header and trailer stay the engine's — BeginString, BodyLength,
            // MsgSeqNum, SendingTime and CheckSum are filled in on the way out,
            // so nothing above ever computes a sequence number.
            Message outbound = new Message();
            outbound.getHeader().setString(FixTags.MSG_TYPE, message.msgType());
            message.storedFields().forEach((tag, value) -> {
                // Header tags must go in the header. Setting one in the body
                // leaves the message out of field order, and a counterparty
                // reading strictly will not find the body fields that follow.
                //
                // The classification is the same one the pipeline uses to decide
                // what belongs in an order's view: two hand-kept copies of it
                // disagreed, and a session field ended up journalled as an
                // order attribute.
                if (FixLayers.isHeader(tag)) {
                    outbound.getHeader().setString(tag, value);
                } else if (!FixLayers.isTrailer(tag)) {
                    outbound.setString(tag, value);
                }
            });
            copyGroups(outbound, message, sessionId);
            return Session.sendToTarget(outbound, sessionID);
        } catch (SessionNotFound failure) {
            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    sessionId, TransportEvents.Kind.CONNECTION_LOST, failure.getMessage()));
            return false;
        }
    }

    /**
     * The port this session is reached on, or 0 when the settings do not say.
     *
     * <p>An acceptor's port and an initiator's are different settings, and a
     * session inherits whichever its connector was started with. Zero means
     * unknown rather than a real port: reporting a guess would be worse than
     * reporting nothing, because a guess is acted on.
     */
    private int portOf(SessionID sessionID) {
        if (settings == null || sessionID == null) {
            return 0;
        }
        String key = acceptor ? "SocketAcceptPort" : "SocketConnectPort";
        try {
            return (int) settings.getLong(sessionID, key);
        } catch (Exception notConfigured) {
            return 0;
        }
    }

    /** Where an initiator dials; an acceptor does not dial, so it has none. */
    private String hostOf(SessionID sessionID) {
        if (settings == null || sessionID == null || acceptor) {
            return null;
        }
        try {
            return settings.getString(sessionID, "SocketConnectHost");
        } catch (Exception notConfigured) {
            return null;
        }
    }

    @Override
    public Set<String> sessions() {
        Set<String> ids = new LinkedHashSet<>();
        knownSessions.forEach(sessionID -> ids.add(idOf(sessionID)));
        return ids;
    }

    @Override
    public boolean isLoggedOn(String sessionId) {
        SessionID sessionID = lookup(sessionId);
        if (sessionID == null) {
            return false;
        }
        Session session = Session.lookupSession(sessionID);
        return session != null && session.isLoggedOn();
    }

    @Override
    public SessionStatus status(String sessionId) {
        SessionID sessionID = lookup(sessionId);
        Session session = sessionID == null ? null : Session.lookupSession(sessionID);
        String role = acceptor ? "acceptor" : "initiator";
        if (session == null) {
            return new SessionStatus(sessionId, false, 0, 0, "unknown",
                    role, portOf(sessionID), hostOf(sessionID));
        }
        return new SessionStatus(
                sessionId,
                session.isLoggedOn(),
                session.getExpectedSenderNum(),
                session.getExpectedTargetNum(),
                sessionID.getBeginString(),
                role,
                portOf(sessionID),
                hostOf(sessionID));
    }

    /**
     * Copy repeating groups onto the outbound message.
     *
     * <p>Counters are written from the entry count rather than carried as
     * fields, so a group a plugin rewrote cannot disagree with its own counter.
     */
    private void copyGroups(Message target, FixMessage source, String sessionId) {
        var templates = dialects.forSession(sessionId).groupsFor(source.msgType());

        source.allGroups().forEach((counterTag, entries) -> {
            target.setInt(counterTag, entries.size());
            // The delimiter comes from the dialect that declared the group, not
            // from whichever field happens to come first: a group entry is only
            // recognisable on the wire by leading with the declared tag, and
            // guessing it produces a message the counterparty reads apart at the
            // wrong boundaries.
            var template = templates.get(counterTag);
            for (FixMessage.Group entry : entries) {
                int delimiter = template != null
                        ? template.delimiterTag()
                        : entry.delimiterTag();
                quickfix.Group group = new quickfix.Group(counterTag, delimiter);
                entry.fields().forEach(group::setString);
                target.addGroup(group);
            }
        });
    }

    // ------------------------------------------------------------------

    /** Stable id for a session: {@code SENDER->TARGET}. */
    static String idOf(SessionID sessionID) {
        return sessionID.getSenderCompID() + "->" + sessionID.getTargetCompID();
    }

    // ------------------------------------------------------------------
    // Controlling a session
    // ------------------------------------------------------------------

    @Override
    public boolean logon(String sessionId) {
        return onSession(sessionId, session -> {
            session.logon();
            return true;
        });
    }

    @Override
    public boolean logout(String sessionId, String reason) {
        return onSession(sessionId, session -> {
            // The reason reaches the counterparty in Text(58), so their log
            // shows what happened rather than an unexplained disconnect.
            session.logout(reason == null ? "requested" : reason);
            return true;
        });
    }

    @Override
    public boolean disconnect(String sessionId, String reason) {
        return onSession(sessionId, session -> {
            try {
                // false: do not expect the counterparty to answer. This is for
                // a session that has already stopped responding.
                session.disconnect(reason == null ? "requested" : reason, false);
                return true;
            } catch (IOException failure) {
                ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                        sessionId, TransportEvents.Kind.CONNECTION_LOST,
                        "disconnect failed: " + failure.getMessage()));
                return false;
            }
        });
    }

    @Override
    public boolean reset(String sessionId) {
        return onSession(sessionId, session -> {
            session.reset();
            ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                    sessionId, TransportEvents.Kind.SEQUENCE_RESET,
                    "sequence numbers reset to 1"));
            return true;
        });
    }

    @Override
    public boolean resequence(String sessionId, Integer nextSender, Integer nextTarget) {
        return onSession(sessionId, session -> {
            try {
                if (nextSender != null) {
                    session.setNextSenderMsgSeqNum(nextSender);
                }
                if (nextTarget != null) {
                    session.setNextTargetMsgSeqNum(nextTarget);
                }
                ctx.emit(TransportEvents.SESSION, new TransportEvents.SessionEvent(
                        sessionId, TransportEvents.Kind.SEQUENCE_RESET,
                        "next sender " + session.getExpectedSenderNum()
                                + ", next target " + session.getExpectedTargetNum()));
                return true;
            } catch (IOException failure) {
                // The store refused the write. Reporting success here would
                // leave an operator believing a resequence took that did not.
                return false;
            }
        });
    }

    /** Run something against a live session, or report that there is none. */
    private boolean onSession(
            String sessionId, java.util.function.Function<Session, Boolean> action) {

        SessionID sessionID = lookup(sessionId);
        if (sessionID == null) {
            return false;
        }
        Session session = Session.lookupSession(sessionID);
        return session != null && action.apply(session);
    }

    private SessionID lookup(String sessionId) {
        for (SessionID sessionID : knownSessions) {
            if (idOf(sessionID).equals(sessionId)) {
                return sessionID;
            }
        }
        return null;
    }
}
