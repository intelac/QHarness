package io.nexum.demo;

import io.nexum.message.Dialect;
import io.nexum.message.FixCodec;
import io.nexum.message.FixMessage;
import io.nexum.message.GroupTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Repeating groups: parsed from a template, rewritten, and re-encoded. */
public final class GroupDemo {

    static final int NO_CONTRA_BROKERS = 382;
    static final int CONTRA_BROKER = 375;
    static final int CONTRA_TRADER = 337;
    static final int NO_PARTY_IDS = 453;
    static final int PARTY_ID = 448;
    static final int PARTY_ROLE = 452;
    static final int NO_PARTY_SUB_IDS = 802;
    static final int PARTY_SUB_ID = 523;
    static final int PARTY_SUB_ID_TYPE = 803;

    /** A dialect assembled in code; production loads the same shape from DD XML. */
    static Dialect demoDialect() {
        GroupTemplate partySubIds =
                GroupTemplate.of(NO_PARTY_SUB_IDS, PARTY_SUB_ID, PARTY_SUB_ID, PARTY_SUB_ID_TYPE);

        GroupTemplate parties =
                new GroupTemplate(
                        NO_PARTY_IDS,
                        PARTY_ID,
                        List.of(PARTY_ID, PARTY_ROLE, NO_PARTY_SUB_IDS),
                        Map.of(NO_PARTY_SUB_IDS, partySubIds));

        GroupTemplate contraBrokers =
                GroupTemplate.of(NO_CONTRA_BROKERS, CONTRA_BROKER, CONTRA_BROKER, CONTRA_TRADER);

        Map<Integer, GroupTemplate> forExecReport =
                Map.of(NO_CONTRA_BROKERS, contraBrokers, NO_PARTY_IDS, parties);

        return new Dialect() {
            public String name() {
                return "demo";
            }

            public Map<Integer, GroupTemplate> groupsFor(String msgType) {
                return "8".equals(msgType) ? forExecReport : Map.of();
            }
        };
    }

    public static void main(String[] args) {
        FixCodec codec = new FixCodec(demoDialect());

        String wire =
                "35=8|11=ORD-1|55=IBM|"
                        + "382=2|375=BRK1|337=TRD1|375=BRK2|337=TRD2|"
                        + "453=2|448=FUND_X|452=3|802=1|523=SUB1|803=1|448=BROKER_A|452=1|"
                        + "39=2|";

        System.out.println("wire in:\n  " + wire + "\n");

        FixMessage msg = codec.parse(wire);

        System.out.println("parsed:");
        System.out.println("  msgType   = " + msg.msgType());
        System.out.println("  ClOrdID   = " + msg.get(11));
        System.out.println("  Symbol    = " + msg.get(55));
        System.out.println("  OrdStatus = " + msg.get(39));

        System.out.println("\n  ContraBrokers(382): " + msg.groups(NO_CONTRA_BROKERS).size()
                + " entries");
        for (FixMessage.Group entry : msg.groups(NO_CONTRA_BROKERS)) {
            System.out.println("    broker=" + entry.get(CONTRA_BROKER)
                    + "  trader=" + entry.get(CONTRA_TRADER));
        }

        System.out.println("\n  Parties(453): " + msg.groups(NO_PARTY_IDS).size() + " entries");
        for (FixMessage.Group party : msg.groups(NO_PARTY_IDS)) {
            System.out.println("    id=" + party.get(PARTY_ID) + "  role=" + party.get(PARTY_ROLE));
            for (FixMessage.Group sub : party.nested(NO_PARTY_SUB_IDS)) {
                System.out.println("      sub=" + sub.get(PARTY_SUB_ID)
                        + "  type=" + sub.get(PARTY_SUB_ID_TYPE)
                        + "   <- nested group");
            }
        }

        System.out.println("\n-- routing sees flat fields only --");
        System.out.println("  " + msg.flatFields());
        System.out.println("  group contents are excluded by design");

        System.out.println("\n-- a destination plugin rewrites a party id --");
        List<FixMessage.Group> rewritten = new ArrayList<>();
        for (FixMessage.Group party : msg.groups(NO_PARTY_IDS)) {
            rewritten.add("FUND_X".equals(party.get(PARTY_ID))
                    ? party.set(PARTY_ID, "VENUE_ACCT_77")
                    : party);
        }
        FixMessage outbound = msg.setGroup(NO_PARTY_IDS, rewritten);

        System.out.println("  before: " + msg.groups(NO_PARTY_IDS).get(0).get(PARTY_ID));
        System.out.println("  after : " + outbound.groups(NO_PARTY_IDS).get(0).get(PARTY_ID));
        System.out.println("  original untouched: "
                + msg.groups(NO_PARTY_IDS).get(0).get(PARTY_ID)
                + "   (messages are immutable)");

        System.out.println("\n-- an entry is dropped; the counter follows automatically --");
        FixMessage trimmed = outbound.setGroup(
                NO_CONTRA_BROKERS, List.of(outbound.groups(NO_CONTRA_BROKERS).get(0)));
        String encoded = codec.encode(trimmed, '|');
        System.out.println("  " + encoded);
        System.out.println("  382 now reads " + trimmed.groups(NO_CONTRA_BROKERS).size()
                + " and cannot disagree with the entries");

        System.out.println("\n-- round trip --");
        FixMessage reparsed = codec.parse(encoded);
        boolean same = reparsed.groups(NO_PARTY_IDS).size() == trimmed.groups(NO_PARTY_IDS).size()
                && reparsed.groups(NO_CONTRA_BROKERS).size()
                        == trimmed.groups(NO_CONTRA_BROKERS).size()
                && reparsed.groups(NO_PARTY_IDS).get(0).nested(NO_PARTY_SUB_IDS).size() == 1;
        System.out.println("  groups survive encode/parse: " + (same ? "OK" : "FAIL"));

        System.out.println("\n-- without a template the same bytes flatten --");
        FixMessage flat = new FixCodec(Dialect.flat("none")).parse(wire);
        System.out.println("  groups found: " + flat.allGroups().size());
        System.out.println("  375 collapsed to: " + flat.get(CONTRA_BROKER)
                + "   (first entry lost, only the last survives)");
    }
}
