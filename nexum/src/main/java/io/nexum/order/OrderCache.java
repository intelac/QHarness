package io.nexum.order;

import java.util.Collection;
import java.util.Optional;

/**
 * The record of every live order and the index that makes the return path work.
 *
 * <p>Downstream routing is decided from message content. The return path cannot
 * be — an execution report carries venue identifiers, not the client's. Every
 * report is therefore resolved here, and the client view it points at is what
 * gets sent back upstream.
 *
 * <p>Resolution order is <b>OrderID(37) first, ClOrdID(11) as fallback</b>. The
 * venue's OrderID is stable for the life of the order, while ClOrdID changes on
 * every replace; the first ack often carries only the ClOrdID, so both indexes
 * are needed and 37 is preferred the moment it exists.
 */
public interface OrderCache {

    /** Register a newly accepted client order. Indexed by the client's ClOrdID. */
    void put(Order order);

    /**
     * Index an order under the ClOrdID we minted for the venue. Called when the
     * order is sent downstream.
     */
    void indexOutbound(String ourClOrdId, String orderId);

    /**
     * Index an order under the venue's OrderID(37), the first time a report
     * carries one.
     */
    void indexVenueOrderId(String venueOrderId, String orderId);

    Optional<Order> byOrderId(String orderId);

    Optional<Order> byClientClOrdId(String clientClOrdId);

    Optional<Order> byOurClOrdId(String ourClOrdId);

    Optional<Order> byVenueOrderId(String venueOrderId);

    /**
     * Resolve an inbound execution report back to its order.
     *
     * @param venueOrderId value of OrderID(37), or null when absent
     * @param ourClOrdId value of ClOrdID(11) as the venue echoed it
     */
    default Optional<Order> resolve(String venueOrderId, String ourClOrdId) {
        // ClOrdID first, because it is ours: every value was minted here and
        // never reissued. OrderID belongs to the venue, and a venue that has
        // restarted hands out the same one again — preferring it sent the
        // report to whichever order claimed that id first, and left the order
        // it was actually about waiting for an acknowledgement that had
        // already arrived and gone elsewhere.
        if (ourClOrdId != null) {
            Optional<Order> found = byOurClOrdId(ourClOrdId);
            if (found.isPresent()) {
                return found;
            }
        }

        // Nothing matched on our own identifier: an unsolicited report, or one
        // for an order whose ClOrdID the venue did not echo. OrderID is then
        // the only thing left to go on.
        return venueOrderId == null ? Optional.empty() : byVenueOrderId(venueOrderId);
    }

    /** Replace a stored order after a state change. */
    void update(Order order);

    Collection<Order> active();

    int size();
}
