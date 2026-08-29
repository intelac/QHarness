package io.nexum.order;

/**
 * Where an order stands.
 *
 * <p>Wider than OrdStatus(39), because two orders with the same OrdStatus can
 * need opposite handling. An order this system has created but not yet put on
 * the wire and one the venue has not acknowledged both look pending, but the
 * first is ours to retry and the second is not — and an alert that confuses them
 * sends someone chasing a broker about a message that never left.
 *
 * <p>Each constant declares its {@link Phase} and whether a request of ours is
 * {@link Outstanding}. Both are constructor arguments with no default, so a
 * state added later cannot be left unclassified: the five questions the rest of
 * the system asks about a state are answered from those two fields rather than
 * from five hand-maintained lists that a new constant would silently fall
 * outside of.
 */
public enum OrderState {

    /**
     * Accepted from the client, not yet sent. The venue does not know it exists.
     *
     * <p>Ours to resolve: a failure here is a send that did not happen, and the
     * order can be retried or cancelled outright without asking anyone.
     */
    CREATED(Phase.OURS, Outstanding.NOTHING),

    /**
     * On the wire, no acknowledgement yet.
     *
     * <p>The dangerous one. The venue may or may not have it, so neither
     * retrying nor abandoning is safe — resolution needs an OrderStatusRequest
     * or a conversation, not a decision made locally.
     */
    PENDING_NEW(Phase.AWAITING_ACK, Outstanding.THE_ORDER),

    /** Acknowledged and working. Nothing has traded. */
    NEW(Phase.WORKING, Outstanding.NOTHING),

    /** Working, with some quantity done. Further fills are expected. */
    PARTIALLY_FILLED(Phase.WORKING, Outstanding.NOTHING),

    /** A cancel has been sent; the order is still live until the venue answers. */
    PENDING_CANCEL(Phase.WORKING, Outstanding.A_CANCEL),

    /**
     * The venue refused the cancel. The order is <em>still working</em> — the
     * most misread state on the desk, because "cancel rejected" reads like an
     * ending and is not one.
     */
    CANCEL_REJECTED(Phase.WORKING, Outstanding.NOTHING),

    /** A replace has been sent; the original remains live until it is answered. */
    PENDING_REPLACE(Phase.WORKING, Outstanding.A_REPLACE),

    /** The venue refused the replace. The original terms still stand. */
    REPLACE_REJECTED(Phase.WORKING, Outstanding.NOTHING),

    /**
     * The venue accepted a replace and the order now works on the new terms.
     *
     * <p>Not terminal. FIX names this state after what happened to the original
     * terms, which reads like an ending — but the order is live, can trade, and
     * can be cancelled or replaced again.
     */
    REPLACED(Phase.WORKING, Outstanding.NOTHING),

    /**
     * Accepted into a bidding process rather than sent to execute. A basket or
     * program trade sits here until the bid is decided.
     */
    ACCEPTED_FOR_BIDDING(Phase.WORKING, Outstanding.NOTHING),

    /**
     * The venue has stopped working it without ending it — a halt, a compliance
     * hold. It may resume, so it is not terminal.
     */
    SUSPENDED(Phase.HELD, Outstanding.NOTHING),

    /**
     * No further execution today, but the order was not cancelled. A day order
     * that did not fill ends here; a longer-dated one may work again tomorrow.
     */
    DONE_FOR_DAY(Phase.HELD, Outstanding.NOTHING),

    /**
     * The venue stopped executing it — a stop order that triggered, or an
     * intervention. What usually follows is a fill.
     */
    STOPPED(Phase.SETTLING, Outstanding.NOTHING),

    /**
     * Quantities and average price have been calculated for settlement. The
     * order is no longer executing but the record is not final either.
     */
    CALCULATED(Phase.SETTLING, Outstanding.NOTHING),

    // --- finished ---------------------------------------------------------

    /** Fully executed. */
    FILLED(Phase.FINISHED, Outstanding.NOTHING),

    /** Cancelled, by us or by the venue. */
    CANCELED(Phase.FINISHED, Outstanding.NOTHING),

    /** The venue refused the order itself. Nothing was ever working. */
    REJECTED(Phase.FINISHED, Outstanding.NOTHING),

    /** Its time in force ran out. */
    EXPIRED(Phase.FINISHED, Outstanding.NOTHING),

    /**
     * The order never left this system, so no venue ever saw it.
     *
     * <p>Distinct from {@link #REJECTED}, which says a venue looked at the
     * order and refused it. The two call for opposite responses: a refusal is
     * about the order — its symbol, its price, the client it came from — while
     * this is about us, and the same order will go out unchanged once whatever
     * stopped it is fixed. Reporting one as the other sends whoever reads it
     * after a fault that is not there.
     */
    NOT_SENT(Phase.FINISHED, Outstanding.NOTHING);

    // ------------------------------------------------------------------

    /**
     * How far along an order is, and therefore who is expected to act.
     *
     * <p>One phase per state: an order is in exactly one of these at a time.
     */
    public enum Phase {
        /** This system has it and the venue does not. Ours to resolve. */
        OURS,
        /** Sent; the venue has not said whether it has it. Nobody can act safely. */
        AWAITING_ACK,
        /** The venue will execute it. */
        WORKING,
        /** The venue holds it without executing. It may resume. */
        HELD,
        /** No more fills, but reports may still arrive. */
        SETTLING,
        /** Nothing further is expected. */
        FINISHED
    }

    /**
     * Which request of ours, if any, the venue has not yet answered.
     *
     * <p>Separate from {@link Phase} because the two are independent: an order
     * can be working <em>and</em> have a cancel outstanding, and losing either
     * fact loses something the desk needs.
     */
    public enum Outstanding {
        NOTHING,
        /** The order itself — sent, unacknowledged. */
        THE_ORDER,
        A_CANCEL,
        A_REPLACE
    }

    private final Phase phase;
    private final Outstanding outstanding;

    OrderState(Phase phase, Outstanding outstanding) {
        this.phase = phase;
        this.outstanding = outstanding;
    }

    public Phase phase() {
        return phase;
    }

    public Outstanding outstanding() {
        return outstanding;
    }

    // ------------------------------------------------------------------
    // The questions the rest of the system asks
    // ------------------------------------------------------------------

    /**
     * True once no further reports are expected.
     *
     * <p>{@link #DONE_FOR_DAY} and {@link #SUSPENDED} are deliberately excluded:
     * both may resume, and treating them as closed would drop an order from the
     * book while it is still live at the venue.
     */
    /**
     * What this state is called when someone is reading it.
     *
     * <p>The enum names follow FIX, where {@code NEW} means an order the venue
     * has acknowledged and is working — which reads as the opposite of what it
     * is to anyone who has not spent years with the protocol. The display name
     * says what the state actually is.
     */
    /**
     * OrdStatus(39) for this state.
     *
     * <p>Narrower than the states here, because two of them can share a status:
     * a cancel that was refused and one that was never sent both leave an order
     * the venue considers new. That is what OrdStatus can say, and a reject has
     * to carry one — a client reads it to learn what the order is now, having
     * just been told what it is not.
     */
    public String fixOrdStatus() {
        return switch (this) {
            case CREATED, PENDING_NEW -> "A";
            case NEW, CANCEL_REJECTED, REPLACE_REJECTED, REPLACED,
                 ACCEPTED_FOR_BIDDING -> "0";
            case PARTIALLY_FILLED -> "1";
            case FILLED -> "2";
            case DONE_FOR_DAY -> "3";
            case CANCELED, NOT_SENT -> "4";
            case PENDING_CANCEL -> "6";
            case STOPPED -> "7";
            case REJECTED -> "8";
            case SUSPENDED -> "9";
            case CALCULATED -> "B";
            case EXPIRED -> "C";
            case PENDING_REPLACE -> "E";
        };
    }

    public String label() {
        return switch (this) {
            case CREATED -> "created";
            case PENDING_NEW -> "pending new";
            case NEW -> "on market";
            case PARTIALLY_FILLED -> "partial fill";
            case PENDING_CANCEL -> "pending cancel";
            case CANCEL_REJECTED -> "cancel rejected";
            case PENDING_REPLACE -> "pending amend";
            case REPLACE_REJECTED -> "amend rejected";
            case REPLACED -> "amended";
            case ACCEPTED_FOR_BIDDING -> "accepted for bidding";
            case SUSPENDED -> "suspended";
            case DONE_FOR_DAY -> "done for day";
            case STOPPED -> "stopped";
            case CALCULATED -> "calculated";
            case FILLED -> "fully filled";
            case CANCELED -> "cancelled";
            case REJECTED -> "rejected";
            case NOT_SENT -> "not sent";
            case EXPIRED -> "expired";
        };
    }

    public boolean isTerminal() {
        return phase == Phase.FINISHED;
    }

    /** True while the venue is willing to execute it. */
    public boolean isWorking() {
        return phase == Phase.WORKING;
    }

    /**
     * True while the venue may still send reports, even though the order is not
     * executing.
     *
     * <p>Evicting one of these would leave its remaining reports unmatched.
     */
    public boolean isSettling() {
        return phase == Phase.SETTLING;
    }

    /** True while the venue holds it without executing, and may resume. */
    public boolean isHeld() {
        return phase == Phase.HELD;
    }

    /**
     * True while this system, not the venue, is the one that must act.
     *
     * <p>What lets an alert say whether to look at our own send path or to call
     * the broker.
     */
    public boolean isOurs() {
        return phase == Phase.OURS;
    }

    /** True while the outcome of something already sent is unknown. */
    public boolean isInFlight() {
        return outstanding != Outstanding.NOTHING;
    }

    /** True while a cancel or replace of ours awaits an answer. */
    public boolean awaitsRequestAnswer() {
        return outstanding == Outstanding.A_CANCEL || outstanding == Outstanding.A_REPLACE;
    }

    /**
     * True while the venue could still act on it — working, held, or with a
     * request in flight.
     *
     * <p>The question a cancel asks: a halted order is still on the venue's
     * book, and a halt is exactly when someone wants it gone.
     */
    public boolean isCancellable() {
        return phase == Phase.WORKING || phase == Phase.HELD || phase == Phase.AWAITING_ACK;
    }
}
