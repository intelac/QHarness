package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.core.Scope;
import io.nexum.message.FixMessage;
import io.nexum.transport.Transport;
import io.nexum.transport.TransportEvents;

/**
 * Sending a message out through the layers it belongs to.
 *
 * <p>Inbound crosses session, client, routing and destination. Outbound must
 * cross the same layers in reverse, and for a long time it did not: the walk
 * lived inside the transport, which is handed a session id and cannot know
 * which destination or client a message is for. A gate mounted on a destination
 * therefore never fired on the order path — the seam existed and nothing ever
 * crossed it.
 *
 * <p>It lives here instead, above the transport, because this is the first
 * place that knows both. That also means every transport gets the behaviour
 * rather than each having to remember it; the recording transport used in tests
 * did not, which is how the gap stayed invisible.
 *
 * <p>Order is counterparty-then-session, so the session layer sees whatever the
 * counterparty layer did and has the final say before the wire.
 */
public final class OutboundPath {

    private OutboundPath() {}

    /**
     * Send to a venue, crossing the destination layer and then the session's.
     *
     * @return false when a layer held the message back or the transport refused
     *     it. The caller cannot distinguish the two, and should not: in both
     *     cases nothing reached the venue.
     */
    public static boolean toDestination(
            Context ctx, Transport transport,
            String destinationId, String sessionId, FixMessage message) {

        return send(ctx, transport, sessionId, message, Scope.destination(destinationId));
    }

    /**
     * Send to a client, crossing that client's layer and then the session's.
     *
     * @param clientId may be null for an order whose client was never resolved,
     *     in which case only the session layer runs
     */
    public static boolean toClient(
            Context ctx, Transport transport,
            String clientId, String sessionId, FixMessage message) {

        return clientId == null
                ? send(ctx, transport, sessionId, message)
                : send(ctx, transport, sessionId, message, Scope.client(clientId));
    }

    private static boolean send(
            Context ctx, Transport transport,
            String sessionId, FixMessage message, Scope... layers) {

        TransportEvents.InFlight flight =
                TransportEvents.InFlight.inbound(message, sessionId);

        for (Scope layer : layers) {
            flight = ctx.waterfall(
                    TransportEvents.MESSAGE_OUTBOUND, layer, flight, unchanged -> unchanged);
            if (flight.rejected()) {
                return false;
            }
        }

        // Last before the wire, so a session plugin sees the finished message.
        flight = ctx.waterfall(
                TransportEvents.MESSAGE_OUTBOUND, Scope.session(sessionId),
                flight, unchanged -> unchanged);
        if (flight.rejected()) {
            return false;
        }

        return transport.send(sessionId, flight.message());
    }
}
