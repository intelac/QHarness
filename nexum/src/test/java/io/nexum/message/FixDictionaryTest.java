package io.nexum.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a raw message can be read without a reference open beside it.
 *
 * <p>{@code 39=1 150=F 434=1} is three numbers. The whole point of the
 * dictionary is that it stops being three numbers.
 */
class FixDictionaryTest {

    @Test
    @DisplayName("the tags a report is read through have names")
    void reportTagsAreNamed() {
        assertEquals("OrdStatus", FixDictionary.name(39).orElseThrow());
        assertEquals("ExecType", FixDictionary.name(150).orElseThrow());
        assertEquals("CumQty", FixDictionary.name(14).orElseThrow());
        assertEquals("CxlRejResponseTo", FixDictionary.name(434).orElseThrow());
        assertEquals("OrigClOrdID", FixDictionary.name(41).orElseThrow());
    }

    @Test
    @DisplayName("coded values are spelled out")
    void codesAreExplained() {
        assertEquals("PartiallyFilled", FixDictionary.meaning(39, "1").orElseThrow());
        assertEquals("Trade", FixDictionary.meaning(150, "F").orElseThrow());
        assertEquals("Buy", FixDictionary.meaning(54, "1").orElseThrow());

        // The one that matters most: 434 is what says a rejection refuses a
        // cancel rather than an order, and 39 on that message still reads New.
        assertEquals("OrderCancelRequest", FixDictionary.meaning(434, "1").orElseThrow());
    }

    @Test
    @DisplayName("a tag nobody named is still shown")
    void unknownTagsAreNotSwallowed() {
        // A counterparty's own field. The display shows the number, which is
        // exactly the tag someone needs to go and ask about.
        assertTrue(FixDictionary.name(9303).isEmpty());
        assertTrue(FixDictionary.meaning(39, "Z").isEmpty(),
                "an unknown value should not be given a wrong meaning");
    }

    @Test
    @DisplayName("session fields are marked so they can be dimmed")
    void sessionFieldsAreMarked() {
        assertTrue(FixDictionary.isSession(34), "MsgSeqNum belongs to the session");
        assertTrue(FixDictionary.isSession(49), "SenderCompID belongs to the session");
        assertFalse(FixDictionary.isSession(55), "Symbol belongs to the order");
        assertFalse(FixDictionary.isSession(38), "OrderQty belongs to the order");
    }

    @Test
    @DisplayName("a price means what it says and gets no gloss")
    void plainValuesAreNotGlossed() {
        assertTrue(FixDictionary.meaning(44, "150.00").isEmpty());
        assertTrue(FixDictionary.meaning(38, "1000").isEmpty());
    }
}
