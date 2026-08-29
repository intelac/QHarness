package io.nexum.order;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The state an order is in, concluded from the events it has seen.
 *
 * <p>The venue's OrdStatus(39) is a summary it chose to send, and it is not
 * dependable: a Cancel Reject echoes the order's old status rather than
 * reporting the refusal, resends replay history, and one status can cover two
 * situations needing opposite handling. Copying it would make this system's view
 * a mirror of the venue's guesses.
 *
 * <p>So the state is derived here. The same event means different things in
 * different states — a cancel refusal on a working order leaves it working, the
 * same refusal on an order that has since filled is noise — and that pairing is
 * what this class encodes.
 */
public final class OrderStateMachine {

    /** What an event should do to the order it arrived for. */
    public sealed interface Decision {

        /** The order moves. */
        record Advance(OrderState to, String because) implements Decision {}

        /**
         * Recognised, but describing a point the order has already passed.
         * Ordinary during a resend; recorded so a run of them is visible.
         */
        record Stale(OrderState current, OrderEventType event, String why)
                implements Decision {}

        /**
         * Cannot follow from here. The venue and this system disagree about the
         * order — nothing is applied, and it is raised.
         */
        record Illegal(OrderState current, OrderEventType event, String why)
                implements Decision {}

        /** Nothing new; the state stands. */
        record Unchanged(OrderState current, String why) implements Decision {}
    }

    private OrderStateMachine() {}

    /**
     * Decide what an event means for an order in {@code current}.
     *
     * @param cumQty CumQty(14) from the report
     * @param knownCumQty what this order was already filled for
     */
    public static Decision decide(
            OrderState current, OrderEventType event, double cumQty, double knownCumQty) {
        return decide(current, event, cumQty, knownCumQty, null);
    }

    /**
     * Decide what an event means, taking into account any request already
     * outstanding.
     *
     * <p>The pending request matters because a fill does not answer it. An order
     * that trades while a cancel is in flight is still awaiting that cancel's
     * outcome, and a state machine that moved it to PartiallyFilled would have
     * quietly forgotten a request the venue is still going to answer.
     *
     * @param pending a cancel or replace awaiting an answer, or null
     */
    public static Decision decide(
            OrderState current,
            OrderEventType event,
            double cumQty,
            double knownCumQty,
            PendingRequest pending) {

        if (event == OrderEventType.RESTATEMENT) {
            return new Decision.Unchanged(current, "restatement carries no state change");
        }
        if (event == OrderEventType.UNKNOWN) {
            return new Decision.Illegal(current, event,
                    "execution report of a kind this system does not model");
        }

        // Terminal orders are done being told things. Almost every such report
        // is a resend replaying what happened before the order closed; treating
        // them as disagreements would bury the real ones.
        if (current.isTerminal() && !isTerminalEvent(event)) {
            return new Decision.Stale(current, event,
                    "order finished as " + current + "; this predates that");
        }

        return switch (event) {
            case ACCEPTED_FROM_CLIENT -> new Decision.Illegal(current, event,
                    "the order already exists");

            case SENT_TO_VENUE -> current.isOurs()
                    ? new Decision.Advance(OrderState.PENDING_NEW, "on the wire")
                    : new Decision.Stale(current, event, "already sent");

            // Ours to resolve: nothing reached a venue, so the order can be
            // closed out locally without asking anyone.
            //
            // Valid from PendingNew as well as Created, because the order is
            // marked as sent before the send is attempted — the failure arrives
            // when it already reads as in flight.
            case SEND_FAILED -> current.isOurs() || current == OrderState.PENDING_NEW
                    ? new Decision.Advance(OrderState.NOT_SENT, "never left this system")
                    : new Decision.Illegal(current, event,
                            "a send cannot fail for an order the venue has acknowledged");

            case ACK -> switch (current) {
                // A fill can arrive before the ack that describes the same
                // order. Advancing to New then would erase quantity the venue
                // has already reported.
                case CREATED, PENDING_NEW -> knownCumQty > 0
                        ? new Decision.Stale(current, event,
                                "already filled " + knownCumQty + "; this ack predates that")
                        : new Decision.Advance(OrderState.NEW, "acknowledged by the venue");
                // An ack while a cancel or replace is outstanding is the venue
                // saying the order is still working, not a step backwards.
                case PENDING_CANCEL, PENDING_REPLACE, SUSPENDED, DONE_FOR_DAY,
                     CANCEL_REJECTED, REPLACE_REJECTED ->
                        new Decision.Advance(OrderState.NEW, "working again");
                case NEW -> new Decision.Unchanged(current, "already acknowledged");
                case PARTIALLY_FILLED ->
                        new Decision.Stale(current, event, "already trading");
                default -> new Decision.Illegal(current, event, "unexpected acknowledgement");
            };

            case PARTIAL_FILL -> {
                if (!current.isWorking() && current != OrderState.PENDING_NEW) {
                    yield new Decision.Illegal(current, event,
                            "a fill for an order that is not working");
                }
                if (cumQty <= knownCumQty) {
                    // A repeat carries no new quantity; a genuine partial does.
                    yield new Decision.Stale(current, event,
                            "no quantity beyond the " + knownCumQty + " already known");
                }
                // A fill does not answer an outstanding cancel or replace. The
                // quantity moves and the request stays outstanding, so the state
                // must stay where it is or the request would be forgotten.
                //
                // PendingNew is not that case: nothing is outstanding there but
                // the acknowledgement, and a fill is a stronger acknowledgement
                // than an ack. Leaving it pending would have a demonstrably
                // trading order raise a "no acknowledgement" alert.
                yield current.awaitsRequestAnswer()
                        ? new Decision.Advance(current,
                                "filled " + cumQty + "; " + describe(pending) + " still awaited")
                        : new Decision.Advance(OrderState.PARTIALLY_FILLED,
                                "filled " + cumQty + " of the order");
            }

            // A complete fill does end the order, and with it anything
            // outstanding: there is nothing left for the venue to cancel.
            case FILL -> current.isWorking() || current == OrderState.PENDING_NEW
                    ? new Decision.Advance(OrderState.FILLED, "fully executed")
                    : new Decision.Illegal(current, event,
                            "a fill for an order that is not working");

            case ORDER_REJECTED -> current.isOurs() || current == OrderState.PENDING_NEW
                    ? new Decision.Advance(OrderState.REJECTED, "refused by the venue")
                    : new Decision.Illegal(current, event,
                            "an order already working cannot be rejected");

            // A halted or done-for-day order is still on the venue's book, and
            // a halt is exactly when someone wants it gone.
            // One request at a time. An order with a cancel already in flight
            // is still working, so isCancellable alone would let a second one
            // through — and then two requests answer to one order, with the
            // venue's reply matching whichever this system guessed. Refusing
            // the second is the only reading that stays true.
            case CANCEL_PENDING -> current.outstanding() != OrderState.Outstanding.NOTHING
                    ? new Decision.Illegal(current, event,
                            "a request is already outstanding on this order")
                    : current.isCancellable()
                    ? new Decision.Advance(OrderState.PENDING_CANCEL, "cancel in flight")
                    : new Decision.Illegal(current, event, "nothing working to cancel");

            case CANCELLED -> current.isCancellable()
                    ? new Decision.Advance(OrderState.CANCELED, "cancelled")
                    : new Decision.Illegal(current, event, "nothing to cancel");

            // The refusal itself is the news. The order keeps working, which is
            // the part most easily missed — "cancel rejected" reads like an
            // ending and is the opposite.
            case CANCEL_REFUSED -> current == OrderState.PENDING_CANCEL
                    ? new Decision.Advance(OrderState.CANCEL_REJECTED,
                            "cancel refused; the order is still working")
                    : new Decision.Stale(current, event, "no cancel was outstanding");

            case REPLACE_PENDING -> current.outstanding() != OrderState.Outstanding.NOTHING
                    ? new Decision.Illegal(current, event,
                            "a request is already outstanding on this order")
                    : current.isWorking()
                    ? new Decision.Advance(OrderState.PENDING_REPLACE, "replace in flight")
                    : new Decision.Illegal(current, event, "nothing working to replace");

            // The order keeps working on the new terms, so it can be replaced
            // or cancelled again from here.
            case REPLACED -> current == OrderState.PENDING_REPLACE
                    ? new Decision.Advance(OrderState.REPLACED,
                            "replaced; working on the new terms")
                    : new Decision.Illegal(current, event, "no replace was outstanding");

            case REPLACE_REFUSED -> current == OrderState.PENDING_REPLACE
                    ? new Decision.Advance(OrderState.REPLACE_REJECTED,
                            "replace refused; the original terms stand")
                    : new Decision.Stale(current, event, "no replace was outstanding");

            case DONE_FOR_DAY -> current.isWorking() || current.isHeld()
                    ? new Decision.Advance(OrderState.DONE_FOR_DAY,
                            "no further execution today")
                    : new Decision.Illegal(current, event, "not working today");

            case SUSPENDED -> current.isWorking()
                    ? new Decision.Advance(OrderState.SUSPENDED, "held by the venue")
                    : new Decision.Illegal(current, event, "nothing working to suspend");

            case EXPIRED -> current.isCancellable()
                    ? new Decision.Advance(OrderState.EXPIRED, "time in force ran out")
                    : new Decision.Illegal(current, event, "nothing working to expire");

            // The venue stopped executing it. What usually follows is a fill,
            // so the order is not finished — it is waiting on one.
            case STOPPED -> current.isWorking() || current == OrderState.PENDING_NEW
                    ? new Decision.Advance(OrderState.STOPPED, "execution stopped by the venue")
                    : new Decision.Illegal(current, event, "nothing executing to stop");

            // Settlement arithmetic is done. No more fills, but the record is
            // not final either.
            case CALCULATED -> current.isWorking() || current.isSettling() || current.isHeld()
                    ? new Decision.Advance(OrderState.CALCULATED, "quantities calculated")
                    : new Decision.Illegal(current, event, "nothing to calculate");

            case ACCEPTED_FOR_BIDDING ->
                    current == OrderState.PENDING_NEW || current == OrderState.CREATED
                    ? new Decision.Advance(OrderState.ACCEPTED_FOR_BIDDING,
                            "accepted into the bidding process")
                    : new Decision.Illegal(current, event, "not awaiting acceptance");

            // A correction restates a fill the venue already reported, and the
            // corrected quantity may be lower than what stands. This is the one
            // case where a position legitimately moves backwards, so no
            // comparison against the known quantity applies.
            case TRADE_CORRECTED -> {
                if (current.isTerminal() && current != OrderState.FILLED) {
                    yield new Decision.Illegal(current, event,
                            "no fills to correct on an order that never traded");
                }
                yield new Decision.Advance(
                        cumQty <= 0 ? OrderState.NEW
                                : leavesOpen(cumQty, current)
                                        ? OrderState.PARTIALLY_FILLED
                                        : OrderState.FILLED,
                        "fill corrected to " + cumQty);
            }

            // A busted trade. The quantity comes off, and an order that had
            // filled goes back to working — which is why FILLED is reachable
            // from here in reverse.
            case TRADE_CANCELLED -> {
                if (current != OrderState.FILLED
                        && current != OrderState.PARTIALLY_FILLED
                        && current != OrderState.CALCULATED) {
                    yield new Decision.Illegal(current, event,
                            "no fill to withdraw");
                }
                yield new Decision.Advance(
                        cumQty <= 0 ? OrderState.NEW : OrderState.PARTIALLY_FILLED,
                        "fill withdrawn, quantity now " + cumQty);
            }

            // The answer to a status request describes the order rather than
            // changing it. Acting on one would let a query rewrite history.
            case STATUS_RESPONSE ->
                    new Decision.Unchanged(current, "status response describes, does not change");

            case RESTATEMENT, UNKNOWN ->
                    new Decision.Unchanged(current, "handled above");
        };
    }

    /** The state an order starts in when the client's order is accepted. */
    public static OrderState initial() {
        return OrderState.CREATED;
    }

    private static String describe(PendingRequest pending) {
        if (pending == null) {
            return "a request";
        }
        return pending.isCancel() ? "the cancel" : "the replace";
    }

    /**
     * Whether a corrected quantity still leaves something to work.
     *
     * <p>Only the order's own quantity can answer that, and the state machine
     * does not carry it. Absent that, a correction is treated as leaving the
     * order partially filled — the safer reading, since closing an order that
     * is still working loses track of it.
     */
    private static boolean leavesOpen(double cumQty, OrderState current) {
        return current != OrderState.FILLED || cumQty > 0;
    }

    /**
     * Events that legitimately arrive for an order that has already closed.
     *
     * <p>Trade corrections and busts belong here: they are precisely the
     * messages a venue sends about a fill that already happened, and refusing
     * them would leave a corrected position uncorrected.
     */
    private static boolean isTerminalEvent(OrderEventType event) {
        return event == OrderEventType.CANCELLED
                || event == OrderEventType.FILL
                || event == OrderEventType.ORDER_REJECTED
                || event == OrderEventType.EXPIRED
                || event == OrderEventType.REPLACED
                || event == OrderEventType.TRADE_CORRECTED
                || event == OrderEventType.TRADE_CANCELLED
                || event == OrderEventType.CALCULATED
                || event == OrderEventType.STATUS_RESPONSE;
    }

    // ------------------------------------------------------------------
    // Reachability, derived from the decisions themselves
    // ------------------------------------------------------------------

    private static final Map<OrderState, Set<OrderState>> REACHABLE =
            new EnumMap<>(OrderState.class);

    static {
        // Computed from decide() rather than maintained beside it, so the graph
        // cannot drift from the behaviour it claims to describe.
        for (OrderState from : OrderState.values()) {
            Set<OrderState> targets = EnumSet.noneOf(OrderState.class);
            for (OrderEventType event : OrderEventType.values()) {
                if (decide(from, event, Double.MAX_VALUE, 0, null)
                        instanceof Decision.Advance(OrderState to, String ignored)) {
                    targets.add(to);
                }
            }
            REACHABLE.put(from, targets);
        }
    }

    /** States reachable from another in a single event. */
    public static Set<OrderState> reachableFrom(OrderState from) {
        return REACHABLE.getOrDefault(from, Set.of());
    }

    public static boolean canTransition(OrderState from, OrderState to) {
        return reachableFrom(from).contains(to);
    }
}
