package io.nexum.order;

import java.util.Collection;
import java.util.Optional;

/**
 * An {@link OrderCache} that journals what only it observes.
 *
 * <p>Most order events are written by the pipeline, which knows the wire
 * reference — the session and sequence number of the message that caused the
 * change — and can therefore record something an auditor can follow back to the
 * bytes. This wrapper covers the one event the pipeline does not raise itself:
 * learning the venue's OrderID.
 *
 * <p>Wraps another cache rather than replacing it: the in-memory implementation
 * still answers every read at memory speed, and this adds durability in front of
 * the writes. Consumers see the same interface and cannot tell the difference,
 * which is the point of keeping the cache behind one.
 *
 * <p>Journal first, then apply. The other order leaves a window in which an
 * order is live in memory but absent from disk, and a crash inside that window
 * produces exactly the orphaned reports the journal exists to prevent.
 */
public final class JournalingOrderCache implements OrderCache {

    private final OrderCache delegate;
    private final OrderJournal journal;

    public JournalingOrderCache(OrderCache delegate, OrderJournal journal) {
        this.delegate = delegate;
        this.journal = journal;
    }

    /**
     * Journalled by the pipeline, which holds the wire reference this cannot
     * see, so the write here would duplicate a richer record.
     */
    @Override
    public void put(Order order) {
        delegate.put(order);
    }

    /**
     * Journalled by the handler together with the report that revealed the id.
     *
     * <p>Recording it here as well wrote every venue id twice — once from the
     * report's own timestamp and once from the clock at index time. The
     * handler's is the one to keep: it carries the moment the venue actually
     * said so, which is what a reconstruction needs.
     */
    @Override
    public void indexVenueOrderId(String venueOrderId, String orderId) {
        delegate.indexVenueOrderId(venueOrderId, orderId);
    }

    /** Journalled by the pipeline together with the report that caused it. */
    @Override
    public void update(Order order) {
        delegate.update(order);
    }

    /**
     * Not journalled: the outbound ClOrdID is already carried by the
     * {@code created} record, so replay rebuilds this index without a second
     * entry.
     */
    @Override
    public void indexOutbound(String ourClOrdId, String orderId) {
        delegate.indexOutbound(ourClOrdId, orderId);
    }

    // ------------------------------------------------------------------
    // Reads pass straight through
    // ------------------------------------------------------------------

    @Override
    public Optional<Order> byOrderId(String orderId) {
        return delegate.byOrderId(orderId);
    }

    @Override
    public Optional<Order> byClientClOrdId(String clientClOrdId) {
        return delegate.byClientClOrdId(clientClOrdId);
    }

    @Override
    public Optional<Order> byOurClOrdId(String ourClOrdId) {
        return delegate.byOurClOrdId(ourClOrdId);
    }

    @Override
    public Optional<Order> byVenueOrderId(String venueOrderId) {
        return delegate.byVenueOrderId(venueOrderId);
    }

    @Override
    public Collection<Order> active() {
        return delegate.active();
    }

    @Override
    public int size() {
        return delegate.size();
    }

}
