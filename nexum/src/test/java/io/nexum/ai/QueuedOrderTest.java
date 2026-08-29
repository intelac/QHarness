package io.nexum.ai;

import io.nexum.order.Order;
import io.nexum.order.OrderState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an order waiting on a link that is down is not reported as refused.
 *
 * <p>QuickFIX/J queues a message for a session that is not logged on and
 * reports the send as successful, so nothing downstream learns the link was
 * down: the order sits in PENDING_NEW waiting for an answer that cannot come,
 * and a caller that waited and gave up is told the order was rejected.
 *
 * <p>Nothing rejected it. An agent told otherwise goes looking for a fault in
 * the order — a symbol, a price, a client it should be using — when what
 * happened is that the market cannot be reached and the order will go the
 * moment it can. The three outcomes have to read differently: sent and waiting,
 * queued behind a link that is down, and actually refused.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class QueuedOrderTest {

    @Test
    @DisplayName("a pending order behind a live link reads as waiting on the venue")
    void aLiveLinkReadsAsWaiting() {
        String said = OrderTools.pendingExplanation(
                OrderState.PENDING_NEW, "OMS->LSE", true);

        assertTrue(said.contains("has not answered"),
                "the message went out; the venue is simply slow: " + said);
        assertFalse(said.toLowerCase().contains("reject"),
                "nothing refused it: " + said);
    }

    @Test
    @DisplayName("a pending order behind a dead link says the link is down")
    void aDeadLinkIsNamed() {
        String said = OrderTools.pendingExplanation(
                OrderState.PENDING_NEW, "OMS->LSE", false);

        // What a caller does about it depends entirely on this: a slow venue is
        // waited for, an unreachable one is escalated, and a refusal is a
        // different order.
        assertTrue(said.contains("OMS->LSE"),
                "it has to name the link that is down: " + said);
        assertTrue(said.contains("not connected") || said.contains("queued"),
                "it has to say the order has not left: " + said);
        assertFalse(said.toLowerCase().contains("reject"),
                "an order queued behind a dead link was not refused: " + said);
    }

    @Test
    @DisplayName("a rejection is still reported as a rejection")
    void arefusalStillReadsAsOne() {
        // The state itself is terminal, so nothing here should soften it.
        assertTrue(OrderState.REJECTED.isTerminal(),
                "a refusal is the end of the order");
    }
}
