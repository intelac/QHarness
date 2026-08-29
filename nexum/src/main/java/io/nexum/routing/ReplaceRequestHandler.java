package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.order.InboundEvent;
import io.nexum.order.ManagedOrder;
import io.nexum.order.OrderEvent;
import io.nexum.order.OrderEvents;
import io.nexum.order.OrderId;
import io.nexum.order.OutboundEvent;
import io.nexum.order.PendingRequest;
import io.nexum.transport.TransportEvents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A client's replace request.
 *
 * <p>A replace arrives as a whole order, most of which restates terms nobody
 * asked to change. Only {@link #AMENDABLE} is read out of it; treating the
 * restatement as an amendment would silently alter fields the client merely
 * echoed back.
 */
public final class ReplaceRequestHandler implements MessageHandler {

    /** Fields a replace may ask to change. */
    private static final Set<Integer> AMENDABLE =
            Set.of(FixTags.ORDER_QTY, FixTags.PRICE, FixTags.TIME_IN_FORCE);

    @Override
    public Set<String> handles() {
        return Set.of("G");
    }

    @Override
    public void handle(Context ctx, OrderServices services, TransportEvents.InFlight arrival) {
        FixMessage request = arrival.message();

        Optional<ManagedOrder> found = OrderLookup.byClientRequest(
                services.ids(), services.book(), request, arrival.sessionId());
        if (found.isEmpty()) {
            ctx.emit(OrderEvents.REQUEST_UNKNOWN, new OrderEvents.UnknownRequest(
                    arrival.sessionId(), "G",
                    request.get(FixTags.ORIG_CL_ORD_ID), arrival.at()));
            return;
        }

        ManagedOrder order = found.get();
        String ourClOrdId = services.wireIds().forReplace();
        String origOurClOrdId = OrderLookup.ourOutboundId(order, ourClOrdId);

        Map<Integer, String> requested = new LinkedHashMap<>();
        AMENDABLE.forEach(tag -> {
            String value = request.get(tag);
            if (value != null) {
                requested.put(tag, value);
            }
        });

        String clientClOrdId = request.get(FixTags.CL_ORD_ID);

        List<OutboundEvent> concluded = order.on(new InboundEvent.ReplaceRequested(
                arrival.at(), ourClOrdId, origOurClOrdId, clientClOrdId, requested));
        OrderEventPublisher.publish(ctx, concluded);

        if (!OrderEventPublisher.accepted(concluded)) {
            // The order refused it, and said why. Returning here left the
            // client holding a request that was neither answered nor
            // forwarded, unable to tell a refusal from one still being worked
            // on — FIX has a message for this, and the reason to put in it.
            RequestRefusal.send(ctx, services, order, request, true,
                    concluded, arrival.at());
            return;
        }

        // The client may amend again quoting either the identifier it used
        // originally or the one on this request; both name the same order.
        if (clientClOrdId != null) {
            services.ids().alsoKnownAs(
                    OrderId.parse(order.orderId()), arrival.sessionId(), clientClOrdId);
        }

        FixMessage outbound = request
                .set(FixTags.CL_ORD_ID, ourClOrdId)
                .set(FixTags.ORIG_CL_ORD_ID, origOurClOrdId);

        services.record(new OrderEvent.RequestSent(
                order.orderId(), arrival.at(), PendingRequest.Kind.REPLACE,
                ourClOrdId, origOurClOrdId, clientClOrdId, requested,
                OrderFields.asStrings(request.flatFields()),
                OrderFields.asStrings(outbound.flatFields())));

        services.cache().update(order.snapshot());
        services.cache().indexOutbound(ourClOrdId, order.orderId());
        OutboundPath.toDestination(
                ctx, services.transport(), order.destinationId(), order.destinationId(),
                outbound);
    }
}
