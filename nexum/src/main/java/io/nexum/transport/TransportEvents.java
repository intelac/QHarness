package io.nexum.transport;

import io.nexum.message.FixMessage;

/**
 * Event names the transport publishes, and the payloads that travel on them.
 *
 * <p>Application messages cross {@code MESSAGE_INBOUND} and
 * {@code MESSAGE_OUTBOUND} as waterfalls, so plugins mounted on any layer can
 * validate, enrich, rewrite or reject them. Session-level traffic — logon,
 * heartbeats, resend requests — is never offered to those chains: those messages
 * belong to the engine, and a plugin rewriting one would corrupt the session.
 */
public final class TransportEvents {

    private TransportEvents() {}

    /** Waterfall. An application message arriving from a counterparty. */
    public static final String MESSAGE_INBOUND = "message/inbound";

    /** Waterfall. An application message on its way out. */
    public static final String MESSAGE_OUTBOUND = "message/outbound";

    /** Emit. Every message crossing the wire, already framed, for audit. */
    public static final String WIRE = "transport/wire";

    /** Emit. Session lifecycle: logon, logout, connection loss. */
    public static final String SESSION = "transport/session";

    /**
     * A message in flight together with where it is in the layering.
     *
     * @param rejected set by a gate that refuses the message; downstream gates
     *     do not run and the transport does not send it
     */
    public record InFlight(
            FixMessage message,
            String sessionId,
            String clientId,
            String destinationId,
            boolean rejected,
            String rejectReason,
            int seqNum,
            long at) {

        /** @param seqNum MsgSeqNum(34) as it appeared on the wire, or 0 outbound */
        public static InFlight inbound(FixMessage message, String sessionId, int seqNum) {
            // Stamped once, on arrival. Reading the clock at each step instead
            // would let a journal show a state change predating the report that
            // caused it.
            return new InFlight(message, sessionId, null, null, false, null,
                    seqNum, System.currentTimeMillis());
        }

        public static InFlight inbound(FixMessage message, String sessionId) {
            return inbound(message, sessionId, 0);
        }

        public InFlight with(FixMessage rewritten) {
            return new InFlight(
                    rewritten, sessionId, clientId, destinationId, rejected, rejectReason, seqNum, at);
        }

        public InFlight toClient(String id) {
            return new InFlight(
                    message, sessionId, id, destinationId, rejected, rejectReason, seqNum, at);
        }

        public InFlight toDestination(String id) {
            return new InFlight(
                    message, sessionId, clientId, id, rejected, rejectReason, seqNum, at);
        }

        public InFlight reject(String reason) {
            return new InFlight(
                    message, sessionId, clientId, destinationId, true, reason, seqNum, at);
        }

        /** Where this message sits in its session's log. */
        public io.nexum.order.OrderEvent.WireRef wireRef() {
            return new io.nexum.order.OrderEvent.WireRef(sessionId, seqNum);
        }


    }

    /** One raw message as it crossed the wire. */
    public record Wire(String sessionId, Direction direction, String raw) {}

    public record SessionEvent(String sessionId, Kind kind, String detail) {}

    public enum Direction {
        IN,
        OUT
    }

    public enum Kind {
        CREATED,
        LOGON,
        /** A logon the policy would not allow. Worth alerting on. */
        LOGON_REFUSED,
        /**
         * Sequence numbers were changed by hand.
         *
         * <p>Recorded because it is the one session action with no counterparty
         * message behind it — the record here is the only evidence it happened.
         */
        SEQUENCE_RESET,
        LOGOUT,
        CONNECTION_LOST
    }
}
