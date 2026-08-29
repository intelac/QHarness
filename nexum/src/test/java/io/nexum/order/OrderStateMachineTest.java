package io.nexum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state machine, exercised over every state and every event.
 *
 * <p>Written against behaviour rather than the transition tables, because the
 * tables are derived from the decisions — a test that read them would agree with
 * whatever the code does.
 */
class OrderStateMachineTest {

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("no combination of state and event is left undecided")
    class Totality {

        @ParameterizedTest(name = "{0}")
        @EnumSource(OrderState.class)
        void everyEventDecidedInEveryState(OrderState state) {
            for (OrderEventType event : OrderEventType.values()) {
                OrderStateMachine.Decision decision =
                        OrderStateMachine.decide(state, event, 100, 0);
                assertNotNull(decision,
                        state + " + " + event + " produced no decision");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(OrderState.class)
        void decidingTwiceGivesTheSameAnswer(OrderState state) {
            for (OrderEventType event : OrderEventType.values()) {
                assertEquals(
                        OrderStateMachine.decide(state, event, 100, 0).getClass(),
                        OrderStateMachine.decide(state, event, 100, 0).getClass(),
                        state + " + " + event + " is not deterministic");
            }
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a terminal order accepts nothing further")
    class Terminal {

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.nexum.order.OrderStateMachineTest#terminalStates")
        void nonTerminalEventsAreTreatedAsReplays(OrderState terminal) {
            OrderStateMachine.Decision decision =
                    OrderStateMachine.decide(terminal, OrderEventType.ACK, 0, 0);

            // A replayed ack after the order closed is a resend, not a
            // disagreement — treating it as one would bury the real ones.
            assertInstanceOf(OrderStateMachine.Decision.Stale.class, decision,
                    terminal + " should have treated a late ACK as stale");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.nexum.order.OrderStateMachineTest#terminalStates")
        void onlyACorrectionMovesATerminalOrder(OrderState terminal) {
            for (OrderEventType event : OrderEventType.values()) {
                OrderStateMachine.Decision decision =
                        OrderStateMachine.decide(terminal, event, 1000, 0);

                if (decision instanceof OrderStateMachine.Decision.Advance) {
                    // A correction or a bust is the venue revising a fill it
                    // already reported. Refusing them would leave a corrected
                    // position uncorrected, which is worse than a state moving.
                    assertTrue(revisesAFill(event),
                            terminal + " advanced on " + event
                                    + ", which does not revise a fill");
                }
            }
        }

        @Test
        void aBustedFillPutsTheOrderBackToWorking() {
            OrderStateMachine.Decision decision = OrderStateMachine.decide(
                    OrderState.FILLED, OrderEventType.TRADE_CANCELLED, 0, 1000);

            assertEquals(OrderState.NEW, advanced(decision),
                    "a withdrawn fill leaves the order with nothing done");
        }

        @Test
        void aCorrectionCanLowerTheQuantity() {
            OrderStateMachine.Decision decision = OrderStateMachine.decide(
                    OrderState.FILLED, OrderEventType.TRADE_CORRECTED, 600, 1000);

            // The one case where a position legitimately moves backwards.
            assertInstanceOf(OrderStateMachine.Decision.Advance.class, decision);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the ordinary path")
    class HappyPath {

        @Test
        void createdToFilled() {
            assertAdvances(OrderState.CREATED, OrderEventType.SENT_TO_VENUE,
                    OrderState.PENDING_NEW);
            assertAdvances(OrderState.PENDING_NEW, OrderEventType.ACK, OrderState.NEW);
            assertAdvances(OrderState.NEW, OrderEventType.PARTIAL_FILL,
                    OrderState.PARTIALLY_FILLED, 300, 0);
            assertAdvances(OrderState.PARTIALLY_FILLED, OrderEventType.FILL,
                    OrderState.FILLED, 1000, 300);
        }

        @Test
        void anAckWhileAlreadyAcknowledgedChangesNothing() {
            assertInstanceOf(OrderStateMachine.Decision.Unchanged.class,
                    OrderStateMachine.decide(OrderState.NEW, OrderEventType.ACK, 0, 0));
        }

        @Test
        void aRestatementNeverMovesTheOrder() {
            for (OrderState state : OrderState.values()) {
                assertInstanceOf(OrderStateMachine.Decision.Unchanged.class,
                        OrderStateMachine.decide(state, OrderEventType.RESTATEMENT, 0, 0),
                        "a restatement moved an order in " + state);
            }
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("quantity decides whether a partial fill is news")
    class Quantities {

        @Test
        void aPartialCarryingNewQuantityAdvances() {
            assertAdvances(OrderState.PARTIALLY_FILLED, OrderEventType.PARTIAL_FILL,
                    OrderState.PARTIALLY_FILLED, 600, 300);
        }

        @Test
        void aPartialRepeatingAKnownQuantityIsStale() {
            assertInstanceOf(OrderStateMachine.Decision.Stale.class,
                    OrderStateMachine.decide(
                            OrderState.PARTIALLY_FILLED, OrderEventType.PARTIAL_FILL, 600, 600));
        }

        @Test
        void aPartialGoingBackwardsIsStale() {
            assertInstanceOf(OrderStateMachine.Decision.Stale.class,
                    OrderStateMachine.decide(
                            OrderState.PARTIALLY_FILLED, OrderEventType.PARTIAL_FILL, 300, 600));
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a cancel in flight")
    class Cancels {

        @Test
        void aRefusedCancelLeavesTheOrderWorking() {
            OrderStateMachine.Decision decision = OrderStateMachine.decide(
                    OrderState.PENDING_CANCEL, OrderEventType.CANCEL_REFUSED, 0, 0);

            OrderState after = advanced(decision);
            assertEquals(OrderState.CANCEL_REJECTED, after);
            assertTrue(after.isWorking(),
                    "a refused cancel must leave the order working — this is the state"
                            + " most often misread as an ending");
        }

        @Test
        void aFillDuringACancelKeepsTheCancelOutstanding() {
            PendingRequest cancel = PendingRequest.cancel("CXL-1", "OUR-1", null, 0);

            OrderStateMachine.Decision decision = OrderStateMachine.decide(
                    OrderState.PENDING_CANCEL, OrderEventType.PARTIAL_FILL, 400, 0, cancel);

            // The state deliberately stays put: a fill does not answer a cancel,
            // and moving to PartiallyFilled would forget a request the venue is
            // still going to reply to.
            assertEquals(OrderState.PENDING_CANCEL, advanced(decision));
        }

        @Test
        void aFullFillEndsTheOrderDespiteTheCancel() {
            assertAdvances(OrderState.PENDING_CANCEL, OrderEventType.FILL,
                    OrderState.FILLED, 1000, 0);
        }

        @Test
        void aCancelRefusalWithNoCancelOutstandingIsStale() {
            assertInstanceOf(OrderStateMachine.Decision.Stale.class,
                    OrderStateMachine.decide(
                            OrderState.NEW, OrderEventType.CANCEL_REFUSED, 0, 0));
        }

        @Test
        void anOrderThatIsNotWorkingCannotBeCancelled() {
            assertInstanceOf(OrderStateMachine.Decision.Illegal.class,
                    OrderStateMachine.decide(
                            OrderState.CREATED, OrderEventType.CANCEL_PENDING, 0, 0));
        }

        @Test
        void aCancelRejectedOrderCanBeCancelledAgain() {
            assertAdvances(OrderState.CANCEL_REJECTED, OrderEventType.CANCEL_PENDING,
                    OrderState.PENDING_CANCEL);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a replace in flight")
    class Replaces {

        @Test
        void aRefusedReplaceLeavesTheOriginalWorking() {
            OrderState after = advanced(OrderStateMachine.decide(
                    OrderState.PENDING_REPLACE, OrderEventType.REPLACE_REFUSED, 0, 0));

            assertEquals(OrderState.REPLACE_REJECTED, after);
            assertTrue(after.isWorking());
        }

        @Test
        void anAcceptedReplaceLeavesTheOrderWorkingOnNewTerms() {
            assertAdvances(OrderState.PENDING_REPLACE, OrderEventType.REPLACED,
                    OrderState.REPLACED);

            // FIX names this state after what happened to the original terms,
            // which reads like an ending. The order is live: treating it as
            // finished leaves a working order the system refuses to act on.
            assertFalse(OrderState.REPLACED.isTerminal());
            assertTrue(OrderState.REPLACED.isWorking());
        }

        @Test
        void aReplacedOrderCanBeCancelled() {
            assertAdvances(OrderState.REPLACED, OrderEventType.CANCEL_PENDING,
                    OrderState.PENDING_CANCEL);
        }

        @Test
        void aReplacedOrderCanBeReplacedAgain() {
            assertAdvances(OrderState.REPLACED, OrderEventType.REPLACE_PENDING,
                    OrderState.PENDING_REPLACE);
        }

        @Test
        void aReplacedOrderCanStillTrade() {
            assertAdvances(OrderState.REPLACED, OrderEventType.PARTIAL_FILL,
                    OrderState.PARTIALLY_FILLED, 300, 0);
        }

        @Test
        void aFillDuringAReplaceKeepsTheReplaceOutstanding() {
            PendingRequest replace = PendingRequest.replace("AMD-1", "OUR-1", null, 0, Map.of(44, "155.00"));

            assertEquals(OrderState.PENDING_REPLACE, advanced(OrderStateMachine.decide(
                    OrderState.PENDING_REPLACE, OrderEventType.PARTIAL_FILL, 400, 0, replace)));
        }

        @Test
        void aReplacedReportWithNoReplaceOutstandingIsIllegal() {
            assertInstanceOf(OrderStateMachine.Decision.Illegal.class,
                    OrderStateMachine.decide(OrderState.NEW, OrderEventType.REPLACED, 0, 0));
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("what belongs to us and what belongs to the venue")
    class Ownership {

        @Test
        void aFailedSendClosesAnOrderThatNeverLeft() {
            assertAdvances(OrderState.CREATED, OrderEventType.SEND_FAILED,
                    OrderState.NOT_SENT);
        }

        @Test
        void aSendCannotFailOnceTheVenueHasAcknowledged() {
            assertInstanceOf(OrderStateMachine.Decision.Illegal.class,
                    OrderStateMachine.decide(OrderState.NEW, OrderEventType.SEND_FAILED, 0, 0));
        }

        @Test
        void onlyCreatedIsOurs() {
            for (OrderState state : OrderState.values()) {
                assertEquals(state == OrderState.CREATED, state.isOurs(),
                        state + " reported the wrong ownership");
            }
        }

        @Test
        void inFlightCoversTheThreeUnansweredRequests() {
            assertTrue(OrderState.PENDING_NEW.isInFlight());
            assertTrue(OrderState.PENDING_CANCEL.isInFlight());
            assertTrue(OrderState.PENDING_REPLACE.isInFlight());
            assertFalse(OrderState.NEW.isInFlight());
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("states that hold without ending")
    class NotQuiteDone {

        @Test
        void doneForDayIsNotTerminalBecauseTheOrderMayTradeTomorrow() {
            assertFalse(OrderState.DONE_FOR_DAY.isTerminal());
            assertAdvances(OrderState.DONE_FOR_DAY, OrderEventType.ACK, OrderState.NEW);
        }

        @Test
        void suspendedIsNotTerminalBecauseItMayResume() {
            assertFalse(OrderState.SUSPENDED.isTerminal());
            assertAdvances(OrderState.SUSPENDED, OrderEventType.ACK, OrderState.NEW);
        }

        @Test
        void aSuspendedOrderCanStillBeCancelled() {
            assertAdvances(OrderState.SUSPENDED, OrderEventType.CANCELLED,
                    OrderState.CANCELED);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the reachability graph agrees with the decisions")
    class Reachability {

        @ParameterizedTest(name = "{0}")
        @EnumSource(OrderState.class)
        void everyReachableTargetIsProducedBySomeEvent(OrderState from) {
            for (OrderState to : OrderStateMachine.reachableFrom(from)) {
                boolean produced = false;
                for (OrderEventType event : OrderEventType.values()) {
                    if (OrderStateMachine.decide(from, event, Double.MAX_VALUE, 0)
                            instanceof OrderStateMachine.Decision.Advance(var target, var why)
                            && target == to) {
                        produced = true;
                        break;
                    }
                }
                assertTrue(produced,
                        from + " claims " + to + " is reachable but no event produces it");
            }
        }

        @Test
        void aTerminalOrderOnlyMovesOnACorrection() {
            for (OrderState state : OrderState.values()) {
                if (!state.isTerminal()) {
                    continue;
                }
                for (OrderState target : OrderStateMachine.reachableFrom(state)) {
                    boolean onlyByRevision = true;
                    for (OrderEventType event : OrderEventType.values()) {
                        if (OrderStateMachine.decide(state, event, Double.MAX_VALUE, 0)
                                        instanceof OrderStateMachine.Decision.Advance(
                                                var to, var why)
                                && to == target
                                && !revisesAFill(event)) {
                            onlyByRevision = false;
                        }
                    }
                    assertTrue(onlyByRevision,
                            state + " reaches " + target + " by something other than"
                                    + " a correction");
                }
            }
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("recognising what a message is")
    class Recognition {

        @Test
        void execTypeIsPreferredOverOrdStatus() {
            // The venue says Filled in OrdStatus while ExecType reports a trade
            // with quantity left. ExecType and LeavesQty are the facts.
            assertEquals(OrderEventType.PARTIAL_FILL,
                    OrderEventType.fromExecutionReport("F", "2", 500.0));
        }

        @Test
        void leavesQtySeparatesAPartialFromAFill() {
            assertEquals(OrderEventType.PARTIAL_FILL,
                    OrderEventType.fromExecutionReport("F", null, 100.0));
            assertEquals(OrderEventType.FILL,
                    OrderEventType.fromExecutionReport("F", null, 0.0));
        }

        @Test
        void anAbsentLeavesQtyIsReadAsAPartial() {
            // Safer: treating an unknown remainder as finished would close an
            // order that is still working.
            assertEquals(OrderEventType.PARTIAL_FILL,
                    OrderEventType.fromExecutionReport("F", null, null));
        }

        @Test
        void ordStatusIsUsedOnlyWhenExecTypeIsAbsent() {
            assertEquals(OrderEventType.FILL,
                    OrderEventType.fromExecutionReport(null, "2", 0.0));
            assertEquals(OrderEventType.CANCELLED,
                    OrderEventType.fromExecutionReport("", "4", null));
        }

        @Test
        void cancelRejectsAreToldApartByCxlRejResponseTo() {
            assertEquals(OrderEventType.CANCEL_REFUSED,
                    OrderEventType.fromCancelReject("1"));
            assertEquals(OrderEventType.REPLACE_REFUSED,
                    OrderEventType.fromCancelReject("2"));
        }

        @Test
        void aRejectWithoutAttributionIsReadAsARefusedCancel() {
            // The more dangerous to miss: an order believed cancelled is still
            // working.
            assertEquals(OrderEventType.CANCEL_REFUSED,
                    OrderEventType.fromCancelReject(null));
        }

        @Test
        void anUnknownExecTypeIsNotGuessedAt() {
            assertEquals(OrderEventType.UNKNOWN,
                    OrderEventType.fromExecutionReport("Z", null, null));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Events where the venue revises a fill it already reported. */
    private static boolean revisesAFill(OrderEventType event) {
        return event == OrderEventType.TRADE_CORRECTED
                || event == OrderEventType.TRADE_CANCELLED;
    }

    static Stream<OrderState> terminalStates() {
        return Stream.of(OrderState.values()).filter(OrderState::isTerminal);
    }

    private static void assertAdvances(
            OrderState from, OrderEventType event, OrderState expected) {
        assertAdvances(from, event, expected, Double.MAX_VALUE, 0);
    }

    private static void assertAdvances(
            OrderState from,
            OrderEventType event,
            OrderState expected,
            double cumQty,
            double knownCumQty) {

        OrderStateMachine.Decision decision =
                OrderStateMachine.decide(from, event, cumQty, knownCumQty);
        assertInstanceOf(OrderStateMachine.Decision.Advance.class, decision,
                from + " + " + event + " should have advanced but was " + decision);
        assertEquals(expected, advanced(decision));
    }

    private static OrderState advanced(OrderStateMachine.Decision decision) {
        assertInstanceOf(OrderStateMachine.Decision.Advance.class, decision,
                "expected an advance but was " + decision);
        return ((OrderStateMachine.Decision.Advance) decision).to();
    }
}
