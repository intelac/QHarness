package io.nexum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The identifiers that leave the building. */
class WireIdMinterTest {

    @Test
    @DisplayName("short enough for a field that is commonly capped at twenty")
    void fitsTheField() {
        WireIdMinter minter = new WireIdMinter();

        String id = minter.forOrder();
        assertEquals(8, id.length());
        assertTrue(id.length() <= 20,
                "a counterparty that truncates rather than rejects produces orders"
                        + " whose reports resolve to the wrong place");
    }

    @Test
    @DisplayName("the kind is readable without cross-referencing")
    void kindIsVisible() {
        WireIdMinter minter = new WireIdMinter();

        assertTrue(minter.forOrder().startsWith("O"));
        assertTrue(minter.forCancel().startsWith("C"));
        assertTrue(minter.forReplace().startsWith("A"));
    }

    @Test
    @DisplayName("one sequence across all three kinds")
    void neverRepeatsAcrossKinds() {
        WireIdMinter minter = new WireIdMinter();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            assertTrue(seen.add(minter.forOrder()));
            assertTrue(seen.add(minter.forCancel()));
            assertTrue(seen.add(minter.forReplace()));
        }
        assertEquals(300, seen.size());
    }

    @Test
    @DisplayName("a prefix keeps two gateways apart")
    void prefixesSeparateInstances() {
        assertNotEquals(
                new WireIdMinter("GW1-").forOrder(),
                new WireIdMinter("GW2-").forOrder());
    }

    @Test
    @DisplayName("a restart resumes past what was already sent")
    void resumesAfterARestart() {
        WireIdMinter before = new WireIdMinter();
        String last = null;
        for (int i = 0; i < 50; i++) {
            last = before.forOrder();
        }

        // Recovered from the journal, as the pipeline does on startup.
        WireIdMinter after = new WireIdMinter();
        after.resumeAfter(WireIdMinter.numberOf(last));

        // Reusing an identifier would attach a fresh order to a previous
        // order's reports.
        assertTrue(WireIdMinter.numberOf(after.forOrder())
                        > WireIdMinter.numberOf(last),
                "the new sequence overlapped the old one");
    }

    @Test
    @DisplayName("the number can be read back out")
    void numberIsRecoverable() {
        WireIdMinter minter = new WireIdMinter();
        minter.forOrder();
        minter.forOrder();

        assertEquals(3, WireIdMinter.numberOf(minter.forOrder()));
    }

    @Test
    @DisplayName("an identifier from elsewhere is not mistaken for ours")
    void foreignIdentifiersAreRejected() {
        assertEquals(-1, WireIdMinter.numberOf("SOME-BROKER-ID"));
        assertEquals(-1, WireIdMinter.numberOf("O123"));
        assertEquals(-1, WireIdMinter.numberOf(null));
    }

    @Test
    @DisplayName("beyond ten million a day it grows rather than wrapping")
    void growsRatherThanWrapping() {
        WireIdMinter minter = new WireIdMinter("", 9_999_999);

        assertEquals("O9999999", minter.forOrder());
        // A longer identifier is awkward; a reused one is wrong.
        assertEquals("O10000000", minter.forOrder());
    }

    @Test
    @DisplayName("concurrent minting never repeats")
    void concurrentMintingIsUnique() throws Exception {
        WireIdMinter minter = new WireIdMinter();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        int perThread = 500;
        int threads = 8;
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            seen.add(minter.forOrder());
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }

        // Sessions run on their own threads, so two orders can be minted at the
        // same instant on different connections.
        assertEquals(threads * perThread, seen.size(),
                "two requests were given the same identifier");
    }
}
