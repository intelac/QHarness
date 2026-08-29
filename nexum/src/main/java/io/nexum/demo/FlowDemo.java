package io.nexum.demo;

import io.nexum.core.Context;
import io.nexum.core.Events;
import io.nexum.core.Fingerprint;
import io.nexum.core.Plugin;
import io.nexum.core.PluginLoader;
import io.nexum.core.Scope;
import io.nexum.order.InMemoryOrderCache;
import io.nexum.order.Order;
import io.nexum.order.OrderCache;
import io.nexum.order.OrderCachePlugin;
import io.nexum.order.OrderState;
import io.nexum.order.OrderView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end walk through the four layers: a client order arrives on a session,
 * is routed to a client and then a destination, goes out under our own
 * identifiers, and the venue's report is resolved back and answered in the
 * client's terms.
 *
 * <p>Run with: {@code java io.nexum.demo.FlowDemo}
 */
public final class FlowDemo {

    /** Tag numbers used here, named so the flow reads as FIX rather than integers. */
    static final int CL_ORD_ID = 11;
    static final int ORDER_ID = 37;
    static final int SYMBOL = 55;
    static final int SIDE = 54;
    static final int ORDER_QTY = 38;
    static final int ON_BEHALF_OF = 115;
    static final int HANDL_INST = 21;
    static final int TRANSACT_TIME = 60;
    static final int CURRENCY = 15;
    static final int ORD_STATUS = 39;
    static final int SECURITY_EXCHANGE = 207;

    /** A message in flight: tag/value plus the routing decided so far. */
    record Msg(Map<Integer, String> fields, String sessionId, String clientId, String destId) {
        static Msg inbound(String sessionId, Map<Integer, String> fields) {
            return new Msg(fields, sessionId, null, null);
        }

        String get(int tag) {
            return fields.get(tag);
        }

        Msg set(int tag, String value) {
            Map<Integer, String> merged = new LinkedHashMap<>(fields);
            merged.put(tag, value);
            return new Msg(merged, sessionId, clientId, destId);
        }

        Msg toClient(String id) {
            return new Msg(fields, sessionId, id, destId);
        }

        Msg toDestination(String id) {
            return new Msg(fields, sessionId, clientId, id);
        }

        String render() {
            StringBuilder text = new StringBuilder();
            fields.forEach((tag, value) -> text.append(tag).append('=').append(value).append('|'));
            return text.toString();
        }
    }

    /** One fingerprint rule and what it selects. */
    record Rule(Fingerprint fingerprint, String target) {}

    // ------------------------------------------------------------------
    // Plugins — one per layer, each unaware of the others
    // ------------------------------------------------------------------

    /** GLOBAL: sees every message at every layer. Observes only, always delegates. */
    static final class AuditPlugin implements Plugin {
        public String name() {
            return "audit";
        }

        public void apply(Context ctx) {
            ctx.onGate("msg", Scope.global(), (Events.Gate<Msg>) (msg, next) -> {
                Msg out = next.apply(msg);
                if (!out.render().equals(msg.render())) {
                    System.out.println("      [audit] rewritten: " + msg.render());
                    System.out.println("      [audit]         -> " + out.render());
                }
                return out;
            });
        }
    }

    /** SESSION layer: fixes up what this particular counterparty always omits. */
    static final class SessionPlugin implements Plugin {
        public String name() {
            return "session-broker-a";
        }

        public void apply(Context ctx) {
            ctx.onGate("msg", Scope.session("BROKER_A"), (Events.Gate<Msg>) (msg, next) -> {
                System.out.println("    [session:BROKER_A] stamping HandlInst + TransactTime");
                return next.apply(msg.set(HANDL_INST, "1").set(TRANSACT_TIME, "20260824-10:30:00"));
            });
        }
    }

    /** CLIENT layer: rules that apply to this customer only. */
    static final class ClientPlugin implements Plugin {
        public String name() {
            return "client-fund-x";
        }

        public void apply(Context ctx) {
            ctx.onGate("msg", Scope.client("FUND_X"), (Events.Gate<Msg>) (msg, next) -> {
                System.out.println("    [client:FUND_X] defaulting currency to USD");
                return next.apply(msg.set(CURRENCY, "USD"));
            });
        }
    }

    /** ROUTING layer: normalisation that has to happen before venue selection. */
    static final class RoutingPlugin implements Plugin {
        public String name() {
            return "routing-normalise";
        }

        public void apply(Context ctx) {
            ctx.onGate("msg", Scope.routing(), (Events.Gate<Msg>) (msg, next) -> {
                System.out.println("    [routing] normalising symbol");
                return next.apply(msg);
            });
        }
    }

    /** DESTINATION layer: the venue's own dialect. */
    static final class DestinationPlugin implements Plugin {
        public String name() {
            return "dest-lse";
        }

        public void apply(Context ctx) {
            ctx.onGate("msg", Scope.destination("LSE"), (Events.Gate<Msg>) (msg, next) -> {
                String symbol = msg.get(SYMBOL);
                System.out.println("    [dest:LSE] GBp pricing dialect, suffixing symbol");
                return next.apply(msg.set(SYMBOL, symbol + ".L"));
            });
        }
    }

    /** DESTINATION layer: a different venue, entirely different behaviour. */
    static final class NysePlugin implements Plugin {
        public String name() {
            return "dest-nyse";
        }

        public void apply(Context ctx) {
            ctx.onGate("msg", Scope.destination("NYSE"), (Events.Gate<Msg>) (msg, next) -> {
                System.out.println("    [dest:NYSE] appending venue suffix");
                return next.apply(msg.set(SYMBOL, msg.get(SYMBOL) + ".N"));
            });
        }
    }

    /** Fingerprint routing for both hops, published as the {@code router} service. */
    static final class RouterPlugin implements Plugin {
        private final List<Rule> clientRules;
        private final List<Rule> destRules;

        RouterPlugin(List<Rule> clientRules, List<Rule> destRules) {
            this.clientRules = clientRules;
            this.destRules = destRules;
        }

        public String name() {
            return "router";
        }

        public void apply(Context ctx) {
            ctx.register("router", new Router(clientRules, destRules));
        }
    }

    record Router(List<Rule> clientRules, List<Rule> destRules) {
        Optional<String> toClient(Msg msg) {
            return first(clientRules, msg);
        }

        Optional<String> toDestination(Msg msg) {
            return first(destRules, msg);
        }

        private static Optional<String> first(List<Rule> rules, Msg msg) {
            for (Rule rule : rules) {
                if (rule.fingerprint().matches(msg.fields())) {
                    return Optional.of(rule.target());
                }
            }
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // The flow
    // ------------------------------------------------------------------

    private static final AtomicInteger ourSeq = new AtomicInteger(1);

    public static void main(String[] args) {
        Context ctx = new Context();
        PluginLoader loader = new PluginLoader(ctx);

        List<Rule> clientRules = List.of(
                new Rule(Fingerprint.of().eq(ON_BEHALF_OF, "FUNDX").build(), "FUND_X"),
                new Rule(Fingerprint.of().eq(ON_BEHALF_OF, "FUNDY").build(), "FUND_Y"));

        // Specific rules first, catch-all last — first match wins.
        List<Rule> destRules = List.of(
                new Rule(Fingerprint.of().eq(SECURITY_EXCHANGE, "L").gt(ORDER_QTY, "100000").build(), "DARK_POOL"),
                new Rule(Fingerprint.of().eq(SECURITY_EXCHANGE, "L").build(), "LSE"),
                new Rule(Fingerprint.any(), "NYSE"));

        loader.load(List.of(
                new AuditPlugin(),
                new SessionPlugin(),
                new ClientPlugin(),
                new RoutingPlugin(),
                new DestinationPlugin(),
                new NysePlugin(),
                new RouterPlugin(clientRules, destRules),
                new OrderCachePlugin(new InMemoryOrderCache())));

        System.out.println("loaded: " + loader.loadedNames() + "\n");

        OrderCache cache = ctx.get("orders");
        Router router = ctx.get("router");

        // ---- downstream ------------------------------------------------
        System.out.println("=== client order arrives on session BROKER_A ===");
        Map<Integer, String> incoming = new LinkedHashMap<>();
        incoming.put(CL_ORD_ID, "CLIENT-ORD-99");
        incoming.put(ON_BEHALF_OF, "FUNDX");
        incoming.put(SYMBOL, "VOD");
        incoming.put(SECURITY_EXCHANGE, "L");
        incoming.put(SIDE, "1");
        incoming.put(ORDER_QTY, "1000");

        Msg msg = Msg.inbound("BROKER_A", incoming);
        System.out.println("  wire in: " + msg.render());

        System.out.println("\n  -- SESSION layer --");
        msg = ctx.waterfall("msg", Scope.session("BROKER_A"), msg, m -> m);

        String clientId = router.toClient(msg).orElseThrow(
                () -> new IllegalStateException("no client fingerprint matched"));
        System.out.println("\n  >> fingerprint routed to client: " + clientId);
        msg = msg.toClient(clientId);

        System.out.println("\n  -- CLIENT layer --");
        msg = ctx.waterfall("msg", Scope.client(clientId), msg, m -> m);

        System.out.println("\n  -- ROUTING layer --");
        msg = ctx.waterfall("msg", Scope.routing(), msg, m -> m);

        String destId = router.toDestination(msg).orElseThrow();
        System.out.println("\n  >> fingerprint routed to destination: " + destId);
        msg = msg.toDestination(destId);

        System.out.println("\n  -- DESTINATION layer --");
        msg = ctx.waterfall("msg", Scope.destination(destId), msg, m -> m);

        // Three sets of identifiers, not a pass-through.
        String orderId = "ORD-" + ourSeq.getAndIncrement();
        String ourClOrdId = "OUR-" + ourSeq.getAndIncrement();
        msg = msg.set(CL_ORD_ID, ourClOrdId);

        Order order = new Order(
                orderId,
                "BROKER_A",
                clientId,
                destId,
                OrderView.of("CLIENT-ORD-99", incoming),
                OrderView.of(orderId, Map.of()),
                OrderView.of(ourClOrdId, msg.fields()),
                OrderState.PENDING_NEW);
        cache.put(order);
        cache.indexOutbound(ourClOrdId, orderId);

        System.out.println("\n  wire out: " + msg.render());
        System.out.println("  cached  : client=CLIENT-ORD-99  ours=" + ourClOrdId
                + "  internal=" + orderId);

        // ---- return path, first ack: ClOrdID only ----------------------
        System.out.println("\n=== venue acks — no OrderID(37) yet ===");
        Order viaClOrdId = cache.resolve(null, ourClOrdId).orElseThrow();
        System.out.println("  resolved by ClOrdID(11)=" + ourClOrdId
                + " -> " + viaClOrdId.orderId());
        System.out.println("  answering client as ClOrdID(11)="
                + viaClOrdId.client().clOrdId());

        String venueOrderId = "LSE-ORDER-7781";
        cache.indexVenueOrderId(venueOrderId, orderId);
        cache.update(viaClOrdId.withVenueOrderId(venueOrderId).withState(OrderState.NEW));
        System.out.println("  learned OrderID(37)=" + venueOrderId + ", now indexed");

        // ---- return path, later report: prefer 37 ----------------------
        System.out.println("\n=== venue fills — report carries OrderID(37) ===");
        Order viaOrderId = cache.resolve(venueOrderId, ourClOrdId).orElseThrow();
        System.out.println("  resolved by OrderID(37)=" + venueOrderId
                + " -> " + viaOrderId.orderId() + "  (37 preferred over 11)");
        cache.update(viaOrderId.withState(OrderState.FILLED));

        System.out.println("\n  translating back to client terms:");
        System.out.println("    ours   : ClOrdID=" + viaOrderId.destination().clOrdId()
                + "  Symbol=" + viaOrderId.destination().field(SYMBOL));
        System.out.println("    client : ClOrdID=" + viaOrderId.client().clOrdId()
                + "  Symbol=" + viaOrderId.client().field(SYMBOL));

        // ---- a second order proving the layers really are per-entity ----
        System.out.println("\n=== a US order — same code, different plugins fire ===");
        Map<Integer, String> usOrder = new LinkedHashMap<>();
        usOrder.put(CL_ORD_ID, "CLIENT-ORD-100");
        usOrder.put(ON_BEHALF_OF, "FUNDX");
        usOrder.put(SYMBOL, "IBM");
        usOrder.put(SIDE, "1");
        usOrder.put(ORDER_QTY, "500");

        Msg us = Msg.inbound("BROKER_A", usOrder);
        us = ctx.waterfall("msg", Scope.session("BROKER_A"), us, m -> m);
        String usClient = router.toClient(us).orElseThrow();
        us = ctx.waterfall("msg", Scope.client(usClient), us.toClient(usClient), m -> m);
        us = ctx.waterfall("msg", Scope.routing(), us, m -> m);
        String usDest = router.toDestination(us).orElseThrow();
        System.out.println("  >> routed to " + usDest + " (no .L suffix, so the catch-all rule won)");
        us = ctx.waterfall("msg", Scope.destination(usDest), us.toDestination(usDest), m -> m);
        System.out.println("  wire out: " + us.render());

        // ---- unload one layer's plugin ---------------------------------
        System.out.println("\n=== unload the LSE plugin ===");
        loader.unload("dest-lse");
        Msg again = ctx.waterfall("msg", Scope.destination("LSE"),
                Msg.inbound("BROKER_A", Map.of(SYMBOL, "VOD")), m -> m);
        System.out.println("  symbol now: " + again.get(SYMBOL)
                + (again.get(SYMBOL).endsWith(".L")
                        ? "   FAIL - residue left behind"
                        : "   OK - gone, other layers untouched"));

        System.out.println("\ncache holds " + cache.size() + " orders, "
                + cache.active().size() + " active");
        loader.unloadAll();
    }
}
