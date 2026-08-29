package io.nexum.monitor;

import io.nexum.order.OrderState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A check run over the live order book.
 *
 * <p>Rules are evaluated on a schedule rather than on each event, because what
 * they look for is mostly the <em>absence</em> of events — an order nobody has
 * mentioned for a while raises nothing to react to.
 */
@FunctionalInterface
public interface AnomalyRule {

    String STUCK_PENDING = "stuck-pending";
    String SILENT_PARTIAL = "silent-partial";
    String LONG_LIVED = "long-lived";
    String OVERFILL = "overfill";

    /**
     * @param now evaluation time, passed in so a rule is deterministic and can be
     *     tested against a fixed clock
     */
    List<Anomaly> check(List<OrderSnapshot> orders, long now);

    // ------------------------------------------------------------------
    // The rules that catch what actually goes wrong
    // ------------------------------------------------------------------

    /**
     * Sent, never acknowledged.
     *
     * <p>The most consequential failure on the order path: the venue may or may
     * not have the order, and until an ack arrives there is no safe action —
     * cancelling an order that was never accepted and leaving one that was are
     * both wrong.
     */
    static AnomalyRule stuckPending(long thresholdMillis) {
        return (orders, now) -> {
            List<Anomaly> found = new ArrayList<>();
            for (OrderSnapshot order : orders) {
                if (!order.awaitingAck()) {
                    continue;
                }
                long waiting = now - order.createdAt();
                if (waiting > thresholdMillis) {
                    found.add(new Anomaly(
                            STUCK_PENDING,
                            Anomaly.Severity.CRITICAL,
                            order.orderId(),
                            "no acknowledgement after " + (waiting / 1000) + "s",
                            now,
                            Map.of(
                                    "destination", String.valueOf(order.destinationId()),
                                    "ourClOrdId", String.valueOf(order.ourClOrdId()),
                                    "waitingSeconds", String.valueOf(waiting / 1000))));
                }
            }
            return found;
        };
    }

    /**
     * Partially filled, then silence.
     *
     * <p>Distinct from an order that is simply resting: this one was trading and
     * stopped, which points at a dropped report or a venue-side problem rather
     * than at the market.
     */
    static AnomalyRule silentPartial(long thresholdMillis) {
        return (orders, now) -> {
            List<Anomaly> found = new ArrayList<>();
            for (OrderSnapshot order : orders) {
                if (order.state() != OrderState.PARTIALLY_FILLED) {
                    continue;
                }
                long silent = order.silentFor(now);
                if (silent > thresholdMillis) {
                    found.add(new Anomaly(
                            SILENT_PARTIAL,
                            Anomaly.Severity.WARNING,
                            order.orderId(),
                            "partially filled, nothing heard for " + (silent / 1000) + "s",
                            now,
                            Map.of(
                                    "filled", order.cumQty() + "/" + order.orderQty(),
                                    "leaves", String.valueOf(order.leavesQty()),
                                    "silentSeconds", String.valueOf(silent / 1000))));
                }
            }
            return found;
        };
    }

    /** Still working long after it was sent — normal for some strategies, not all. */
    static AnomalyRule longLived(long thresholdMillis) {
        return (orders, now) -> {
            List<Anomaly> found = new ArrayList<>();
            for (OrderSnapshot order : orders) {
                long age = now - order.createdAt();
                if (age > thresholdMillis && !order.isTerminal()) {
                    found.add(new Anomaly(
                            LONG_LIVED,
                            Anomaly.Severity.INFO,
                            order.orderId(),
                            "working for " + (age / 60000) + " minutes",
                            now,
                            Map.of(
                                    "state", order.state().name(),
                                    "filled", order.cumQty() + "/" + order.orderQty())));
                }
            }
            return found;
        };
    }

    /**
     * Filled beyond what was ordered.
     *
     * <p>Should be impossible and therefore matters: it means duplicate reports,
     * a mismatched identifier, or an order counted against the wrong parent.
     * Raised regardless of age, because the quantity is already wrong.
     */
    static AnomalyRule overfill() {
        return (orders, now) -> {
            List<Anomaly> found = new ArrayList<>();
            for (OrderSnapshot order : orders) {
                if (order.orderQty() > 0 && order.cumQty() > order.orderQty()) {
                    found.add(new Anomaly(
                            OVERFILL,
                            Anomaly.Severity.CRITICAL,
                            order.orderId(),
                            "filled " + order.cumQty() + " against an order for "
                                    + order.orderQty(),
                            now,
                            Map.of(
                                    "cumQty", String.valueOf(order.cumQty()),
                                    "orderQty", String.valueOf(order.orderQty()),
                                    "venueOrderId", String.valueOf(order.venueOrderId()))));
                }
            }
            return found;
        };
    }
}
