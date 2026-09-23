package io.nexum.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("what belongs to the session and what belongs to the order")
class FixLayersTest {

    @Test
    @DisplayName("the version fields a FIXT session carries are session-layer")
    void fixtVersionFieldsAreSessionLayer() {
        // ApplVerID states which application version a message is in, which is
        // a fact about the session it crossed. Recording it against the order
        // would put the counterparty's protocol level in the order's history,
        // where it was never a term anyone agreed.
        assertFalse(FixLayers.isBusiness(FixTags.APPL_VER_ID),
                "ApplVerID(1128) belongs to the session, like the routing fields around it");
        assertFalse(FixLayers.isBusiness(FixTags.DEFAULT_APPL_VER_ID),
                "DefaultApplVerID(1137) is logon-time session negotiation");
        assertTrue(FixLayers.isHeader(FixTags.APPL_VER_ID),
                "it rides in the header, which is where a hop rewrites it");
    }

    @Test
    @DisplayName("the terms of an order stay business fields")
    void orderTermsStayBusiness() {
        assertTrue(FixLayers.isBusiness(FixTags.CL_ORD_ID));
        assertTrue(FixLayers.isBusiness(FixTags.SYMBOL));
        assertTrue(FixLayers.isBusiness(FixTags.ORDER_QTY));
        assertTrue(FixLayers.isBusiness(FixTags.PRICE));
    }

    @Test
    @DisplayName("what changes at every hop is not the order's own")
    void perHopFieldsAreNotTheOrders() {
        assertFalse(FixLayers.isBusiness(FixTags.MSG_SEQ_NUM));
        assertFalse(FixLayers.isBusiness(FixTags.SENDER_COMP_ID));
        assertFalse(FixLayers.isBusiness(FixTags.TARGET_COMP_ID));
        assertFalse(FixLayers.isBusiness(FixTags.SENDING_TIME));
        assertFalse(FixLayers.isBusiness(FixTags.ON_BEHALF_OF_COMP_ID),
                "the field this rule was written for");
    }

    @Test
    @DisplayName("the trailer is a subset of the session layer, not a third kind")
    void theTrailerIsSessionLayer() {
        for (int tag : FixLayers.TRAILER) {
            assertFalse(FixLayers.isBusiness(tag),
                    () -> "trailer field " + tag + " is session-layer by definition");
        }
    }
}
