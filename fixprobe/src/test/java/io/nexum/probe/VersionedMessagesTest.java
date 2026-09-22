package io.nexum.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.field.ApplVerID;
import quickfix.field.BeginString;
import quickfix.field.ExecTransType;
import quickfix.field.MsgType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("messages carry the version their session speaks")
class VersionedMessagesTest {

    private static Message execution(ProbeFixVersion version) {
        return new HarnessMessages(version).executionReport(
                "V-1", "C-1", null, "BP", '1', 1000,
                '0', '0', 0, 0, 1000, 50, null);
    }

    @Test
    @DisplayName("a 4.x message names its version in BeginString and nothing else")
    void fourPointXNamesItselfInBeginString() throws Exception {
        Message order = new HarnessMessages(ProbeFixVersion.FIX42)
                .newOrderSingle("C-1", "BP", '1', 100, 50.0, null, null);

        assertEquals("FIX.4.2", order.getHeader().getString(BeginString.FIELD));
        assertFalse(order.getHeader().isSetField(ApplVerID.FIELD),
                "BeginString already says the version; ApplVerID would be a second answer");
    }

    @Test
    @DisplayName("a 5.0 message goes out over FIXT.1.1 and names its application version")
    void fiveGoesOverFixt() throws Exception {
        Message order = new HarnessMessages(ProbeFixVersion.FIX50)
                .newOrderSingle("C-1", "BP", '1', 100, 50.0, null, null);

        assertEquals("FIXT.1.1", order.getHeader().getString(BeginString.FIELD));
        assertEquals("7", order.getHeader().getString(ApplVerID.FIELD),
                "without it the counterparty parses against whatever it defaults to");
    }

    @Test
    @DisplayName("every version builds the same message type")
    void everyVersionBuildsTheSameType() throws Exception {
        for (ProbeFixVersion version : ProbeFixVersion.values()) {
            Message order = new HarnessMessages(version)
                    .newOrderSingle("C-1", "BP", '1', 100, 50.0, null, null);
            assertEquals(MsgType.ORDER_SINGLE, order.getHeader().getString(MsgType.FIELD),
                    () -> version + " builds a new order single");
        }
    }

    @Test
    @DisplayName("ExecTransType rides on a 4.2 report and on no other")
    void execTransTypeIsFourTwoOnly() throws Exception {
        Message fourTwo = execution(ProbeFixVersion.FIX42);

        assertTrue(fourTwo.isSetField(ExecTransType.FIELD),
                "4.2 requires it, and a report without it is rejected outright");
        // As a value, not as the code of the character that spells it: a report
        // reading 20=48 names no transaction type the counterparty knows.
        assertEquals("0", fourTwo.getString(ExecTransType.FIELD));

        assertFalse(execution(ProbeFixVersion.FIX44).isSetField(ExecTransType.FIELD),
                "4.4 removed it");
        assertFalse(execution(ProbeFixVersion.FIX50).isSetField(ExecTransType.FIELD),
                "and it did not come back in the 5.0 series");
    }

    @Test
    @DisplayName("the fields a caller gave are on the message whatever the version")
    void theCallersFieldsSurvive() throws Exception {
        for (ProbeFixVersion version : ProbeFixVersion.values()) {
            Message report = execution(version);
            assertEquals("V-1", report.getString(quickfix.field.OrderID.FIELD));
            assertEquals("C-1", report.getString(quickfix.field.ClOrdID.FIELD));
            assertEquals("BP", report.getString(quickfix.field.Symbol.FIELD));
            assertEquals(1000, (int) report.getDouble(quickfix.field.LeavesQty.FIELD));
        }
    }
}
