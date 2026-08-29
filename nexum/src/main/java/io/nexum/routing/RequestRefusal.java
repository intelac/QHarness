package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.order.ManagedOrder;
import io.nexum.order.OrderEvent;
import io.nexum.order.OutboundEvent;
import io.nexum.transport.Transport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telling a client its cancel or replace was refused.
 *
 * <p>A request this system will not carry used to end in a return statement.
 * The order knew why — it is not on the market yet, it already has a request
 * outstanding — and the reason went no further than the method that read it,
 * so the client had sent a request that was neither answered nor forwarded and
 * could not tell the difference between refused and still being worked on.
 *
 * <p>FIX has a message for exactly this. A cancel reject names the request in
 * ClOrdID(11), the order in OrigClOrdID(41), and says which of the two kinds it
 * answers in CxlRejResponseTo(434) — the only field that distinguishes a
 * refused cancel from a refused amendment, and the one a client matches on.
 */
final class RequestRefusal {

    /** CxlRejResponseTo(434): what the reject answers. */
    private static final String TO_CANCEL = "1";
    private static final String TO_REPLACE = "2";

    /**
     * CxlRejReason(102) = 0, "too late to cancel".
     *
     * <p>The nearest of the codes FIX defines. The refusals this sends are
     * about timing — the order has not reached the venue, or a request is
     * already outstanding — and none of the other codes says that. Text(58)
     * carries what actually happened, in the order's own words.
     */
    private static final String REASON_TOO_LATE = "0";

    private RequestRefusal() {
    }

    /**
     * Send the reject and record it.
     *
     * @param concluded what the order decided; the refusal's reason is read
     *     from it, so the client is told what the order said rather than a
     *     description composed here
     */
    static void send(
            Context ctx,
            OrderServices services,
            ManagedOrder order,
            FixMessage request,
            boolean isReplace,
            List<OutboundEvent> concluded,
            long at) {

        String clOrdId = request.get(FixTags.CL_ORD_ID);
        String origClOrdId = request.get(FixTags.ORIG_CL_ORD_ID);
        String why = reason(concluded);

        Map<Integer, String> fields = new LinkedHashMap<>();
        if (clOrdId != null) {
            fields.put(FixTags.CL_ORD_ID, clOrdId);
        }
        if (origClOrdId != null) {
            fields.put(FixTags.ORIG_CL_ORD_ID, origClOrdId);
        }
        fields.put(FixTags.ORD_STATUS, order.state().fixOrdStatus());
        fields.put(FixTags.CXL_REJ_RESPONSE_TO, isReplace ? TO_REPLACE : TO_CANCEL);
        fields.put(FixTags.CXL_REJ_REASON, REASON_TOO_LATE);
        if (why != null) {
            fields.put(FixTags.TEXT, why);
        }

        FixMessage reject = FixMessage.of("9", fields);
        OutboundPath.toClient(ctx, services.transport(), order.clientId(),
                order.sessionId(), reject);

        services.record(new OrderEvent.RequestRefused(
                order.orderId(), at, isReplace, clOrdId, origClOrdId, why,
                OrderFields.asStrings(OrderFields.business(request)),
                OrderFields.asStrings(reject.flatFields())));
    }

    /**
     * What the order gave as its reason.
     *
     * <p>Both refusals carry one — a state machine that declined without
     * saying why would leave this composing a description of a decision it did
     * not make.
     */
    private static String reason(List<OutboundEvent> concluded) {
        for (OutboundEvent event : concluded) {
            switch (event) {
                case OutboundEvent.Ignored ignored -> {
                    return ignored.why();
                }
                case OutboundEvent.Disagreement disagreement -> {
                    return disagreement.why();
                }
                default -> {
                    // not a refusal
                }
            }
        }
        return null;
    }
}
