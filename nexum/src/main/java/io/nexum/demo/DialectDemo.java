package io.nexum.demo;

import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.message.Dialect;
import io.nexum.message.DialectOverlay;
import io.nexum.message.DialectPlugin;
import io.nexum.message.DialectRegistry;
import io.nexum.message.FixCodec;
import io.nexum.message.FixMessage;
import io.nexum.message.FixVersion;
import io.nexum.message.GroupTemplate;
import io.nexum.message.SessionDialectPlugin;

import java.util.List;

/**
 * A session declares a FIX version and gets standard templates. A session that
 * deviates loads one overlay plugin stating only the difference.
 */
public final class DialectDemo {

    static final int NO_PARTY_IDS = 453;
    static final int PARTY_ID = 448;
    static final int PARTY_ROLE = 452;
    static final int BROKER_ACCOUNT = 20001;   // a tag BROKER_A adds to Parties
    static final int NO_CONTRA_BROKERS = 382;

    public static void main(String[] args) {
        Context ctx = new Context();
        PluginLoader loader = new PluginLoader(ctx);

        loader.load(List.of(
                new DialectPlugin(List.of(
                        new DialectPlugin.SessionDeclaration("BROKER_A", FixVersion.FIX44),
                        new DialectPlugin.SessionDeclaration("BROKER_B", FixVersion.FIX44),
                        new DialectPlugin.SessionDeclaration("VENUE_C", FixVersion.FIX42)))));

        DialectRegistry registry = ctx.get("dialects");

        System.out.println("=== three sessions, no dialect configuration yet ===");
        registry.describe().forEach((where, what) -> System.out.println("  " + where + " -> " + what));

        String wire = "35=D|11=ORD-1|55=IBM|"
                + "453=2|448=FUND_X|452=3|20001=ACCT99|448=BROKER_A|452=1|"
                + "38=1000|";

        System.out.println("\nwire:\n  " + wire);

        System.out.println("\n=== BROKER_B on the standard baseline ===");
        show(registry.codecForSession("BROKER_B"), wire);
        System.out.println("  20001 is not in the standard Parties group, so the second entry");
        System.out.println("  is cut short there — the standard reading of a non-standard message");

        System.out.println("\n=== now load BROKER_A's deviations ===");
        loader.load(List.of(new SessionDialectPlugin("BROKER_A", base ->
                DialectOverlay.on(base, "BROKER_A")
                        .replaceGroup("D", new GroupTemplate(
                                NO_PARTY_IDS,
                                PARTY_ID,
                                List.of(PARTY_ID, PARTY_ROLE, BROKER_ACCOUNT),
                                java.util.Map.of()))
                        .removeGroup("8", NO_CONTRA_BROKERS)
                        .build())));

        registry.describe().forEach((where, what) -> System.out.println("  " + where + " -> " + what));

        Dialect brokerA = registry.forSession("BROKER_A");
        if (brokerA instanceof DialectOverlay layered) {
            System.out.println("\n  deviations from " + layered.base().name() + ":");
            layered.deviations().forEach(line -> System.out.println("    " + line));
        }

        System.out.println("\n=== BROKER_A reading the same bytes ===");
        show(registry.codecForSession("BROKER_A"), wire);
        System.out.println("  20001 belongs to the group here, so both entries parse whole");

        System.out.println("\n=== BROKER_B is untouched by BROKER_A's overlay ===");
        show(registry.codecForSession("BROKER_B"), wire);

        System.out.println("\n=== unload BROKER_A's dialect ===");
        loader.unload("dialect-BROKER_A");
        registry.describe().forEach((where, what) -> System.out.println("  " + where + " -> " + what));
        System.out.println("  falls back to the declared version, never to nothing:");
        show(registry.codecForSession("BROKER_A"), wire);

        System.out.println("\n=== a session with no declared version fails loudly ===");
        try {
            registry.forSession("UNKNOWN");
        } catch (IllegalStateException expected) {
            System.out.println("  " + expected.getMessage());
        }
    }

    private static void show(FixCodec codec, String wire) {
        System.out.println("  dialect: " + codec.dialect().name());
        FixMessage msg = codec.parse(wire);
        List<FixMessage.Group> parties = msg.groups(NO_PARTY_IDS);
        System.out.println("  Parties(453): " + parties.size() + " entries");
        for (FixMessage.Group party : parties) {
            System.out.println("    id=" + party.get(PARTY_ID)
                    + "  role=" + party.get(PARTY_ROLE)
                    + "  account=" + party.get(BROKER_ACCOUNT));
        }
        codec.lastWarnings().forEach(w -> System.out.println("  ! " + w));
    }
}
