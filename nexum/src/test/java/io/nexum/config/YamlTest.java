package io.nexum.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configuration reader, against the shapes people actually write.
 *
 * <p>A parser that rejects valid YAML fails at startup with an error naming the
 * wrong cause — "no sessions configured" for a file that plainly configures
 * sessions — which is a worse morning than a parse error.
 */
class YamlTest {

    @Test
    @DisplayName("a block sequence at the parent key's indentation")
    void listAtParentIndentation() {
        // The style every editor produces, and the one the parser used to
        // silently read as an empty map.
        Config config = Yaml.parse("""
                sessions:
                - id: OMS->FUNDX
                  version: FIX.4.4
                - id: OMS->LSE
                  version: FIX.4.2
                """);

        List<Config> sessions = config.sections("sessions");
        assertEquals(2, sessions.size());
        assertEquals("OMS->FUNDX", sessions.get(0).string("id"));
        assertEquals("FIX.4.4", sessions.get(0).string("version"));
        assertEquals("FIX.4.2", sessions.get(1).string("version"));
    }

    @Test
    @DisplayName("a block sequence indented under its key")
    void listIndentedUnderKey() {
        Config config = Yaml.parse("""
                sessions:
                  - id: OMS->FUNDX
                    version: FIX.4.4
                """);

        assertEquals(1, config.sections("sessions").size());
        assertEquals("FIX.4.4", config.sections("sessions").get(0).string("version"));
    }

    @Test
    @DisplayName("continuation keys stay inside their list item whatever the spacing")
    void continuationKeysStayInTheItem() {
        // Three spaces after the dash rather than one. Assuming two pushed the
        // later keys out of the item and into the enclosing map, where they
        // surfaced as a missing required key.
        Config config = Yaml.parse("""
                sessions:
                  -   id: OMS->FUNDX
                      version: FIX.4.4
                      port: 9876
                """);

        Config session = config.sections("sessions").get(0);
        assertEquals("OMS->FUNDX", session.string("id"));
        assertEquals("FIX.4.4", session.string("version"));
        assertEquals(9876, session.integer("port", 0));
    }

    @Test
    @DisplayName("nested maps inside a list item")
    void nestedMapInListItem() {
        Config config = Yaml.parse("""
                clients:
                  - id: FUND_X
                    fingerprint:
                      115: FUNDX
                      207: L
                """);

        Config client = config.sections("clients").get(0);
        assertEquals("FUND_X", client.string("id"));
        assertEquals("FUNDX", client.section("fingerprint").string("115"));
        assertEquals("L", client.section("fingerprint").string("207"));
    }

    @Test
    @DisplayName("comments and blank lines are ignored")
    void commentsAndBlanks() {
        Config config = Yaml.parse("""
                # what this file configures
                web:
                  port: 8080       # the monitor screen

                orders:
                  journal: data/orders
                """);

        assertEquals(8080, config.section("web").integer("port", 0));
        assertEquals("data/orders", config.section("orders").string("journal"));
    }

    @Test
    @DisplayName("a hash inside a quoted value is not a comment")
    void hashInsideQuotes() {
        Config config = Yaml.parse("""
                note: "order #1 for the desk"
                """);

        assertEquals("order #1 for the desk", config.string("note"));
    }

    @Test
    @DisplayName("an overlay states a difference rather than restating everything")
    void overlayMerges() {
        Config base = Yaml.parse("""
                orders:
                  journal: data/orders
                  sync: true
                web:
                  port: 8080
                """);
        Config production = Yaml.parse("""
                web:
                  port: 80
                """);

        Config merged = base.overlay(production);

        assertEquals(80, merged.section("web").integer("port", 0));
        assertEquals("data/orders", merged.section("orders").string("journal"),
                "an overlay must leave alone what it does not mention");
        assertTrue(merged.section("orders").flag("sync", false));
    }

    @Test
    @DisplayName("a mixture of scalars, lists and nesting")
    void aWholeConfiguration() {
        Config config = Yaml.parse("""
                monitor:
                  enabled: false

                sessions:
                - id: OMS->FUNDX
                  version: FIX.4.4
                  role: acceptor
                  port: 19881

                routes:
                - destination: OMS->LSE
                  fingerprint:
                    207: L
                - destination: OMS->NYSE
                  fingerprint: any
                """);

        assertEquals(1, config.sections("sessions").size());
        assertEquals("acceptor", config.sections("sessions").get(0).string("role"));

        List<Config> routes = config.sections("routes");
        assertEquals(2, routes.size());
        assertEquals("L", routes.get(0).section("fingerprint").string("207"));
        assertEquals("any", routes.get(1).string("fingerprint"),
                "a catch-all is a scalar where the others are maps");
    }
}
