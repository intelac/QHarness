package io.nexum.order;

import io.nexum.core.Context;
import io.nexum.core.Plugin;

import java.nio.file.Path;
import java.util.List;

/**
 * Publishes the order cache as the {@code orders} service.
 *
 * <p>The cache is indispensable — routing, the return path and monitoring all
 * depend on it — but indispensable is not the same as built in. Consumers
 * declare {@code inject("orders")} and receive whatever provider is configured,
 * so a deployment can move from heap to journalled to distributed by changing
 * this one line, with no consumer aware of it.
 *
 * <p>With a journal path, the log is replayed at startup before the service is
 * published: reports for orders placed before a restart must resolve, or they
 * arrive as orphans with identifiers that mean nothing.
 */
public final class OrderCachePlugin implements Plugin {

    private final Path journalPath;
    private final boolean syncOnWrite;
    private final OrderCache supplied;

    /** In-memory only. Suitable for tests; a restart forgets every open order. */
    public OrderCachePlugin() {
        this(null, false, null);
    }

    /**
     * Journalled to a directory of dated segments and replayed at startup.
     *
     * @param journalPath directory holding the segments, not a single file
     */
    public OrderCachePlugin(Path journalPath, boolean syncOnWrite) {
        this(journalPath, syncOnWrite, null);
    }

    /** A caller-supplied implementation — Redis, off-heap, a fake in a test. */
    public OrderCachePlugin(OrderCache supplied) {
        this(null, false, supplied);
    }

    private OrderCachePlugin(Path journalPath, boolean syncOnWrite, OrderCache supplied) {
        this.journalPath = journalPath;
        this.syncOnWrite = syncOnWrite;
        this.supplied = supplied;
    }

    @Override
    public String name() {
        return "order-cache";
    }

    @Override
    public List<String> provides() {
        return journalPath == null ? List.of("orders") : List.of("orders", "journal");
    }

    @Override
    public void apply(Context ctx) {
        if (supplied != null) {
            ctx.register("orders", supplied);
            return;
        }
        if (journalPath == null) {
            ctx.register("orders", new InMemoryOrderCache());
            return;
        }

        OrderCache memory = new InMemoryOrderCache();

        // Replay before wrapping, so recovery does not write the history it is
        // reading back into the journal.
        SegmentedJournal.Replay replay = SegmentedJournal.replay(journalPath, memory);
        ctx.emit(OrderEvents.REPLAYED, new OrderEvents.Replayed(
                replay.recovered(),
                replay.skipped(),
                String.valueOf(replay.checkpoint())));

        // The journal and the service registration are torn down together: a
        // registered cache whose journal has been closed would accept writes it
        // cannot record.
        SegmentedJournal journal = new SegmentedJournal(journalPath, syncOnWrite);
        ctx.effect(() -> journal::close);
        ctx.register("journal", journal);
        ctx.register("orders", new JournalingOrderCache(memory, journal));
    }
}
