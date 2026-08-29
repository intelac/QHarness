package io.nexum.monitor;

import io.nexum.core.Context;
import io.nexum.core.Plugin;
import io.nexum.order.Order;
import io.nexum.order.OrderEvents;
import io.nexum.order.OutboundEvent;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mounts the order monitor and runs the anomaly rules on a schedule.
 *
 * <p>Subscribes to order events rather than reading the cache, so nothing here
 * can slow an order down. The rules run on their own thread for the same
 * reason — and because most of what they look for is silence, which no event
 * announces.
 *
 * <p>Anomalies are published, not printed. Whether one becomes a console line,
 * a page or a dashboard row is the alerting layer's decision.
 */
public final class MonitorPlugin implements Plugin {

    /** Emit. One anomaly, the first time it is seen. */
    public static final String ANOMALY = "monitor/anomaly";

    /** Emit. A previously raised anomaly that no longer holds. */
    public static final String ANOMALY_CLEARED = "monitor/anomaly-cleared";

    private final Clock clock;
    private final long checkIntervalMillis;
    private final long retainTerminalMillis;
    private final List<AnomalyRule> rules;

    public MonitorPlugin(
            long checkIntervalMillis, long retainTerminalMillis, List<AnomalyRule> rules) {
        this(Clock.systemUTC(), checkIntervalMillis, retainTerminalMillis, rules);
    }

    /**
     * @param clock supplied so the rules can be tested against a fixed time. A
     *     rule with a four-hour threshold is otherwise only testable by waiting
     *     four hours, which means it is not tested.
     */
    public MonitorPlugin(
            Clock clock,
            long checkIntervalMillis,
            long retainTerminalMillis,
            List<AnomalyRule> rules) {
        this.clock = clock;
        this.checkIntervalMillis = checkIntervalMillis;
        this.retainTerminalMillis = retainTerminalMillis;
        this.rules = List.copyOf(rules);
    }

    /** Sensible thresholds for an equities desk; tune per deployment. */
    public static MonitorPlugin withDefaults() {
        return new MonitorPlugin(
                5_000,
                TimeUnit.MINUTES.toMillis(30),
                List.of(
                        AnomalyRule.stuckPending(TimeUnit.SECONDS.toMillis(10)),
                        AnomalyRule.silentPartial(TimeUnit.MINUTES.toMillis(5)),
                        AnomalyRule.longLived(TimeUnit.HOURS.toMillis(4)),
                        AnomalyRule.overfill()));
    }

    @Override
    public String name() {
        return "order-monitor";
    }

    @Override
    public List<String> provides() {
        return List.of("monitor");
    }

    @Override
    public void apply(Context ctx) {
        OrderMonitor monitor = new OrderMonitor(retainTerminalMillis);
        monitor.register(Projections.byState());
        monitor.register(Projections.byClient());
        ctx.register("monitor", monitor);

        ctx.on(OrderEvents.CREATED, (Order order) ->
                monitor.onCreated(OrderSnapshot.of(order, clock.millis())));

        ctx.on(OrderEvents.VENUE_ID_ASSIGNED, (OutboundEvent.VenueIdLearned learned) ->
                monitor.onVenueId(learned.orderId(), learned.venueOrderId()));

        // The time comes from the event, not from the clock. The report was
        // stamped when it arrived, and reading the clock again here is how a
        // record ends up showing a state change before the report that caused
        // it — and how the anomaly rules became impossible to test.
        ctx.on(OrderEvents.STATE_CHANGED, (OutboundEvent.StateChanged changed) ->
                monitor.onReport(
                        changed.orderId(), changed.to(), changed.cumQty(),
                        changed.orderQty(), changed.at()));

        ctx.on(OrderEvents.QUANTITY_CHANGED, (OutboundEvent.QuantityChanged changed) ->
                monitor.onReport(
                        changed.orderId(), changed.state(), changed.to(), changed.at()));

        ctx.on(OrderEvents.REPORT_UNMATCHED, (OrderEvents.UnmatchedReport miss) ->
                monitor.onUnmatched(
                        miss.venueOrderId(), miss.clOrdId(), miss.sessionId()));

        // Raised anomalies, so a condition that persists is reported once and
        // withdrawn when it clears. A rule that fires every interval trains
        // people to ignore it.
        Map<String, Anomaly> raised = new ConcurrentHashMap<>();

        ctx.effect(() -> {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "order-monitor");
                        thread.setDaemon(true);
                        return thread;
                    });
            scheduler.scheduleWithFixedDelay(
                    () -> sweep(ctx, monitor, raised),
                    checkIntervalMillis,
                    checkIntervalMillis,
                    TimeUnit.MILLISECONDS);
            return scheduler::shutdownNow;
        });
    }

    private void sweep(Context ctx, OrderMonitor monitor, Map<String, Anomaly> raised) {
        try {
            long now = clock.millis();
            List<OrderSnapshot> active = monitor.active();

            List<Anomaly> current = new ArrayList<>();
            for (AnomalyRule rule : rules) {
                current.addAll(rule.check(active, now));
            }

            List<String> stillHolding = new ArrayList<>();
            for (Anomaly anomaly : current) {
                stillHolding.add(anomaly.key());
                if (raised.putIfAbsent(anomaly.key(), anomaly) == null) {
                    ctx.emit(ANOMALY, anomaly);
                }
            }

            raised.keySet().removeIf(key -> {
                if (stillHolding.contains(key)) {
                    return false;
                }
                ctx.emit(ANOMALY_CLEARED, key);
                return true;
            });

            monitor.evictTerminal(now);
        } catch (RuntimeException failure) {
            // A rule that throws must not kill the scheduler; the next sweep
            // should still run.
            ctx.emit("monitor/failed", failure.toString());
        }
    }

    private static double parse(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
