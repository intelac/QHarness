package io.nexum.message;

/**
 * FIX tag numbers, in one place.
 *
 * <p>These were previously redeclared in fifteen files. Duplication of a
 * constant is usually harmless; duplication of a <em>set</em> of them is not —
 * two copies of "which tags belong to the session layer" drifted apart and
 * OnBehalfOfCompID(115) ended up recorded as an order's own business field,
 * which is exactly what that classification exists to prevent.
 *
 * <p>Only tags this system reasons about are here. A dialect declares whatever
 * else a counterparty sends; nothing needs a constant for a field it merely
 * carries through.
 */
public final class FixTags {

    private FixTags() {}

    // --- session layer ----------------------------------------------------

    public static final int BEGIN_STRING = 8;
    public static final int BODY_LENGTH = 9;
    public static final int CHECK_SUM = 10;
    public static final int MSG_SEQ_NUM = 34;
    public static final int MSG_TYPE = 35;
    public static final int POSS_DUP_FLAG = 43;
    public static final int SENDER_COMP_ID = 49;
    public static final int SENDER_SUB_ID = 50;
    public static final int SENDING_TIME = 52;
    public static final int TARGET_COMP_ID = 56;
    public static final int TARGET_SUB_ID = 57;
    public static final int SIGNATURE = 89;
    public static final int SECURE_DATA_LEN = 90;
    public static final int SECURE_DATA = 91;
    public static final int SIGNATURE_LENGTH = 93;
    public static final int POSS_RESEND = 97;
    public static final int ON_BEHALF_OF_COMP_ID = 115;
    public static final int DELIVER_TO_COMP_ID = 128;
    public static final int DELIVER_TO_SUB_ID = 129;
    public static final int SENDER_LOCATION_ID = 142;
    public static final int TARGET_LOCATION_ID = 143;
    public static final int ON_BEHALF_OF_LOCATION_ID = 144;
    public static final int DELIVER_TO_LOCATION_ID = 145;
    public static final int ORIG_SENDING_TIME = 122;
    public static final int MESSAGE_ENCODING = 347;
    public static final int LAST_MSG_SEQ_NUM_PROCESSED = 369;
    public static final int HOP_COMP_ID = 628;
    public static final int HOP_SENDING_TIME = 629;
    public static final int HOP_REF_ID = 630;
    public static final int NO_HOPS = 627;
    public static final int ON_BEHALF_OF_SUB_ID = 116;

    // --- order identity ---------------------------------------------------

    public static final int CL_ORD_ID = 11;
    public static final int ORIG_CL_ORD_ID = 41;
    public static final int ORDER_ID = 37;
    public static final int SECONDARY_ORDER_ID = 198;
    public static final int EXEC_ID = 17;
    public static final int EXEC_REF_ID = 19;

    // --- order terms ------------------------------------------------------

    public static final int SYMBOL = 55;
    public static final int SECURITY_ID = 48;
    public static final int SECURITY_ID_SOURCE = 22;
    public static final int SECURITY_EXCHANGE = 207;
    public static final int SECURITY_TYPE = 167;
    public static final int SIDE = 54;
    public static final int ORDER_QTY = 38;
    public static final int ORD_TYPE = 40;
    public static final int PRICE = 44;
    public static final int STOP_PX = 99;
    public static final int TIME_IN_FORCE = 59;
    public static final int CURRENCY = 15;
    public static final int HANDL_INST = 21;
    public static final int TRANSACT_TIME = 60;
    public static final int ACCOUNT = 1;
    public static final int EXPIRE_TIME = 126;

    // --- execution --------------------------------------------------------

    public static final int EXEC_TYPE = 150;
    public static final int ORD_STATUS = 39;
    public static final int CUM_QTY = 14;
    public static final int LEAVES_QTY = 151;
    public static final int LAST_QTY = 32;
    public static final int LAST_PX = 31;
    public static final int AVG_PX = 6;
    public static final int ORD_REJ_REASON = 103;
    public static final int CXL_REJ_RESPONSE_TO = 434;
    public static final int CXL_REJ_REASON = 102;
    public static final int TEXT = 58;

    // --- repeating groups -------------------------------------------------

    public static final int NO_PARTY_IDS = 453;
    public static final int PARTY_ID = 448;
    public static final int PARTY_ID_SOURCE = 447;
    public static final int PARTY_ROLE = 452;
    public static final int NO_PARTY_SUB_IDS = 802;
    public static final int PARTY_SUB_ID = 523;
    public static final int PARTY_SUB_ID_TYPE = 803;

    public static final int NO_CONTRA_BROKERS = 382;
    public static final int CONTRA_BROKER = 375;
    public static final int CONTRA_TRADER = 337;
    public static final int CONTRA_TRADE_QTY = 437;
    public static final int CONTRA_TRADE_TIME = 438;

    public static final int NO_ALLOCS = 78;
    public static final int ALLOC_ACCOUNT = 79;
    public static final int ALLOC_QTY = 80;

    public static final int NO_MD_ENTRIES = 268;
    public static final int MD_ENTRY_TYPE = 269;
    public static final int MD_ENTRY_PX = 270;
    public static final int MD_ENTRY_SIZE = 271;
    public static final int NO_MD_ENTRY_TYPES = 267;
    public static final int NO_RELATED_SYM = 146;

    /** Logon(35=A) credentials. */
    public static final int USERNAME = 553;
    public static final int PASSWORD = 554;
}
