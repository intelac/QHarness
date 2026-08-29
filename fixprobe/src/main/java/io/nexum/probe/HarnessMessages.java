package io.nexum.probe;

import quickfix.Message;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The messages the harness sends, built field by field from what a caller asked
 * for.
 *
 * <p>A conformance test asserts what a system does with a particular message,
 * so the message has to be the one the test chose. Nothing here derives a value
 * the caller did not give: an execution report carries the ExecType, quantities
 * and prices it was handed, including combinations a well-behaved venue would
 * never send, because a system's handling of those is exactly what a test needs
 * to pin down.
 */
public final class HarnessMessages {

    private final AtomicLong execIds = new AtomicLong(1);

    /** Client side: a new order. */
    public Message newOrderSingle(
            String clOrdId, String symbol, char side, double quantity,
            Double limitPrice, String account, String onBehalfOf) {

        quickfix.fix44.NewOrderSingle order = new quickfix.fix44.NewOrderSingle(
                new ClOrdID(clOrdId), new Side(side), new TransactTime(),
                new OrdType(limitPrice == null ? OrdType.MARKET : OrdType.LIMIT));
        order.set(new Symbol(symbol));
        order.set(new OrderQty(quantity));
        order.set(new HandlInst(
                HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        if (limitPrice != null) {
            order.set(new Price(limitPrice));
        }
        if (account != null && !account.isBlank()) {
            order.setString(quickfix.field.Account.FIELD, account);
        }
        stamp(order, onBehalfOf);
        return order;
    }

    /** Client side: cancel an order. */
    public Message cancelRequest(
            String clOrdId, String origClOrdId, String symbol, char side, double quantity,
            String onBehalfOf) {

        quickfix.fix44.OrderCancelRequest cancel = new quickfix.fix44.OrderCancelRequest(
                new OrigClOrdID(origClOrdId), new ClOrdID(clOrdId),
                new Side(side), new TransactTime());
        cancel.set(new Symbol(symbol));
        cancel.set(new OrderQty(quantity));
        stamp(cancel, onBehalfOf);
        return cancel;
    }

    /** Client side: amend an order. */
    public Message replaceRequest(
            String clOrdId, String origClOrdId, String symbol, char side,
            double quantity, Double limitPrice, String onBehalfOf) {

        quickfix.fix44.OrderCancelReplaceRequest replace =
                new quickfix.fix44.OrderCancelReplaceRequest(
                        new OrigClOrdID(origClOrdId), new ClOrdID(clOrdId),
                        new Side(side), new TransactTime(),
                        new OrdType(limitPrice == null ? OrdType.MARKET : OrdType.LIMIT));
        replace.set(new Symbol(symbol));
        replace.set(new OrderQty(quantity));
        if (limitPrice != null) {
            replace.set(new Price(limitPrice));
        }
        stamp(replace, onBehalfOf);
        return replace;
    }

    /**
     * Market side: an execution report, exactly as asked for.
     *
     * <p>ExecType and OrdStatus are separate parameters rather than one derived
     * from the other. They disagree legitimately — a cancel confirmation
     * reports what the order became, not what happened to it — and a system
     * that reads the wrong one is a bug worth being able to provoke.
     *
     * @param orderId the venue's own id for the order; a system under test is
     *     entitled to expect the same one on every report about it
     */
    public Message executionReport(
            String orderId, String clOrdId, String origClOrdId, String symbol, char side,
            double orderQty, char execType, char ordStatus,
            double lastQty, double cumQty, double leavesQty, double price, String text) {

        quickfix.fix44.ExecutionReport report = new quickfix.fix44.ExecutionReport(
                new OrderID(orderId),
                new ExecID("HARNESS-" + execIds.getAndIncrement()),
                new ExecType(execType),
                new OrdStatus(ordStatus),
                new Side(side),
                new LeavesQty(leavesQty),
                new CumQty(cumQty),
                new AvgPx(price));
        report.set(new ClOrdID(clOrdId));
        report.set(new Symbol(symbol));
        report.set(new OrderQty(orderQty));
        if (origClOrdId != null && !origClOrdId.isBlank()) {
            report.set(new OrigClOrdID(origClOrdId));
        }
        if (lastQty > 0) {
            report.set(new LastQty(lastQty));
            report.set(new LastPx(price));
        }
        if (text != null && !text.isBlank()) {
            report.set(new Text(text));
        }
        return report;
    }

    /**
     * Market side: refuse a cancel or a replace.
     *
     * @param responseTo which request is being refused — '1' a cancel, '2' a
     *     replace. It is the only field that says so: OrdStatus here reports
     *     the order's own state, so a system reading that instead cannot tell a
     *     refusal from an ordinary status message.
     */
    public Message cancelReject(
            String orderId, String clOrdId, String origClOrdId,
            char ordStatus, char responseTo, String reason) {

        quickfix.fix44.OrderCancelReject reject = new quickfix.fix44.OrderCancelReject(
                new OrderID(orderId),
                new ClOrdID(clOrdId),
                new OrigClOrdID(origClOrdId),
                new OrdStatus(ordStatus),
                new CxlRejResponseTo(responseTo));
        if (reason != null && !reason.isBlank()) {
            reject.set(new Text(reason));
        }
        return reject;
    }

    /**
     * Name the client a message is sent on behalf of.
     *
     * <p>A router that serves many clients over one session tells them apart by
     * this header field, and refuses what it cannot attribute — so a harness
     * that never sends it can only test systems that do not ask for it.
     */
    private static void stamp(Message message, String onBehalfOf) {
        if (onBehalfOf != null && !onBehalfOf.isBlank()) {
            message.getHeader().setString(OnBehalfOfCompID.FIELD, onBehalfOf);
        }
    }
}
