package io.nexum.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("an execution report is written in the version of the session it goes out on")
class VersionAdapterTest {

    private static FixMessage report(String execType, String leaves, Map<Integer, String> extra) {
        FixMessage message = FixMessage.of("8", Map.of(
                FixTags.CL_ORD_ID, "C-1",
                FixTags.EXEC_TYPE, execType,
                FixTags.LEAVES_QTY, leaves));
        for (Map.Entry<Integer, String> field : extra.entrySet()) {
            message = message.set(field.getKey(), field.getValue());
        }
        return message;
    }

    @Test
    @DisplayName("going to 4.2, a Trade becomes the partial or full fill 4.2 names it")
    void tradeBecomesFillForFourTwo() {
        assertEquals("1", VersionAdapter.adapt(report("F", "300", Map.of()), FixVersion.FIX42)
                .get(FixTags.EXEC_TYPE), "quantity still open is a partial fill");
        assertEquals("2", VersionAdapter.adapt(report("F", "0", Map.of()), FixVersion.FIX42)
                .get(FixTags.EXEC_TYPE), "nothing left open is a fill");
    }

    @Test
    @DisplayName("going to 4.2, ExecTransType is supplied when the report arrived without it")
    void fourTwoGetsExecTransType() {
        FixMessage adapted = VersionAdapter.adapt(report("0", "100", Map.of()), FixVersion.FIX42);

        assertEquals("0", adapted.get(FixTags.EXEC_TRANS_TYPE),
                "required through 4.2; New is the only kind this engine sends");
    }

    @Test
    @DisplayName("going to 4.2, an ExecTransType the report already carries is kept")
    void fourTwoKeepsAGivenExecTransType() {
        FixMessage adapted = VersionAdapter.adapt(
                report("0", "100", Map.of(FixTags.EXEC_TRANS_TYPE, "3")), FixVersion.FIX42);

        assertEquals("3", adapted.get(FixTags.EXEC_TRANS_TYPE),
                "a status report from a 4.2 venue says so, and that is not ours to overwrite");
    }

    @Test
    @DisplayName("going to 4.4 or 5.0, a 4.2 fill becomes a Trade and ExecTransType is dropped")
    void fourTwoFillBecomesTrade() {
        for (FixVersion target : new FixVersion[] {
                FixVersion.FIX44, FixVersion.FIX50, FixVersion.FIX50SP2 }) {
            FixMessage partial = VersionAdapter.adapt(
                    report("1", "300", Map.of(FixTags.EXEC_TRANS_TYPE, "0")), target);
            FixMessage full = VersionAdapter.adapt(report("2", "0", Map.of()), target);

            assertEquals("F", partial.get(FixTags.EXEC_TYPE), target.label());
            assertEquals("F", full.get(FixTags.EXEC_TYPE), target.label());
            // 5.0SP1 and later do not define 1 or 2 at all, and none of 4.3
            // onwards defines ExecTransType on this message.
            assertFalse(partial.has(FixTags.EXEC_TRANS_TYPE), target.label());
        }
    }

    @Test
    @DisplayName("an ExecType both versions define crosses unchanged")
    void aSharedExecTypeIsUnchanged() {
        assertEquals("4", VersionAdapter.adapt(report("4", "0", Map.of()), FixVersion.FIX42)
                .get(FixTags.EXEC_TYPE));
        assertEquals("0", VersionAdapter.adapt(report("0", "100", Map.of()), FixVersion.FIX44)
                .get(FixTags.EXEC_TYPE));
    }

    @Test
    @DisplayName("messages other than execution reports are not touched")
    void otherMessagesAreUntouched() {
        FixMessage cancelReject = FixMessage.of("9", Map.of(FixTags.CL_ORD_ID, "C-1"));

        assertSame(cancelReject, VersionAdapter.adapt(cancelReject, FixVersion.FIX42));
    }
}
