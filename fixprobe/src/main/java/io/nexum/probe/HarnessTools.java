package io.nexum.probe;

import io.nexum.ai.AiTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The harness, as tools an agent can call.
 *
 * <p>Testing an order router means standing on both sides of it: sending it a
 * request as a client would, then answering as the market would and seeing what
 * it passes back. These expose both halves, so an agent can drive a scenario
 * end to end and read what the system under test did with each message.
 *
 * <p>Replies are never automatic. The agent chooses the execution report it
 * sends, including one a well-behaved venue would never produce, because how
 * the system handles those is what a conformance test is for.
 */
public final class HarnessTools {

    private final HarnessRig rig;

    public HarnessTools(HarnessRig rig) {
        this.rig = rig;
    }

    /** Every harness tool, for registration with an MCP server. */
    public List<AiTool> tools() {
        return List.of(
                new Connect(), new Disconnect(), new Status(),
                new SendOrder(), new SendCancel(), new SendReplace(),
                new SendExecution(), new SendCancelReject(),
                new Traffic(), new ClearTraffic());
    }

    // ------------------------------------------------------------------

    /** Bring one side of the harness up against the system under test. */
    private final class Connect implements AiTool {

        @Override
        public String name() {
            return "harness_connect";
        }

        @Override
        public String description() {
            return "Bring up one side of the test harness against the system under test. "
                    + "Start the market side first: the system dials out to it, and a client "
                    + "brought up before it has nothing to reach. The client side dials in as "
                    + "a client would, to the port the system listens on; the market side "
                    + "listens on the port the system dials out to. Then call harness_status "
                    + "until both sides report logged on. Typical order: "
                    + "harness_connect(side=market, port=<it dials out to>), "
                    + "harness_connect(side=client, port=<it listens on>), harness_status.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            parameters.put("side", Parameter.required("string",
                    "What to call this endpoint. \"client\" and \"market\" are the"
                            + " usual two and imply which way they connect; any other"
                            + " name is fine, and needs \"dials\" to say."));
            parameters.put("dials", Parameter.optionalOneOf(
                    "Whether this endpoint dials out or waits to be dialled. Only"
                            + " needed for a name other than client or market:"
                            + " a client dials in, a market is dialled.",
                    "out", "in"));
            parameters.put("port", Parameter.required("number",
                    "The port: the one the system listens on for a client side, "
                            + "the one it dials out to for a market side"));
            parameters.put("senderCompId", Parameter.required("string",
                    "Who the harness claims to be, e.g. FUNDX for a client"));
            parameters.put("targetCompId", Parameter.required("string",
                    "Who the harness expects to be talking to, i.e. the system under test"));
            parameters.put("host", Parameter.optional("string",
                    "Where the system under test listens; client side only, default 127.0.0.1"));
            return parameters;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public String destination() {
            return DESTINATION;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String side = text(arguments, "side");
            // Optional, so read it without text(), which treats absence as a
            // caller error.
            Object dialsGiven = arguments.get("dials");
            String dials = dialsGiven == null ? null : String.valueOf(dialsGiven);
            String host = arguments.get("host") == null
                    ? "127.0.0.1" : text(arguments, "host");
            int port = (int) number(arguments, "port");
            String sender = text(arguments, "senderCompId");
            String target = text(arguments, "targetCompId");
            try {
                // The two usual sides imply which way they connect; a name the
                // scenario invented does not, and guessing for it would stand
                // up an endpoint at the wrong end of the connection.
                if (dials == null) {
                    rig.connect(side, host, port, sender, target);
                } else {
                    rig.connect(side, "out".equals(dials), host, port, sender, target);
                }
            } catch (Exception failure) {
                return Result.failed("could not bring up " + side + ": "
                        + failure.getMessage());
            }
            return Result.of(side + " is up; call harness_status to see whether it "
                    + "has logged on yet");
        }
    }

    /** Take one side down. */
    private final class Disconnect implements AiTool {

        @Override
        public String name() {
            return "harness_disconnect";
        }

        @Override
        public String description() {
            return "Take one side of the harness down, so it can be pointed somewhere else.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of("side", Parameter.required("string", "Which endpoint to take down"));
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public String destination() {
            return DESTINATION;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String side = text(arguments, "side");
            rig.disconnect(side);
            return Result.of(side + " side is down");
        }
    }

    /** Whether each side is up and logged on. */
    private final class Status implements AiTool {

        @Override
        public String name() {
            return "harness_status";
        }

        @Override
        public String description() {
            return "Whether each side of the harness is connected and logged on, and how "
                    + "many messages have crossed it.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of();
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            StringBuilder text = new StringBuilder();
            List<Object> rows = new ArrayList<>();
            // Whatever is up, not a fixed pair: an endpoint the scenario named
            // is invisible in a listing that only knows two words, and a
            // listing that cannot show an endpoint cannot be used to find it.
            List<String> running = rig.names();
            for (String name : running) {
                CounterpartyHarness endpoint = rig.side(name);
                boolean loggedOn = endpoint != null && endpoint.isLoggedOn();
                int messages = endpoint == null ? 0 : endpoint.traffic().size();
                text.append(name).append(": ")
                        .append(loggedOn ? "logged on" : "connecting")
                        .append("  ").append(messages).append(" messages")
                        .append('\n');
                rows.add(Map.of("endpoint", name, "started", true,
                        "loggedOn", loggedOn, "messages", messages));
            }

            // The two usual sides are worth mentioning when absent, because a
            // scenario that forgot one reads the same as one that has not got
            // there yet. A named endpoint has no such expectation to miss.
            for (String usual : List.of(HarnessRig.CLIENT, HarnessRig.MARKET)) {
                if (!running.contains(usual)) {
                    text.append(usual).append(": not started\n");
                    rows.add(Map.of("endpoint", usual, "started", false,
                            "loggedOn", false, "messages", 0));
                }
            }
            return Result.of(text.toString().trim(), Map.of("sides", rows));
        }
    }

    /** Client side: send a new order to the system under test. */
    private final class SendOrder implements AiTool {

        @Override
        public String name() {
            return "harness_send_order";
        }

        @Override
        public String description() {
            return "Send a new order into the system under test, as a client would. "
                    + "Omit price for a market order.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            parameters.put("clOrdId", Parameter.required("string",
                    "The client order id; every later message about this order names it"));
            parameters.put("symbol", Parameter.required("string", "e.g. BP"));
            parameters.put("side", Parameter.oneOf("Which way", "buy", "sell"));
            parameters.put("quantity", Parameter.required("number", "How many"));
            parameters.put("price", Parameter.optional("number",
                    "Limit price; omitting it sends a market order"));
            parameters.put("account", Parameter.optional("string", "Account, when the system needs one"));
            parameters.put("onBehalfOf", Parameter.optional("string", ON_BEHALF_OF));
            parameters.put("endpoint", Parameter.optional("string",
                    "Which endpoint to send from, when more than one is up."
                            + " Defaults to the usual side for this message."));
            return parameters;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public String destination() {
            return DESTINATION;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            return rig.on(endpointOr(arguments, HarnessRig.CLIENT), endpoint -> {
                Double price = arguments.get("price") == null
                        ? null : number(arguments, "price");
                endpoint.send(rig.messages().newOrderSingle(
                        text(arguments, "clOrdId"), text(arguments, "symbol"),
                        side(arguments), number(arguments, "quantity"), price,
                        arguments.get("account") == null ? null : text(arguments, "account"),
                        onBehalfOf(arguments)));
                return Result.of("sent order " + text(arguments, "clOrdId")
                        + "; read what came back with harness_traffic");
            });
        }
    }

    /** Client side: cancel an order. */
    private final class SendCancel implements AiTool {

        @Override
        public String name() {
            return "harness_send_cancel";
        }

        @Override
        public String description() {
            return "Ask the system under test to cancel an order, as a client would.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            parameters.put("clOrdId", Parameter.required("string",
                    "A new id for the cancel request itself"));
            parameters.put("origClOrdId", Parameter.required("string",
                    "The id of the order being cancelled"));
            parameters.put("symbol", Parameter.required("string", "e.g. BP"));
            parameters.put("side", Parameter.oneOf("The original order's side", "buy", "sell"));
            parameters.put("quantity", Parameter.required("number", "The original order quantity"));
            parameters.put("onBehalfOf", Parameter.optional("string", ON_BEHALF_OF));
            parameters.put("endpoint", Parameter.optional("string",
                    "Which endpoint to send from, when more than one is up."
                            + " Defaults to the usual side for this message."));
            return parameters;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public String destination() {
            return DESTINATION;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            return rig.on(endpointOr(arguments, HarnessRig.CLIENT), endpoint -> {
                endpoint.send(rig.messages().cancelRequest(
                        text(arguments, "clOrdId"), text(arguments, "origClOrdId"),
                        text(arguments, "symbol"), side(arguments),
                        number(arguments, "quantity"), onBehalfOf(arguments)));
                return Result.of("sent cancel for " + text(arguments, "origClOrdId"));
            });
        }
    }

    /** Client side: amend an order. */
    private final class SendReplace implements AiTool {

        @Override
        public String name() {
            return "harness_send_replace";
        }

        @Override
        public String description() {
            return "Ask the system under test to amend an order's quantity or price, "
                    + "as a client would.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            parameters.put("clOrdId", Parameter.required("string",
                    "A new id for the amended order"));
            parameters.put("origClOrdId", Parameter.required("string",
                    "The id of the order being amended"));
            parameters.put("symbol", Parameter.required("string", "e.g. BP"));
            parameters.put("side", Parameter.oneOf("The original order's side", "buy", "sell"));
            parameters.put("quantity", Parameter.required("number", "The amended quantity"));
            parameters.put("price", Parameter.optional("number", "The amended limit price"));
            parameters.put("onBehalfOf", Parameter.optional("string", ON_BEHALF_OF));
            parameters.put("endpoint", Parameter.optional("string",
                    "Which endpoint to send from, when more than one is up."
                            + " Defaults to the usual side for this message."));
            return parameters;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public String destination() {
            return DESTINATION;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            return rig.on(endpointOr(arguments, HarnessRig.CLIENT), endpoint -> {
                Double price = arguments.get("price") == null ? null : number(arguments, "price");
                endpoint.send(rig.messages().replaceRequest(
                        text(arguments, "clOrdId"), text(arguments, "origClOrdId"),
                        text(arguments, "symbol"), side(arguments),
                        number(arguments, "quantity"), price, onBehalfOf(arguments)));
                return Result.of("sent amendment for " + text(arguments, "origClOrdId"));
            });
        }
    }

    /** Market side: answer the system under test with an execution report. */
    private final class SendExecution implements AiTool {

        @Override
        public String name() {
            return "harness_send_execution";
        }

        @Override
        public String description() {
            return "Answer the system under test with an execution report, as a market would: "
                    + "an acknowledgement, a fill, a partial fill, a cancel confirmation, or a "
                    + "rejection. Nothing is derived — execType and ordStatus are sent exactly "
                    + "as given, so combinations a real venue would not send can be tested too. "
                    + "The clOrdId is NOT the one the client sent: the system forwards under an "
                    + "id of its own, and this report has to name that one. Read it first with "
                    + "harness_traffic(side=market, msgType=D) and take ClOrdID(11) from the "
                    + "message shown there.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            parameters.put("clOrdId", Parameter.required("string",
                    "The client order id from the order the system under test sent"));
            parameters.put("orderId", Parameter.required("string",
                    "The market's own id for the order; keep it the same across reports"));
            parameters.put("symbol", Parameter.required("string", "e.g. BP"));
            parameters.put("side", Parameter.oneOf("The order's side", "buy", "sell"));
            parameters.put("orderQty", Parameter.required("number", "The order's total quantity"));
            parameters.put("execType", Parameter.oneOf(
                    "What happened: new acknowledges, trade reports a fill",
                    "new", "trade", "canceled", "replaced", "rejected", "expired", "pending_cancel"));
            parameters.put("ordStatus", Parameter.oneOf(
                    "What the order now is",
                    "new", "partially_filled", "filled", "canceled", "replaced",
                    "rejected", "expired", "pending_cancel"));
            parameters.put("lastQty", Parameter.optional("number",
                    "How much traded on this report; 0 or omitted for a non-trade"));
            parameters.put("cumQty", Parameter.optional("number",
                    "Total traded so far across the whole order, including this"
                            + " report — not the quantity on this one. It only"
                            + " ever grows, so a cancel confirmation carries what"
                            + " already traded: omitting it there tells the client"
                            + " a filled position vanished. Omitted means 0, which"
                            + " is a claim, not a blank."));
            parameters.put("leavesQty", Parameter.optional("number",
                    "How much is still working. Omit and it is OrderQty - CumQty,"
                            + " which is what a venue reports; state 0 to send an"
                            + " order that is finished, or a wrong figure on purpose."));
            parameters.put("price", Parameter.optional("number", "The price to report"));
            parameters.put("origClOrdId", Parameter.optional("string",
                    "The previous id, on a cancel or replace confirmation"));
            parameters.put("text", Parameter.optional("string", "A reason, for a rejection"));
            parameters.put("endpoint", Parameter.optional("string",
                    "Which endpoint to send from, when more than one is up."
                            + " Defaults to the usual side for this message."));
            return parameters;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public String destination() {
            return DESTINATION;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            return rig.on(endpointOr(arguments, HarnessRig.MARKET), endpoint -> {
                endpoint.send(rig.messages().executionReport(
                        text(arguments, "orderId"), text(arguments, "clOrdId"),
                        arguments.get("origClOrdId") == null ? null : text(arguments, "origClOrdId"),
                        text(arguments, "symbol"), side(arguments),
                        number(arguments, "orderQty"),
                        execType(text(arguments, "execType")),
                        ordStatus(text(arguments, "ordStatus")),
                        optional(arguments, "lastQty"), optional(arguments, "cumQty"),
                        leavesQty(arguments), optional(arguments, "price"),
                        arguments.get("text") == null ? null : text(arguments, "text")));
                return Result.of("sent " + text(arguments, "execType")
                        + " for " + text(arguments, "clOrdId"));
            });
        }
    }

    /** Market side: refuse a cancel or replace. */
    private final class SendCancelReject implements AiTool {

        @Override
        public String name() {
            return "harness_send_cancel_reject";
        }

        @Override
        public String description() {
            return "Refuse a cancel or an amendment, as a market would. responseTo is what "
                    + "distinguishes the two, and it is the only field that does: ordStatus "
                    + "here reports the order's own state, so a system reading that instead "
                    + "cannot tell a refusal apart from a status message.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            parameters.put("clOrdId", Parameter.required("string",
                    "The id of the request being refused"));
            parameters.put("origClOrdId", Parameter.required("string",
                    "The id of the order it named"));
            parameters.put("orderId", Parameter.required("string", "The market's id for the order"));
            parameters.put("responseTo", Parameter.oneOf(
                    "Which request is refused", "cancel", "replace"));
            parameters.put("ordStatus", Parameter.oneOf(
                    "The order's own state, unchanged by the refusal",
                    "new", "partially_filled", "filled", "canceled", "rejected"));
            parameters.put("reason", Parameter.optional("string", "Why"));
            parameters.put("endpoint", Parameter.optional("string",
                    "Which endpoint to send from, when more than one is up."
                            + " Defaults to the usual side for this message."));
            return parameters;
        }

        @Override
        public Effect effect() {
            return Effect.SENDS_TO_VENUE;
        }

        @Override
        public String destination() {
            return DESTINATION;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            return rig.on(endpointOr(arguments, HarnessRig.MARKET), endpoint -> {
                endpoint.send(rig.messages().cancelReject(
                        text(arguments, "orderId"), text(arguments, "clOrdId"),
                        text(arguments, "origClOrdId"),
                        ordStatus(text(arguments, "ordStatus")),
                        "replace".equals(text(arguments, "responseTo")) ? '2' : '1',
                        arguments.get("reason") == null ? null : text(arguments, "reason")));
                return Result.of("refused the " + text(arguments, "responseTo")
                        + " for " + text(arguments, "origClOrdId"));
            });
        }
    }

    /** What crossed either side. */
    private final class Traffic implements AiTool {

        /** Enough to see a scenario without filling a context window with raw FIX. */
        private static final int LIMIT = 40;

        @Override
        public String name() {
            return "harness_traffic";
        }

        @Override
        public String description() {
            return "What crossed one side of the harness, newest last. This is how to see "
                    + "what the system under test sent — including the clOrdId it used on "
                    + "its own order, which a market-side reply has to name.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            parameters.put("side", Parameter.required("string",
                    "Which endpoint; \"client\" and \"market\" are the usual two,"
                            + " and harness_status lists whatever is up"));
            parameters.put("msgType", Parameter.optional("string",
                    "Only this FIX message type, e.g. D for a new order, 8 for an execution report"));
            return parameters;
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            String side = text(arguments, "side");
            CounterpartyHarness endpoint = rig.side(side);
            if (endpoint == null) {
                return Result.failed("the " + side + " side is not started");
            }
            String wanted = arguments.get("msgType") == null ? null : text(arguments, "msgType");
            List<CounterpartyHarness.Traffic> all = endpoint.traffic().stream()
                    // Heartbeats and logons crowd out what a scenario is about.
                    .filter(entry -> !ADMIN.contains(entry.msgType()))
                    .filter(entry -> wanted == null || wanted.equals(entry.msgType()))
                    .toList();

            List<CounterpartyHarness.Traffic> shown = all.size() > LIMIT
                    ? all.subList(all.size() - LIMIT, all.size())
                    : all;

            StringBuilder text = new StringBuilder();
            List<Object> rows = new ArrayList<>();
            for (CounterpartyHarness.Traffic entry : shown) {
                text.append(entry.direction()).append(' ')
                        .append(describe(entry.msgType())).append("  ")
                        .append(entry.raw().replace('', '|')).append('\n');
                rows.add(Map.of("direction", entry.direction(), "msgType", entry.msgType(),
                        "raw", entry.raw(), "at", entry.at()));
            }
            if (shown.isEmpty()) {
                return Result.of("nothing on the " + side + " side yet");
            }
            Map<String, Object> data = Map.of("side", side, "messages", rows);
            return all.size() > shown.size()
                    ? Result.partial(text.toString().trim(), data,
                            "showing the last " + LIMIT + " of " + all.size()
                                    + "; narrow it with msgType")
                    : Result.of(text.toString().trim(), data);
        }
    }

    /** Forget what crossed, so one scenario does not read another's. */
    private final class ClearTraffic implements AiTool {

        @Override
        public String name() {
            return "harness_clear_traffic";
        }

        @Override
        public String description() {
            return "Forget the messages recorded so far, so the next scenario starts clean.";
        }

        @Override
        public Map<String, Parameter> parameters() {
            return Map.of();
        }

        @Override
        public Effect effect() {
            return Effect.LOCAL_CHANGE;
        }

        @Override
        public Result call(Map<String, Object> arguments) {
            rig.clearTraffic();
            return Result.of("cleared");
        }
    }

    // ------------------------------------------------------------------

    /**
     * What the harness's acting tools reach, for the gate.
     *
     * <p>Deliberately not the deployment's venue: unlocking the harness must
     * not also permit the agent to trade, and unlocking trading must not permit
     * a harness that talks to somebody else's system.
     */
    public static final String DESTINATION = "test-harness";

    /** What OnBehalfOfCompID is for, said once. */
    private static final String ON_BEHALF_OF =
            "Which client this is sent for (FIX tag 115). A router serving many clients "
            + "over one session identifies them by it and may refuse what it cannot attribute.";

    private static String onBehalfOf(Map<String, Object> arguments) {
        return arguments.get("onBehalfOf") == null ? null : text(arguments, "onBehalfOf");
    }

    /** Session-level types a scenario is never about. */
    private static final List<String> ADMIN = List.of("0", "1", "2", "4", "5", "A");

    private static String describe(String msgType) {
        return switch (msgType) {
            case "D" -> "new order single";
            case "F" -> "order cancel request";
            case "G" -> "order cancel/replace request";
            case "8" -> "execution report";
            case "9" -> "order cancel reject";
            case "3" -> "reject";
            default -> "35=" + msgType;
        };
    }

    private static char execType(String name) {
        return switch (name) {
            case "new" -> '0';
            case "trade" -> 'F';
            case "canceled" -> '4';
            case "replaced" -> '5';
            case "rejected" -> '8';
            case "expired" -> 'C';
            case "pending_cancel" -> '6';
            default -> throw new IllegalArgumentException("unknown execType " + name);
        };
    }

    private static char ordStatus(String name) {
        return switch (name) {
            case "new" -> '0';
            case "partially_filled" -> '1';
            case "filled" -> '2';
            case "canceled" -> '4';
            case "replaced" -> '5';
            case "rejected" -> '8';
            case "expired" -> 'C';
            case "pending_cancel" -> '6';
            default -> throw new IllegalArgumentException("unknown ordStatus " + name);
        };
    }

    private static char side(Map<String, Object> arguments) {
        return "sell".equals(text(arguments, "side")) ? '2' : '1';
    }

    /**
     * Which endpoint a call acts on.
     *
     * <p>Defaults to the usual side for the tool, so every scenario written
     * before endpoints could be named goes on meaning what it did. Naming one
     * is what a scenario with several clients needs.
     */
    private static String endpointOr(Map<String, Object> arguments, String fallback) {
        // Read straight from the map: text() treats an absent value as a
        // caller error, which is right for a required parameter and wrong for
        // this one — omitting it is the ordinary case.
        Object named = arguments.get("endpoint");
        if (named == null) {
            return fallback;
        }
        String asText = String.valueOf(named);
        return asText.isBlank() ? fallback : asText;
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return String.valueOf(value);
    }

    private static double number(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value instanceof Number given ? given.doubleValue()
                : Double.parseDouble(String.valueOf(value));
    }

    /**
     * What is still working, derived when it was not given.
     *
     * <p>Omitting it used to mean zero, which is not "unstated" — it is the
     * report a venue sends when an order is finished. An acknowledgement
     * carrying OrderQty=1000 beside LeavesQty=0 says the order is both fully
     * open and entirely gone, and a system reading the second field acts on a
     * position that never closed. It is also a report no real venue produces,
     * so the system under test is being asked about a case that cannot happen
     * while the case that does happen goes untested.
     *
     * <p>So an absent value means the identity every execution report holds:
     * OrderQty - CumQty. Stating zero still sends zero — a conformance harness
     * has to be able to produce a malformed report on purpose, and this only
     * stops it happening by accident.
     */
    static double leavesQty(Map<String, Object> arguments) {
        if (arguments.get("leavesQty") != null) {
            return optional(arguments, "leavesQty");
        }
        double remaining = optional(arguments, "orderQty") - optional(arguments, "cumQty");
        return remaining > 0 ? remaining : 0;
    }

    private static double optional(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? 0 : value instanceof Number given ? given.doubleValue()
                : Double.parseDouble(String.valueOf(value));
    }
}
