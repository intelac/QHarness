package io.nexum.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the venue trades on price and never reports more than it was asked to.
 *
 * <p>The arithmetic is the point: a client folds cumQty into a position, so a
 * book that double-counts a slice or fills past the order quantity corrupts
 * every downstream number rather than merely failing a request.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MatchingBookTest {

    private static final char BUY = '1';
    private static final char SELL = '2';

    @Test
    @DisplayName("a limit below the market rests instead of trading")
    void anUncrossedBuyRests() {
        MatchingBook book = new MatchingBook();
        // The market opens at 100; a bid of 95 is not marketable.
        List<MatchingBook.Trade> trades = book.place("c1", "SIM-1", "BP", BUY, 1000, 95);

        assertTrue(trades.isEmpty(), "an uncrossed order must not trade");
        assertEquals(1, book.resting().size());
    }

    @Test
    @DisplayName("a limit at or above the market trades straight away")
    void aCrossedBuyTrades() {
        MatchingBook book = new MatchingBook();
        List<MatchingBook.Trade> trades = book.place("c1", "SIM-1", "BP", BUY, 1000, 105);

        assertEquals(1, trades.size());
        MatchingBook.Trade trade = trades.get(0);
        assertEquals(100.0, trade.price(), "it trades at the market, not at its limit");
        assertFalse(trade.complete(), "a slice trades, not the whole order");
        assertTrue(trade.lastQty() < 1000);
    }

    @Test
    @DisplayName("a sell crosses on the other side of the market")
    void aSellCrossesDownward() {
        // A seller crosses by asking at or below the market, the mirror of a buy.
        MatchingBook asking = new MatchingBook();
        assertTrue(asking.place("c1", "SIM-1", "BP", SELL, 100, 95).isEmpty() == false,
                "an offer at 95 should trade into a 100 market");

        MatchingBook holding = new MatchingBook();
        assertTrue(holding.place("c2", "SIM-2", "BP", SELL, 100, 105).isEmpty(),
                "an offer at 105 should rest, not trade into a 100 market");
    }

    @Test
    @DisplayName("cumQty accumulates across fills and stops at the order quantity")
    void cumQtyAccumulatesAndStops() {
        MatchingBook book = new MatchingBook();
        List<MatchingBook.Trade> all = new ArrayList<>(book.place("c1", "SIM-1", "BP", BUY, 1000, 105));

        // Each further move at a crossing price takes another slice.
        for (int move = 0; move < 5; move++) {
            all.addAll(book.reprice("BP", 100));
        }

        double previous = 0;
        for (MatchingBook.Trade trade : all) {
            assertTrue(trade.cumQty() > previous, "cumQty must grow, saw " + trade.cumQty());
            assertTrue(trade.cumQty() <= 1000,
                    "cumQty must never exceed the order: " + trade.cumQty());
            assertEquals(trade.orderQty() - trade.cumQty(), trade.leavesQty(), 1e-9);
            previous = trade.cumQty();
        }
        assertEquals(1000, all.get(all.size() - 1).cumQty(), "the order finishes exactly filled");
        assertTrue(all.get(all.size() - 1).complete());
        assertTrue(book.resting().isEmpty(), "a filled order leaves the book");
    }

    @Test
    @DisplayName("a price move away from the limit stops the fills")
    void aMoveAwayStopsTrading() {
        MatchingBook book = new MatchingBook();
        book.place("c1", "SIM-1", "BP", BUY, 1000, 105);

        // Above the bid: the order is no longer marketable and must sit.
        assertTrue(book.reprice("BP", 110).isEmpty());
        assertEquals(1, book.resting().size());

        // Back within reach and it trades again.
        assertFalse(book.reprice("BP", 104).isEmpty());
    }

    @Test
    @DisplayName("a cancel keeps what already traded")
    void aCancelKeepsTradedQuantity() {
        MatchingBook book = new MatchingBook();
        double traded = book.place("c1", "SIM-1", "BP", BUY, 1000, 105).get(0).cumQty();

        MatchingBook.Resting cancelled = book.cancel("c1").orElseThrow();

        assertEquals(traded, cancelled.cumQty(),
                "cancelling stops the rest; it does not undo what was done");
        assertTrue(book.resting().isEmpty());
    }

    @Test
    @DisplayName("a repriced order trades as soon as the amendment crosses")
    void aReplaceCanCrossImmediately() {
        MatchingBook book = new MatchingBook();
        book.place("c1", "SIM-1", "BP", BUY, 1000, 95);
        assertTrue(book.resting().get(0).cumQty() == 0);

        // Lifting the bid to the market makes it marketable.
        List<MatchingBook.Trade> trades = book.replace("c1", "c2", 1000, 105).orElseThrow();

        assertFalse(trades.isEmpty(), "the amended order should trade");
        assertEquals("c2", trades.get(0).clOrdId(), "later reports name the new client id");
    }

    @Test
    @DisplayName("an amendment keeps the quantity already traded")
    void aReplaceCarriesCumQty() {
        MatchingBook book = new MatchingBook();
        double traded = book.place("c1", "SIM-1", "BP", BUY, 1000, 105).get(0).cumQty();

        book.replace("c1", "c2", 1000, 95).orElseThrow();

        assertEquals(traded, book.find("c2").orElseThrow().cumQty(),
                "an amendment does not reset the position");
    }

    @Test
    @DisplayName("cancelling an order the book does not have answers empty")
    void anUnknownCancelAnswersEmpty() {
        assertTrue(new MatchingBook().cancel("nope").isEmpty());
    }

    @Test
    @DisplayName("a cancel racing a fill never lets cumQty pass the order quantity")
    void aCancelRacingAFillCannotOverfill() throws Exception {
        // The failure this guards against is silent: an unsynchronized book
        // reads leavesQty, decides a slice, and writes it back while a cancel
        // retires the same quantity in between, so the client is told more
        // traded than it ordered and its position is wrong from then on.
        for (int attempt = 0; attempt < 200; attempt++) {
            MatchingBook book = new MatchingBook();
            List<MatchingBook.Trade> seen = java.util.Collections.synchronizedList(new ArrayList<>());
            // The placement itself trades, so its execution belongs in the
            // sequence the assertions below walk; dropping it would make the
            // running total disagree with cumQty for reasons of the test's own
            // making rather than the book's.
            seen.addAll(book.place("c1", "SIM-1", "BP", BUY, 10, 105));

            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);

            Thread filler = new Thread(() -> {
                try {
                    start.await();
                    for (int move = 0; move < 20; move++) {
                        seen.addAll(book.reprice("BP", 100));
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            Thread canceller = new Thread(() -> {
                try {
                    start.await();
                    book.cancel("c1");
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });

            filler.start();
            canceller.start();
            start.countDown();
            filler.join(5000);
            canceller.join(5000);

            if (failure.get() != null) {
                throw new AssertionError("a book operation threw", failure.get());
            }
            for (MatchingBook.Trade trade : seen) {
                assertTrue(trade.cumQty() <= 10,
                        "attempt " + attempt + ": cumQty " + trade.cumQty() + " exceeds the order");
                assertTrue(trade.leavesQty() >= 0,
                        "attempt " + attempt + ": negative leavesQty " + trade.leavesQty());
            }
            // Each execution must advance the position by its own size, with no
            // slice counted twice.
            double running = 0;
            for (MatchingBook.Trade trade : seen) {
                running += trade.lastQty();
                assertEquals(running, trade.cumQty(), 1e-9,
                        "attempt " + attempt + ": cumQty disagrees with the fills that produced it");
            }
        }
    }

    @Test
    @DisplayName("concurrent placements each get their own fills")
    void concurrentPlacementsStayConsistent() throws Exception {
        MatchingBook book = new MatchingBook();
        int orders = 25;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<MatchingBook.Trade> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < orders; i++) {
            String id = "c" + i;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    seen.addAll(book.place(id, "SIM-" + id, "BP", BUY, 100, 105));
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join(5000);

        assertSame(null, failure.get(), "no placement should have thrown");
        for (MatchingBook.Trade trade : seen) {
            assertTrue(trade.cumQty() <= trade.orderQty(),
                    trade.clOrdId() + " overfilled: " + trade.cumQty());
        }
    }
}
