package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.order.InboundEvent;
import io.nexum.order.ManagedOrder;
import io.nexum.order.OrderEvent;
import io.nexum.order.OrderEvents;
import io.nexum.order.OutboundEvent;
import io.nexum.order.PendingRequest;
import io.nexum.transport.TransportEvents;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A client's cancel request.
 *
 * <p>Whether the cancel is allowed is the order's decision, not this handler's.
 * The order is told what was asked and answers with what it concluded; if it
 * refused — already terminal, nothing working to cancel — nothing goes on the
 * wire. Restating that rule here is how one call site comes to disagree with
 * another about when a cancel is legal.
 */
public final class CancelRequestHandler implements MessageHandler {

    @Override
    public Set<String> handles() {
        return Set.of("F");
    }

    @Override
    public void handle(Context ctx, OrderServices services, TransportEvents.InFlight arrival) {
        FixMessage request = arrival.message();

        Optional<ManagedOrder> found = OrderLookup.byClientRequest(
                services.ids(), services.book(), request, arrival.sessionId());
        if (found.isEmpty()) {
            ctx.emit(OrderEvents.REQUEST_UNKNOWN, new OrderEvents.UnknownRequest(
                    arrival.sessionId(), "F",
                    request.get(FixTags.ORIG_CL_ORD_ID), arrival.at()));
            return;
        }

        ManagedOrder order = found.get();
        String ourClOrdId = services.wireIds().forCancel();
        String origOurClOrdId = OrderLookup.ourOutboundId(order, ourClOrdId);

        // What the client called this request, kept so the confirmation can be
        // echoed back under it rather than under the order's own identifier.
        String clientClOrdId = request.get(FixTags.CL_ORD_ID);

        List<OutboundEvent> concluded = order.on(new InboundEvent.CancelRequested(
                arrival.at(), ourClOrdId, origOurClOrdId, clientClOrdId));
        OrderEventPublisher.publish(ctx, concluded);

        if (!OrderEventPublisher.accepted(concluded)) {
            // The order refused it, and said why. Returning here left the
            // client holding a request that was neither answered nor
            // forwarded, unable to tell a refusal from one still being worked
            // on — FIX has a message for this, and the reason to put in it.
            RequestRefusal.send(ctx, services, order, request, false,
                    concluded, arrival.at());
            return;
        }

        // Built before it is recorded so the journal holds the message that
        // actually goes out, not a reconstruction of it.
        FixMessage outbound = request
                .set(FixTags.CL_ORD_ID, ourClOrdId)
                .set(FixTags.ORIG_CL_ORD_ID, origOurClOrdId);

        // Recorded before it goes out: the venue will answer this, and an order
        // that has forgotten the request cannot make sense of the answer.
        services.record(new OrderEvent.RequestSent(
                order.orderId(), arrival.at(), PendingRequest.Kind.CANCEL,
                ourClOrdId, origOurClOrdId, clientClOrdId, Map.of(),
                OrderFields.asStrings(request.flatFields()),
                OrderFields.asStrings(outbound.flatFields())));

        services.cache().update(order.snapshot());
        services.cache().indexOutbound(ourClOrdId, order.orderId());
        OutboundPath.toDestination(
                ctx, services.transport(), order.destinationId(), order.destinationId(),
                outbound);
    }
}
