package io.nexum.order;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A durable fact about an order. The order state is not stored — it is folded
 * from these.
 *
 * <p>An order's life is already a sequence of events on the wire; recording that
 * sequence rather than the latest snapshot means a restart can rebuild exactly
 * what was true, and an auditor can be shown why a state was reached rather than
 * only what it became.
 *
 * <p>Encoded as one line per event so the log can be tailed, grepped and
 * truncated with ordinary tools during an incident.
 */
public sealed interface OrderEvent {

    /**
     * Where a message sits in a session's log: the session and its sequence
     * number.
     *
     * <p>The journal says what happened to an order; the FIX message log holds
     * the bytes that were actually exchanged. Without a link between them,
     * "why did this report leave the order in that state" is answered by
     * matching timestamps by hand. MsgSeqNum is the natural key — it is FIX's
     * own way of naming one message on one session.
     */
    record WireRef(String sessionId, int seqNum) {

        static void put(Map<String, String> target, String key, WireRef ref) {
            if (ref != null && ref.sessionId() != null) {
                target.put(key, ref.sessionId() + ":" + ref.seqNum());
            }
        }

        public static WireRef parse(String text) {
            if (text == null) {
                return null;
            }
            int colon = text.lastIndexOf(':');
            if (colon < 0) {
                return null;
            }
            try {
                return new WireRef(
                        text.substring(0, colon),
                        Integer.parseInt(text.substring(colon + 1)));
            } catch (NumberFormatException notASeqNum) {
                return null;
            }
        }

        @Override
        public String toString() {
            return sessionId + ":" + seqNum;
        }
    }

    String orderId();

    long timestamp();

    String type();

    Map<String, String> fields();

    /** An order accepted from a client and assigned our identifiers. */
    record Created(
            String orderId,
            long timestamp,
            String sessionId,
            String clientId,
            String destinationId,
            String clientClOrdId,
            String ourClOrdId,
            Map<String, String> clientFields,
            Map<String, String> destinationFields,
            WireRef inbound,
            WireRef outbound) implements OrderEvent {

        public String type() {
            return "created";
        }

        public Map<String, String> fields() {
            Map<String, String> all = new LinkedHashMap<>();
            all.put("session", sessionId);
            all.put("client", clientId);
            all.put("destination", destinationId);
            all.put("clientClOrdId", clientClOrdId);
            all.put("ourClOrdId", ourClOrdId);
            clientFields.forEach((tag, value) -> all.put("c." + tag, value));
            destinationFields.forEach((tag, value) -> all.put("d." + tag, value));
            WireRef.put(all, "wireIn", inbound);
            WireRef.put(all, "wireOut", outbound);
            return all;
        }
    }

    /** The venue's own OrderID(37), learned once and stable thereafter. */
    record VenueIdAssigned(String orderId, long timestamp, String venueOrderId)
            implements OrderEvent {

        public String type() {
            return "venue-id";
        }

        public Map<String, String> fields() {
            return Map.of("venueOrderId", venueOrderId);
        }
    }

    /**
     * A cancel or replace went out and has not been answered.
     *
     * <p>Recorded because it must survive a restart: the venue will answer it,
     * and an order that has forgotten the request cannot make sense of the
     * answer when it arrives.
     */
    record RequestSent(
            String orderId,
            long timestamp,
            PendingRequest.Kind kind,
            String clOrdId,
            String origClOrdId,
            String clientClOrdId,
            Map<Integer, String> requestedTerms,
            Map<String, String> fromClient,
            Map<String, String> toVenue) implements OrderEvent {

        /** Without the messages, for a caller that has none to hand. */
        public RequestSent(
                String orderId, long timestamp, PendingRequest.Kind kind,
                String clOrdId, String origClOrdId, String clientClOrdId,
                Map<Integer, String> requestedTerms) {

            this(orderId, timestamp, kind, clOrdId, origClOrdId, clientClOrdId,
                    requestedTerms, Map.of(), Map.of());
        }

        public RequestSent {
            fromClient = fromClient == null ? Map.of() : Map.copyOf(fromClient);
            toVenue = toVenue == null ? Map.of() : Map.copyOf(toVenue);
        }

        public String type() {
            return "request";
        }

        public Map<String, String> fields() {
            Map<String, String> all = new LinkedHashMap<>();
            all.put("kind", kind.name());
            all.put("clOrdId", clOrdId);
            all.put("origClOrdId", String.valueOf(origClOrdId));
            // Without this a restart forgets what the client called the
            // request, and the confirmation goes back under the wrong id.
            all.put("clientClOrdId", String.valueOf(clientClOrdId));
            requestedTerms.forEach((tag, value) -> all.put("r." + tag, value));
            // Both sides of the request, tag by tag. What went to the venue is
            // the message a dispute is argued over, and it was the one thing
            // the journal did not keep.
            fromClient.forEach((tag, value) -> all.put("c." + tag, value));
            toVenue.forEach((tag, value) -> all.put("d." + tag, value));
            return all;
        }
    }

    /** The venue answered an outstanding request, so nothing is pending now. */
    /**
     * A cancel or replace this system would not carry, and why.
     *
     * <p>Distinct from {@link RequestAnswered}, which records what a venue
     * decided: this one never reached a venue. The order refused it — it is not
     * on the market yet, or already has a request outstanding — and without an
     * entry the journal shows a client's request arriving and nothing
     * happening, which is the shape of a message that was dropped.
     */
    record RequestRefused(
            String orderId, long timestamp, boolean replace,
            String clOrdId, String origClOrdId, String why,
            Map<String, String> fromClient,
            Map<String, String> toClient) implements OrderEvent {

        /** Without the messages, for a caller that does not hold them. */
        public RequestRefused(
                String orderId, long timestamp, boolean replace,
                String clOrdId, String origClOrdId, String why) {
            this(orderId, timestamp, replace, clOrdId, origClOrdId, why,
                    Map.of(), Map.of());
        }

        public RequestRefused {
            fromClient = fromClient == null ? Map.of() : Map.copyOf(fromClient);
            toClient = toClient == null ? Map.of() : Map.copyOf(toClient);
        }

        public String type() {
            return "request-refused";
        }

        public Map<String, String> fields() {
            Map<String, String> all = new LinkedHashMap<>();
            all.put("kind", replace ? "replace" : "cancel");
            if (clOrdId != null) all.put("clOrdId", clOrdId);
            if (origClOrdId != null) all.put("origClOrdId", origClOrdId);
            if (why != null) all.put("why", why);
            // Both messages, the way an accepted request records them: what the
            // client asked for, and what it was told back. A refusal with the
            // reason but not the messages answers why while leaving what was
            // actually exchanged to be found in a session log.
            //
            // `r.` and not `d.`: this reply went back to the client, and `d.`
            // means it went out to the venue. A reader — or a screen — that
            // takes the prefix at its word would have this system forwarding a
            // rejection to a venue that never saw the request.
            fromClient.forEach((tag, value) -> all.put("c." + tag, value));
            toClient.forEach((tag, value) -> all.put("r." + tag, value));
            return all;
        }
    }

    record RequestAnswered(String orderId, long timestamp, boolean accepted)
            implements OrderEvent {

        public String type() {
            return "request-answered";
        }

        public Map<String, String> fields() {
            return Map.of("accepted", String.valueOf(accepted));
        }
    }

    /**
     * A state change reported by the venue.
     *
     * @param from the state left behind. Recorded because a history that shows
     *     only where an order arrived cannot show how it got there — reading a
     *     bare "CANCELED" leaves open whether it was working, held, or already
     *     part filled a moment earlier.
     * @param cause what the message was recognised as, which is the other half:
     *     the same destination state is reached by different events, and which
     *     one it was is the thing being reconstructed.
     */
    record StateChanged(
            String orderId,
            long timestamp,
            OrderState from,
            OrderState state,
            OrderEventType cause,
            String ordStatus,
            String cumQty,
            String lastQty,
            String lastPx,
            Map<String, String> message,
            Map<String, String> toClient,
            WireRef wire) implements OrderEvent {

        /** Without the message, keeping only the fields the state machine read. */
        public StateChanged(
                String orderId, long timestamp, OrderState from, OrderState state,
                OrderEventType cause, String ordStatus, String cumQty,
                String lastQty, String lastPx, WireRef wire) {

            this(orderId, timestamp, from, state, cause, ordStatus, cumQty,
                    lastQty, lastPx, Map.of(), Map.of(), wire);
        }

        /** With only the venue's message, for a change nothing was forwarded for. */
        public StateChanged(
                String orderId, long timestamp, OrderState from, OrderState state,
                OrderEventType cause, String ordStatus, String cumQty,
                String lastQty, String lastPx, Map<String, String> message,
                WireRef wire) {

            this(orderId, timestamp, from, state, cause, ordStatus, cumQty,
                    lastQty, lastPx, message, Map.of(), wire);
        }

        public StateChanged {
            message = message == null ? Map.of() : Map.copyOf(message);
            toClient = toClient == null ? Map.of() : Map.copyOf(toClient);
        }

        public String type() {
            return "state";
        }

        public Map<String, String> fields() {
            Map<String, String> all = new LinkedHashMap<>();
            put(all, "from", from == null ? null : from.name());
            put(all, "cause", cause == null ? null : cause.name());
            all.put("state", state.name());
            put(all, "ordStatus", ordStatus);
            put(all, "cumQty", cumQty);
            put(all, "lastQty", lastQty);
            put(all, "lastPx", lastPx);
            // The report itself, tag by tag. The four fields above are what the
            // state machine read; this is everything the venue actually sent,
            // which is what someone reconstructing a dispute needs to see.
            message.forEach((tag, value) -> all.put("m." + tag, value));
            // And what the client was told, which is not the same message: the
            // identifiers are translated back to the ones it sent. Without it
            // the record answers what the venue said and not what our client
            // saw, and only the second is what a client disputes.
            toClient.forEach((tag, value) -> all.put("c." + tag, value));
            WireRef.put(all, "wire", wire);
            return all;
        }

        private static void put(Map<String, String> target, String key, String value) {
            if (value != null) {
                target.put(key, value);
            }
        }
    }
}
