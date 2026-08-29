package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.core.Scope;
import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.order.InboundEvent;
import io.nexum.order.ManagedOrder;
import io.nexum.order.OrderEvent;
import io.nexum.order.OrderEvents;
import io.nexum.order.OrderId;
import io.nexum.order.OutboundEvent;
import io.nexum.transport.TransportEvents;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A client's new order, on its way to a venue.
 *
 * <p>This is where the four layers are crossed. The message arrives having
 * already been through the session chain; it is recognised as some client's,
 * crosses that client's chain and the routing chain, is assigned a destination,
 * and crosses that destination's chain before anything is minted. Each hop is a
 * separate waterfall because a routing decision sits between them — the
 * destination is not known until routing has run, so the destination's plugins
 * cannot be part of the same pass.
 *
 * <p>A rejection at any layer ends the walk. Nothing has been minted or
 * journalled at that point, so there is nothing to undo.
 */
public final class NewOrderHandler implements MessageHandler {

    @Override
    public Set<String> handles() {
        return Set.of("D");
    }

    @Override
    public void handle(Context ctx, OrderServices services, TransportEvents.InFlight arrival) {
        FixMessage fromClient = arrival.message();
        String sessionId = arrival.sessionId();
        Router router = services.router();

        Optional<String> client = router.toClient(fromClient);
        if (client.isEmpty()) {
            ctx.emit(RoutingEvents.RULE_UNMATCHED, new RoutingEvents.Unmatched(
                    RoutingEvents.Unmatched.Stage.CLIENT, sessionId, null,
                    router.explainNoClient(fromClient), arrival.at()));
            return;
        }
        String clientId = client.get();

        TransportEvents.InFlight afterClient = ctx.waterfall(
                TransportEvents.MESSAGE_INBOUND, Scope.client(clientId),
                arrival.toClient(clientId), flight -> flight);
        if (afterClient.rejected()) {
            return;
        }

        TransportEvents.InFlight afterRouting = ctx.waterfall(
                TransportEvents.MESSAGE_INBOUND, Scope.routing(), afterClient, flight -> flight);
        if (afterRouting.rejected()) {
            return;
        }

        Optional<String> destination = router.toDestination(afterRouting.message());
        if (destination.isEmpty()) {
            ctx.emit(RoutingEvents.RULE_UNMATCHED, new RoutingEvents.Unmatched(
                    RoutingEvents.Unmatched.Stage.DESTINATION, sessionId, clientId,
                    router.explainNoDestination(afterRouting.message()), arrival.at()));
            return;
        }
        String destinationId = destination.get();

        TransportEvents.InFlight afterDestination = ctx.waterfall(
                TransportEvents.MESSAGE_INBOUND, Scope.destination(destinationId),
                afterRouting.toDestination(destinationId), flight -> flight);
        if (afterDestination.rejected()) {
            return;
        }

        // The identity is minted here, from the order itself: the trading day,
        // the session it arrived on, and the ClOrdID the client chose.
        OrderId orderId;
        try {
            orderId = services.ids().forNewOrder(fromClient, sessionId);
        } catch (IllegalArgumentException malformed) {
            ctx.emit(OrderEvents.UNIDENTIFIABLE, new OrderEvents.Unidentifiable(
                    sessionId, malformed.getMessage(), arrival.at()));
            return;
        }

        ManagedOrder order = services.book().open(orderId, new InboundEvent.ClientOrder(
                arrival.at(),
                sessionId,
                clientId,
                fromClient.get(FixTags.CL_ORD_ID),
                OrderFields.business(afterDestination.message())));

        // Ours on the wire, not the client's: a report can then be matched
        // without depending on the venue echoing anything the client chose.
        String ourClOrdId = services.wireIds().forOrder();
        FixMessage outbound = afterDestination.message().set(FixTags.CL_ORD_ID, ourClOrdId);

        List<OutboundEvent> concluded = order.on(
                new InboundEvent.SentToVenue(arrival.at(), destinationId, ourClOrdId));

        // Journalled before the send: an order the venue knows about and this
        // system has forgotten cannot be recovered.
        services.record(new OrderEvent.Created(
                order.orderId(),
                arrival.at(),
                sessionId,
                clientId,
                destinationId,
                fromClient.get(FixTags.CL_ORD_ID),
                ourClOrdId,
                OrderFields.asStrings(OrderFields.business(fromClient)),
                OrderFields.asStrings(OrderFields.business(outbound)),
                arrival.wireRef(),
                null));

        services.cache().put(order.snapshot());
        services.cache().indexOutbound(ourClOrdId, order.orderId());
        OrderEventPublisher.publish(ctx, concluded);
        ctx.emit(OrderEvents.CREATED, order.snapshot());

        if (!OutboundPath.toDestination(
                ctx, services.transport(), destinationId, destinationId, outbound)) {
            // The order exists and the venue never saw it. That is ours to
            // resolve, and saying so is the difference between chasing our own
            // send path and chasing a broker.
            //
            // Whether the link is up decides what a reader should do: a session
            // that is not connected will carry the order when it comes back,
            // while one that refused the message will not.
            boolean connected = services.transport().isLoggedOn(destinationId);
            String why = connected
                    ? "the transport refused the message"
                    : "not connected to " + destinationId + "; the order never left";

            List<OutboundEvent> failed =
                    order.on(new InboundEvent.SendFailed(arrival.at(), why));
            OrderEventPublisher.publish(ctx, failed);
            services.cache().update(order.snapshot());

            // Recorded, because the journal is what an order's state is meant
            // to be recoverable from. Only reports were being journalled, so an
            // order that never left read as refused with nothing on disk saying
            // when, or by whom, or why — and "why was this refused" had no
            // answer at all.
            for (OutboundEvent event : failed) {
                if (event instanceof OutboundEvent.StateChanged changed) {
                    services.record(new OrderEvent.StateChanged(
                            order.orderId(), changed.at(), changed.from(), changed.to(),
                            changed.cause(), null, null, null, null,
                            java.util.Map.of("because", changed.because()), null));
                }
            }
        }
    }
}
