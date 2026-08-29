package io.nexum.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That JSON-RPC requests are read as they were sent.
 *
 * <p>A parser is where mistakes hide quietly: a mis-read id goes back on a
 * response the client then cannot match, and a mis-read argument reaches an
 * order.
 */
class JsonReadTest {

    @Test
    @DisplayName("a JSON-RPC request reads back field for field")
    void aRequestReadsBack() {
        Map<String, Object> request = Json.readObject("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call",
                 "params":{"name":"place_order",
                           "arguments":{"symbol":"VOD","quantity":1000,"side":"buy"}}}
                """);

        assertEquals("2.0", request.get("jsonrpc"));
        assertEquals(7L, request.get("id"));
        assertEquals("tools/call", request.get("method"));

        Map<?, ?> params = (Map<?, ?>) request.get("params");
        Map<?, ?> arguments = (Map<?, ?>) params.get("arguments");
        assertEquals("VOD", arguments.get("symbol"));
        assertEquals(1000L, arguments.get("quantity"));
    }

    @Test
    @DisplayName("a whole number stays whole")
    void integersDoNotBecomeDecimals() {
        // An id returned as 1.0 does not match the 1 a client sent, and it is
        // the response that then looks unsolicited.
        assertEquals(1L, Json.readObject("{\"id\":1}").get("id"));
        assertEquals(1.5, Json.readObject("{\"n\":1.5}").get("n"));
        assertEquals(-42L, Json.readObject("{\"n\":-42}").get("n"));
        assertEquals(1.0e3, Json.readObject("{\"n\":1.0e3}").get("n"));
    }

    @Test
    @DisplayName("a string id is left a string")
    void stringIdsSurvive() {
        assertEquals("call-3", Json.readObject("{\"id\":\"call-3\"}").get("id"));
    }

    @Test
    @DisplayName("escapes are unescaped")
    void escapesAreRead() {
        Map<String, Object> read = Json.readObject(
                "{\"s\":\"a\\\"b\\\\c\\nd\\u0041\"}");
        assertEquals("a\"b\\c\ndA", read.get("s"));
    }

    @Test
    @DisplayName("nesting, arrays, null and booleans")
    void structuresAreRead() {
        Map<String, Object> read = Json.readObject("""
                {"a":[1,{"b":null},true,false],"c":{},"d":[]}
                """);

        List<?> a = (List<?>) read.get("a");
        assertEquals(4, a.size());
        assertEquals(1L, a.get(0));
        assertNull(((Map<?, ?>) a.get(1)).get("b"));
        assertEquals(Boolean.TRUE, a.get(2));
        assertEquals(Boolean.FALSE, a.get(3));
        assertTrue(((Map<?, ?>) read.get("c")).isEmpty());
        assertTrue(((List<?>) read.get("d")).isEmpty());
    }

    @Test
    @DisplayName("what is written can be read")
    void writingAndReadingRoundTrip() {
        Map<String, Object> original = Map.of(
                "text", "a \"quoted\" line\nand another",
                "count", 42,
                "flag", true);

        assertEquals(original.get("text"), Json.readObject(Json.write(original)).get("text"));
        assertEquals(42L, Json.readObject(Json.write(original)).get("count"));
    }

    @Test
    @DisplayName("malformed input says where it went wrong")
    void malformedInputIsRejected() {
        // Silently returning an empty object would turn a client's bad request
        // into a call with no arguments, which is worse than a refusal.
        assertThrows(IllegalArgumentException.class, () -> Json.read("{"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("{\"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("\"unterminated"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("{} trailing"));
    }
}
