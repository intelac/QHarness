package io.nexum.routing;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.Disposable;
import io.nexum.core.PluginLoader;
import io.nexum.message.FixMessage;
import io.nexum.transport.RecordingTransport;
import io.nexum.transport.TransportEvents;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a message type this system has never seen can be supported without
 * editing it.
 *
 * <p>Dispatch used to be a switch inside the pipeline, so a new message type
 * meant changing the file every other message type already went through. These
 * tests hold the registry to the claim that replaced it: the handler below is
 * written entirely from outside, and nothing in {@code io.nexum.routing} was
 * touched to make the system route to it.
 */
class HandlerRegistryTest {

    private static final int CL_ORD_ID = 11;
    private static final int ON_BEHALF_OF = 115;
    private static final int SYMBOL = 55;
    private static final int SIDE = 54;
    private static final int ORDER_QTY = 38;

    private static final String CLIENT_SESSION = "OMS->FUNDX";
    private static final String VENUE_SESSION = "OMS->LSE";

    private static final String CONFIG = """
            monitor:
              enabled: false

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
              - id: OMS->LSE
                version: FIX.4.4

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->LSE
                fingerprint: any
            """;

    private Context ctx;
    private PluginLoader loader;
    private RecordingTransport transport;
    private HandlerRegistry handlers;

    @BeforeEach
    void start() {
        ctx = new Context();
        transport = new RecordingTransport(CLIENT_SESSION, VENUE_SESSION);
        loader = Bootstrap.from(CONFIG).with(transport).start(ctx);
        handlers = ctx.get("handlers");
    }

    @AfterEach
    void stop() {
        loader.unloadAll();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a message type nobody anticipated")
    class NewMessageTypes {

        @Test
        void isUnhandledUntilSomethingClaimsIt() {
            List<String> unhandled = new ArrayList<>();
            ctx.on(RoutingEvents.MESSAGE_UNHANDLED,
                    (RoutingEvents.Unhandled event) -> unhandled.add(event.msgType()));

            transport.deliver(CLIENT_SESSION, massCancel("MC-1"));

            assertEquals(List.of("q"), unhandled,
                    "an unclaimed type should be surfaced, not dropped in silence");
        }

        @Test
        void isRoutedToAPluginThatClaimsIt() {
            MassCancelHandler added = new MassCancelHandler();
            handlers.register(added);

            transport.deliver(CLIENT_SESSION, massCancel("MC-1"));

            assertEquals(List.of("MC-1"), added.seen,
                    "the registered handler should have received the message");
        }

        @Test
        void reachesTheServicesItNeedsToDoRealWork() {
            // The point of OrderServices: a handler written outside this package
            // can mint identifiers, open orders and send, which is what made a
            // new message type a pipeline change before.
            handlers.register(new MassCancelHandler());

            transport.deliver(CLIENT_SESSION, massCancel("MC-1"));

            List<RecordingTransport.Sent> out = transport.to(VENUE_SESSION);
            assertEquals(1, out.size(), "the handler should have been able to send");
            assertEquals("q", out.get(0).message().msgType());
            assertFalse("MC-1".equals(out.get(0).field(CL_ORD_ID)),
                    "it should have been able to mint an identifier of ours");
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        void isReversible() {
            MassCancelHandler added = new MassCancelHandler();
            Disposable registered = handlers.register(added);

            assertTrue(handlers.handles("q"));
            registered.dispose();
            assertFalse(handlers.handles("q"),
                    "a disposed registration should leave nothing behind");

            transport.deliver(CLIENT_SESSION, massCancel("MC-1"));
            assertTrue(added.seen.isEmpty(), "a disposed handler must stop receiving");
        }

        @Test
        void refusesASecondClaimOnTheSameType() {
            handlers.register(new MassCancelHandler());

            // Quietly preferring one would make which handler runs depend on
            // load order, which is not a thing anyone can debug.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> handlers.register(new MassCancelHandler()));
            assertTrue(refused.getMessage().contains("q"),
                    "the refusal should name the type that clashed: " + refused.getMessage());
        }

        @Test
        void leavesNothingHalfInstalledWhenRefused() {
            handlers.register(new MassCancelHandler());

            // Claims "q" — which is taken — and "AB", which is free. The
            // refusal must not leave "AB" claimed by a handler that failed to
            // register.
            assertThrows(IllegalStateException.class,
                    () -> handlers.register(new TwoTypeHandler()));

            assertFalse(handlers.handles("AB"),
                    "a refused registration should have rolled back its other claims");
        }

        @Test
        void allowsLayeringWhenTheOrderDiffers() {
            MassCancelHandler first = new MassCancelHandler();
            MassCancelHandler second = new MassCancelHandler(10);
            handlers.register(first);
            handlers.register(second);

            transport.deliver(CLIENT_SESSION, massCancel("MC-1"));

            assertEquals(List.of("MC-1"), first.seen);
            assertEquals(List.of("MC-1"), second.seen, "both should run, in order");
            assertEquals(List.of("MassCancelHandler", "MassCancelHandler"),
                    handlers.describe().get("q"));
        }

        @Test
        void doesNotDisturbTheTypesTheSystemAlreadyHandles() {
            assertTrue(handlers.handles("D"), "new orders");
            assertTrue(handlers.handles("F"), "cancels");
            assertTrue(handlers.handles("G"), "replaces");
            assertTrue(handlers.handles("8"), "execution reports");
            assertTrue(handlers.handles("9"), "cancel rejects");
        }
    }

    // ------------------------------------------------------------------

    private static FixMessage massCancel(String clOrdId) {
        return FixMessage.of("q", Map.of(
                CL_ORD_ID, clOrdId,
                ON_BEHALF_OF, "FUNDX",
                SYMBOL, "VOD"));
    }

    /**
     * Everything a real handler for a new message type would be.
     *
     * <p>Written against nothing but the interface and the services record —
     * this is the shape a counterparty's proprietary message would take.
     */
    private static final class MassCancelHandler implements MessageHandler {

        private final List<String> seen = new ArrayList<>();
        private final int order;

        MassCancelHandler() {
            this(0);
        }

        MassCancelHandler(int order) {
            this.order = order;
        }

        @Override
        public Set<String> handles() {
            return Set.of("q");
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public void handle(
                Context ctx, OrderServices services, TransportEvents.InFlight arrival) {

            seen.add(arrival.message().get(CL_ORD_ID));

            // Only the first of a layered pair sends, so the assertion on how
            // many messages left stays about the registry and not about this.
            if (order != 0) {
                return;
            }
            String ours = services.wireIds().forCancel();
            services.transport().send(
                    VENUE_SESSION, arrival.message().set(CL_ORD_ID, ours));
        }
    }

    /** Claims one taken type and one free one, to test rollback. */
    private static final class TwoTypeHandler implements MessageHandler {

        @Override
        public Set<String> handles() {
            // Ordered so the free claim is taken before the clash is found.
            return new java.util.LinkedHashSet<>(List.of("AB", "q"));
        }

        @Override
        public void handle(
                Context ctx, OrderServices services, TransportEvents.InFlight arrival) {
            // never reached
        }
    }
}
