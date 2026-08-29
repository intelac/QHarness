package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.order.InboundEvent;
import io.nexum.order.ManagedOrder;
import io.nexum.order.Order;
import io.nexum.order.OrderEvent;
import io.nexum.order.OrderEventType;
import io.nexum.order.OrderEvents;
import io.nexum.order.OrderState;
import io.nexum.order.OutboundEvent;
import io.nexum.transport.TransportEvents;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a venue says about an order, on its way back to the client.
 *
 * <p>Handles both the Execution Report and the Cancel Reject, because the two
 * answer the same outstanding request and differ only in how the event is
 * recognised.
 */
public final class ExecutionReportHandler implements MessageHandler {

    @Override
    public Set<String> handles() {
        return Set.of("8", "9");
    }

    @Override
    public void handle(Context ctx, OrderServices services, TransportEvents.InFlight arrival) {
        FixMessage report = arrival.message();

        // OrderID(37) when the venue gave us one, ClOrdID(11) otherwise — the
        // first ack is commonly all we have to go on.
        Optional<Order> stored = services.cache().resolve(
                report.get(FixTags.ORDER_ID), report.get(FixTags.CL_ORD_ID));
        if (stored.isEmpty()) {
            // A restart, a stale venue message, or an order this instance never
            // sent. Surfaced rather than dropped in silence.
            ctx.emit(OrderEvents.REPORT_UNMATCHED, new OrderEvents.UnmatchedReport(
                    arrival.sessionId(),
                    report.get(FixTags.ORDER_ID),
                    report.get(FixTags.CL_ORD_ID),
                    arrival.at()));
            return;
        }

        ManagedOrder order = services.book().byOrderId(stored.get().orderId())
                .orElseGet(() -> services.book().restore(stored.get(), arrival.at()));

        // Recognise what the message is from ExecType rather than OrdStatus: the
        // venue's status is a summary it chose, and on a Cancel Reject it still
        // reads New.
        OrderEventType type = "9".equals(report.msgType())
                ? OrderEventType.fromCancelReject(report.get(FixTags.CXL_REJ_RESPONSE_TO))
                : OrderEventType.fromExecutionReport(
                        report.get(FixTags.EXEC_TYPE),
                        report.get(FixTags.ORD_STATUS),
                        OrderFields.optionalNumber(report.get(FixTags.LEAVES_QTY)));

        List<OutboundEvent> concluded = order.on(new InboundEvent.VenueReport(
                arrival.at(),
                type,
                OrderFields.number(report.get(FixTags.CUM_QTY)),
                OrderFields.optionalNumber(report.get(FixTags.LEAVES_QTY)),
                report.get(FixTags.ORDER_ID),
                report.get(FixTags.CL_ORD_ID),
                report.flatFields()));

        // The client sees its own identifiers, never the ones we chose. Which
        // ones depends on what the report answers: the order decided that, and
        // says so in ForwardToClient.
        //
        // Built before the entry is written so the entry can hold it: what the
        // venue said and what the client was told are two halves of the same
        // moment, and a record of only the first cannot answer "what did our
        // client actually see" — the question a dispute starts from.
        FixMessage toClient = translate(report, concluded, order);

        journal(services, order, concluded, report, toClient, arrival);
        OrderEventPublisher.publish(ctx, concluded);

        OutboundPath.toClient(
                ctx, services.transport(), order.clientId(), order.sessionId(), toClient);
    }

    /**
     * Put the client's own identifiers on a report.
     *
     * <p>For a report answering a cancel or replace, ClOrdID(11) names the
     * request and OrigClOrdID(41) names the order — a client matching a cancel
     * confirmation looks for the id it sent the cancel with, so sending the
     * order's id back leaves the request unanswered as far as it can tell. For
     * an ordinary report there is no request, and 11 is the order's own.
     */
    private static FixMessage translate(
            FixMessage report, List<OutboundEvent> concluded, ManagedOrder order) {

        OutboundEvent.ForwardToClient forward = concluded.stream()
                .filter(OutboundEvent.ForwardToClient.class::isInstance)
                .map(OutboundEvent.ForwardToClient.class::cast)
                .findFirst()
                .orElse(null);

        if (forward == null) {
            return report.set(FixTags.CL_ORD_ID, order.clientView().clOrdId());
        }

        FixMessage translated = report.set(FixTags.CL_ORD_ID, forward.clientClOrdId());
        return forward.origClOrdId() == null
                ? translated
                : translated.set(FixTags.ORIG_CL_ORD_ID, forward.origClOrdId());
    }

    private void journal(
            OrderServices services,
            ManagedOrder order,
            List<OutboundEvent> concluded,
            FixMessage report,
            FixMessage toClient,
            TransportEvents.InFlight arrival) {

        for (OutboundEvent event : concluded) {
            switch (event) {
                case OutboundEvent.VenueIdLearned learned -> {
                    services.cache().indexVenueOrderId(learned.venueOrderId(), order.orderId());
                    services.record(new OrderEvent.VenueIdAssigned(
                            order.orderId(), learned.at(), learned.venueOrderId()));
                }
                case OutboundEvent.StateChanged changed ->
                        record(services, order, changed.at(),
                                changed.from(), changed.to(), changed.cause(),
                                report, toClient, arrival);

                // A partial that arrived while a request was outstanding leaves
                // the state alone but moves the position — recording only state
                // changes would lose it. From and to are the same state here,
                // which is exactly what happened.
                case OutboundEvent.QuantityChanged changed ->
                        record(services, order, changed.at(),
                                changed.state(), changed.state(), OrderEventType.PARTIAL_FILL,
                                report, toClient, arrival);

                case OutboundEvent.RequestAnswered answered ->
                        services.record(new OrderEvent.RequestAnswered(
                                order.orderId(), answered.at(), answered.accepted()));

                default -> {
                    // nothing durable to record
                }
            }
        }
        services.cache().update(order.snapshot());
    }

    private static void record(
            OrderServices services,
            ManagedOrder order,
            long at,
            OrderState from,
            OrderState to,
            OrderEventType cause,
            FixMessage report,
            FixMessage toClient,
            TransportEvents.InFlight arrival) {

        services.record(new OrderEvent.StateChanged(
                order.orderId(), at, from, to, cause,
                report.get(FixTags.ORD_STATUS), report.get(FixTags.CUM_QTY),
                report.get(FixTags.LAST_QTY), report.get(FixTags.LAST_PX),
                OrderFields.asStrings(report.flatFields()),
                toClient == null ? null
                        : OrderFields.asStrings(OrderFields.business(toClient)),
                arrival.wireRef()));
    }
}
