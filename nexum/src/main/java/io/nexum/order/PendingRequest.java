package io.nexum.order;

import java.util.Map;

/**
 * A cancel or replace that has been sent and not yet answered.
 *
 * <p>Held beside the order rather than folded into its state, because the two
 * are independent: an order with a cancel outstanding keeps trading until the
 * venue answers, and a fill arriving in the meantime must not erase the fact
 * that a cancel is still in flight.
 *
 * <p>A replace also carries the terms it is asking for. Until the venue accepts,
 * the order still works on its original terms — so both sets have to exist at
 * once, and reading the new ones as current is how a desk ends up believing it
 * has an exposure it does not have.
 *
 * @param clOrdId what the venue was asked under — ours
 * @param origClOrdId what the venue knows the order by — ours
 * @param clientClOrdId what the CLIENT called this request. Kept because the
 *     confirmation that comes back must be echoed under the client's own
 *     identifier for the request, not the order's: a client matching a cancel
 *     confirmation looks for the id it sent the cancel with.
 */
public record PendingRequest(
        Kind kind,
        String clOrdId,
        String origClOrdId,
        String clientClOrdId,
        long sentAt,
        Map<Integer, String> requestedTerms) {

    public enum Kind {
        CANCEL,
        REPLACE
    }

    public static PendingRequest cancel(
            String clOrdId, String origClOrdId, String clientClOrdId, long sentAt) {
        return new PendingRequest(
                Kind.CANCEL, clOrdId, origClOrdId, clientClOrdId, sentAt, Map.of());
    }

    /**
     * @param requestedTerms the fields the replace is asking to change — price,
     *     quantity, time in force. Not yet in effect.
     */
    public static PendingRequest replace(
            String clOrdId, String origClOrdId, String clientClOrdId, long sentAt,
            Map<Integer, String> requestedTerms) {
        return new PendingRequest(
                Kind.REPLACE, clOrdId, origClOrdId, clientClOrdId, sentAt,
                Map.copyOf(requestedTerms));
    }

    public PendingRequest {
        requestedTerms = Map.copyOf(requestedTerms);
    }

    /** How long the venue has had this request without answering. */
    public long outstandingFor(long now) {
        return now - sentAt;
    }

    public boolean isCancel() {
        return kind == Kind.CANCEL;
    }

    public boolean isReplace() {
        return kind == Kind.REPLACE;
    }

    /** A requested field, or null when this request does not change it. */
    public String requested(int tag) {
        return requestedTerms.get(tag);
    }
}
