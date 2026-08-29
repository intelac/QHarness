package io.nexum.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The venue's order book, matched against a market price.
 *
 * <p>Holds the resting orders and decides what trades. It is deliberately free
 * of FIX: an order arrives as quantities and a limit, and a decision comes back
 * as a list of {@link Trade}. That keeps the matching rules testable without a
 * socket, a session, or a dictionary, and leaves {@link SimVenue} responsible
 * only for turning those decisions into execution reports.
 *
 * <p>Every method is synchronized on the book. Matching reads an order's
 * remaining quantity, decides how much of it trades, and writes the result
 * back; run concurrently with a cancel, the unsynchronized version of that
 * sequence emits a fill for quantity a cancel had already retired, and the
 * client is told more traded than it ever ordered. The lock is coarse because
 * the book is small and the alternative — a lock per order — still has to be
 * held across the same read-decide-write, while making the cancel path race
 * the price path for two locks in an order neither one controls.
 */
public final class MatchingBook {

    /** One order resting on the book. */
    public record Resting(
            String clOrdId, String orderId, String symbol, char side,
            double orderQty, double limitPrice, double cumQty) {

        /** What has not traded yet, which is what a cancel retires. */
        public double leavesQty() {
            return orderQty - cumQty;
        }
    }

    /**
     * One execution the book decided on.
     *
     * @param cumQty the order's total traded quantity after this execution, not
     *     this execution's own size — the client tracks its position from it,
     *     so it accumulates rather than restating {@code lastQty}.
     */
    public record Trade(
            String clOrdId, String orderId, String symbol, char side,
            double orderQty, double lastQty, double cumQty, double leavesQty,
            double price, boolean complete) {}

    private final Map<String, Resting> book = new LinkedHashMap<>();
    private final Map<String, Double> prices = new LinkedHashMap<>();

    /** The price a symbol trades at until something moves it. */
    private static final double OPENING_PRICE = 100.0;

    /**
     * How much of a marketable order trades at once.
     *
     * <p>Below one, so an order that crosses is filled over several executions
     * rather than in one report. A venue that always fills completely never
     * produces the partial-fill sequence where cumQty accumulates across
     * reports, which is the arithmetic most often gotten wrong downstream.
     */
    private static final double SLICE = 0.4;

    /** The market price for a symbol, opening at a default until it moves. */
    public synchronized double price(String symbol) {
        return prices.computeIfAbsent(symbol, unused -> OPENING_PRICE);
    }

    /**
     * Move a symbol's price and match every order the move crosses.
     *
     * @return the executions the move caused, in the order they happened.
     */
    public synchronized List<Trade> reprice(String symbol, double price) {
        prices.put(symbol, price);
        return matchSymbol(symbol);
    }

    /**
     * Take a new order.
     *
     * <p>The order rests whether or not it trades, so a later price move or a
     * cancel has something to act on; a fully filled one is removed as part of
     * matching.
     *
     * @return the executions it caused immediately, empty when it rests without
     *     trading.
     */
    public synchronized List<Trade> place(
            String clOrdId, String orderId, String symbol, char side,
            double orderQty, double limitPrice) {

        book.put(clOrdId, new Resting(clOrdId, orderId, symbol, side, orderQty, limitPrice, 0));
        return matchSymbol(symbol);
    }

    /** One resting order, for a cancel or replace that has to answer about it. */
    public synchronized Optional<Resting> find(String clOrdId) {
        return Optional.ofNullable(book.get(clOrdId));
    }

    /**
     * Retire an order's untraded quantity.
     *
     * <p>What already traded survives: cancelling stops the rest, it does not
     * undo what was done.
     *
     * @return the order as it stood when cancelled, or empty when the book does
     *     not have it — already filled, already cancelled, or never known.
     */
    public synchronized Optional<Resting> cancel(String clOrdId) {
        return Optional.ofNullable(book.remove(clOrdId));
    }

    /**
     * Amend an order, keeping what it has already traded.
     *
     * <p>The amended order takes the new client id, because a replace mints one
     * and every later message names that one instead.
     *
     * @return the executions the amendment caused — a repriced order that now
     *     crosses trades immediately — or empty when the original is unknown.
     */
    public synchronized Optional<List<Trade>> replace(
            String origClOrdId, String clOrdId, double orderQty, double limitPrice) {

        Resting original = book.remove(origClOrdId);
        if (original == null) {
            return Optional.empty();
        }
        book.put(clOrdId, new Resting(clOrdId, original.orderId(), original.symbol(),
                original.side(), orderQty, limitPrice, original.cumQty()));
        return Optional.of(matchSymbol(original.symbol()));
    }

    /** Every order still working, for a status view or a test. */
    public synchronized List<Resting> resting() {
        return List.copyOf(book.values());
    }

    // ------------------------------------------------------------------

    /**
     * Trade every order on a symbol that the current price crosses.
     *
     * <p>A buy crosses when its limit is at or above the market, a sell when it
     * is at or below. Each crossing order gives up one slice per pass, so a
     * large order fills over several price moves rather than at once, and the
     * remainder keeps resting.
     */
    private List<Trade> matchSymbol(String symbol) {
        double market = price(symbol);
        List<Trade> trades = new ArrayList<>();

        for (Resting order : List.copyOf(book.values())) {
            if (!order.symbol().equals(symbol) || !crosses(order, market)) {
                continue;
            }

            // Never more than what is left: the slice is a fraction of the
            // original quantity, so on the last pass it would otherwise fill
            // past the order and report a position the client never asked for.
            double lastQty = Math.min(Math.ceil(order.orderQty() * SLICE), order.leavesQty());
            if (lastQty <= 0) {
                continue;
            }

            double cumQty = order.cumQty() + lastQty;
            double leavesQty = order.orderQty() - cumQty;
            boolean complete = leavesQty <= 0;

            if (complete) {
                book.remove(order.clOrdId());
            } else {
                book.put(order.clOrdId(), new Resting(order.clOrdId(), order.orderId(),
                        order.symbol(), order.side(), order.orderQty(), order.limitPrice(), cumQty));
            }

            trades.add(new Trade(order.clOrdId(), order.orderId(), order.symbol(), order.side(),
                    order.orderQty(), lastQty, cumQty, Math.max(leavesQty, 0), market, complete));
        }
        return trades;
    }

    /** Whether an order's limit is on the trading side of the market price. */
    private static boolean crosses(Resting order, double market) {
        return order.side() == '1' ? order.limitPrice() >= market : order.limitPrice() <= market;
    }
}
