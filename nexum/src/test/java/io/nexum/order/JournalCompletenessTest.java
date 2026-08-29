package io.nexum.order;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.transport.TransportEvents;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the journal accounts for the state an order is reported to be in.
 *
 * <p>An order's state is meant to be recoverable from the journal: that is what
 * makes the journal the record and the cache a convenience. A state the journal
 * cannot account for breaks that in the way that matters — the order reads as
 * refused, and nothing on disk says when or by whom, so the question "why was
 * this refused" has no answer at all.
 *
 * <p>It also misleads whoever is reading. An order the market never saw, held
 * because the link was down, is reported as refused; an agent goes looking for
 * a fault in the order, and the journal offers nothing to correct it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JournalCompletenessTest {

    private Context ctx;
    private PluginLoader loader;
    private Path journalDirectory;

    @BeforeEach
    void bringUp() throws Exception {
        journalDirectory = Files.createTempDirectory("journal-completeness");
        journalDirectory.toFile().deleteOnExit();
        ctx = new Context();
        // The venue port has nothing behind it, so the session never logs on
        // and anything sent to it is queued rather than delivered.
        loader = Bootstrap.from(
                config(freePort(), freePort(), journalDirectory.toString())).start(ctx);
    }

    @AfterEach
    void tearDown() {
        if (loader != null) loader.unloadAll();
    }

    @Test
    @DisplayName("a state the order reached is accounted for on disk")
    void everyStateHasARecord() throws Exception {
        placeOrder("JC-1");

        OrderCache cache = ctx.get("orders");
        Order order = awaitOrder(cache, "JC-1");

        // Whatever state it settled in, the journal has to explain it. A
        // 'created' line alone says the order exists and nothing about how it
        // came to be refused.
        String journal = readJournal();
        if (order.state() == OrderState.NEW || order.state() == OrderState.PENDING_NEW) {
            return; // Still waiting is a state the created record does explain.
        }
        assertTrue(journal.lines().count() > 1,
                "the order reads as " + order.state()
                        + " but the journal holds only its creation: " + journal);
    }

    @Test
    @DisplayName("an order the market never saw is not recorded as refused")
    void aQueuedOrderIsNotRecordedAsRefused() throws Exception {
        placeOrder("JC-2");

        OrderCache cache = ctx.get("orders");
        Order order = awaitOrder(cache, "JC-2");

        // The link is down, so the order never left: nothing has refused it,
        // and calling that a refusal sends its reader after the wrong fault —
        // a symbol, a price, a client — when the market never saw the order.
        assertFalse(order.state() == OrderState.REJECTED,
                "nothing refused this order; the link to the market is merely down");
        assertTrue(order.state() == OrderState.NOT_SENT,
                "an order that never left has its own ending: " + order.state());
        assertTrue(order.state().isTerminal(),
                "nothing will carry it now, so it is over");
    }

    // ------------------------------------------------------------------

    private void placeOrder(String clOrdId) {
        FixMessage order = FixMessage.of("D", Map.of(
                FixTags.CL_ORD_ID, clOrdId,
                FixTags.SYMBOL, "BP",
                FixTags.SIDE, "1",
                FixTags.ORDER_QTY, "1000",
                FixTags.ORD_TYPE, "2",
                FixTags.PRICE, "50",
                115, "FUNDX"));
        ctx.emit(TransportEvents.MESSAGE_INBOUND + "/accepted",
                TransportEvents.InFlight.inbound(order, "OMS->FUNDX"));
    }

    private Order awaitOrder(OrderCache cache, String clOrdId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            java.util.Optional<Order> found = cache.byClientClOrdId(clOrdId);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the order never reached the cache");
    }

    private String readJournal() throws Exception {
        try (var files = Files.list(journalDirectory)) {
            List<Path> segments = files
                    .filter(path -> path.toString().endsWith(".journal"))
                    .toList();
            StringBuilder text = new StringBuilder();
            for (Path segment : segments) {
                text.append(Files.readString(segment));
            }
            return text.toString();
        }
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
                    logPath: target/journal-completeness/logs
                    persistent: false

                  - id: OMS->LSE
                    version: FIX.4.4
                    role: initiator
                    host: 127.0.0.1
                    port: %d
                    logPath: target/journal-completeness/logs
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
