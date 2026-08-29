package io.nexum.transport;

import io.nexum.message.FixMessage;

import java.util.Set;

/**
 * Sending and receiving, with the engine behind it hidden.
 *
 * <p>Consumers depend on this and never on QuickFIX/J, so the engine is a
 * configuration choice rather than a commitment: a different provider can be
 * mounted under the same {@code transport} name without any consumer changing.
 *
 * <p>The session layer — logon, heartbeats, sequence numbers, resend, message
 * store — belongs entirely to the provider. Nothing above this interface should
 * reason about sequence numbers or reconnects.
 */
public interface Transport {

    /**
     * Send on a session.
     *
     * @return true when the engine accepted the message for delivery; false when
     *     the session is not logged on
     */
    boolean send(String sessionId, FixMessage message);

    /** Session ids currently configured, logged on or not. */
    Set<String> sessions();

    boolean isLoggedOn(String sessionId);

    /** What the engine reports about a session, for status tools. */
    SessionStatus status(String sessionId);

    // ------------------------------------------------------------------
    // Controlling a session
    //
    // The session layer belongs to the provider, but starting, stopping and
    // resequencing a session is what an operator does on an ordinary day —
    // a counterparty asks for a reset, a session hangs after a network
    // interruption, the day ends. Leaving these out means reaching past this
    // interface to the engine, which is the thing it exists to prevent.
    // ------------------------------------------------------------------

    /**
     * Ask the engine to log this session on.
     *
     * <p>An initiator dials out; an acceptor becomes willing to answer. Either
     * way the logon completes later, so a caller that needs to know it
     * succeeded watches {@link #isLoggedOn}.
     *
     * @return false when there is no such session
     */
    boolean logon(String sessionId);

    /**
     * Log this session out, telling the counterparty why.
     *
     * <p>A clean logout leaves both sides agreeing about sequence numbers. A
     * session that simply disappears leaves the counterparty holding one it
     * believes is live, and the next logon argues about what was missed.
     *
     * @param reason sent in Text(58), so the counterparty's log says what
     *     happened rather than showing an unexplained disconnect
     */
    boolean logout(String sessionId, String reason);

    /**
     * Drop the connection without logging out.
     *
     * <p>For a session that has stopped responding, where a logout would wait
     * for an answer that is not coming. Rarely the right first move.
     */
    boolean disconnect(String sessionId, String reason);

    /**
     * Reset both sequence numbers to 1 and clear the message store.
     *
     * <p>What "end of day" means to a FIX session, and what a counterparty
     * asks for when the two sides have lost track of each other. Both sides
     * must do it: resetting one alone produces a session that disagrees about
     * every message from then on.
     */
    boolean reset(String sessionId);

    /**
     * Set the next number this side will send, or expect to receive.
     *
     * <p>The targeted alternative to a full reset: a counterparty that has
     * skipped a message asks for a specific number rather than for everything
     * to start again.
     *
     * @param nextSender the next MsgSeqNum this side will put on the wire, or
     *     null to leave it alone
     * @param nextTarget the next MsgSeqNum this side expects, or null to leave
     *     it alone
     */
    boolean resequence(String sessionId, Integer nextSender, Integer nextTarget);

    /**
     * @param role {@code acceptor} when this side waits to be dialled,
     *     {@code initiator} when it dials out, or {@code unknown} where the
     *     transport does not say
     * @param port the port this session listens on or dials out to, or 0 when
     *     it is not known. Together with the role and the session's own id,
     *     this is what a counterparty needs to reach it — and it is otherwise
     *     recorded only in a configuration file, which is exactly what a
     *     reader should not have to open to answer "how do I connect".
     * @param host where an initiator dials, null for an acceptor
     */
    record SessionStatus(
            String sessionId,
            boolean loggedOn,
            int nextSenderSeqNum,
            int nextTargetSeqNum,
            String beginString,
            String role,
            int port,
            String host) {

        /** For a transport that knows nothing about how the session is wired. */
        public SessionStatus(
                String sessionId, boolean loggedOn,
                int nextSenderSeqNum, int nextTargetSeqNum, String beginString) {
            this(sessionId, loggedOn, nextSenderSeqNum, nextTargetSeqNum,
                    beginString, "unknown", 0, null);
        }
    }
}
