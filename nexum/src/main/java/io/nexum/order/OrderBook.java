package io.nexum.order;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live orders, each one a {@link ManagedOrder} that decides for itself.
 *
 * <p>Sits in front of the cache rather than replacing it. The cache answers
 * "which order does this identifier belong to"; this holds the objects that
 * know what to do when told something. Keeping the lookup and the behaviour
 * apart means a report resolves the same way whether the order is live here or
 * being rebuilt from a journal.
 */
public final class OrderBook {

    private final java.util.Map<String, ManagedOrder> live = new ConcurrentHashMap<>();

    /**
     * Open an order under the identity minted for it.
     *
     * <p>The identity comes from the message, not from a counter here: a counter
     * would have to survive restarts and stay unique across instances, and an
     * identity derived from the order says which day, which session and which
     * client request it belongs to without a lookup.
     */
    public ManagedOrder open(OrderId orderId, InboundEvent.ClientOrder request) {
        ManagedOrder order = ManagedOrder.accept(orderId.toString(), request);
        live.put(orderId.toString(), order);
        return order;
    }

    /** Put a rebuilt order back in the book. */
    public ManagedOrder restore(Order stored, long at) {
        ManagedOrder order = ManagedOrder.restore(stored, at);
        live.put(stored.orderId(), order);
        return order;
    }

    public Optional<ManagedOrder> byOrderId(OrderId orderId) {
        return byOrderId(orderId.toString());
    }

    public Optional<ManagedOrder> byOrderId(String orderId) {
        return Optional.ofNullable(live.get(orderId));
    }

    public Collection<ManagedOrder> all() {
        return live.values();
    }

    /** Orders the venue may still act on. */
    public List<ManagedOrder> working() {
        return live.values().stream()
                .filter(order -> !order.state().isTerminal())
                .toList();
    }

    /**
     * Drop an order that has finished.
     *
     * <p>Not automatic on reaching a terminal state: a late report often follows
     * the one that closed an order, and resolving it to nothing would raise a
     * disagreement where there is none.
     */
    public void evict(String orderId) {
        live.remove(orderId);
    }

    /**
     * Drop orders that finished longer ago than the retention window.
     *
     * <p>The window exists because the last report is rarely the last message:
     * a correction, a bust or a resend follows, and an order dropped too eagerly
     * makes those look like reports for something that never existed. Long
     * enough to absorb that, short enough that the book does not grow for the
     * life of the process.
     *
     * @return the identifiers dropped, so a caller can release what it holds
     *     against them
     */
    public List<String> evictSettled(long now, long retainMillis) {
        List<String> dropped = new ArrayList<>();
        live.forEach((orderId, order) -> {
            if (order.state().isTerminal()
                    && now - order.lastEventAt() > retainMillis) {
                dropped.add(orderId);
            }
        });
        dropped.forEach(live::remove);
        return dropped;
    }

    public int size() {
        return live.size();
    }

}
