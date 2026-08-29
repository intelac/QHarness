package io.nexum.order;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One party's picture of an order. Every order carries three of these — what the
 * client sent, what we hold internally, and what we sent downstream — because
 * the identifiers are <em>not</em> passed through.
 *
 * <p>The client has its own ClOrdID(11), ExecID(17) and ExecRefID(19); we mint
 * our own for the venue; the venue answers with its OrderID(37). Keeping the
 * three sets apart is what lets a report come back in venue terms and leave in
 * client terms.
 */
public record OrderView(
        String clOrdId,
        String origClOrdId,
        String orderId,
        Map<Integer, String> fields) {

    public OrderView {
        fields = Map.copyOf(fields);
    }

    public static OrderView of(String clOrdId, Map<Integer, String> fields) {
        return new OrderView(clOrdId, null, null, fields);
    }

    public String field(int tag) {
        return fields.get(tag);
    }

    public OrderView withOrderId(String newOrderId) {
        return new OrderView(clOrdId, origClOrdId, newOrderId, fields);
    }

    public OrderView withClOrdId(String newClOrdId) {
        return new OrderView(newClOrdId, clOrdId, orderId, fields);
    }

    public OrderView withField(int tag, String value) {
        Map<Integer, String> merged = new LinkedHashMap<>(fields);
        merged.put(tag, value);
        return new OrderView(clOrdId, origClOrdId, orderId, merged);
    }
}
