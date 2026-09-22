package io.nexum.probe;

import quickfix.Message;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecTransType;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
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
    private final VersionedMessages messages;

    /**
     * @param version the FIX version the session these messages go out on speaks.
     */
    public HarnessMessages(ProbeFixVersion version) {
        this.messages = new VersionedMessages(version);
    }

    /** The version these messages are built for. */
    public ProbeFixVersion version() {
        return messages.version();
    }

    /** Client side: a new order. */
    public Message newOrderSingle(
            String clOrdId, String symbol, char side, double quantity,
            Double limitPrice, String account, String onBehalfOf) {

        Message order = messages.create(MsgType.ORDER_SINGLE);
        order.setField(new ClOrdID(clOrdId));
        order.setField(new Side(side));
        order.setField(new TransactTime());
        order.setField(new OrdType(limitPrice == null ? OrdType.MARKET : OrdType.LIMIT));
        order.setField(new Symbol(symbol));
        order.setField(new OrderQty(quantity));
        order.setField(new HandlInst(
                HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        if (limitPrice != null) {
            order.setField(new Price(limitPrice));
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

        Message cancel = messages.create(MsgType.ORDER_CANCEL_REQUEST);
        cancel.setField(new OrigClOrdID(origClOrdId));
        cancel.setField(new ClOrdID(clOrdId));
        cancel.setField(new Side(side));
        cancel.setField(new TransactTime());
        cancel.setField(new Symbol(symbol));
        cancel.setField(new OrderQty(quantity));
        stamp(cancel, onBehalfOf);
        return cancel;
    }

    /** Client side: amend an order. */
    public Message replaceRequest(
            String clOrdId, String origClOrdId, String symbol, char side,
            double quantity, Double limitPrice, String onBehalfOf) {

        Message replace = messages.create(MsgType.ORDER_CANCEL_REPLACE_REQUEST);
        replace.setField(new OrigClOrdID(origClOrdId));
        replace.setField(new ClOrdID(clOrdId));
        replace.setField(new Side(side));
        replace.setField(new TransactTime());
        replace.setField(new OrdType(limitPrice == null ? OrdType.MARKET : OrdType.LIMIT));
        replace.setField(new Symbol(symbol));
        replace.setField(new OrderQty(quantity));
        if (limitPrice != null) {
            replace.setField(new Price(limitPrice));
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

        Message report = messages.create(MsgType.EXECUTION_REPORT);
        report.setField(new OrderID(orderId));
        report.setField(new ExecID("HARNESS-" + execIds.getAndIncrement()));
        report.setField(new ExecType(execType));
        report.setField(new OrdStatus(ordStatus));
        report.setField(new Side(side));
        report.setField(new LeavesQty(leavesQty));
        report.setField(new CumQty(cumQty));
        report.setField(new AvgPx(price));
        if (messages.version() == ProbeFixVersion.FIX42) {
            // Required through 4.2 and gone by 4.4: a report without it is
            // rejected outright by a 4.2 counterparty, so the version that
            // needs it gets it and no other does.
            report.setField(new ExecTransType(ExecTransType.NEW));
        }
        report.setField(new ClOrdID(clOrdId));
        report.setField(new Symbol(symbol));
        report.setField(new OrderQty(orderQty));
        if (origClOrdId != null && !origClOrdId.isBlank()) {
            report.setField(new OrigClOrdID(origClOrdId));
        }
        if (lastQty > 0) {
            report.setField(new LastQty(lastQty));
            report.setField(new LastPx(price));
        }
        if (text != null && !text.isBlank()) {
            report.setField(new Text(text));
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

        Message reject = messages.create(MsgType.ORDER_CANCEL_REJECT);
        reject.setField(new OrderID(orderId));
        reject.setField(new ClOrdID(clOrdId));
        reject.setField(new OrigClOrdID(origClOrdId));
        reject.setField(new OrdStatus(ordStatus));
        reject.setField(new CxlRejResponseTo(responseTo));
        if (reason != null && !reason.isBlank()) {
            reject.setField(new Text(reason));
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
