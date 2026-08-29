package io.nexum.monitor;

/**
 * A view folded from the order event stream.
 *
 * <p>Monitoring reads the same events the journal records rather than querying
 * the cache, which keeps the two apart: the cache answers "where is this order",
 * a projection answers "what does the whole book look like". A slow or failing
 * projection cannot then stall the path an order takes.
 *
 * <p>State is rebuilt by replaying, so a projection added later starts correct
 * without a migration.
 *
 * @param <S> the shape this projection maintains
 */
public interface Projection<S> {

    /** Name it is registered and queried under. */
    String name();

    /** The value before any event has been seen. */
    S initial();

    /**
     * Fold one event into the state.
     *
     * <p>Returns the same instance when nothing changed, so consumers can skip
     * work by reference comparison rather than by diffing.
     */
    S apply(S state, OrderSnapshot snapshot, Change change);

    /** What happened to an order, in terms a projection can switch on. */
    enum Change {
        CREATED,
        VENUE_ID_ASSIGNED,
        STATE_CHANGED,
        REPORT_UNMATCHED
    }
}
