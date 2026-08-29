package io.nexum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a prefix names one thing.
 *
 * <p>The journal prefixes say who spoke and to whom: {@code c.} the client on
 * its way in, {@code d.} what went out to the venue, {@code m.} what the venue
 * sent back, {@code r.} what was replied to the client. A screen lays the
 * conversation out from those four, so a prefix that means two things puts a
 * message in the wrong mouth.
 *
 * <p>Which is what happened. An amendment's requested terms — the quantity the
 * client is asking for — were written under {@code r.} as well, and a single
 * {@code r.38} on an event that replied nothing was enough for the monitor to
 * read the request as an answer to the client: the message forwarded to the
 * venue was drawn as going the other way, labelled "As it went to the client".
 * The cancel beside it, carrying no requested terms, was drawn correctly.
 */
class RequestPrefixTest {

    @Test
    @DisplayName("a forwarded request does not read as a reply to the client")
    void requestedTermsAreNotAReply() {
        OrderEvent.RequestSent sent = new OrderEvent.RequestSent(
                "20260101:OMS->FUND:S-1", 0L, PendingRequest.Kind.REPLACE,
                "A0000004", "O0000002", "S-4",
                Map.of(FixQty.ORDER_QTY, "800"),
                Map.of("11", "S-4", "35", "G", "38", "800"),
                Map.of("11", "A0000004", "35", "G", "38", "800"));

        Map<String, String> fields = sent.fields();

        // Nothing was said to the client here: the request came in and went
        // out to the venue. A reply prefix on this event is a message the
        // system never sent.
        assertTrue(fields.keySet().stream().noneMatch(key -> key.startsWith("r.")),
                "a request that replied nothing carries a reply prefix: "
                        + fields.keySet().stream().filter(k -> k.startsWith("r.")).toList());

        // The terms are still recorded — they are what the amendment asks for,
        // and an accepted replace carries them onto the order.
        assertEquals("800", fields.get("q." + FixQty.ORDER_QTY),
                "the requested terms are no longer recorded: " + fields);

        // Both messages stay where they were.
        assertEquals("S-4", fields.get("c.11"), "the client's message is missing");
        assertEquals("A0000004", fields.get("d.11"), "what went to the venue is missing");
    }

    @Test
    @DisplayName("a reply to the client still reads as one")
    void refusalKeepsTheReplyPrefix() {
        OrderEvent.RequestRefused refused = new OrderEvent.RequestRefused(
                "20260101:OMS->FUND:S-1", 0L, true, "S-3", "S-2",
                "a request is already outstanding on this order",
                Map.of("11", "S-3", "35", "G"),
                Map.of("11", "S-3", "35", "9", "434", "2"));

        Map<String, String> fields = refused.fields();

        // This one did answer the client, and the prefix is how a screen knows
        // to draw it going back rather than on to a venue.
        assertEquals("9", fields.get("r.35"),
                "the reply to the client lost its prefix: " + fields);
        assertFalse(fields.containsKey("d.35"),
                "a refusal never reached a venue: " + fields);
    }

    /** The one tag this test names, kept out of the assertions' way. */
    private static final class FixQty {
        static final int ORDER_QTY = 38;
    }
}
