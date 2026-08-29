package io.nexum.routing;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.Events;
import io.nexum.core.PluginLoader;
import io.nexum.core.Scope;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a plugin mounted on a layer sees messages going out, not only coming in.
 *
 * <p>Enriching an outgoing order for one venue and rewriting a report on its way
 * back to one client are the two things the layered design exists for. The
 * inbound path ran all four layers; the outbound path ran only the session's,
 * because the transport takes a session id and cannot know which destination or
 * client a message belongs to. A gate mounted on a destination therefore never
 * fired on the order path — the seam was there and nothing crossed it.
 */
class OutboundLayersTest {

    private static final int CL_ORD_ID = 11;
    private static final int ON_BEHALF_OF = 115;
    private static final int SYMBOL = 55;
    private static final int SIDE = 54;
    private static final int ORDER_QTY = 38;
    private static final int PRICE = 44;
    private static final int ORD_STATUS = 39;
    private static final int EXEC_TYPE = 150;
    private static final int LEAVES_QTY = 151;
    private static final int CUM_QTY = 14;
    private static final int ORDER_ID = 37;
    private static final int TEXT = 58;
    private static final int ACCOUNT = 1;

    private static final String CLIENT_SESSION = "OMS->FUNDX";
    private static final String VENUE_SESSION = "OMS->LSE";
    private static final String CLIENT_ID = "FUND_X";

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

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("an order on its way to a venue")
    class ToTheVenue {

        @Test
        void crossesTheDestinationLayer() {
            // A venue that requires a field nobody upstream knows about — the
            // reason a per-destination plugin exists at all.
            enrich(Scope.destination(VENUE_SESSION), ACCOUNT, "LSE-ACCT-7");

            send(newOrder("FX-1"));

            assertEquals("LSE-ACCT-7",
                    transport.to(VENUE_SESSION).get(0).field(ACCOUNT),
                    "a destination plugin must be able to enrich what goes to its venue");
        }

        @Test
        void canBeStoppedByTheDestinationLayer() {
            ctx.onGate(TransportEvents.MESSAGE_OUTBOUND, Scope.destination(VENUE_SESSION),
                    (Events.Gate<TransportEvents.InFlight>) (flight, next) ->
                            flight.reject("this venue is not accepting orders"));

            send(newOrder("FX-1"));

            assertEquals(0, transport.to(VENUE_SESSION).size(),
                    "a destination gate must be able to hold a message back");
        }

        @Test
        void stillCrossesTheSessionLayer() {
            // What already worked must keep working.
            enrich(Scope.session(VENUE_SESSION), TEXT, "session-touched");

            send(newOrder("FX-1"));

            assertEquals("session-touched",
                    transport.to(VENUE_SESSION).get(0).field(TEXT));
        }

        @Test
        void crossesDestinationBeforeSession() {
            // Session is the last thing before the wire, so it sees what the
            // destination did and gets the final say.
            List<String> order = new ArrayList<>();
            note(Scope.destination(VENUE_SESSION), order, "destination");
            note(Scope.session(VENUE_SESSION), order, "session");

            send(newOrder("FX-1"));

            assertEquals(List.of("destination", "session"), order);
        }
    }

    @Nested
    @DisplayName("a report on its way back to a client")
    class ToTheClient {

        @Test
        void crossesTheClientLayer() {
            enrich(Scope.client(CLIENT_ID), TEXT, "client-touched");

            String ourId = placeAndAck("FX-1");

            assertEquals("client-touched",
                    transport.to(CLIENT_SESSION).get(0).field(TEXT),
                    "a client plugin must be able to rewrite what its client sees");
        }

        @Test
        void canBeStoppedByTheClientLayer() {
            ctx.onGate(TransportEvents.MESSAGE_OUTBOUND, Scope.client(CLIENT_ID),
                    (Events.Gate<TransportEvents.InFlight>) (flight, next) ->
                            flight.reject("held back from this client"));

            placeAndAck("FX-1");

            assertEquals(0, transport.to(CLIENT_SESSION).size(),
                    "a client gate must be able to hold a report back");
        }

        @Test
        void crossesClientBeforeSession() {
            List<String> order = new ArrayList<>();
            note(Scope.client(CLIENT_ID), order, "client");
            note(Scope.session(CLIENT_SESSION), order, "session");

            placeAndAck("FX-1");

            assertEquals(List.of("client", "session"), order);
        }
    }

    // ------------------------------------------------------------------

    /** Mount a plugin on a layer that adds one field on the way out. */
    private void enrich(Scope scope, int tag, String value) {
        ctx.onGate(TransportEvents.MESSAGE_OUTBOUND, scope,
                (Events.Gate<TransportEvents.InFlight>) (flight, next) ->
                        next.apply(flight.with(flight.message().set(tag, value))));
    }

    /** Mount a plugin that only records that it ran. */
    private void note(Scope scope, List<String> into, String label) {
        ctx.onGate(TransportEvents.MESSAGE_OUTBOUND, scope,
                (Events.Gate<TransportEvents.InFlight>) (flight, next) -> {
                    into.add(label);
                    return next.apply(flight);
                });
    }

    private void send(FixMessage message) {
        transport.deliver(CLIENT_SESSION, message);
    }

    /** Place an order and acknowledge it, so a report goes back to the client. */
    private String placeAndAck(String clOrdId) {
        send(newOrder(clOrdId));
        String ourId = transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);
        transport.clear();
        transport.deliver(VENUE_SESSION, FixMessage.of("8", Map.of(
                CL_ORD_ID, ourId,
                ORDER_ID, "LSE-1",
                EXEC_TYPE, "0",
                ORD_STATUS, "0",
                CUM_QTY, "0",
                LEAVES_QTY, "1000")));
        return ourId;
    }

    private static FixMessage newOrder(String clOrdId) {
        return FixMessage.of("D", Map.of(
                CL_ORD_ID, clOrdId,
                ON_BEHALF_OF, "FUNDX",
                SYMBOL, "VOD",
                SIDE, "1",
                ORDER_QTY, "1000",
                PRICE, "150.00"));
    }
}
