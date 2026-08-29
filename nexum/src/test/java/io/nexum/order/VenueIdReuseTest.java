package io.nexum.order;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.message.FixMessage;
import io.nexum.transport.RecordingTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What happens when a venue reuses an OrderID(37).
 *
 * <p>A restarted venue reissues SIM-1 while this system still remembers the
 * previous one, and {@code resolve} prefers 37 over ClOrdID. A report then
 * matches an order that has nothing to do with it, and the order it was
 * actually about is never told.
 */
class VenueIdReuseTest {

    private static final int CL_ORD_ID = 11;
    private static final int ORDER_ID = 37;
    private static final int ORD_STATUS = 39;
    private static final int EXEC_TYPE = 150;
    private static final int LEAVES_QTY = 151;
    private static final int CUM_QTY = 14;

    private static final String CLIENT = "OMS->FUNDX";
    private static final String VENUE = "OMS->LSE";

    private PluginLoader loader;
    private RecordingTransport transport;
    private OrderCache cache;

    @AfterEach
    void stop() {
        if (loader != null) {
            loader.unloadAll();
        }
    }

    @Test
    @DisplayName("a reused venue id must not steer a report to the wrong order")
    void reusedVenueIdDoesNotMisroute() {
        Context ctx = new Context();
        transport = new RecordingTransport(CLIENT, VENUE);
        loader = Bootstrap.from("""
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
                """).with(transport).start(ctx);
        cache = ctx.get("orders");

        // First order, told about SIM-1, and filled so it settles.
        send(newOrder("FIRST"));
        String firstWireId = transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);
        deliver(report(firstWireId, "0", "0", 0, 1000, "SIM-1"));
        deliver(report(firstWireId, "F", "2", 1000, 0, "SIM-1"));

        // Second order. The venue restarted and hands out SIM-1 again.
        send(newOrder("SECOND"));
        String secondWireId = transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);
        deliver(report(secondWireId, "0", "0", 0, 500, "SIM-1"));

        Order second = cache.byClientClOrdId("SECOND").orElseThrow();
        assertEquals(OrderState.NEW, second.state(),
                "the second order should have been acknowledged, but the report went"
                        + " to whichever order already claimed SIM-1");
    }

    @Test
    @DisplayName("OrderID still matches a report that omits our ClOrdID")
    void venueOrderIdStillMatchesWhenClOrdIdIsAbsent() {
        start();

        send(newOrder("ONLY"));
        String wireId = transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);
        deliver(report(wireId, "0", "0", 0, 1000, "SIM-9"));

        // Now a report carrying only OrderID — an unsolicited fill, or a venue
        // that does not echo ClOrdID. Preferring ClOrdID must not mean ignoring
        // OrderID when there is nothing else.
        deliver(FixMessage.of("8", Map.of(
                ORDER_ID, "SIM-9",
                EXEC_TYPE, "F",
                ORD_STATUS, "2",
                CUM_QTY, "1000",
                LEAVES_QTY, "0")));

        assertEquals(OrderState.FILLED,
                cache.byClientClOrdId("ONLY").orElseThrow().state(),
                "a report with only OrderID should still find its order");
    }

    private void start() {
        Context ctx = new Context();
        transport = new RecordingTransport(CLIENT, VENUE);
        loader = Bootstrap.from("""
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
                """).with(transport).start(ctx);
        cache = ctx.get("orders");
    }

    private void send(FixMessage m) {
        transport.deliver(CLIENT, m);
    }

    private void deliver(FixMessage m) {
        transport.deliver(VENUE, m);
    }

    private static FixMessage newOrder(String clOrdId) {
        return FixMessage.of("D", Map.of(
                CL_ORD_ID, clOrdId, 115, "FUNDX", 55, "VOD",
                54, "1", 38, "1000", 44, "150.00"));
    }

    private static FixMessage report(String clOrdId, String execType, String ordStatus,
                                     double cum, double leaves, String venueId) {
        return FixMessage.of("8", Map.of(
                CL_ORD_ID, clOrdId, ORDER_ID, venueId,
                EXEC_TYPE, execType, ORD_STATUS, ordStatus,
                CUM_QTY, String.valueOf((long) cum),
                LEAVES_QTY, String.valueOf((long) leaves)));
    }
}
