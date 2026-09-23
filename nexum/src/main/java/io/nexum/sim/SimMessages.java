package io.nexum.sim;

import io.nexum.message.FixVersion;
import quickfix.Message;
import quickfix.field.ApplVerID;
import quickfix.field.BeginString;
import quickfix.field.ExecType;
import quickfix.field.MsgType;

/**
 * What the simulators need to speak a given FIX version.
 *
 * <p>They build messages from MsgType and fields rather than from QuickFIX's
 * per-version classes, the same way the engine and the probe do, so a version
 * is chosen once at startup instead of at every message.
 */
final class SimMessages {

    private SimMessages() {}

    /**
     * The {@code [session]} lines that fix a session's version.
     *
     * <p>For the 5.0 series that is FIXT.1.1 plus the application version,
     * without which QuickFIX will not start the session.
     */
    static String sessionVersionLines(FixVersion version) {
        String lines = "BeginString=" + version.beginString() + "\n";
        if (version.applVerID() != null) {
            lines += "DefaultApplVerID=" + version.applVerID() + "\n";
        }
        return lines;
    }

    /** An empty message of one type, with its header version fields set. */
    static Message create(FixVersion version, String msgType) {
        Message message = new Message();
        message.getHeader().setString(BeginString.FIELD, version.beginString());
        message.getHeader().setString(MsgType.FIELD, msgType);
        if (version.applVerID() != null) {
            message.getHeader().setString(ApplVerID.FIELD, version.applVerID());
        }
        return message;
    }

    /**
     * The ExecType a version writes for what 4.3 onwards calls Trade.
     *
     * <p>Through 4.2 a fill says whether it was partial in ExecType itself,
     * because there is no F; anything other than Trade is unchanged.
     */
    static char execType(FixVersion version, char execType, double leavesQty) {
        if (execType != ExecType.TRADE || !version.usesExecTransType()) {
            return execType;
        }
        return leavesQty == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL;
    }
}
