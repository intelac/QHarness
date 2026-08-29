package io.nexum.order;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.message.FixMessage;
import io.nexum.transport.RecordingTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the whole path actually writes down.
 *
 * <p>There was a gap here: the tests that drove a full order used no journal,
 * and the tests that used a journal wrote events into it by hand. So nothing
 * checked what a real order flow records — and every venue id was journalled
 * twice, once by the handler and once again by the caching decorator, for as
 * long as both existed. Replay tolerated it, which is why it stayed invisible.
 */
class JournalContentTest {

    private static final int CL_ORD_ID = 11;
    private static final int ORDER_ID = 37;
    private static final int ORD_STATUS = 39;
    private static final int EXEC_TYPE = 150;
    private static final int LEAVES_QTY = 151;
    private static final int CUM_QTY = 14;
    private static final int ON_BEHALF_OF = 115;

    private static final String CLIENT_SESSION = "OMS->FUNDX";
    private static final String VENUE_SESSION = "OMS->LSE";

    private PluginLoader loader;
    private RecordingTransport transport;

    @AfterEach
    void stop() {
        if (loader != null) {
            loader.unloadAll();
        }
    }

    @Test
    @DisplayName("a venue id is recorded once, not once per layer that noticed it")
    void theVenueIdIsRecordedOnce(@TempDir Path directory) throws Exception {
        start(directory);

        send(newOrder("FX-1", "VOD", 1000));
        String ourId = transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);

        // An ack and a fill, both carrying the same OrderID(37). The id is
        // learned once; the second report must not re-announce it.
        deliver(execReport(ourId, "0", "0", 0, 1000, "SIM-1"));
        deliver(execReport(ourId, "F", "2", 1000, 0, "SIM-1"));

        List<String> venueIds = journalLines(directory).stream()
                .filter(line -> line.contains("\tvenue-id\t"))
                .toList();

        assertEquals(1, venueIds.size(),
                "the venue id should be journalled once, but was: " + venueIds);
    }

    @Test
    @DisplayName("the order's life is recorded in order")
    void theWholeLifeIsRecorded(@TempDir Path directory) throws Exception {
        start(directory);

        send(newOrder("FX-2", "VOD", 1000));
        String ourId = transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);
        deliver(execReport(ourId, "0", "0", 0, 1000, "SIM-2"));
        deliver(execReport(ourId, "F", "2", 1000, 0, "SIM-2"));

        List<String> types = journalLines(directory).stream()
                .map(line -> line.split("\t")[1])
                .toList();

        // Created before anything else: an order the venue knows about and this
        // system has not recorded cannot be recovered.
        assertEquals("created", types.get(0));
        assertTrue(types.contains("venue-id"));
        assertTrue(types.contains("state"),
                "state changes should be recorded, but got " + types);
    }

    @Test
    @DisplayName("a report records what the client was told, not only what the venue said")
    void aReportKeepsBothHalves(@TempDir Path directory) throws Exception {
        start(directory);

        send(newOrder("FX-9", "VOD", 1000));
        String ourId = transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);
        deliver(execReport(ourId, "F", "1", 400, 600, "SIM-9"));

        String state = journalLines(directory).stream()
                .filter(line -> line.contains("\tstate\t"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no state entry was written"));

        // The two are not the same message — the identifiers are translated
        // back on the way out — and only the second is what a client disputes.
        // An entry holding just the venue's half answers what the venue said
        // and not what our client saw.
        assertTrue(state.contains("m." + CL_ORD_ID + "=" + ourId),
                "the venue's half should name the id the venue was given: " + state);
        assertTrue(state.contains("c." + CL_ORD_ID + "=FX-9"),
                "the client's half should name the id the client sent: " + state);
    }

    @Test
    @DisplayName("a request records the message that went out")
    void aRequestKeepsItsMessage(@TempDir Path directory) throws Exception {
        start(directory);

        send(newOrder("FX-3", "VOD", 1000));
        String ourId = transport.lastOfType("D").orElseThrow().field(CL_ORD_ID);
        deliver(execReport(ourId, "0", "0", 0, 1000, "SIM-3"));

        send(cancelRequest("FX-3-CXL", "FX-3"));

        String request = journalLines(directory).stream()
                .filter(line -> line.contains("\trequest\t"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the cancel was not recorded"));

        // Both sides. What went to the venue is the message a dispute is
        // argued over, and it was the one thing the journal did not keep —
        // a cancel opened in the monitor showed no message at all.
        assertTrue(request.contains("c.11=FX-3-CXL"),
                "the client's own request should be recorded: " + request);
        assertTrue(request.contains("d.11=" + wireIdOfCancel(directory)),
                "the message sent to the venue should be recorded: " + request);
        assertTrue(request.contains("d.41="),
                "OrigClOrdID is what the venue matches the cancel on");
    }

    /** The identifier this system put on the cancel it sent. */
    private String wireIdOfCancel(Path directory) throws Exception {
        return transport.lastOfType("F").orElseThrow().field(CL_ORD_ID);
    }

    // ------------------------------------------------------------------

    private void start(Path directory) {
        Context ctx = new Context();
        transport = new RecordingTransport(CLIENT_SESSION, VENUE_SESSION);
        loader = Bootstrap.from("""
                monitor:
                  enabled: false

                orders:
                  journal: %s
                  sync: true

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
                """.formatted(directory.resolve("journal")))
                .with(transport)
                .start(ctx);
    }

    private static List<String> journalLines(Path directory) throws Exception {
        Path journal = directory.resolve("journal");
        try (var files = Files.list(journal)) {
            Path segment = files
                    .filter(p -> p.getFileName().toString().endsWith(".journal"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no journal segment in " + journal));
            return Files.readAllLines(segment);
        }
    }

    private void send(FixMessage message) {
        transport.deliver(CLIENT_SESSION, message);
    }

    private void deliver(FixMessage message) {
        transport.deliver(VENUE_SESSION, message);
    }

    private static FixMessage newOrder(String clOrdId, String symbol, double qty) {
        return FixMessage.of("D", Map.of(
                CL_ORD_ID, clOrdId,
                ON_BEHALF_OF, "FUNDX",
                55, symbol,
                54, "1",
                38, String.valueOf((long) qty),
                44, "150.00"));
    }

    private static FixMessage cancelRequest(String clOrdId, String origClOrdId) {
        return FixMessage.of("F", Map.of(
                CL_ORD_ID, clOrdId,
                41, origClOrdId,
                ON_BEHALF_OF, "FUNDX",
                55, "VOD",
                54, "1"));
    }

    private static FixMessage execReport(
            String clOrdId, String execType, String ordStatus,
            double cumQty, double leavesQty, String venueOrderId) {

        return FixMessage.of("8", Map.of(
                CL_ORD_ID, clOrdId,
                ORDER_ID, venueOrderId,
                EXEC_TYPE, execType,
                ORD_STATUS, ordStatus,
                CUM_QTY, String.valueOf((long) cumQty),
                LEAVES_QTY, String.valueOf((long) leavesQty)));
    }
}
