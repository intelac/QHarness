package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.core.Plugin;
import io.nexum.order.Order;
import io.nexum.order.OrderBook;
import io.nexum.order.OrderCache;
import io.nexum.order.OrderId;
import io.nexum.order.OrderIdResolver;
import io.nexum.order.OrderJournal;
import io.nexum.order.WireIdMinter;
import io.nexum.transport.Transport;
import io.nexum.transport.TransportEvents;

import java.util.List;

/**
 * Carries orders across the four layers and brings their reports back.
 *
 * <p>This plugin owns the things an order's lifetime depends on — the book, the
 * identity resolver, the wire identifiers — and hands them to whichever
 * {@link MessageHandler} claims an arriving message type. What each message
 * <em>means</em> lives in the handler; what a state change means lives in the
 * order. Neither is decided here.
 *
 * <p>Dispatch is a registry rather than a switch, so supporting a message type
 * this system has never seen is a plugin someone writes beside it.
 */
public final class OrderPipeline implements Plugin {

    /**
     * How long a finished order stays in the book.
     *
     * <p>The last report is rarely the last message — a correction, a bust or a
     * resend follows. Half an hour absorbs that without letting the book grow
     * for the life of the process.
     */
    private static final long RETAIN_SETTLED = java.time.Duration.ofMinutes(30).toMillis();

    private final OrderBook book = new OrderBook();

    private final HandlerRegistry handlers = new HandlerRegistry();

    private java.util.concurrent.ScheduledExecutorService housekeeping;

    /**
     * Where an order's identity comes from, and where a later message about it
     * is looked up. One instance, so the minting and the lookups cannot diverge.
     */
    private final OrderIdResolver ids = new OrderIdResolver(java.time.ZoneOffset.UTC);

    /**
     * What goes on the wire. Short, because ClOrdID is commonly capped around
     * twenty characters and the three-part internal identity does not fit.
     */
    private final WireIdMinter wireIds = new WireIdMinter();

    @Override
    public String name() {
        return "order-pipeline";
    }

    @Override
    public List<String> inject() {
        return List.of("orders", "router", "transport");
    }

    @Override
    public List<String> provides() {
        return List.of("order-pipeline", "book", "handlers", "order-services");
    }

    @Override
    public void apply(Context ctx) {
        OrderCache cache = ctx.get("orders");
        Router router = ctx.get("router");
        Transport transport = ctx.get("transport");
        OrderJournal journal = ctx.<OrderJournal>find("journal").orElse(OrderJournal.none());

        OrderServices services = new OrderServices(
                cache, book, ids, wireIds, router, transport, journal);

        ctx.register("book", book);
        // Published so a plugin can add a handler for a message type this
        // system does not know about, without this file changing.
        ctx.register("handlers", handlers);
        ctx.register("order-services", services);

        ctx.effect(() -> handlers.register(new NewOrderHandler()));
        ctx.effect(() -> handlers.register(new CancelRequestHandler()));
        ctx.effect(() -> handlers.register(new ReplaceRequestHandler()));
        ctx.effect(() -> handlers.register(new ExecutionReportHandler()));

        recover(cache);
        scheduleHousekeeping(ctx);

        ctx.onEvent(TransportEvents.MESSAGE_INBOUND + "/accepted",
                (TransportEvents.InFlight flight) -> dispatch(ctx, services, flight));
    }

    private void dispatch(
            Context ctx, OrderServices services, TransportEvents.InFlight flight) {

        String msgType = flight.message().msgType();
        List<MessageHandler> claimed = handlers.forMsgType(msgType);

        if (claimed.isEmpty()) {
            ctx.emit(RoutingEvents.MESSAGE_UNHANDLED, new RoutingEvents.Unhandled(
                    flight.sessionId(), msgType, flight.at()));
            return;
        }

        for (MessageHandler handler : claimed) {
            handler.handle(ctx, services, flight);
        }
    }

    /**
     * Rebuild the live book from whatever the cache recovered.
     *
     * <p>Without this a report for an order placed before a restart finds
     * nothing that knows what to do with it.
     */
    private void recover(OrderCache cache) {
        long now = System.currentTimeMillis();
        long highestWireId = 0;
        for (Order stored : cache.active()) {
            book.restore(stored, now);
            // Without this a client cancelling an order it placed before the
            // restart is told the order does not exist — the identity is
            // minted once and would otherwise be lost with the process.
            rememberClientIdentifiers(stored);
            // Resume past what has already been sent. Reusing an identifier
            // would attach a fresh order to a previous one's reports.
            highestWireId = Math.max(
                    highestWireId, WireIdMinter.numberOf(stored.destination().clOrdId()));
        }
        wireIds.resumeAfter(highestWireId);
    }

    /**
     * Without this both the book and the resolver's alias map grow for the life
     * of the process. The leak is invisible in a test and shows up after the
     * system has been up long enough to matter.
     */
    private void scheduleHousekeeping(Context ctx) {
        ctx.effect(() -> {
            housekeeping = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "order-housekeeping");
                        thread.setDaemon(true);
                        return thread;
                    });
            housekeeping.scheduleWithFixedDelay(
                    this::releaseSettled, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
            return housekeeping::shutdownNow;
        });
    }

    /** Drop finished orders and the identifiers they answered to. */
    private void releaseSettled() {
        try {
            for (String orderId : book.evictSettled(System.currentTimeMillis(), RETAIN_SETTLED)) {
                if (OrderId.looksLikeOne(orderId)) {
                    ids.forget(OrderId.parse(orderId));
                }
            }
        } catch (RuntimeException failure) {
            // Housekeeping that throws must not kill its own schedule.
            System.err.println("order housekeeping failed: " + failure);
        }
    }

    /**
     * Teach the resolver the identifiers a restored order answers to.
     *
     * <p>An order id encodes the ClOrdID the client first used, so that one is
     * recoverable from the id itself; any introduced by a later replace are not,
     * and are taken from the stored view.
     */
    private void rememberClientIdentifiers(Order stored) {
        if (!OrderId.looksLikeOne(stored.orderId())) {
            return;
        }
        OrderId orderId = OrderId.parse(stored.orderId());
        ids.alsoKnownAs(orderId, stored.sessionId(), orderId.clientClOrdId());

        String clientClOrdId = stored.client().clOrdId();
        if (clientClOrdId != null && !clientClOrdId.equals(orderId.clientClOrdId())) {
            ids.alsoKnownAs(orderId, stored.sessionId(), clientClOrdId);
        }
    }
}
