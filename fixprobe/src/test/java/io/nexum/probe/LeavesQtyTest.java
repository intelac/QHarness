package io.nexum.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * That an execution report says what is still working.
 *
 * <p>LeavesQty was optional and absent meant zero, which is not "unstated" —
 * zero is what a venue reports when an order is finished. An acknowledgement
 * carrying OrderQty=1000 beside LeavesQty=0 tells its reader the order is both
 * fully open and entirely gone, and a system acting on the second field has a
 * position it believes closed.
 *
 * <p>It also wastes the test. No venue sends that report, so a scenario
 * omitting the field asks the system under test about a case that cannot
 * happen, while the case that does happen goes unasked. That is how a
 * conformance harness passes a system it never exercised.
 */
class LeavesQtyTest {

    @Test
    @DisplayName("an acknowledgement leaves the whole order working")
    void ackReportsTheWholeOrder() {
        assertEquals(1000d, sent(report("new", "new", 1000, null, null)),
                "nothing has traded, so all of it is still working");
    }

    @Test
    @DisplayName("a partial fill leaves what has not traded")
    void partialFillReportsTheRest() {
        assertEquals(600d, sent(report("trade", "partially_filled", 1000, 400d, null)),
                "400 of 1000 traded, so 600 is still working");
    }

    @Test
    @DisplayName("a full fill leaves nothing")
    void fullFillLeavesNothing() {
        assertEquals(0d, sent(report("trade", "filled", 1000, 1000d, null)));
    }

    @Test
    @DisplayName("a figure given is the figure sent")
    void anExplicitValueIsHonoured() {
        // A conformance harness has to be able to send a report a venue never
        // would — that is what it is for. Deriving the value only stops it
        // happening by accident.
        assertEquals(0d, sent(report("new", "new", 1000, null, 0d)),
                "an explicit zero must still send zero");
        assertEquals(9999d, sent(report("new", "new", 1000, null, 9999d)),
                "a deliberately wrong figure must reach the wire");
    }

    @Test
    @DisplayName("more traded than ordered does not leave a negative")
    void overfillDoesNotGoNegative() {
        // A venue that overfills is a case worth being able to send, but
        // LeavesQty is a quantity: negative is not a smaller number, it is a
        // field no counterparty can parse as one.
        assertEquals(0d, sent(report("trade", "filled", 1000, 1200d, null)));
    }

    // ------------------------------------------------------------------

    /** The arguments an agent would pass, with cumQty and leavesQty optional. */
    private static Map<String, Object> report(
            String execType, String ordStatus, double orderQty, Double cumQty, Double leavesQty) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("clOrdId", "X-1");
        a.put("orderId", "MKT-1");
        a.put("symbol", "BP");
        a.put("side", "buy");
        a.put("orderQty", orderQty);
        a.put("execType", execType);
        a.put("ordStatus", ordStatus);
        if (cumQty != null) a.put("cumQty", cumQty);
        if (leavesQty != null) a.put("leavesQty", leavesQty);
        return a;
    }

    /**
     * LeavesQty as it would go on the wire.
     *
     * <p>Read from the message the harness builds rather than from the helper
     * that computes it: what matters is the field a counterparty receives, and
     * a value correct on the way in can still be dropped on the way out.
     */
    private static double sent(Map<String, Object> arguments) {
        HarnessRig rig = new HarnessRig();
        // The tool's own derivation, not a copy of it: a test that recomputes
        // the rule passes whatever the rule becomes, including its removal.
        double leaves = HarnessTools.leavesQty(arguments);

        quickfix.Message message = rig.messages().executionReport(
                "MKT-1", "X-1", null, "BP", '1', num(arguments, "orderQty"),
                '0', '0', 0, num(arguments, "cumQty"), leaves, 50, null);
        try {
            return message.getDouble(151);
        } catch (quickfix.FieldNotFound absent) {
            throw new AssertionError("LeavesQty(151) is not on the report", absent);
        }
    }

    private static double num(Map<String, Object> arguments, String key) {
        Object v = arguments.get(key);
        return v == null ? 0 : ((Number) v).doubleValue();
    }
}
