package io.nexum.routing;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.order.OrderCache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an order the router gives up on says so, and says why.
 *
 * <p>An agent placing orders for someone who does not know FIX has only what
 * the tools return. Told nothing, it cannot tell a symbol it got wrong from a
 * client the deployment does not recognise from a market it cannot reach — and
 * a wrong guess sends it off correcting the wrong thing. Each of these is
 * something the router already knows at the moment it decides to stop.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AbandonedOrderTest {

    private Context ctx;
    private PluginLoader loader;
    private final List<Object> unmatched = new CopyOnWriteArrayList<>();

    @BeforeEach
    void bringUp() throws Exception {
        java.nio.file.Path journal = java.nio.file.Files.createTempDirectory("abandoned");
        journal.toFile().deleteOnExit();
        ctx = new Context();
        loader = Bootstrap.from(config(freePort(), freePort(), journal.toString())).start(ctx);

        // Whatever the router decides not to route, it announces here.
        ctx.on(RoutingEvents.RULE_UNMATCHED,
                (RoutingEvents.Unmatched event) -> unmatched.add(event));
    }

    @AfterEach
    void tearDown() {
        if (loader != null) loader.unloadAll();
    }

    @Test
    @DisplayName("an order from a client the deployment does not know is explained")
    void anUnknownClientIsExplained() throws Exception {
        // Tag 115 is how a deployment tells its clients apart, and this one
        // names nobody it knows.
        send("STRANGER", "BP", 1000);

        RoutingEvents.Unmatched said = awaitUnmatched();
        assertFalse(said.reasons().isEmpty(),
                "the router knows why it stopped; it has to say: " + said);
        assertTrue(said.reasons().stream().anyMatch(r -> r != null && !r.isBlank()),
                "a blank reason tells a reader nothing: " + said.reasons());
    }

    @Test
    @DisplayName("what it says names the stage it stopped at")
    void theStageIsNamed() throws Exception {
        send("STRANGER", "BP", 1000);

        RoutingEvents.Unmatched said = awaitUnmatched();
        // Which stage failed decides who fixes it: a client the deployment does
        // not know is configuration, a destination it cannot pick is routing.
        assertTrue(said.stage() == RoutingEvents.Unmatched.Stage.CLIENT,
                "an unrecognised client is a client-stage failure: " + said.stage());
    }

    // ------------------------------------------------------------------

    private void send(String onBehalfOf, String symbol, int quantity) {
        io.nexum.message.FixMessage order = io.nexum.message.FixMessage.of("D",
                java.util.Map.of(
                        io.nexum.message.FixTags.CL_ORD_ID, "ABANDON-1",
                        io.nexum.message.FixTags.SYMBOL, symbol,
                        io.nexum.message.FixTags.SIDE, "1",
                        io.nexum.message.FixTags.ORDER_QTY, String.valueOf(quantity),
                        115, onBehalfOf));
        ctx.emit(io.nexum.transport.TransportEvents.MESSAGE_INBOUND + "/accepted",
                io.nexum.transport.TransportEvents.InFlight.inbound(order, "OMS->FUNDX"));
    }

    private RoutingEvents.Unmatched awaitUnmatched() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!unmatched.isEmpty()) {
                return (RoutingEvents.Unmatched) unmatched.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the router gave up without announcing anything");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String config(int clientPort, int venuePort, String journal) {
        return """
                orders:
                  journal: %s
                  sync: true

                monitor:
                  enabled: false

                sessions:
                  - id: OMS->FUNDX
                    version: FIX.4.4
                    role: acceptor
                    port: %d
                    logPath: target/abandoned/logs
                    persistent: false

                  - id: OMS->LSE
                    version: FIX.4.4
                    role: initiator
                    host: 127.0.0.1
                    port: %d
                    logPath: target/abandoned/logs
                    persistent: false

                clients:
                  - id: FUND_X
                    fingerprint:
                      115: FUNDX

                routes:
                  - destination: OMS->LSE
                    fingerprint: any
                """.formatted(journal, clientPort, venuePort);
    }
}
