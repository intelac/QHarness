package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.order.OrderEvents;
import io.nexum.order.OutboundEvent;

import java.util.List;

/**
 * Turns what an order concluded into events the rest of the system can see.
 *
 * <p>One place, because every handler produces the same outbound events and a
 * second copy of this mapping would drift. The records are published as they
 * are — flattening them to strings is how a state the machine derived carefully
 * ends up re-derived badly downstream.
 */
public final class OrderEventPublisher {

    private OrderEventPublisher() {}

    public static void publish(Context ctx, List<OutboundEvent> concluded) {
        for (OutboundEvent event : concluded) {
            switch (event) {
                case OutboundEvent.StateChanged changed ->
                        ctx.emit(OrderEvents.STATE_CHANGED, changed);

                case OutboundEvent.QuantityChanged changed ->
                        ctx.emit(OrderEvents.QUANTITY_CHANGED, changed);

                case OutboundEvent.RequestOutstanding outstanding ->
                        ctx.emit(OrderEvents.REQUEST_SENT, outstanding);

                case OutboundEvent.RequestAnswered answered ->
                        ctx.emit(OrderEvents.REQUEST_ANSWERED, answered);

                case OutboundEvent.TermsAmended amended ->
                        ctx.emit(OrderEvents.TERMS_AMENDED, amended);

                case OutboundEvent.Ignored ignored ->
                        ctx.emit(OrderEvents.REPORT_IGNORED, ignored);

                case OutboundEvent.Disagreement disagreement ->
                        ctx.emit(OrderEvents.DISAGREEMENT, disagreement);

                case OutboundEvent.VenueIdLearned learned ->
                        ctx.emit(OrderEvents.VENUE_ID_ASSIGNED, learned);

                case OutboundEvent.ForwardToClient forward -> {
                    // The handler holds the transport and does this itself.
                }
            }
        }
    }

    /** True when the order took a request rather than refusing it. */
    public static boolean accepted(List<OutboundEvent> concluded) {
        return concluded.stream().anyMatch(OutboundEvent.RequestOutstanding.class::isInstance);
    }
}
