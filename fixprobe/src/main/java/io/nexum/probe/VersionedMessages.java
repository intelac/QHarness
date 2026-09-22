package io.nexum.probe;

import quickfix.Message;
import quickfix.field.ApplVerID;
import quickfix.field.MsgType;

/**
 * Building a message for whichever FIX version a session speaks.
 *
 * <p>QuickFIX ships a class per message per version — {@code fix44.NewOrderSingle},
 * {@code fix42.NewOrderSingle} — and choosing between them at every call site
 * would put a switch on the version in front of every message this harness can
 * send. The fields do not need it: {@code quickfix.field.*} is one set across
 * versions, and a message is its MsgType plus the fields set on it. So the type
 * is what varies here, and the field code above it stays the same for every
 * version.
 *
 * <p>What the versions genuinely disagree about is the session layer. The 4.x
 * series names its version in BeginString; the 5.0 series puts FIXT.1.1 there
 * and names the application version separately, in ApplVerID, which is why
 * {@link #create} stamps it on every 5.0 message rather than leaving the
 * counterparty to infer one.
 */
public final class VersionedMessages {

    private final ProbeFixVersion version;

    /**
     * @param version the version this builder's session speaks.
     */
    public VersionedMessages(ProbeFixVersion version) {
        this.version = version;
    }

    /** Which version these messages are built for. */
    public ProbeFixVersion version() {
        return version;
    }

    /**
     * An empty message of one type, addressed to this builder's version.
     *
     * @param msgType MsgType(35), e.g. {@code "D"} for NewOrderSingle.
     * @return the message, with its header version fields already set.
     */
    public Message create(String msgType) {
        Message message = new Message();
        message.getHeader().setString(quickfix.field.BeginString.FIELD, version.beginString());
        message.getHeader().setString(MsgType.FIELD, msgType);
        if (version.applVerID() != null) {
            // FIXT.1.1 carries any application version, so a message that does
            // not name one leaves the counterparty to guess from its own
            // default — which is how two correctly configured sides end up
            // parsing the same bytes differently.
            message.getHeader().setString(ApplVerID.FIELD, version.applVerID());
        }
        return message;
    }
}
