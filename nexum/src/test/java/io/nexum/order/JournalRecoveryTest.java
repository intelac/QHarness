package io.nexum.order;

import io.nexum.core.Context;
import io.nexum.core.PluginLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a restart actually recovers.
 *
 * <p>Recovery is the kind of thing that looks fine until the day it is needed.
 * These run the real journal and the real replay, then check the recovered order
 * against what it was — not against what was written.
 */
class JournalRecoveryTest {

    private static final int CL_ORD_ID = 11;
    private static final int SYMBOL = 55;
    private static final int ORDER_QTY = 38;
    private static final int PRICE = 44;

    @Test
    @DisplayName("a partially filled order comes back with its quantity")
    void quantitySurvives(@TempDir Path directory) {
        OrderCache before = load(directory);
        Order order = placed("ORD-1", "FUNDX-1", "O0000001");
        before.put(order);
        before.indexOutbound("O0000001", "ORD-1");

        // Three hundred done, seven hundred left.
        journal(directory, new OrderEvent.Created(
                "ORD-1", 1, "OMS->FUNDX", "FUND_X", "OMS->LSE",
                "FUNDX-1", "O0000001",
                Map.of("55", "VOD", "38", "1000"),
                Map.of("55", "VOD", "38", "1000"),
                null, null));
        journal(directory, new OrderEvent.StateChanged(
                "ORD-1", 2, null, OrderState.PARTIALLY_FILLED, null, "1", "300", "300", "150.0", null));

        OrderCache after = load(directory);
        Order recovered = after.byOrderId("ORD-1").orElseThrow();

        assertEquals(OrderState.PARTIALLY_FILLED, recovered.state());
        assertEquals(300, recovered.cumQty(),
                "a quantity lost on replay makes the staleness guard fail, and the"
                        + " next resent report is counted as a fresh fill");
    }

    @Test
    @DisplayName("a resent report after recovery is recognised as stale")
    void aResendAfterRecoveryIsNotCountedTwice(@TempDir Path directory) {
        journal(directory, new OrderEvent.Created(
                "ORD-1", 1, "OMS->FUNDX", "FUND_X", "OMS->LSE",
                "FUNDX-1", "O0000001",
                Map.of("55", "VOD", "38", "1000"),
                Map.of("55", "VOD", "38", "1000"),
                null, null));
        journal(directory, new OrderEvent.StateChanged(
                "ORD-1", 2, null, OrderState.PARTIALLY_FILLED, null, "1", "300", "300", "150.0", null));

        OrderCache after = load(directory);
        ManagedOrder order = ManagedOrder.restore(after.byOrderId("ORD-1").orElseThrow(), 10);

        // The venue resends the same fill it already reported.
        List<OutboundEvent> concluded = order.on(new InboundEvent.VenueReport(
                11, OrderEventType.PARTIAL_FILL, 300, 700.0, "LSE-1", "O0000001", Map.of()));

        assertEquals(300, order.cumQty(), "the fill must not be counted twice");
        assertTrue(concluded.stream().anyMatch(OutboundEvent.Ignored.class::isInstance));
    }

    @Test
    @DisplayName("an outstanding cancel survives the restart")
    void pendingRequestSurvives(@TempDir Path directory) {
        journal(directory, new OrderEvent.Created(
                "ORD-1", 1, "OMS->FUNDX", "FUND_X", "OMS->LSE",
                "FUNDX-1", "O0000001",
                Map.of("55", "VOD", "38", "1000"),
                Map.of("55", "VOD", "38", "1000"),
                null, null));
        journal(directory, new OrderEvent.StateChanged(
                "ORD-1", 2, null, OrderState.PENDING_CANCEL, null, "6", "0", null, null, null));
        journal(directory, new OrderEvent.RequestSent(
                "ORD-1", 3, PendingRequest.Kind.CANCEL, "C0000002", "O0000001", "CLIENT-REQ", Map.of()));

        OrderCache after = load(directory);
        Order recovered = after.byOrderId("ORD-1").orElseThrow();

        assertTrue(recovered.hasPending(),
                "the venue is going to answer this cancel, and an order that has"
                        + " forgotten it cannot make sense of the answer");
        assertEquals("C0000002", recovered.pending().clOrdId());
        assertTrue(recovered.pending().isCancel());
    }

    @Test
    @DisplayName("an answered request is not restored as outstanding")
    void answeredRequestIsCleared(@TempDir Path directory) {
        journal(directory, new OrderEvent.Created(
                "ORD-1", 1, "OMS->FUNDX", "FUND_X", "OMS->LSE",
                "FUNDX-1", "O0000001",
                Map.of("55", "VOD", "38", "1000"),
                Map.of("55", "VOD", "38", "1000"),
                null, null));
        journal(directory, new OrderEvent.RequestSent(
                "ORD-1", 2, PendingRequest.Kind.CANCEL, "C0000002", "O0000001", "CLIENT-REQ", Map.of()));
        journal(directory, new OrderEvent.RequestAnswered("ORD-1", 3, false));

        Order recovered = load(directory).byOrderId("ORD-1").orElseThrow();

        assertFalse(recovered.hasPending(),
                "a request the venue already answered must not come back outstanding");
    }

    @Test
    @DisplayName("a replace's requested terms survive")
    void requestedTermsSurvive(@TempDir Path directory) {
        journal(directory, new OrderEvent.Created(
                "ORD-1", 1, "OMS->FUNDX", "FUND_X", "OMS->LSE",
                "FUNDX-1", "O0000001",
                Map.of("55", "VOD", "38", "1000", "44", "150.00"),
                Map.of("55", "VOD", "38", "1000", "44", "150.00"),
                null, null));
        journal(directory, new OrderEvent.RequestSent(
                "ORD-1", 2, PendingRequest.Kind.REPLACE, "A0000002", "O0000001",
                "CLIENT-REQ", Map.of(ORDER_QTY, "1500", PRICE, "155.00")));

        Order recovered = load(directory).byOrderId("ORD-1").orElseThrow();

        assertTrue(recovered.pending().isReplace());
        assertEquals("155.00", recovered.pending().requested(PRICE),
                "the terms the venue is being asked for are part of the request");
        assertEquals("150.00", recovered.destination().field(PRICE),
                "and the terms in force are still the original ones");
    }

    @Test
    @DisplayName("a truncated segment does not stop the system starting")
    void truncatedSegmentStillStarts(@TempDir Path directory) throws Exception {
        journal(directory, new OrderEvent.Created(
                "ORD-1", 1, "OMS->FUNDX", "FUND_X", "OMS->LSE",
                "FUNDX-1", "O0000001",
                Map.of("55", "VOD"), Map.of("55", "VOD"), null, null));

        // A crash leaves the last line half written.
        Path segment = java.nio.file.Files.list(directory)
                .filter(path -> path.getFileName().toString().endsWith(".journal"))
                .findFirst()
                .orElseThrow();
        java.nio.file.Files.writeString(segment,
                java.nio.file.Files.readString(segment) + "17875\tstat",
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        OrderCache cache = new InMemoryOrderCache();
        SegmentedJournal.Replay replay = SegmentedJournal.replay(directory, cache);

        assertEquals(1, replay.recovered(),
                "refusing to start over a truncated line turns a recoverable"
                        + " situation into an outage");
        assertFalse(replay.skipped().isEmpty(), "what was skipped should be reported");
    }

    // ------------------------------------------------------------------

    private static void journal(Path directory, OrderEvent event) {
        try (SegmentedJournal journal = new SegmentedJournal(directory, true)) {
            journal.append(event);
        }
    }

    private static OrderCache load(Path directory) {
        Context ctx = new Context();
        new PluginLoader(ctx).load(List.of(new OrderCachePlugin(directory, true)));
        return ctx.get("orders");
    }

    private static Order placed(String orderId, String clientClOrdId, String ourClOrdId) {
        Map<Integer, String> fields = Map.of(
                CL_ORD_ID, clientClOrdId, SYMBOL, "VOD", ORDER_QTY, "1000");
        return new Order(
                orderId, "OMS->FUNDX", "FUND_X", "OMS->LSE",
                OrderView.of(clientClOrdId, fields),
                OrderView.of(orderId, Map.of()),
                OrderView.of(ourClOrdId, fields),
                OrderState.PENDING_NEW);
    }
}
