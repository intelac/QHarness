package io.nexum.message;

import java.util.Set;

/**
 * Which layer a tag belongs to.
 *
 * <p>One source of truth, because two of them drifted. The pipeline kept its own
 * idea of "session layer" and the transport kept another, and they disagreed
 * about OnBehalfOfCompID(115) — so a field that changes on every hop was stored
 * as an order's own business attribute and journalled as though it described the
 * order.
 *
 * <p>The distinction matters in two places that must agree: what is worth
 * recording about an order, and where a tag has to sit when a message is
 * rebuilt. Deriving both from one set is what keeps them agreeing.
 */
public final class FixLayers {

    private FixLayers() {}

    /**
     * Tags that describe the transport rather than the order.
     *
     * <p>Sequence numbers, routing identifiers, timestamps and lengths: every
     * one of them changes as a message crosses a hop, so keeping any of them in
     * an order's view records something that was never true of the order.
     */
    public static final Set<Integer> SESSION = Set.of(
            FixTags.BEGIN_STRING,
            FixTags.BODY_LENGTH,
            FixTags.CHECK_SUM,
            FixTags.MSG_SEQ_NUM,
            FixTags.MSG_TYPE,
            FixTags.POSS_DUP_FLAG,
            FixTags.SENDER_COMP_ID,
            FixTags.SENDER_SUB_ID,
            FixTags.SENDING_TIME,
            FixTags.TARGET_COMP_ID,
            FixTags.TARGET_SUB_ID,
            FixTags.SIGNATURE,
            FixTags.SECURE_DATA_LEN,
            FixTags.SECURE_DATA,
            FixTags.SIGNATURE_LENGTH,
            FixTags.POSS_RESEND,
            FixTags.ON_BEHALF_OF_COMP_ID,
            FixTags.ON_BEHALF_OF_SUB_ID,
            FixTags.ON_BEHALF_OF_LOCATION_ID,
            FixTags.DELIVER_TO_COMP_ID,
            FixTags.DELIVER_TO_SUB_ID,
            FixTags.DELIVER_TO_LOCATION_ID,
            FixTags.SENDER_LOCATION_ID,
            FixTags.TARGET_LOCATION_ID,
            FixTags.ORIG_SENDING_TIME,
            FixTags.MESSAGE_ENCODING,
            FixTags.LAST_MSG_SEQ_NUM_PROCESSED,
            FixTags.NO_HOPS,
            FixTags.HOP_COMP_ID,
            FixTags.HOP_SENDING_TIME,
            FixTags.HOP_REF_ID);

    /**
     * Computed by the engine on the way out and never carried from above.
     *
     * <p>A subset of {@link #SESSION}: they are session-layer fields that also
     * happen to belong in the trailer.
     */
    public static final Set<Integer> TRAILER = Set.of(
            FixTags.CHECK_SUM,
            FixTags.SIGNATURE,
            FixTags.SIGNATURE_LENGTH);

    /**
     * Session-layer tags that belong in a message's header when it is rebuilt.
     *
     * <p>Derived rather than listed. A tag set out of order is a message the
     * counterparty reads apart at the wrong boundaries, and a second hand-kept
     * list is how the two views came to disagree in the first place.
     */
    public static final Set<Integer> HEADER = SESSION.stream()
            .filter(tag -> !TRAILER.contains(tag))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** True when the tag describes the transport rather than the order. */
    public static boolean isSessionLayer(int tag) {
        return SESSION.contains(tag);
    }

    /** True when the tag belongs in the header of a rebuilt message. */
    public static boolean isHeader(int tag) {
        return HEADER.contains(tag);
    }

    /** True when the engine computes it on the way out. */
    public static boolean isTrailer(int tag) {
        return TRAILER.contains(tag);
    }

    /**
     * True when the tag says something about the order itself.
     *
     * <p>The one question the journal and the order views should be asking.
     */
    public static boolean isBusiness(int tag) {
        return !SESSION.contains(tag);
    }
}
