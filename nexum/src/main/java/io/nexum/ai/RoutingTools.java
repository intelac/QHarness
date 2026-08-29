package io.nexum.ai;

import io.nexum.message.FixMessage;
import io.nexum.message.FixTags;
import io.nexum.routing.Router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where an order would go, and why.
 *
 * <p>Routing is decided from message content rather than from the connection it
 * arrived on, so "which venue will this reach" has an answer that nothing else
 * exposes: the rules live in configuration, and an agent holding only order and
 * session tools can discover them in exactly one way — by sending an order and
 * seeing where it lands. That is an expensive question to ask on a live venue,
 * and it cannot be asked at all about an order that should not be sent yet.
 *
 * <p>So this answers it without sending anything. The rules can be listed, and
 * a proposed order can be tried against them for the client it would be
 * recognised as and the destination it would take. Both readings come from the
 * same {@link Router} the engine itself uses, so what this reports and what
 * would happen cannot disagree.
 *
 * <p>It also gives the failures somewhere to point. An order that is refused
 * because no rule matched is refused for a reason that is written down, and
 * saying which condition failed turns "it did not route" into something a
 * person can act on.
 */
public final class RoutingTools {

    private final Router router;

    public RoutingTools(Router router) {
        this.router = router;
    }

    public List<AiTool> all() {
        return List.of(new ExplainRouting());
    }

    final class ExplainRouting implements AiTool {

        @Override
        public String name() {
            return "explain_routing";
        }

        @Override
        public String description() {
            return "Which client an order would be recognised as, and which venue"
                    + " it would be sent to — worked out from the routing rules"
                    + " without sending anything. Call with no arguments to see"
                    + " every rule; describe an order to see where that one would"
                    + " go, and which condition stops it when it would go nowhere."
                    + " Routing is decided by what an order carries, not by the"
                    + " session it arrives on, so a session being up does not mean"
                    + " orders reach it.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> p = new LinkedHashMap<>();
            p.put("symbol", Parameter.optional("string",
                    "The instrument, e.g. BP. Omit to list the rules instead."));
            p.put("side", Parameter.optionalOneOf(
                    "Which way the order goes. Defaults to buy.", "buy", "sell"));
            p.put("quantity", Parameter.optional("number", "How many shares"));
            p.put("price", Parameter.optional("number",
                    "Limit price. Omit for a market order."));
            p.put("onBehalfOf", Parameter.optional("string",
                    "Who the order is for, as OnBehalfOfCompID(115) would carry it"
                            + " — commonly what identifies the client. Rules"
                            + " frequently match on this, so a trial that omits it"
                            + " can miss the rule a real order would match."));
            p.put("fields", Parameter.optional("string",
                    "Any further FIX tags the rules might read, as tag=value pairs"
                            + " separated by commas, e.g. 100=XLON,15=GBP"));
            return p;
        }

        @Override
        public Effect effect() {
            // Reads the rules and matches against them in memory. Nothing is
            // journalled, no order is minted, nothing reaches a counterparty.
            return Effect.READ_ONLY;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            FixMessage trial = trialOrder(arguments);
            return trial == null ? listRules() : tryRules(trial);
        }
    }

    // ------------------------------------------------------------------
    // With no order to try: what the rules are
    // ------------------------------------------------------------------

    private AiTool.Result listRules() {
        List<Router.Rule> clients = router.clientRules();
        List<Router.Rule> destinations = router.destinationRules();

        StringBuilder text = new StringBuilder();
        text.append("Clients — who an order is recognised as:\n");
        appendRules(text, clients);
        text.append("\nDestinations — where an order is sent:\n");
        appendRules(text, destinations);
        text.append("\nRules are tried in order and the first match wins.");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clientRules", describeRules(clients));
        data.put("destinationRules", describeRules(destinations));
        return AiTool.Result.of(text.toString(), data);
    }

    private static void appendRules(StringBuilder text, List<Router.Rule> rules) {
        if (rules.isEmpty()) {
            text.append("  (none configured)\n");
            return;
        }
        for (int position = 0; position < rules.size(); position++) {
            Router.Rule rule = rules.get(position);
            text.append("  %d. %s  when %s%n".formatted(
                    position + 1, rule.target(), rule.fingerprint()));
        }
    }

    private static List<Object> describeRules(List<Router.Rule> rules) {
        List<Object> rows = new ArrayList<>();
        for (int position = 0; position < rules.size(); position++) {
            Router.Rule rule = rules.get(position);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", position + 1);
            row.put("target", rule.target());
            row.put("matches", rule.fingerprint().toString());
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // With an order to try: where it would go
    // ------------------------------------------------------------------

    private AiTool.Result tryRules(FixMessage trial) {
        Optional<String> client = router.toClient(trial);
        Optional<String> destination = router.toDestination(trial);

        StringBuilder text = new StringBuilder();
        Map<String, Object> data = new LinkedHashMap<>();

        // Both hops are reported even when the first fails. A trial sends
        // nothing, so there is no reason to stop at the first refusal and
        // hand back half of what was asked.
        if (client.isPresent()) {
            text.append("Recognised as client ").append(client.get()).append(".\n");
            data.put("client", client.get());
        } else {
            text.append("No client rule matches, so this order would be refused")
                    .append(" before it was routed. Why each rule did not match:\n");
            appendFailures(text, router.explainNoClient(trial));
            data.put("client", null);
            data.put("clientRefusals", router.explainNoClient(trial));
        }

        if (destination.isPresent()) {
            // Conditional where the first hop failed. The destination rule
            // matching says nothing about an order that is refused before
            // routing begins, and "would be sent" beside "would be refused"
            // reads as a contradiction rather than as two separate readings.
            text.append(client.isPresent()
                            ? "Would be sent to "
                            : "Its destination rule would be ")
                    .append(destination.get())
                    .append(client.isPresent() ? "." : ", but it does not get that far.");
            data.put("destination", destination.get());
        } else {
            text.append("No destination rule matches, so it would go nowhere.")
                    .append(" Why each rule did not match:\n");
            appendFailures(text, router.explainNoDestination(trial));
            data.put("destination", null);
            data.put("destinationRefusals", router.explainNoDestination(trial));
        }

        // Being routable is not the same as arriving: the destination still has
        // to be logged on, which is list_sessions' question rather than this
        // tool's, and saying so keeps the two from being confused.
        if (client.isPresent() && destination.isPresent()) {
            text.append("\n\nThis is where it would go, not whether it would"
                    + " arrive — list_sessions says whether that session is up.");
        }

        data.put("wouldRoute", client.isPresent() && destination.isPresent());
        return AiTool.Result.of(text.toString(), data);
    }

    private static void appendFailures(StringBuilder text, List<String> failures) {
        if (failures.isEmpty()) {
            text.append("  (no rules are configured)\n");
            return;
        }
        for (String failure : failures) {
            text.append("  ").append(failure).append('\n');
        }
    }

    // ------------------------------------------------------------------

    /**
     * The order to try the rules against, or null to list them instead.
     *
     * <p>A trial is worth running as soon as anything was described, not only
     * when enough was given to make a valid order: rules match on whatever tags
     * they name, and refusing to try until every field of a real order is
     * present would withhold the answer in exactly the case it is wanted — an
     * order that has not been written yet.
     */
    private static FixMessage trialOrder(Map<String, Object> arguments) {
        Map<Integer, String> fields = new LinkedHashMap<>();

        put(fields, FixTags.SYMBOL, text(arguments, "symbol"));
        put(fields, FixTags.ORDER_QTY, text(arguments, "quantity"));
        put(fields, FixTags.PRICE, text(arguments, "price"));
        put(fields, 115, text(arguments, "onBehalfOf"));

        String side = text(arguments, "side");
        if (side != null) {
            fields.put(FixTags.SIDE, "sell".equalsIgnoreCase(side) ? "2" : "1");
        }
        String price = text(arguments, "price");
        if (price != null) {
            fields.put(FixTags.ORD_TYPE, "2");
        }

        int described = fields.size();
        parseFields(text(arguments, "fields"), fields);

        // Nothing was described, so there is nothing to try the rules against.
        if (described == 0 && fields.isEmpty()) {
            return null;
        }
        return FixMessage.of("D", fields);
    }

    /**
     * Extra tags, as tag=value pairs.
     *
     * <p>Anything unparseable is skipped rather than refused: the rules are
     * matched on what was understood, and a trial that answers about most of
     * what was described beats one that answers about none of it.
     */
    private static void parseFields(String raw, Map<Integer, String> into) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String pair : raw.split(",")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                int tag = Integer.parseInt(pair.substring(0, equals).trim());
                into.put(tag, pair.substring(equals + 1).trim());
            } catch (NumberFormatException notATag) {
                // A tag is a number; anything else names nothing to match on.
            }
        }
    }

    private static void put(Map<Integer, String> fields, int tag, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(tag, value);
        }
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        String asText = String.valueOf(value);
        return asText.isBlank() ? null : asText;
    }
}
