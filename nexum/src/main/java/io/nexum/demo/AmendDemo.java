package io.nexum.demo;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.order.OrderEvents;
import io.nexum.order.OutboundEvent;
import io.nexum.order.ManagedOrder;
import io.nexum.order.OrderBook;
import io.nexum.sim.SimClient;
import io.nexum.sim.SimVenue;
import io.nexum.transport.Transport;

import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

import java.util.Map;

/**
 * Cancel and replace, end to end over real FIX sessions.
 *
 * <p>The three cases that matter: a cancel the venue accepts, a replace it
 * accepts, and a cancel it refuses — the last leaving the order working, which
 * is the outcome most often mishandled.
 */
public final class AmendDemo {

    static final int CLIENT_PORT = 19931;
    static final int VENUE_PORT = 19932;

    private static final String CONFIG = """
            monitor:
              enabled: false

            orders:
              journal: target/amend/orders
              sync: true

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
                role: acceptor
                port: %d
                logPath: target/amend/logs
                persistent: false

              - id: OMS->LSE
                version: FIX.4.4
                role: initiator
                host: 127.0.0.1
                port: %d
                logPath: target/amend/logs
                persistent: false

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->LSE
                fingerprint: any
            """.formatted(CLIENT_PORT, VENUE_PORT);

    public static void main(String[] args) throws Exception {
        // Orders in these symbols rest instead of trading, so there is something
        // live for a cancel or replace to act on.
        SimVenue.restOn("REST");
        SimVenue.restOn("STUBBORN");
        SimVenue.refuseCancelsOn("STUBBORN");

        SocketAcceptor venue = SimVenue.start(VENUE_PORT, "LSE", "OMS");

        Context ctx = new Context();
        PluginLoader loader = Bootstrap.from(CONFIG).start(ctx);
        watch(ctx);

        Transport transport = ctx.get("transport");
        for (int i = 0; i < 100 && !transport.isLoggedOn("OMS->LSE"); i++) {
            Thread.sleep(100);
        }
        SocketInitiator client = SimClient.start(CLIENT_PORT, "FUNDX", "OMS");
        for (int i = 0; i < 100 && !SimClient.isLoggedOn(); i++) {
            Thread.sleep(100);
        }
        System.out.println("both sessions up\n");

        OrderBook book = ctx.get("book");

        System.out.println("=== an order that rests on the book ===");
        SimClient.sendOrder("FX-1", "REST", 1000, "L");
        Thread.sleep(900);
        show(book, "ORD-1");

        System.out.println("\n=== the client asks to change price and quantity ===");
        SimClient.sendReplace("FX-1-AMD", "FX-1", "REST", 1500, 155.0);
        Thread.sleep(900);
        show(book, "ORD-1");

        System.out.println("\n=== and then cancels it ===");
        SimClient.sendCancel("FX-1-CXL", "FX-1", "REST");
        Thread.sleep(900);
        show(book, "ORD-1");

        System.out.println("\n=== an order whose cancel the venue refuses ===");
        SimClient.sendOrder("FX-2", "STUBBORN", 500, "L");
        Thread.sleep(900);

        SimClient.sendCancel("FX-2-CXL", "FX-2", "STUBBORN");
        Thread.sleep(900);
        show(book, "ORD-2");
        System.out.println("  the order is still working, which is the point");

        System.out.println("\n=== a cancel for an order nobody has ===");
        SimClient.sendCancel("FX-9-CXL", "NO-SUCH-ORDER", "REST");
        Thread.sleep(700);

        loader.unloadAll();
        client.stop();
        venue.stop();
        System.out.println("\nstopped");
        System.exit(0);
    }

    private static void show(OrderBook book, String orderId) {
        book.byOrderId(orderId).ifPresentOrElse(
                order -> {
                    System.out.println("  " + order.orderId()
                            + "  state=" + order.state()
                            + "  filled=" + order.cumQty()
                            + (order.pending().isPresent()
                                    ? "  [" + order.pending().get().kind() + " outstanding]"
                                    : ""));
                    order.destinationView().ifPresent(view ->
                            System.out.println("    terms in force: qty=" + view.field(38)
                                    + " price=" + view.field(44)));
                },
                () -> System.out.println("  " + orderId + " not in the book"));
    }

    private static void watch(Context ctx) {
        ctx.on(OrderEvents.STATE_CHANGED, (OutboundEvent.StateChanged e) ->
                System.out.println("    [state] " + e.orderId() + "  "
                        + e.from() + " -> " + e.to() + "  (" + e.because() + ")"));

        ctx.on(OrderEvents.REQUEST_SENT, (OutboundEvent.RequestOutstanding e) ->
                System.out.println("    [sent] " + e.request().kind()
                        + " for " + e.orderId()));

        ctx.on(OrderEvents.REQUEST_ANSWERED, (OutboundEvent.RequestAnswered e) ->
                System.out.println("    [answer] " + e.request().kind()
                        + " " + (e.accepted() ? "accepted" : "refused")));

        ctx.on(OrderEvents.TERMS_AMENDED, (OutboundEvent.TermsAmended e) ->
                System.out.println("    [amended] " + e.newTerms()));

        ctx.on(OrderEvents.REQUEST_UNKNOWN, (OrderEvents.UnknownRequest e) ->
                System.out.println("    [unknown] 35=" + e.msgType()
                        + " referring to " + e.origClOrdId()));

        ctx.on(OrderEvents.REPORT_IGNORED, (OutboundEvent.Ignored e) ->
                System.out.println("    [stale] " + e.why()));

        ctx.on(OrderEvents.DISAGREEMENT, (OutboundEvent.Disagreement e) ->
                System.out.println("    [DISAGREEMENT] " + e.why()));
    }
}
