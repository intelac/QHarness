package io.nexum.message;

import java.util.Map;
import java.util.Optional;

/**
 * What a tag is called, and what its values mean.
 *
 * <p>A raw report is a list of numbers. Reading {@code 39=1 150=F 14=300} takes
 * a reference open beside it, and the reference is the same one every time —
 * so it lives here, and a message can be displayed the way a person reads it.
 *
 * <p>The standard FIX 4.4 fields, not an exhaustive dictionary. A counterparty
 * with fields of its own supplies a dictionary, and one of those overrides this
 * where they overlap; anything neither knows about is still shown, as its
 * number. A tag with no name is a tag someone still needs to see.
 */
public final class FixDictionary {

    private FixDictionary() {}

    /** Tag to field name. */
    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(1, "Account"),
            Map.entry(6, "AvgPx"),
            Map.entry(8, "BeginString"),
            Map.entry(9, "BodyLength"),
            Map.entry(10, "CheckSum"),
            Map.entry(11, "ClOrdID"),
            Map.entry(14, "CumQty"),
            Map.entry(15, "Currency"),
            Map.entry(17, "ExecID"),
            Map.entry(18, "ExecInst"),
            Map.entry(19, "ExecRefID"),
            Map.entry(21, "HandlInst"),
            Map.entry(22, "SecurityIDSource"),
            Map.entry(31, "LastPx"),
            Map.entry(32, "LastQty"),
            Map.entry(34, "MsgSeqNum"),
            Map.entry(35, "MsgType"),
            Map.entry(37, "OrderID"),
            Map.entry(38, "OrderQty"),
            Map.entry(39, "OrdStatus"),
            Map.entry(40, "OrdType"),
            Map.entry(41, "OrigClOrdID"),
            Map.entry(44, "Price"),
            Map.entry(48, "SecurityID"),
            Map.entry(49, "SenderCompID"),
            Map.entry(52, "SendingTime"),
            Map.entry(54, "Side"),
            Map.entry(55, "Symbol"),
            Map.entry(56, "TargetCompID"),
            Map.entry(58, "Text"),
            Map.entry(59, "TimeInForce"),
            Map.entry(60, "TransactTime"),
            Map.entry(75, "TradeDate"),
            Map.entry(98, "EncryptMethod"),
            Map.entry(102, "CxlRejReason"),
            Map.entry(103, "OrdRejReason"),
            Map.entry(108, "HeartBtInt"),
            Map.entry(115, "OnBehalfOfCompID"),
            Map.entry(126, "ExpireTime"),
            Map.entry(128, "DeliverToCompID"),
            Map.entry(141, "ResetSeqNumFlag"),
            Map.entry(150, "ExecType"),
            Map.entry(151, "LeavesQty"),
            Map.entry(167, "SecurityType"),
            Map.entry(198, "SecondaryOrderID"),
            Map.entry(207, "SecurityExchange"),
            Map.entry(336, "TradingSessionID"),
            Map.entry(378, "ExecRestatementReason"),
            Map.entry(432, "ExpireDate"),
            Map.entry(434, "CxlRejResponseTo"),
            Map.entry(448, "PartyID"),
            Map.entry(447, "PartyIDSource"),
            Map.entry(452, "PartyRole"),
            Map.entry(453, "NoPartyIDs"),
            Map.entry(553, "Username"),
            Map.entry(554, "Password"),
            Map.entry(555, "NoLegs"));

    /**
     * What a value means, for the tags whose values are codes.
     *
     * <p>These are the ones worth explaining: a status, a reason, a side. A
     * price or a quantity means what it says.
     */
    private static final Map<Integer, Map<String, String>> VALUES = Map.of(
            35, Map.ofEntries(
                    Map.entry("D", "NewOrderSingle"),
                    Map.entry("F", "OrderCancelRequest"),
                    Map.entry("G", "OrderCancelReplaceRequest"),
                    Map.entry("8", "ExecutionReport"),
                    Map.entry("9", "OrderCancelReject"),
                    Map.entry("A", "Logon"),
                    Map.entry("5", "Logout"),
                    Map.entry("0", "Heartbeat"),
                    Map.entry("1", "TestRequest"),
                    Map.entry("2", "ResendRequest"),
                    Map.entry("3", "Reject"),
                    Map.entry("4", "SequenceReset")),
            39, Map.ofEntries(
                    Map.entry("0", "New"),
                    Map.entry("1", "PartiallyFilled"),
                    Map.entry("2", "Filled"),
                    Map.entry("3", "DoneForDay"),
                    Map.entry("4", "Canceled"),
                    Map.entry("5", "Replaced"),
                    Map.entry("6", "PendingCancel"),
                    Map.entry("7", "Stopped"),
                    Map.entry("8", "Rejected"),
                    Map.entry("9", "Suspended"),
                    Map.entry("A", "PendingNew"),
                    Map.entry("B", "Calculated"),
                    Map.entry("C", "Expired"),
                    Map.entry("E", "PendingReplace")),
            150, Map.ofEntries(
                    Map.entry("0", "New"),
                    Map.entry("3", "DoneForDay"),
                    Map.entry("4", "Canceled"),
                    Map.entry("5", "Replaced"),
                    Map.entry("6", "PendingCancel"),
                    Map.entry("7", "Stopped"),
                    Map.entry("8", "Rejected"),
                    Map.entry("9", "Suspended"),
                    Map.entry("A", "PendingNew"),
                    Map.entry("B", "Calculated"),
                    Map.entry("C", "Expired"),
                    Map.entry("D", "Restated"),
                    Map.entry("E", "PendingReplace"),
                    Map.entry("F", "Trade"),
                    Map.entry("G", "TradeCorrect"),
                    Map.entry("H", "TradeCancel"),
                    Map.entry("I", "OrderStatus")),
            54, Map.of("1", "Buy", "2", "Sell", "5", "SellShort", "6", "SellShortExempt"),
            40, Map.of("1", "Market", "2", "Limit", "3", "Stop", "4", "StopLimit"),
            59, Map.of("0", "Day", "1", "GoodTillCancel", "3", "ImmediateOrCancel",
                    "4", "FillOrKill", "6", "GoodTillDate"),
            434, Map.of("1", "OrderCancelRequest", "2", "OrderCancelReplaceRequest"),
            21, Map.of("1", "AutomatedNoIntervention", "2", "AutomatedIntervention",
                    "3", "Manual"),
            103, Map.of("0", "BrokerOption", "1", "UnknownSymbol", "2", "ExchangeClosed",
                    "3", "OrderExceedsLimit", "5", "UnknownOrder", "6", "DuplicateOrder",
                    "11", "UnsupportedOrderCharacteristic"),
            102, Map.of("0", "TooLateToCancel", "1", "UnknownOrder", "2", "BrokerOption",
                    "3", "AlreadyPending", "6", "DuplicateClOrdID"));

    /** What this tag is called, when it is one the standard names. */
    public static Optional<String> name(int tag) {
        return Optional.ofNullable(NAMES.get(tag));
    }

    /** What this value means, when the tag's values are codes. */
    public static Optional<String> meaning(int tag, String value) {
        Map<String, String> values = VALUES.get(tag);
        return values == null ? Optional.empty() : Optional.ofNullable(values.get(value));
    }

    /** True when this tag belongs to the session layer rather than the order. */
    public static boolean isSession(int tag) {
        return FixLayers.SESSION.contains(tag);
    }
}
