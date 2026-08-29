package io.nexum.order;

/**
 * Where an order's history is written down.
 *
 * <p>The rule the system is built on is that what reaches the wire is recorded
 * first: an order a venue knows about and this system has forgotten cannot be
 * recovered. This is the seam that rule is enforced through.
 *
 * <p>One method, because that is all a caller needs. Reading the journal back is
 * a startup concern with a different shape — a whole directory, a checkpoint, a
 * report of what could not be replayed — and putting it here would force every
 * implementation to answer questions only the file-backed one has.
 *
 * <p>The reason this is an interface at all: a deployment that ships its order
 * history to a database, a durable queue, or somewhere with its own retention
 * policy should be able to, without the pipeline knowing where its events go.
 */
public interface OrderJournal extends AutoCloseable {

    /**
     * Record one event.
     *
     * <p>Called before the corresponding message goes out, and expected to have
     * made the event durable by the time it returns. An implementation that
     * buffers is choosing to lose the tail of a crash.
     */
    void append(OrderEvent event);

    /** Narrower than {@link AutoCloseable}: nothing here throws a checked exception. */
    @Override
    void close();

    /** A journal that keeps nothing, for deployments that do not persist. */
    static OrderJournal none() {
        return new OrderJournal() {
            @Override
            public void append(OrderEvent event) {
                // deliberately nothing
            }

            @Override
            public void close() {
                // deliberately nothing
            }

            @Override
            public String toString() {
                return "OrderJournal.none()";
            }
        };
    }
}
