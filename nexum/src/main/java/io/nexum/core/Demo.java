package io.nexum.core;

import java.util.List;

/**
 * Exercises the three properties the kernel exists to provide: order derived
 * from declarations, a waterfall that can transform and short-circuit, and an
 * unload that leaves nothing behind.
 *
 * <p>Run with: {@code java io.nexum.core.Demo}
 */
public final class Demo {

    /** Stands in for a FIX message: a mutable-free carrier the gates rewrite. */
    record Msg(String body, boolean rejected, String reason) {
        static Msg of(String body) {
            return new Msg(body, false, null);
        }

        Msg with(String newBody) {
            return new Msg(newBody, rejected, reason);
        }

        Msg reject(String why) {
            return new Msg(body, true, why);
        }
    }

    /** Provides a service others depend on. Declares nothing itself. */
    static final class Dictionary implements Plugin {
        public String name() {
            return "dictionary";
        }

        public void apply(Context ctx) {
            ctx.register("dictionary", "FIX44");
            System.out.println("  [start] dictionary");
        }
    }

    /** Rewrites the message, then delegates. An enricher never short-circuits. */
    static final class Enricher implements Plugin {
        public String name() {
            return "enricher";
        }

        public List<String> inject() {
            return List.of("dictionary");
        }

        public void apply(Context ctx) {
            String dict = ctx.get("dictionary");
            ctx.onGate("message/inbound", (Events.Gate<Msg>) (msg, next) -> {
                Msg enriched = msg.with(msg.body() + "|21=1|60=NOW");
                System.out.println("  [enrich] added fields using " + dict);
                return next.apply(enriched);
            });
            System.out.println("  [start] enricher");
        }
    }

    /** Has decision authority, so it may refuse to delegate. */
    static final class Validator implements Plugin {
        public String name() {
            return "validator";
        }

        public List<String> inject() {
            return List.of("dictionary");
        }

        public void apply(Context ctx) {
            ctx.onGate("message/inbound", (Events.Gate<Msg>) (msg, next) -> {
                if (msg.body().contains("55=BAD")) {
                    System.out.println("  [validate] REJECT — unknown symbol");
                    return msg.reject("unknown symbol");
                }
                System.out.println("  [validate] pass");
                return next.apply(msg);
            });
            System.out.println("  [start] validator");
        }
    }

    /** Observes only, so it must always delegate. */
    static final class AuditLog implements Plugin {
        public String name() {
            return "audit";
        }

        public void apply(Context ctx) {
            ctx.onGate("message/inbound", (Events.Gate<Msg>) (msg, next) -> {
                Msg result = next.apply(msg);
                System.out.println("  [audit] in=" + msg.body());
                System.out.println("  [audit] out=" + result.body()
                        + (result.rejected() ? " REJECTED(" + result.reason() + ")" : ""));
                return result;
            });
            System.out.println("  [start] audit");
        }
    }

    public static void main(String[] args) {
        Context ctx = new Context();
        PluginLoader loader = new PluginLoader(ctx);

        System.out.println("== load (declared in dependency-violating order) ==");
        // Deliberately listed wrong: validator and enricher both need dictionary,
        // which appears last. The loader must fix this without being told.
        loader.load(List.of(new AuditLog(), new Validator(), new Enricher(), new Dictionary()));
        System.out.println("loaded: " + loader.loadedNames());

        System.out.println("\n== a good message ==");
        Msg ok = ctx.waterfall("message/inbound", Msg.of("35=D|55=IBM"), m -> {
            System.out.println("  [engine] accepted: " + m.body());
            return m;
        });
        System.out.println("result: " + ok);

        System.out.println("\n== a message the validator refuses ==");
        Msg bad = ctx.waterfall("message/inbound", Msg.of("35=D|55=BAD"), m -> {
            System.out.println("  [engine] SHOULD NOT RUN");
            return m;
        });
        System.out.println("result: " + bad);

        System.out.println("\n== unload the enricher ==");
        loader.unload("enricher");
        System.out.println("loaded: " + loader.loadedNames());

        Msg after = ctx.waterfall("message/inbound", Msg.of("35=D|55=IBM"), m -> {
            System.out.println("  [engine] accepted: " + m.body());
            return m;
        });
        System.out.println("result: " + after);
        System.out.println(after.body().contains("21=1")
                ? "FAIL — enricher left residue behind"
                : "OK — enricher gone, no residue");

        System.out.println("\n== missing dependency is caught at load ==");
        try {
            new PluginLoader(new Context()).load(List.of(new Validator()));
        } catch (IllegalStateException expected) {
            System.out.println("OK — " + expected.getMessage());
        }

        loader.unloadAll();
        System.out.println("\nafter unloadAll: " + loader.loadedNames());
    }
}
