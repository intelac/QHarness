package io.nexum.order;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The identifiers that go on the wire.
 *
 * <p>Short on purpose. The internal identity is three parts and self-describing,
 * which is what a person wants when reading a log; a venue wants something that
 * fits its field limits and parses without surprises. ClOrdID is commonly capped
 * around 20 characters, and a counterparty that truncates rather than rejects
 * produces orders whose reports resolve to the wrong place.
 *
 * <pre>
 *   internal   20260825:OMS-&gt;FUNDX:FX-1
 *   on the wire O0000123      new order
 *               C0000124      cancel
 *               A0000125      replace
 * </pre>
 *
 * <p>The leading letter says what the request was, which makes a session log
 * readable without cross-referencing. The number is one sequence across all
 * three, so no two requests ever share an identifier even across kinds.
 *
 * <p>The link back to the order is the cache's index, not the identifier's
 * shape: nothing decodes these, so their format can change without anything
 * else being taught the new one.
 */
public final class WireIdMinter {

    private static final int DIGITS = 7;

    private final AtomicLong sequence;
    private final String prefix;

    public WireIdMinter() {
        this("");
    }

    /**
     * @param prefix distinguishes instances that share a counterparty, so two
     *     gateways cannot mint the same identifier
     */
    public WireIdMinter(String prefix) {
        this(prefix, 1);
    }

    public WireIdMinter(String prefix, long startAt) {
        this.prefix = prefix;
        this.sequence = new AtomicLong(startAt);
    }

    /** For a new order. */
    public String forOrder() {
        return mint('O');
    }

    /** For a cancel request. */
    public String forCancel() {
        return mint('C');
    }

    /** For a replace request. */
    public String forReplace() {
        return mint('A');
    }

    private String mint(char kind) {
        return prefix + kind + pad(sequence.getAndIncrement());
    }

    /**
     * Resume past identifiers already used.
     *
     * <p>Called after a restart with the highest number recovered from the
     * journal. Reusing one would attach a fresh order to a previous order's
     * reports.
     */
    public void resumeAfter(long highestUsed) {
        sequence.updateAndGet(current -> Math.max(current, highestUsed + 1));
    }

    /** The number inside a minted identifier, or -1 when it is not one of ours. */
    public static long numberOf(String wireId) {
        if (wireId == null || wireId.length() < DIGITS + 1) {
            return -1;
        }
        String digits = wireId.substring(wireId.length() - DIGITS);
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException notOurs) {
            return -1;
        }
    }

    public long next() {
        return sequence.get();
    }

    private static String pad(long number) {
        String digits = Long.toString(number);
        if (digits.length() >= DIGITS) {
            // Past ten million requests in a day the identifier grows rather
            // than wrapping: a longer id is awkward, a reused one is wrong.
            return digits;
        }
        return "0".repeat(DIGITS - digits.length()) + digits;
    }
}
