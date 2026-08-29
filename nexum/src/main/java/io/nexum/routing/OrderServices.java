package io.nexum.routing;

import io.nexum.order.OrderBook;
import io.nexum.order.OrderCache;
import io.nexum.order.OrderEvent;
import io.nexum.order.OrderIdResolver;
import io.nexum.order.OrderJournal;
import io.nexum.order.WireIdMinter;
import io.nexum.transport.Transport;

import java.util.Objects;

/**
 * What a {@link MessageHandler} needs to do its work.
 *
 * <p>Gathered into one value rather than passed as six parameters, and published
 * as a service so a handler can live in a plugin of its own. These were private
 * fields on the pipeline, which is what made a new message type a change to the
 * pipeline instead of an addition beside it.
 *
 * @param journal never null — a deployment that does not persist gets
 *     {@link OrderJournal#none()}. Handlers used to check for null before every
 *     append, which is four chances to forget in four handlers, and forgetting
 *     means an order goes on the wire unrecorded.
 */
public record OrderServices(
        OrderCache cache,
        OrderBook book,
        OrderIdResolver ids,
        WireIdMinter wireIds,
        Router router,
        Transport transport,
        OrderJournal journal) {

    public OrderServices {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(wireIds, "wireIds");
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(transport, "transport");
        journal = journal == null ? OrderJournal.none() : journal;
    }

    /** Record an event. Always safe to call. */
    public void record(OrderEvent event) {
        journal.append(event);
    }
}
