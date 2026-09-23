package io.nexum.message;

/**
 * Rewrites the few fields whose meaning moved between FIX versions, for the
 * session a message is about to go out on.
 *
 * <p>A router carries an order between sessions that need not speak the same
 * version, and forwards the venue's execution reports field by field. Most
 * fields mean the same thing in every version. Two do not, and a report that
 * crosses the 4.2 / 4.3 line carrying the other side's spelling of them is
 * rejected by the counterparty at the session level:
 *
 * <ul>
 *   <li>ExecTransType(20) is required through 4.2 and not defined from 4.3.</li>
 *   <li>A fill is ExecType 1 (partial) or 2 (full) through 4.2, and Trade (F)
 *       from 4.3, with LeavesQty saying which. 5.0SP1 removed 1 and 2.</li>
 * </ul>
 *
 * <p>Nothing else is rewritten. A value both versions define crosses as it
 * came, including one a counterparty would not have chosen — that is the
 * venue's report, and this is not the place to second-guess it.
 */
public final class VersionAdapter {

    private static final String PARTIAL_FILL = "1";
    private static final String FILL = "2";
    private static final String TRADE = "F";
    /** ExecTransType New: the report is not a correction or cancellation of an earlier one. */
    private static final String NEW_TRANSACTION = "0";

    private VersionAdapter() {}

    /**
     * The message as the target version writes it.
     *
     * @param message what is about to be sent.
     * @param target the version of the session it is going out on.
     * @return the rewritten message, or the same instance when nothing moved.
     */
    public static FixMessage adapt(FixMessage message, FixVersion target) {
        if (!"8".equals(message.msgType())) {
            return message;
        }
        return target.usesExecTransType() ? toExecTransTypeEra(message) : toTradeEra(message);
    }

    private static FixMessage toExecTransTypeEra(FixMessage message) {
        FixMessage adapted = message;
        if (TRADE.equals(adapted.get(FixTags.EXEC_TYPE))) {
            adapted = adapted.set(FixTags.EXEC_TYPE, nothingLeft(adapted) ? FILL : PARTIAL_FILL);
        }
        if (!adapted.has(FixTags.EXEC_TRANS_TYPE)) {
            adapted = adapted.set(FixTags.EXEC_TRANS_TYPE, NEW_TRANSACTION);
        }
        return adapted;
    }

    private static FixMessage toTradeEra(FixMessage message) {
        FixMessage adapted = message.remove(FixTags.EXEC_TRANS_TYPE);
        String execType = adapted.get(FixTags.EXEC_TYPE);
        if (PARTIAL_FILL.equals(execType) || FILL.equals(execType)) {
            adapted = adapted.set(FixTags.EXEC_TYPE, TRADE);
        }
        return adapted;
    }

    /** Whether the report leaves nothing open: a full fill rather than a partial one. */
    private static boolean nothingLeft(FixMessage message) {
        String leaves = message.get(FixTags.LEAVES_QTY);
        if (leaves == null) {
            // Without LeavesQty a Trade does not say whether it completed the
            // order; OrdStatus does, and a report without either is not one
            // this can do better with.
            return "2".equals(message.get(FixTags.ORD_STATUS));
        }
        try {
            return Double.parseDouble(leaves) == 0;
        } catch (NumberFormatException unreadable) {
            return "2".equals(message.get(FixTags.ORD_STATUS));
        }
    }
}
