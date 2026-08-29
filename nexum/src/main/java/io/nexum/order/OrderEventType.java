package io.nexum.order;

/**
 * What happened to an order, derived from the message rather than read off
 * OrdStatus(39).
 *
 * <p>OrdStatus is the venue's summary of where it thinks the order stands, and
 * it is not dependable. A Cancel Reject (35=9) echoes the order's existing
 * status rather than reporting the refusal, so an order whose cancel just failed
 * still reads {@code New}. Venues repeat, reorder and occasionally invent
 * statuses. Two different situations — a cancel refused and a replace refused —
 * share one OrdStatus and are distinguished only by another field.
 *
 * <p>ExecType(150) says what the message <em>is</em>, which is the durable fact.
 * The state is then this system's own conclusion, reached from the event and the
 * state it arrives in, rather than a value copied from the wire.
 */
public enum OrderEventType {

    /** We accepted it from the client; nothing has been sent. */
    ACCEPTED_FROM_CLIENT,

    /** We put it on the wire. */
    SENT_TO_VENUE,

    /** The send itself failed — no counterparty ever saw it. */
    SEND_FAILED,

    // --- from the venue ---------------------------------------------------

    /** ExecType=0. Acknowledged and working. */
    ACK,

    /** ExecType=F with quantity remaining. */
    PARTIAL_FILL,

    /** ExecType=F closing the order out, or ExecType=2 on older dialects. */
    FILL,

    /** ExecType=8. The venue refused the order. */
    ORDER_REJECTED,

    /** ExecType=6. A cancel is being processed. */
    CANCEL_PENDING,

    /** ExecType=4. The order is cancelled. */
    CANCELLED,

    /** 35=9 with CxlRejResponseTo=1. The order is still working. */
    CANCEL_REFUSED,

    /** ExecType=E. A replace is being processed. */
    REPLACE_PENDING,

    /** ExecType=5. The replace took effect. */
    REPLACED,

    /** 35=9 with CxlRejResponseTo=2. The original terms still stand. */
    REPLACE_REFUSED,

    /** ExecType=3. No further execution today. */
    DONE_FOR_DAY,

    /** ExecType=9. Held by the venue; may resume. */
    SUSPENDED,

    /** ExecType=C. Time in force ran out. */
    EXPIRED,

    /** ExecType=D: a restatement, carrying no change. */
    RESTATEMENT,

    /** ExecType=I: the answer to an OrderStatusRequest, describing the order as it stands. */
    STATUS_RESPONSE,

    /** ExecType=7. The venue stopped executing — a triggered stop, or an intervention. */
    STOPPED,

    /** ExecType=B. Quantities and average price computed for settlement. */
    CALCULATED,

    /** ExecType=D on some dialects: accepted into a bidding process. */
    ACCEPTED_FOR_BIDDING,

    /**
     * ExecType=G. The venue is correcting a fill it already reported.
     *
     * <p>The cumulative quantity on a correction may be <em>lower</em> than what
     * was previously reported, which is the one case where a position moves
     * backwards legitimately.
     */
    TRADE_CORRECTED,

    /**
     * ExecType=H. The venue is withdrawing a fill it already reported.
     *
     * <p>A busted trade. The quantity comes off the position, and an order that
     * had filled can go back to working.
     */
    TRADE_CANCELLED,

    /** Recognised as an execution report, but of a kind this system does not model. */
    UNKNOWN;

    /**
     * Classify an execution report.
     *
     * <p>ExecType is read first and OrdStatus only consulted where ExecType is
     * absent, which happens on older dialects. A fill is separated from a
     * partial by the quantity left, not by the status the venue chose to attach.
     *
     * @param execType ExecType(150)
     * @param ordStatus OrdStatus(39), used only as a fallback
     * @param leavesQty LeavesQty(151); null when the venue omits it
     */
    /**
     * What this event is called when someone is reading it.
     *
     * <p>FIX has already named these. A new order is what 35=D carries, an
     * acknowledgement is ExecType(150)=0, a cancel request is 35=F — so the
     * names come from the protocol rather than from a vocabulary invented
     * here, and someone reading a history is reading the same words as the
     * specification and the counterparty's onboarding pack.
     *
     * <p>Lower case, where a state's display name is not: seven of these share
     * a name with a state — {@code REPLACED}, {@code EXPIRED}, {@code
     * SUSPENDED} and the rest — and {@code CANCELLED} differs from the state
     * {@code CANCELED} by one letter. Rendered alike, a row reads
     * "REPLACED → REPLACED" and says nothing about which half is which.
     */
    public String label() {
        return switch (this) {
            // What the client sent: 35=D.
            case ACCEPTED_FROM_CLIENT -> "new order single";
            case SENT_TO_VENUE -> "sent to venue";
            case SEND_FAILED -> "send failed";

            // Execution reports, by ExecType(150).
            case ACK -> "execution report: new";
            case PARTIAL_FILL -> "execution report: partial fill";
            case FILL -> "execution report: fill";
            case ORDER_REJECTED -> "execution report: rejected";
            case CANCELLED -> "execution report: cancelled";
            case REPLACED -> "execution report: replaced";
            case DONE_FOR_DAY -> "execution report: done for day";
            case SUSPENDED -> "execution report: suspended";
            case EXPIRED -> "execution report: expired";
            case STOPPED -> "execution report: stopped";
            case CALCULATED -> "execution report: calculated";
            case ACCEPTED_FOR_BIDDING -> "execution report: accepted for bidding";
            case RESTATEMENT -> "execution report: restatement";
            case STATUS_RESPONSE -> "execution report: order status";
            case TRADE_CORRECTED -> "execution report: trade correct";
            case TRADE_CANCELLED -> "execution report: trade cancel";

            // Requests the client sent: 35=F and 35=G.
            case CANCEL_PENDING -> "order cancel request";
            case REPLACE_PENDING -> "order cancel/replace request";

            // Refusals: 35=9, told apart by CxlRejResponseTo(434).
            case CANCEL_REFUSED -> "order cancel reject";
            case REPLACE_REFUSED -> "order cancel/replace reject";

            // Recognised as a report, of a kind this system does not model.
            case UNKNOWN -> "execution report: unrecognised";
        };
    }

    public static OrderEventType fromExecutionReport(
            String execType, String ordStatus, Double leavesQty) {

        if (execType == null || execType.isEmpty()) {
            return fromOrdStatusOnly(ordStatus, leavesQty);
        }
        return switch (execType) {
            case "0" -> ACK;
            case "1" -> PARTIAL_FILL;                       // FIX 4.2 and earlier
            case "2" -> FILL;                               // FIX 4.2 and earlier
            case "3" -> DONE_FOR_DAY;
            case "4" -> CANCELLED;
            case "5" -> OrderEventType.REPLACED;
            case "6" -> CANCEL_PENDING;
            case "7" -> STOPPED;
            case "8" -> ORDER_REJECTED;
            case "9" -> SUSPENDED;
            case "A" -> ACK;                                // PendingNew
            case "B" -> CALCULATED;
            case "C" -> EXPIRED;
            case "D" -> RESTATEMENT;
            case "E" -> REPLACE_PENDING;
            case "G" -> TRADE_CORRECTED;
            case "H" -> TRADE_CANCELLED;
            case "I" -> STATUS_RESPONSE;
            // ExecType=F (Trade) covers both: what separates them is whether
            // anything is left to work, which the venue reports in LeavesQty.
            case "F" -> leavesQty == null || leavesQty > 0 ? PARTIAL_FILL : FILL;
            default -> UNKNOWN;
        };
    }

    /** A cancel/replace reject (35=9), told apart by CxlRejResponseTo(434). */
    public static OrderEventType fromCancelReject(String responseTo) {
        return switch (responseTo == null ? "" : responseTo) {
            case "1" -> CANCEL_REFUSED;
            case "2" -> REPLACE_REFUSED;
            // Without 434 the refusal cannot be attributed. Treated as a refused
            // cancel because that is the more common request and the more
            // dangerous to miss: an order believed cancelled is still working.
            default -> CANCEL_REFUSED;
        };
    }

    private static OrderEventType fromOrdStatusOnly(String ordStatus, Double leavesQty) {
        if (ordStatus == null || ordStatus.isEmpty()) {
            return UNKNOWN;
        }
        return switch (ordStatus) {
            case "0", "A" -> ACK;
            case "1" -> PARTIAL_FILL;
            case "2" -> leavesQty == null || leavesQty <= 0 ? FILL : PARTIAL_FILL;
            case "3" -> DONE_FOR_DAY;
            case "4" -> CANCELLED;
            case "5" -> OrderEventType.REPLACED;
            case "6" -> CANCEL_PENDING;
            case "7" -> STOPPED;
            case "8" -> ORDER_REJECTED;
            case "9" -> SUSPENDED;
            case "B" -> CALCULATED;
            case "C" -> EXPIRED;
            case "D" -> ACCEPTED_FOR_BIDDING;
            case "E" -> REPLACE_PENDING;
            default -> UNKNOWN;
        };
    }
}
