package io.nexum.order;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link OrderCache}: everything in heap, three indexes.
 *
 * <p>Chosen as the default because it is the one implementation that needs no
 * infrastructure. Swap it for a Redis-backed or off-heap provider by loading a
 * different plugin under the same {@code orders} service name — consumers depend
 * on the interface and never see the difference.
 *
 * <p>Not durable: a restart loses everything. Deployments that must recover
 * in-flight orders pair this with an event-log plugin or replace it outright.
 */
public final class InMemoryOrderCache implements OrderCache {

    private final Map<String, Order> byOrderId = new ConcurrentHashMap<>();
    private final Map<String, String> byClientClOrdId = new ConcurrentHashMap<>();
    private final Map<String, String> byOurClOrdId = new ConcurrentHashMap<>();
    private final Map<String, String> byVenueOrderId = new ConcurrentHashMap<>();

    @Override
    public void put(Order order) {
        byOrderId.put(order.orderId(), order);
        byClientClOrdId.put(order.client().clOrdId(), order.orderId());
    }

    @Override
    public void indexOutbound(String ourClOrdId, String orderId) {
        byOurClOrdId.put(ourClOrdId, orderId);
    }

    @Override
    public void indexVenueOrderId(String venueOrderId, String orderId) {
        byVenueOrderId.put(venueOrderId, orderId);
    }

    @Override
    public Optional<Order> byOrderId(String orderId) {
        return Optional.ofNullable(byOrderId.get(orderId));
    }

    @Override
    public Optional<Order> byClientClOrdId(String clientClOrdId) {
        return follow(byClientClOrdId.get(clientClOrdId));
    }

    @Override
    public Optional<Order> byOurClOrdId(String ourClOrdId) {
        return follow(byOurClOrdId.get(ourClOrdId));
    }

    @Override
    public Optional<Order> byVenueOrderId(String venueOrderId) {
        return follow(byVenueOrderId.get(venueOrderId));
    }

    private Optional<Order> follow(String orderId) {
        return orderId == null ? Optional.empty() : byOrderId(orderId);
    }

    @Override
    public void update(Order order) {
        byOrderId.put(order.orderId(), order);
    }

    @Override
    public Collection<Order> active() {
        return byOrderId.values().stream().filter(o -> !o.state().isTerminal()).toList();
    }

    @Override
    public int size() {
        return byOrderId.size();
    }
}
