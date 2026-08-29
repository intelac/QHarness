package io.nexum.ai;

import io.nexum.order.InboundEvent;
import io.nexum.order.ManagedOrder;
import io.nexum.order.OrderEventType;
import io.nexum.order.OrderState;
import io.nexum.order.OutboundEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an order which never left the building says so, and says why.
 *
 * <p>An agent acting for someone who does not know FIX has only what the tools
 * return. Told an order was "rejected" with no reason, it cannot tell a bad
 * symbol from a venue that refused it from a link that was down — and what it
 * does next is guesswork. The engine knows which of those happened; the
 * difference between a usable answer and a misleading one is whether it says.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SendFailureVisibilityTest {

    private static final int SYMBOL = 55;
    private static final int PRICE = 44;
    private static final int QUANTITY = 38;

    /** An order accepted from a client, before anything was sent onward. */
    private static ManagedOrder accepted() {
        return ManagedOrder.accept("ORD-1", new InboundEvent.ClientOrder(
                1, "OMS->FUNDX", "FUND_X", "CLIENT-1",
                Map.of(SYMBOL, "BP", PRICE, "50", QUANTITY, "1000")));
    }

    @Test
    @DisplayName("an order the transport would not take is not left looking pending")
    void aFailedSendReachesATerminalState() {
        ManagedOrder order = accepted();

        order.on(new InboundEvent.SendFailed(2, "the transport refused the message"));

        // Whatever the state is called, the order must not sit in a state that
        // says "waiting for the venue" — nothing is coming.
        assertTrue(order.snapshot().state() != OrderState.PENDING_NEW,
                "an order that never left cannot be waiting on a venue: "
                        + order.snapshot().state());
    }

    @Test
    @DisplayName("the order's history records that the send failed")
    void theHistoryRecordsTheFailure() {
        ManagedOrder order = accepted();

        List<OutboundEvent> out = order.on(
                new InboundEvent.SendFailed(2, "the transport refused the message"));

        // An order reported as rejected whose history shows only "pending new"
        // gives whoever reads it nothing to act on, and the two disagreeing is
        // worse than either alone.
        OutboundEvent.StateChanged recorded = out.stream()
                .filter(OutboundEvent.StateChanged.class::isInstance)
                .map(OutboundEvent.StateChanged.class::cast)
                .filter(event -> event.cause() == OrderEventType.SEND_FAILED)
                .findFirst().orElse(null);

        assertNotNull(recorded, "the failure belongs in the history: " + out);
    }

    @Test
    @DisplayName("the reason the send failed survives into the record")
    void theReasonSurvives() {
        ManagedOrder order = accepted();

        List<OutboundEvent> out = order.on(
                new InboundEvent.SendFailed(2, "the transport refused the message"));

        // The reason is what separates "your symbol is wrong" from "the link to
        // the market is down" — the first is the caller's to fix, the second is
        // not, and an agent told neither will try to fix the wrong one.
        String recorded = out.stream()
                .filter(OutboundEvent.StateChanged.class::isInstance)
                .map(OutboundEvent.StateChanged.class::cast)
                .filter(event -> event.cause() == OrderEventType.SEND_FAILED)
                .map(OutboundEvent.StateChanged::because)
                .findFirst().orElse(null);

        assertEquals("the transport refused the message", recorded,
                "the reason must reach whoever reads the order");
    }
}
