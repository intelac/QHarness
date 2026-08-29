package io.nexum.ai;

import io.nexum.core.Fingerprint;
import io.nexum.routing.Router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That where an order would go can be asked before it is sent.
 *
 * <p>The answers have to come from the same rules the engine routes on. A tool
 * that reported routing from a second reading of the configuration could agree
 * with the engine on the day it was written and diverge later, and a wrong
 * answer here is worse than no answer: it would be trusted.
 */
class RoutingToolsTest {

    @Test
    @DisplayName("the rules can be read without sending anything")
    void listsTheRules() {
        AiTool tool = explainRouting();

        AiTool.Result result = tool.call(Map.of());

        assertTrue(result.ok());
        assertTrue(result.content().contains("FUND_X"),
                "the client rule is not shown: " + result.content());
        assertTrue(result.content().contains("OMS->LSE"),
                "the destination rule is not shown: " + result.content());
        // The conditions themselves, not only the targets — a rule whose
        // condition is invisible cannot be checked against an order.
        assertTrue(result.content().contains("115"),
                "the condition is not shown: " + result.content());
    }

    @Test
    @DisplayName("reading the rules changes nothing")
    void readingIsFree() {
        // The gate refuses to dispatch a mutating tool unless the deployment
        // unlocked it, so a tool that read as anything but READ_ONLY would be
        // unavailable exactly where it is needed: diagnosing a failure.
        assertEquals(AiTool.Effect.READ_ONLY, explainRouting().effect());
    }

    @Test
    @DisplayName("an order is answered with where it would go")
    void tellsWhereAnOrderWouldGo() {
        AiTool tool = explainRouting();

        AiTool.Result result = tool.call(Map.of(
                "symbol", "BP", "quantity", 1000, "price", 50, "onBehalfOf", "FUNDX"));

        assertTrue(result.ok());
        assertEquals("FUND_X", result.data().get("client"));
        assertEquals("OMS->LSE", result.data().get("destination"));
        assertEquals(Boolean.TRUE, result.data().get("wouldRoute"));
    }

    @Test
    @DisplayName("an order no rule recognises is told which condition failed")
    void namesTheConditionThatFailed() {
        AiTool tool = explainRouting();

        // No OnBehalfOfCompID, which is what the client rule matches on.
        AiTool.Result result = tool.call(Map.of("symbol", "BP", "quantity", 1000));

        assertNull(result.data().get("client"),
                "nothing identifies this order as a client's");
        assertEquals(Boolean.FALSE, result.data().get("wouldRoute"));

        // "It did not route" is not actionable; "115 was absent" is. The
        // refusal has to name the condition, not merely report the failure.
        String refusals = String.valueOf(result.data().get("clientRefusals"));
        assertTrue(refusals.contains("115"),
                "the failure does not say which condition: " + refusals);
    }

    @Test
    @DisplayName("both hops are answered even when the first fails")
    void answersBothHops() {
        AiTool tool = explainRouting();

        AiTool.Result result = tool.call(Map.of("symbol", "BP"));

        // A trial sends nothing, so stopping at the first refusal would
        // withhold half of what was asked for no benefit. The destination
        // rule here is a catch-all, so it matches even though the client
        // rule did not.
        assertNull(result.data().get("client"));
        assertNotNull(result.data().get("destination"),
                "the destination hop was not reported: " + result.content());
    }

    @Test
    @DisplayName("a refused order is not also told it would be sent")
    void doesNotContradictItself() {
        AiTool tool = explainRouting();

        // The destination rule here is a catch-all, so it matches even for an
        // order no client rule recognises. Reporting that as "would be sent"
        // beside "would be refused" states both outcomes at once, and a reader
        // acting on the wrong half goes looking for a venue problem that is
        // not there.
        AiTool.Result result = tool.call(Map.of("symbol", "BP", "quantity", 1000));

        assertNull(result.data().get("client"), "this order matches no client rule");
        assertFalse(result.content().contains("Would be sent to"),
                "an order refused before routing is reported as being sent: "
                        + result.content());
        assertFalse(result.content().contains("not whether it would arrive"),
                "arrival is beside the point for an order that never leaves: "
                        + result.content());
    }

    @Test
    @DisplayName("a tag no parameter names still reaches the rules")
    void arbitraryTagsAreMatched() {
        // Rules match on whatever tags they name, and the tool cannot have a
        // parameter for each of them. Without a way to pass one, a rule
        // matching on anything unusual would be untestable through this tool.
        Router router = new Router(
                List.of(new Router.Rule("DESK_A", Fingerprint.of().eq(100, "XLON").build())),
                List.of(new Router.Rule("OMS->LSE", Fingerprint.any())));
        AiTool tool = new RoutingTools(router).all().get(0);

        AiTool.Result matched = tool.call(Map.of("symbol", "BP", "fields", "100=XLON"));
        assertEquals("DESK_A", matched.data().get("client"));

        AiTool.Result missed = tool.call(Map.of("symbol", "BP", "fields", "100=XPAR"));
        assertNull(missed.data().get("client"),
                "a tag that does not match should not route");
    }

    @Test
    @DisplayName("what it reports is what the engine would do")
    void agreesWithTheEngine() {
        // The point of the tool is that it cannot disagree with the router.
        // Asking both the same question is what holds that true as either
        // side changes.
        Router router = router();
        AiTool tool = new RoutingTools(router).all().get(0);

        io.nexum.message.FixMessage order = io.nexum.message.FixMessage.of("D", Map.of(
                io.nexum.message.FixTags.SYMBOL, "BP",
                io.nexum.message.FixTags.SIDE, "1",
                io.nexum.message.FixTags.ORDER_QTY, "1000",
                io.nexum.message.FixTags.ORD_TYPE, "2",
                io.nexum.message.FixTags.PRICE, "50",
                115, "FUNDX"));

        AiTool.Result reported = tool.call(Map.of(
                "symbol", "BP", "side", "buy", "quantity", 1000,
                "price", 50, "onBehalfOf", "FUNDX"));

        assertEquals(router.toClient(order).orElse(null), reported.data().get("client"),
                "the tool and the router disagree about the client");
        assertEquals(router.toDestination(order).orElse(null),
                reported.data().get("destination"),
                "the tool and the router disagree about the destination");
    }

    @Test
    @DisplayName("a sell is matched as a sell")
    void sideIsCarried() {
        // Side arrives as a word and routes as a tag, so a rule on Side(54)
        // only works if the translation happens. It is the one parameter that
        // is not passed through as written.
        Router router = new Router(
                List.of(new Router.Rule("SHORT_DESK",
                        Fingerprint.of().eq(54, "2").build())),
                List.of(new Router.Rule("OMS->LSE", Fingerprint.any())));
        AiTool tool = new RoutingTools(router).all().get(0);

        assertEquals("SHORT_DESK",
                tool.call(Map.of("symbol", "BP", "side", "sell")).data().get("client"));
        assertNull(tool.call(Map.of("symbol", "BP", "side", "buy")).data().get("client"),
                "a buy should not match a rule for sells");
    }

    @Test
    @DisplayName("being routable is not the same as arriving")
    void doesNotClaimTheOrderWouldArrive() {
        // This tool reads rules; whether the session is up is another tool's
        // question. Conflating them is the mistake that sent an agent hunting
        // for a fault in an order whose venue was simply down.
        AiTool.Result result = explainRouting().call(Map.of(
                "symbol", "BP", "onBehalfOf", "FUNDX"));

        assertTrue(result.content().contains("list_sessions"),
                "nothing points at how to check the session: " + result.content());
    }

    @Test
    @DisplayName("a router with no rules says so rather than failing")
    void emptyRulesAreReported() {
        AiTool tool = new RoutingTools(new Router(List.of(), List.of())).all().get(0);

        AiTool.Result listed = tool.call(Map.of());
        assertTrue(listed.ok());
        assertTrue(listed.content().contains("none configured"),
                "an empty configuration is not described: " + listed.content());

        AiTool.Result tried = tool.call(Map.of("symbol", "BP"));
        assertTrue(tried.ok(), "a trial against no rules should answer, not fail");
        assertEquals(Boolean.FALSE, tried.data().get("wouldRoute"));
    }

    @Test
    @DisplayName("unparseable extra fields do not lose the rest of the answer")
    void survivesMalformedFields() {
        AiTool.Result result = explainRouting().call(Map.of(
                "symbol", "BP", "onBehalfOf", "FUNDX",
                "fields", "notatag=x,,100=XLON,=nothing"));

        // What was understood is still matched: an answer about most of what
        // was described beats no answer at all.
        assertTrue(result.ok());
        assertEquals("FUND_X", result.data().get("client"));
        assertFalse(result.content().isBlank());
    }

    // ------------------------------------------------------------------

    private static AiTool explainRouting() {
        return new RoutingTools(router()).all().get(0);
    }

    /** The shape the example deployment has: a client by tag 115, a catch-all venue. */
    private static Router router() {
        return new Router(
                List.of(new Router.Rule("FUND_X", Fingerprint.of().eq(115, "FUNDX").build())),
                List.of(new Router.Rule("OMS->LSE", Fingerprint.any())));
    }
}
