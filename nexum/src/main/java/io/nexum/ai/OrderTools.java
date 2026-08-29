package io.nexum.ai;

import io.nexum.core.Context;
import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.order.Order;
import io.nexum.order.OrderCache;
import io.nexum.order.OrderHistory;
import io.nexum.order.OrderId;
import io.nexum.order.OrderState;
import io.nexum.transport.TransportEvents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What an agent can do with an order.
 *
 * <p>Every tool here puts its message through the same door a real client uses
 * — the inbound event the pipeline listens on — so an order placed by a model
 * crosses the same four layers, the same risk gates and the same routing as one
 * that arrived over a socket. A back channel would be quicker to write and
 * would mean the rules a desk relies on are not the rules an agent is held to.
 *
 * <p>The acting tools wait for the venue rather than returning as soon as the
 * message is away. FIX answers on another thread some milliseconds later, and a
 * caller handed an order still in PENDING_NEW knows nothing it can act on;
 * a model in that position spends a turn asking again.
 */
public final class OrderTools {

    /** How long to wait for a venue before reporting what is known so far. */
    private static final long DEFAULT_WAIT_MILLIS = 5_000;

    private final Context ctx;
    private final OrderCache cache;
    private final OrderWatch watch;
    private final OrderHistory history;
    private final String clientSession;

    /**
     * Fields every message from this agent carries.
     *
     * <p>An agent is a client like any other, and a client is recognised by
     * what its messages carry — commonly OnBehalfOfCompID(115), but that is a
     * deployment's choice and not something to hard-code here. Without them the
     * order matches no client and is dropped at the first layer, which reads as
     * the venue having gone quiet.
     */
    private final Map<Integer, String> identity;

    /** Distinguishes one agent-placed order from the next. */
    private final AtomicLong sequence = new AtomicLong(1);

    /**
     * @param clientSession the session an agent's orders are treated as having
     *     arrived on, which is what decides the client and therefore the rules
     *     that apply
     */
    public OrderTools(
            Context ctx, OrderCache cache, OrderWatch watch,
            OrderHistory history, String clientSession) {

        this(ctx, cache, watch, history, clientSession, Map.of());
    }

    /**
     * @param identity fields identifying this agent as a client, which is what
     *     the routing layer matches on
     */
    public OrderTools(
            Context ctx, OrderCache cache, OrderWatch watch,
            OrderHistory history, String clientSession, Map<Integer, String> identity) {

        this.ctx = ctx;
        this.cache = cache;
        this.watch = watch;
        this.history = history;
        this.clientSession = clientSession;
        this.identity = Map.copyOf(identity);
    }

    /** Every tool this offers. */
    public List<AiTool> all() {
        return List.of(
                new PlaceOrder(), new AmendOrder(), new CancelOrder(),
                new GetOrder(), new ListOrders(), new OrderHistoryTool());
    }

    // ------------------------------------------------------------------
    // Acting
    // ------------------------------------------------------------------

    /** Send a new order and report what the venue made of it. */
    final class PlaceOrder implements AiTool {

        @Override
        public String name() {
            return "place_order";
        }

        @Override
        public String description() {
            return "Send a new order and wait for the venue to answer. Returns the"
                    + " order's identifier and the state it reached — acknowledged,"
                    + " rejected, filled, or still pending if the venue was slow."
                    + " Use the returned orderId to amend or cancel it.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> p = new LinkedHashMap<>();
            p.put("symbol", Parameter.required("string", "Instrument, e.g. VOD"));
            p.put("side", Parameter.oneOf("Buy or sell", "buy", "sell"));
            p.put("quantity", Parameter.required("number", "Number of shares"));
            p.put("price", Parameter.optional("number",
                    "Limit price. Omit for a market order."));
            p.put("timeoutMillis", Parameter.optional("number",
                    "How long to wait for the venue. Default 5000."));
            return p;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String clOrdId = "AI-" + System.currentTimeMillis()
                    + "-" + sequence.getAndIncrement();

            Map<Integer, String> fields = new LinkedHashMap<>(identity);
            fields.put(FixTags.CL_ORD_ID, clOrdId);
            fields.put(FixTags.SYMBOL, text(arguments, "symbol"));
            fields.put(FixTags.SIDE, "sell".equals(text(arguments, "side")) ? "2" : "1");
            fields.put(FixTags.ORDER_QTY, number(arguments, "quantity"));
            fields.put(FixTags.TRANSACT_TIME, utcNow());

            String price = number(arguments, "price");
            if (price == null) {
                fields.put(FixTags.ORD_TYPE, "1");
            } else {
                fields.put(FixTags.ORD_TYPE, "2");
                fields.put(FixTags.PRICE, price);
            }

            // The identity is minted by the pipeline from the session and this
            // ClOrdID, so it can be predicted — and must be, because the watch
            // has to be registered before the message goes anywhere.
            String orderId = OrderId.today(java.time.ZoneOffset.UTC, clientSession, clOrdId).toString();

            // The router may decide not to route this at all — an unknown
            // client, no destination it can pick — and it says why when it
            // does. That announcement is the only account of what happened:
            // the order is abandoned before it exists, so nothing about it
            // reaches the cache or the watch, and a caller told merely that
            // its order never appeared cannot tell a symbol it got wrong from
            // a deployment that does not recognise it.
            java.util.concurrent.atomic.AtomicReference<String> abandoned =
                    new java.util.concurrent.atomic.AtomicReference<>();
            io.nexum.core.Disposable listening = ctx.on(
                    io.nexum.routing.RoutingEvents.RULE_UNMATCHED,
                    (io.nexum.routing.RoutingEvents.Unmatched event) ->
                            abandoned.compareAndSet(null, explain(event)));
            try (OrderWatch.Watch waiting =
                         watch.watch(orderId, OrderTools::settled)) {

                ctx.emit(TransportEvents.MESSAGE_INBOUND + "/accepted",
                        TransportEvents.InFlight.inbound(
                                FixMessage.of("D", fields), clientSession));

                OrderState reached = waiting.await(waitFor(arguments));
                String why = abandoned.get();
                if (why != null) {
                    return AiTool.Result.failed(why);
                }
                return describe(orderId, reached, "placed");
            } finally {
                listening.dispose();
            }
        }
    }

    /** Change an order's price or quantity. */
    final class AmendOrder implements AiTool {

        @Override
        public String name() {
            return "amend_order";
        }

        @Override
        public String description() {
            return "Change an order's quantity or price and wait for the venue's"
                    + " answer. The order keeps its identifier. A venue may refuse,"
                    + " in which case the order carries on unchanged.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> p = new LinkedHashMap<>();
            p.put("orderId", Parameter.required("string",
                    "Any identifier the order answers to: the one this system minted, the ClOrdID the client sent, the one this system sent to the venue, or the venue's own OrderID — whichever the message you are reading carried."));
            p.put("quantity", Parameter.optional("number", "New quantity"));
            p.put("price", Parameter.optional("number", "New limit price"));
            p.put("timeoutMillis", Parameter.optional("number", "Default 5000."));
            return p;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String orderId = text(arguments, "orderId");
            Optional<Order> order = find(orderId);
            if (order.isEmpty()) {
                return Result.failed(notFound(orderId));
            }

            String quantity = number(arguments, "quantity");
            String price = number(arguments, "price");
            if (quantity == null && price == null) {
                return Result.failed("an amendment must change the quantity, the price, or both");
            }

            Map<Integer, String> fields = new LinkedHashMap<>(identity);
            fields.put(FixTags.CL_ORD_ID, "AI-AMD-" + sequence.getAndIncrement());
            fields.put(FixTags.ORIG_CL_ORD_ID, order.get().client().clOrdId());
            fields.put(FixTags.SYMBOL, order.get().client().field(FixTags.SYMBOL));
            fields.put(FixTags.SIDE, order.get().client().field(FixTags.SIDE));
            fields.put(FixTags.TRANSACT_TIME, utcNow());
            fields.put(FixTags.ORD_TYPE, price == null ? "1" : "2");
            if (quantity != null) {
                fields.put(FixTags.ORDER_QTY, quantity);
            }
            if (price != null) {
                fields.put(FixTags.PRICE, price);
            }

            try (OrderWatch.Watch waiting =
                         watch.watch(orderId, state -> !state.awaitsRequestAnswer())) {

                ctx.emit(TransportEvents.MESSAGE_INBOUND + "/accepted",
                        TransportEvents.InFlight.inbound(
                                FixMessage.of("G", fields), order.get().sessionId()));

                OrderState reached = waiting.await(waitFor(arguments));
                return describe(orderId, reached, "amended");
            }
        }
    }

    /** Withdraw an order. */
    final class CancelOrder implements AiTool {

        @Override
        public String name() {
            return "cancel_order";
        }

        @Override
        public String description() {
            return "Cancel an order and wait for the venue's answer. A venue may"
                    + " refuse — too late, already filled — in which case the order"
                    + " is still working and the reply says so.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> p = new LinkedHashMap<>();
            p.put("orderId", Parameter.required("string",
                    "Any identifier the order answers to: the one this system minted, the ClOrdID the client sent, the one this system sent to the venue, or the venue's own OrderID — whichever the message you are reading carried."));
            p.put("timeoutMillis", Parameter.optional("number", "Default 5000."));
            return p;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String orderId = text(arguments, "orderId");
            Optional<Order> order = find(orderId);
            if (order.isEmpty()) {
                return Result.failed(notFound(orderId));
            }

            Map<Integer, String> fields = new LinkedHashMap<>(identity);
            fields.put(FixTags.CL_ORD_ID, "AI-CXL-" + sequence.getAndIncrement());
            fields.put(FixTags.ORIG_CL_ORD_ID, order.get().client().clOrdId());
            fields.put(FixTags.SYMBOL, order.get().client().field(FixTags.SYMBOL));
            fields.put(FixTags.SIDE, order.get().client().field(FixTags.SIDE));
            fields.put(FixTags.TRANSACT_TIME, utcNow());

            try (OrderWatch.Watch waiting =
                         watch.watch(orderId, state -> !state.awaitsRequestAnswer())) {

                ctx.emit(TransportEvents.MESSAGE_INBOUND + "/accepted",
                        TransportEvents.InFlight.inbound(
                                FixMessage.of("F", fields), order.get().sessionId()));

                OrderState reached = waiting.await(waitFor(arguments));
                return describe(orderId, reached, "cancelled");
            }
        }
    }

    // ------------------------------------------------------------------
    // Looking
    // ------------------------------------------------------------------

    final class GetOrder implements AiTool {

        @Override
        public String name() {
            return "get_order";
        }

        @Override
        public String description() {
            return "Everything known about one order: its state, what has filled,"
                    + " and the identifiers the client, this system and the venue"
                    + " each know it by.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of("orderId", Parameter.required("string",
                    "Any identifier the order answers to: the one this system minted, the ClOrdID the client sent, the one this system sent to the venue, or the venue's own OrderID — whichever the message you are reading carried."));
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String orderId = text(arguments, "orderId");
            return find(orderId)
                    .map(order -> Result.of(summarise(order), snapshot(order)))
                    .orElseGet(() -> Result.failed(notFound(orderId)));
        }
    }

    final class ListOrders implements AiTool {

        @Override
        public String name() {
            return "list_orders";
        }

        @Override
        public String description() {
            return "Orders this system is currently holding. Settled orders are"
                    + " released after a while, so this is what is live rather than"
                    + " everything that ever happened.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of("symbol", Parameter.optional("string", "Only this instrument"));
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String symbol = text(arguments, "symbol");

            List<Order> orders = cache.active().stream()
                    .filter(o -> symbol == null || symbol.equalsIgnoreCase(o.client().field(FixTags.SYMBOL)))
                    .toList();

            if (orders.isEmpty()) {
                return Result.of("no orders are being held"
                        + (symbol == null ? "" : " for " + symbol));
            }

            StringBuilder text = new StringBuilder();
            List<Object> rows = new java.util.ArrayList<>();
            for (Order order : orders) {
                text.append(summarise(order)).append('\n');
                rows.add(snapshot(order));
            }
            return Result.of(text.toString().trim(), Map.of("orders", rows));
        }
    }

    final class OrderHistoryTool implements AiTool {

        @Override
        public String name() {
            return "order_events";
        }

        @Override
        public String description() {
            return "What happened to an order, in order: each event and the state"
                    + " it left the order in. Use this to understand why an order"
                    + " is where it is.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> p = new LinkedHashMap<>();
            p.put("orderId", Parameter.required("string",
                    "Any identifier the order answers to: the one this system minted, the ClOrdID the client sent, the one this system sent to the venue, or the venue's own OrderID — whichever the message you are reading carried."));
            p.put("limit", Parameter.optional("number", "Most recent events. Default 50."));
            return p;
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            if (history == null) {
                return Result.failed("this deployment keeps no history");
            }
            String asked = text(arguments, "orderId");
            int limit = (int) asDouble(arguments.get("limit"), 50);

            // The journal is keyed by the identity this system minted, but a
            // caller reading a message holds whichever identifier that message
            // carried. Resolve first, so a ClOrdID off the wire finds the
            // history that belongs to it.
            String orderId = find(asked).map(Order::orderId).orElse(asked);

            OrderHistory.Page page = history.transitionsOf(orderId, limit);
            if (page.entries().isEmpty()) {
                return Result.failed("nothing recorded for " + asked
                        + (orderId.equals(asked) ? "" : " (resolved to " + orderId + ")")
                        + ". The journal is keyed by the identity this system"
                        + " minted; an id from a message is resolved through the"
                        + " order cache, which releases settled orders about half"
                        + " an hour after their last report.");
            }

            StringBuilder text = new StringBuilder();
            for (OrderHistory.Entry entry : page.entries()) {
                text.append(entry.summary()).append('\n');
            }

            // A long-lived order accumulates more than a context window holds,
            // so the model is told what it is not seeing.
            return page.truncated()
                    ? Result.partial(text.toString().trim(),
                            Map.of("shown", page.entries().size(), "total", page.total()),
                            "ask for a larger limit to see earlier events")
                    : Result.of(text.toString().trim());
        }
    }

    // ------------------------------------------------------------------

    /**
     * Whether an order has reached something worth reporting.
     *
     * <p>Anything but waiting for the venue to acknowledge it. A rejection and
     * a fill are both answers; only PENDING_NEW is not.
     */
    private static boolean settled(OrderState state) {
        return state != OrderState.PENDING_NEW;
    }

    /**
     * Say why the router would not route an order, in terms its caller can act
     * on.
     *
     * <p>The stage is the useful part: a client the deployment does not
     * recognise is something to fix in its configuration, while a destination
     * it cannot pick is a routing rule. The rules' own explanations follow,
     * because they name the field that did not match.
     */
    private static String explain(io.nexum.routing.RoutingEvents.Unmatched event) {
        String what = event.stage() == io.nexum.routing.RoutingEvents.Unmatched.Stage.CLIENT
                ? "the order was not routed: this deployment does not recognise the client it came from"
                : "the order was not routed: no destination matched it";
        return event.reasons().isEmpty()
                ? what
                : what + " (" + String.join("; ", event.reasons()) + ")";
    }

    private AiTool.Result describe(String orderId, OrderState reached, String verb) {
        Optional<Order> order = cache.byOrderId(orderId);

        if (reached == null) {
            // Waiting is not by itself a failure, but why it is waiting decides
            // what to do about it — and the two are told apart by whether the
            // link to the destination is up.
            String destination = order.map(Order::destinationId).orElse(null);
            boolean linkUp = destination == null || linkIsUp(destination);
            String said = order
                    .map(o -> pendingExplanation(o.state(), destination, linkUp))
                    .orElse("the order has not appeared yet");
            return AiTool.Result.of(said + ". Order id " + orderId,
                    order.map(OrderTools::snapshot).orElse(Map.of("orderId", orderId)));
        }

        return order
                .map(o -> AiTool.Result.of(verb + ": " + summarise(o), snapshot(o)))
                .orElseGet(() -> AiTool.Result.of(
                        verb + ": " + orderId + " is now " + reached.label(),
                        Map.of("orderId", orderId, "state", reached.name())));
    }

    /**
     * Why an order is still waiting, in terms that decide what to do next.
     *
     * <p>A slow venue is waited for; a link that is down is escalated. The
     * engine cannot tell them apart from the order alone, because a message
     * sent to a session that is not logged on is queued and reported as sent —
     * so the link is asked about directly.
     *
     * @param destination the session the order was routed to, or null when it
     *     was never routed
     * @param linkUp whether that session is logged on
     */
    static String pendingExplanation(OrderState state, String destination, boolean linkUp) {
        if (!linkUp && destination != null) {
            return "the order is queued: this deployment is not connected to "
                    + destination + ", so it has not reached the market yet."
                    + " It will go out when the link comes back";
        }
        return "the order was sent and the venue has not answered yet; it is "
                + state.label();
    }

    /** Whether the session an order was routed to is logged on. */
    private boolean linkIsUp(String destination) {
        io.nexum.transport.Transport transport = ctx.get("transport");
        return transport == null || transport.isLoggedOn(destination);
    }

    private static String summarise(Order order) {
        return "%s  %s %s %s  filled %s of %s  [%s]".formatted(
                order.orderId(),
                order.client().field(FixTags.SIDE) == null ? "?"
                        : "2".equals(order.client().field(FixTags.SIDE)) ? "sell" : "buy",
                trim(order.client().field(FixTags.ORDER_QTY)),
                order.client().field(FixTags.SYMBOL),
                trim(String.valueOf(order.cumQty())),
                trim(order.client().field(FixTags.ORDER_QTY)),
                order.state().label());
    }

    private static Map<String, Object> snapshot(Order order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.orderId());
        data.put("state", order.state().name());
        data.put("stateLabel", order.state().label());
        data.put("symbol", order.client().field(FixTags.SYMBOL));
        data.put("cumQty", order.cumQty());
        data.put("working", order.state().isWorking());
        data.put("terminal", order.state().isTerminal());
        data.put("clientClOrdId", order.client().clOrdId());
        data.put("venueOrderId", order.destination().orderId());
        return data;
    }

    // ------------------------------------------------------------------

    private static long waitFor(Map<String, Object> arguments) {
        return (long) asDouble(arguments.get("timeoutMillis"), DEFAULT_WAIT_MILLIS);
    }

    /**
     * The order an identifier names, whichever identifier it is.
     *
     * <p>One order answers to four: the identity this system minted, the
     * ClOrdID the client sent, the one this system put on the wire, and the
     * venue's own. A caller reading a message has whichever of them that
     * message carried, and requiring the first turned "look up the order I am
     * holding an id for" into a guess — an agent that had just read
     * O0000001 off the wire asked for it by that name and was told no such
     * order existed, three times, while the order sat in the cache.
     */
    private Optional<Order> find(String identifier) {
        if (identifier == null) {
            return Optional.empty();
        }
        Optional<Order> found = cache.byOrderId(identifier);
        if (found.isPresent()) {
            return found;
        }
        found = cache.byClientClOrdId(identifier);
        if (found.isPresent()) {
            return found;
        }
        found = cache.byOurClOrdId(identifier);
        return found.isPresent() ? found : cache.byVenueOrderId(identifier);
    }

    /**
     * Why an identifier found nothing, in terms of what to do next.
     *
     * <p>"No order X" is true of an id that was never used and of one whose
     * order finished half an hour ago, and the two call for different things.
     * Saying which identifiers are accepted turns the first into a correction
     * the caller can make.
     */
    private static String notFound(String identifier) {
        return "no order answers to " + identifier
                + ". An order can be named by the identity this system minted"
                + " (20260101:OMS->FUND:CLIENT-1), the ClOrdID the client sent,"
                + " the one this system sent to the venue, or the venue's own"
                + " OrderID. list_orders shows what is live; an order that has"
                + " settled is released about half an hour after its last"
                + " report, and only its journal remains.";
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** A number as FIX carries it, without a trailing .0 a venue may reject. */
    private static String number(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        return trim(String.valueOf(value));
    }

    private static String trim(String number) {
        if (number == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(number).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notANumber) {
            return number;
        }
    }

    private static double asDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String utcNow() {
        return java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HH:mm:ss.SSS")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now());
    }
}
