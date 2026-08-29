package io.nexum.docs;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.Events;
import io.nexum.core.Plugin;
import io.nexum.core.PluginLoader;
import io.nexum.core.Scope;
import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.routing.HandlerRegistry;
import io.nexum.routing.MessageHandler;
import io.nexum.routing.OrderServices;
import io.nexum.routing.OutboundPath;
import io.nexum.transport.RecordingTransport;
import io.nexum.transport.TransportEvents;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The examples in {@code docs/extending.md}, compiled and run.
 *
 * <p>Documentation that has never been executed is a claim, not a fact. These
 * are the same snippets; if one stops compiling, the document is wrong and this
 * says so before a reader finds out the slow way.
 */
class ExtendingDocTest {

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

    @BeforeEach
    void start() {
        ctx = new Context();
        transport = new RecordingTransport(CLIENT_SESSION, VENUE_SESSION);
        loader = Bootstrap.from(CONFIG).with(transport).start(ctx);
    }

    @AfterEach
    void stop() {
        loader.unloadAll();
    }

    /** The "Adding a message type" example, verbatim. */
    @Test
    void theHandlerExampleWorks() {
        loader.load(List.of(new MassCancelPlugin()));

        transport.deliver(CLIENT_SESSION, FixMessage.of("q", Map.of(
                FixTags.CL_ORD_ID, "MC-1",
                115, "FUNDX",
                FixTags.SYMBOL, "VOD")));

        List<RecordingTransport.Sent> out = transport.to(VENUE_SESSION);
        assertEquals(1, out.size());
        assertNotEquals("MC-1", out.get(0).field(FixTags.CL_ORD_ID),
                "the doc says the handler can mint an identifier of ours");
    }

    /** The "four layers" gate example, verbatim. */
    @Test
    void theGateExampleWorks() {
        ctx.onGate(TransportEvents.MESSAGE_INBOUND, Scope.client("FUND_X"),
                (Events.Gate<TransportEvents.InFlight>) (flight, next) -> {
                    if (flight.message().get(FixTags.CURRENCY) == null) {
                        return next.apply(flight.with(
                                flight.message().set(FixTags.CURRENCY, "USD")));
                    }
                    return next.apply(flight);
                });

        transport.deliver(CLIENT_SESSION, FixMessage.of("D", Map.of(
                FixTags.CL_ORD_ID, "FX-1",
                115, "FUNDX",
                FixTags.SYMBOL, "VOD",
                FixTags.SIDE, "1",
                FixTags.ORDER_QTY, "1000",
                FixTags.PRICE, "150.00")));

        assertEquals("USD", transport.to(VENUE_SESSION).get(0).field(FixTags.CURRENCY),
                "a client gate should have defaulted the currency");
    }

    // ------------------------------------------------------------------
    // Exactly as printed in docs/extending.md
    // ------------------------------------------------------------------

    static final class MassCancelHandler implements MessageHandler {

        @Override
        public Set<String> handles() {
            return Set.of("q");
        }

        @Override
        public void handle(Context ctx, OrderServices services,
                           TransportEvents.InFlight arrival) {
            String venue = services.router()
                    .toDestination(arrival.message())
                    .orElse(null);
            if (venue == null) {
                return;
            }
            String ours = services.wireIds().forCancel();
            OutboundPath.toDestination(ctx, services.transport(), venue, venue,
                    arrival.message().set(FixTags.CL_ORD_ID, ours));
        }
    }

    static final class MassCancelPlugin implements Plugin {

        @Override
        public String name() {
            return "mass-cancel";
        }

        @Override
        public List<String> inject() {
            return List.of("handlers");
        }

        @Override
        public void apply(Context ctx) {
            HandlerRegistry handlers = ctx.get("handlers");
            ctx.effect(() -> handlers.register(new MassCancelHandler()));
        }
    }
}
