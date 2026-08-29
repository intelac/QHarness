package io.nexum.monitor;

import io.nexum.order.OrderState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live picture of the order book: every order monitoring has heard about,
 * plus whatever projections are registered over them.
 *
 * <p>Built from the event stream, not from the cache. The order path must not
 * wait on monitoring, and monitoring must not be able to lose an order because
 * the cache was busy — folding a stream keeps both true.
 *
 * <p>Terminal orders are kept for a while rather than dropped at once. A report
 * often arrives just after the fill that closed an order, and an operator asking
 * "what happened to that order" is usually asking about one that just finished.
 */
public final class OrderMonitor {

    private final Map<String, OrderSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Projection<?>> projections = new LinkedHashMap<>();
    private final Map<String, Object> projectionStates = new ConcurrentHashMap<>();
    private final long retainTerminalMillis;

    public OrderMonitor(long retainTerminalMillis) {
        this.retainTerminalMillis = retainTerminalMillis;
    }

    // ------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------

    public void onCreated(OrderSnapshot snapshot) {
        snapshots.put(snapshot.orderId(), snapshot);
        fold(snapshot, Projection.Change.CREATED);
    }

    public void onVenueId(String orderId, String venueOrderId) {
        update(orderId, snapshot -> snapshot.withVenueOrderId(venueOrderId),
                Projection.Change.VENUE_ID_ASSIGNED);
    }

    public void onReport(String orderId, OrderState state, double cumQty, long at) {
        onReport(orderId, state, cumQty, 0, at);
    }

    /**
     * @param orderQty what the order is for now, or 0 when the caller does not
     *     know — an amendment changes it, and a projection that keeps the
     *     original shows an order filled beyond its own size.
     */
    public void onReport(
            String orderId, OrderState state, double cumQty, double orderQty, long at) {
        update(orderId, snapshot -> snapshot.withReport(state, cumQty, orderQty, at),
                Projection.Change.STATE_CHANGED);
    }

    /** A report that resolved to no order — surfaced, never dropped in silence. */
    public void onUnmatched(String venueOrderId, String clOrdId, String sessionId) {
        unmatched.add(new Unmatched(System.currentTimeMillis(), venueOrderId, clOrdId, sessionId));
        if (unmatched.size() > 200) {
            unmatched.remove(0);
        }
    }

    public record Unmatched(long at, String venueOrderId, String clOrdId, String sessionId) {}

    private final List<Unmatched> unmatched = java.util.Collections.synchronizedList(
            new ArrayList<>());

    public List<Unmatched> unmatchedReports() {
        synchronized (unmatched) {
            return List.copyOf(unmatched);
        }
    }

    private void update(
            String orderId,
            java.util.function.UnaryOperator<OrderSnapshot> change,
            Projection.Change kind) {

        OrderSnapshot existing = snapshots.get(orderId);
        if (existing == null) {
            return;
        }
        OrderSnapshot updated = change.apply(existing);
        snapshots.put(orderId, updated);
        fold(updated, kind);
    }

    // ------------------------------------------------------------------
    // Projections
    // ------------------------------------------------------------------

    public void register(Projection<?> projection) {
        projections.put(projection.name(), projection);
        projectionStates.put(projection.name(), projection.initial());
    }

    public void unregister(String name) {
        projections.remove(name);
        projectionStates.remove(name);
    }

    @SuppressWarnings("unchecked")
    private <S> void fold(OrderSnapshot snapshot, Projection.Change change) {
        projections.forEach((name, projection) -> {
            Projection<S> typed = (Projection<S>) projection;
            S before = (S) projectionStates.get(name);
            S after = typed.apply(before, snapshot, change);
            if (after != before) {
                projectionStates.put(name, after);
            }
        });
    }

    @SuppressWarnings("unchecked")
    public <S> Optional<S> projection(String name) {
        return Optional.ofNullable((S) projectionStates.get(name));
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public Collection<OrderSnapshot> all() {
        return snapshots.values();
    }

    public Optional<OrderSnapshot> byOrderId(String orderId) {
        return Optional.ofNullable(snapshots.get(orderId));
    }

    public List<OrderSnapshot> active() {
        return snapshots.values().stream()
                .filter(snapshot -> !snapshot.isTerminal())
                .sorted(Comparator.comparingLong(OrderSnapshot::createdAt))
                .toList();
    }

    public List<OrderSnapshot> forClient(String clientId) {
        return snapshots.values().stream()
                .filter(snapshot -> clientId.equals(snapshot.clientId()))
                .toList();
    }

    public List<OrderSnapshot> forDestination(String destinationId) {
        return snapshots.values().stream()
                .filter(snapshot -> destinationId.equals(snapshot.destinationId()))
                .toList();
    }

    /**
     * Drop terminal orders past the retention window.
     *
     * <p>Called on a schedule rather than on every fill: sweeping the map on each
     * report would put monitoring's housekeeping on the order path, which is
     * precisely what folding a stream avoids.
     */
    public int evictTerminal(long now) {
        List<String> expired = new ArrayList<>();
        snapshots.forEach((orderId, snapshot) -> {
            if (snapshot.isTerminal()
                    && now - snapshot.lastReportAt() > retainTerminalMillis) {
                expired.add(orderId);
            }
        });
        expired.forEach(snapshots::remove);
        return expired.size();
    }
}
