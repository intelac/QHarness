package io.nexum.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("each session reads its own version's repeating groups")
class DialectVersionTest {

    private static FixMessage parse(FixVersion version, String wire) {
        DialectRegistry registry = new DialectRegistry();
        registry.declareSession("OMS->X", version);
        return registry.codecForSession("OMS->X").parse(wire);
    }

    @Test
    @DisplayName("a 5.0 order's parties are read as a group, from the 5.0 dictionary")
    void fiveReadsItsGroups() {
        // Business messages only: the 5.0 dictionary holds all of them, and the
        // FIXT one holds the seven session messages, which never reach a codec.
        FixMessage order = parse(FixVersion.FIX50,
                "8=FIXT.1.1|35=D|1128=7|11=C-1|453=2"
                        + "|448=BROKER|447=D|452=1"
                        + "|448=DESK|447=D|452=12"
                        + "|55=BP|54=1|38=100|40=2|44=50|");

        List<FixMessage.Group> parties = order.groups(453);
        assertEquals(2, parties.size(), "both entries, not the last one flattened over the first");
        assertEquals("BROKER", parties.get(0).fields().get(448));
        assertEquals("DESK", parties.get(1).fields().get(448));
        assertEquals("BP", order.get(55), "the field after the group is outside it");
    }

    @Test
    @DisplayName("a 4.2 order's allocations are read as a group, from the 4.2 dictionary")
    void fourTwoReadsItsGroups() {
        FixMessage order = parse(FixVersion.FIX42,
                "8=FIX.4.2|35=D|11=C-1|21=1|78=2"
                        + "|79=ACCT-A|80=600"
                        + "|79=ACCT-B|80=400"
                        + "|55=BP|54=1|38=1000|40=2|44=50|");

        List<FixMessage.Group> allocs = order.groups(78);
        assertEquals(2, allocs.size());
        assertEquals("ACCT-A", allocs.get(0).fields().get(79));
        assertEquals("400", allocs.get(1).fields().get(80));
    }

    @Test
    @DisplayName("a 4.4 allocation's nested parties are a group inside the allocation")
    void fourFourNestedPartiesSurvive() {
        // NestedParties is defined once and referenced from other definitions.
        // Reading every <component> under <components> recursively picked up
        // those references too, and a later empty reference replaced the
        // definition: the group came out flat, its entries unreadable.
        FixMessage order = parse(FixVersion.FIX44,
                "8=FIX.4.4|35=D|11=C-1|78=1"
                        + "|79=ACCT-A|539=2"
                        + "|524=TRADER-1|525=D|538=11"
                        + "|524=TRADER-2|525=D|538=12"
                        + "|80=1000"
                        + "|55=BP|54=1|38=1000|40=2|44=50|");

        List<FixMessage.Group> allocs = order.groups(78);
        assertEquals(1, allocs.size());
        List<FixMessage.Group> nested = allocs.get(0).nested().get(539);
        assertEquals(2, nested == null ? 0 : nested.size(),
                "both nested parties, inside the allocation they belong to");
        assertEquals("TRADER-2", nested.get(1).fields().get(524));
        assertEquals("1000", allocs.get(0).fields().get(80),
                "the allocation's own field after the nested group is still the allocation's");
    }

    @Test
    @DisplayName("the startup dump tells the 5.0 versions apart")
    void theDumpTellsFiveVersionsApart() {
        DialectRegistry registry = new DialectRegistry();
        registry.declareSession("A->B", FixVersion.FIX50);
        registry.declareSession("A->C", FixVersion.FIX50SP2);

        String fifty = registry.describe().get("session:A->B");
        String sp2 = registry.describe().get("session:A->C");

        // Both go out as FIXT.1.1, which is exactly why that cannot be what the
        // dump prints: an operator reading it could not tell which is which.
        assertNotEquals(fifty, sp2);
        assertEquals("FIX.5.0 (standard)", fifty);
        assertEquals("FIX.5.0SP2 (standard)", sp2);
    }

    @Test
    @DisplayName("a standard dialect is named for its version, not for its wire")
    void aDialectIsNamedForItsVersion() {
        assertEquals("FIX.5.0SP1", StandardDialects.of(FixVersion.FIX50SP1).name());
        assertEquals("FIX.4.4", StandardDialects.of(FixVersion.FIX44).name());
    }
}
