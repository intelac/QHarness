package io.nexum.monitor;

import io.nexum.order.OrderState;

import java.util.LinkedHashMap;
import java.util.Map;

/** Projections a deployment usually wants. Others register their own. */
public final class Projections {

    private Projections() {}

    /** Live counts by state — the number an operator glances at first. */
    public static Projection<Map<OrderState, Integer>> byState() {
        return new Projection<>() {
            public String name() {
                return "by-state";
            }

            public Map<OrderState, Integer> initial() {
                return Map.of();
            }

            /** Where each order was last counted, so a move can be subtracted. */
            private final Map<String, OrderState> where = new java.util.HashMap<>();

            public Map<OrderState, Integer> apply(
                    Map<OrderState, Integer> state, OrderSnapshot snapshot, Change change) {

                if (change != Change.CREATED && change != Change.STATE_CHANGED) {
                    return state;
                }
                // An order occupies exactly one state at a time, so a move has
                // to leave the state it came from. Counting arrivals alone turns
                // three orders into one tally per state each has passed through.
                OrderState previous = where.put(snapshot.orderId(), snapshot.state());
                if (snapshot.state() == previous) {
                    return state;
                }
                Map<OrderState, Integer> counts = new LinkedHashMap<>(state);
                if (previous != null) {
                    counts.merge(previous, -1, Integer::sum);
                    counts.values().removeIf(count -> count <= 0);
                }
                counts.merge(snapshot.state(), 1, Integer::sum);
                return Map.copyOf(counts);
            }
        };
    }

    /** Notional and fill totals per client, for a desk view. */
    public static Projection<Map<String, ClientTotals>> byClient() {
        return new Projection<>() {
            public String name() {
                return "by-client";
            }

            public Map<String, ClientTotals> initial() {
                return Map.of();
            }

            /**
             * CumQty(14) is cumulative, so each report restates the whole filled
             * quantity for that order. Adding it to a running total on every
             * report inflates the number quietly — the previous contribution has
             * to come back out first.
             */
            private final Map<String, Double> contributed = new java.util.HashMap<>();

            public Map<String, ClientTotals> apply(
                    Map<String, ClientTotals> state, OrderSnapshot snapshot, Change change) {

                if (change == Change.VENUE_ID_ASSIGNED) {
                    return state;
                }
                Map<String, ClientTotals> totals = new LinkedHashMap<>(state);
                ClientTotals current = totals.getOrDefault(
                        snapshot.clientId(), ClientTotals.EMPTY);

                if (change == Change.CREATED) {
                    contributed.put(snapshot.orderId(), 0.0);
                    totals.put(snapshot.clientId(), current.withNewOrder(snapshot.orderQty()));
                } else {
                    double previous = contributed.getOrDefault(snapshot.orderId(), 0.0);
                    contributed.put(snapshot.orderId(), snapshot.cumQty());
                    totals.put(snapshot.clientId(), current.withFill(
                            snapshot.cumQty(), previous, snapshot.isTerminal()));
                }
                return Map.copyOf(totals);
            }
        };
    }

    public record ClientTotals(int orders, int completed, double ordered, double filled) {

        static final ClientTotals EMPTY = new ClientTotals(0, 0, 0, 0);

        ClientTotals withNewOrder(double qty) {
            return new ClientTotals(orders + 1, completed, ordered + qty, filled);
        }

        /**
         * @param previousCumQty what this order had already contributed, so a
         *     cumulative quantity is not added to the total on every report
         */
        ClientTotals withFill(double cumQty, double previousCumQty, boolean terminal) {
            return new ClientTotals(
                    orders,
                    terminal ? completed + 1 : completed,
                    ordered,
                    filled - previousCumQty + cumQty);
        }
    }
}
