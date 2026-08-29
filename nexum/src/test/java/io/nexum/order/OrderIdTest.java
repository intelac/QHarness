package io.nexum.order;

import io.nexum.message.FixMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three-part identity, and what it separates. */
class OrderIdTest {

    private static final int CL_ORD_ID = 11;
    private static final int ORIG_CL_ORD_ID = 41;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 8, 25);

    @Nested
    @DisplayName("what the three parts separate")
    class Identity {

        @Test
        void rendersAsDaySessionClOrdId() {
            assertEquals("20260824:OMS->FUNDX:FUNDX-1",
                    OrderId.of(TODAY, "OMS->FUNDX", "FUNDX-1").toString());
        }

        @Test
        void twoClientsMayUseTheSameClOrdId() {
            // Clients pick their own identifiers and routinely pick the same
            // ones; without the session they would collide.
            assertNotEquals(
                    OrderId.of(TODAY, "OMS->FUNDX", "ORD-1"),
                    OrderId.of(TODAY, "OMS->FUNDY", "ORD-1"));
        }

        @Test
        void aClientMayReuseItsOwnClOrdIdTomorrow() {
            // ClOrdID sequences are commonly reset overnight. Without the day,
            // today's order would resolve to yesterday's.
            assertNotEquals(
                    OrderId.of(TODAY, "OMS->FUNDX", "ORD-1"),
                    OrderId.of(TOMORROW, "OMS->FUNDX", "ORD-1"));
        }

        @Test
        void theSameThreePartsAreTheSameOrder() {
            assertEquals(
                    OrderId.of(TODAY, "OMS->FUNDX", "ORD-1"),
                    OrderId.of(TODAY, "OMS->FUNDX", "ORD-1"));
        }
    }

    @Nested
    @DisplayName("round tripping")
    class Parsing {

        @Test
        void parsesWhatItRenders() {
            OrderId id = OrderId.of(TODAY, "OMS->FUNDX", "FUNDX-1");
            assertEquals(id, OrderId.parse(id.toString()));
        }

        @Test
        void aClientClOrdIdMayContainAColon() {
            // Split from the left twice, not on every colon — a client
            // identifier carrying one would otherwise be truncated.
            OrderId id = OrderId.of(TODAY, "OMS->FUNDX", "FUND:X:ORD:1");
            assertEquals("FUND:X:ORD:1", OrderId.parse(id.toString()).clientClOrdId());
        }

        @Test
        void aSessionNameMayContainAnArrow() {
            assertEquals("OMS->FUNDX",
                    OrderId.parse("20260824:OMS->FUNDX:ORD-1").sessionId());
        }

        @Test
        void malformedTextIsRejectedRatherThanGuessedAt() {
            assertThrows(IllegalArgumentException.class, () -> OrderId.parse("ORD-1"));
            assertThrows(IllegalArgumentException.class,
                    () -> OrderId.parse("20260824:OMS->FUNDX"));
            assertThrows(IllegalArgumentException.class,
                    () -> OrderId.parse("not-a-date:session:ord"));
        }

        @Test
        void looksLikeOneDoesNotThrow() {
            assertTrue(OrderId.looksLikeOne("20260824:OMS->FUNDX:ORD-1"));
            assertFalse(OrderId.looksLikeOne("ORD-1"));
            assertFalse(OrderId.looksLikeOne(null));
        }

        @Test
        void emptyPartsAreRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> OrderId.of(TODAY, "", "ORD-1"));
            assertThrows(IllegalArgumentException.class,
                    () -> OrderId.of(TODAY, "SESSION", ""));
        }
    }

    @Nested
    @DisplayName("finding the order a message refers to")
    class Resolution {

        private final OrderIdResolver resolver = new OrderIdResolver(ZoneOffset.UTC);

        @Test
        void aNewOrderIsIdentifiedFromTheMessageAlone() {
            OrderId id = resolver.forNewOrder(order("FUNDX-1"), "OMS->FUNDX");

            assertEquals("OMS->FUNDX", id.sessionId());
            assertEquals("FUNDX-1", id.clientClOrdId());
            assertEquals(resolver.tradingDay(), id.day());
        }

        @Test
        void anOrderWithoutAClOrdIdCannotBeIdentified() {
            assertThrows(IllegalArgumentException.class,
                    () -> resolver.forNewOrder(FixMessage.of("D"), "OMS->FUNDX"));
        }

        @Test
        void aCancelFindsTheOrderItNames() {
            OrderId placed = resolver.forNewOrder(order("FUNDX-1"), "OMS->FUNDX");

            Optional<OrderId> found =
                    resolver.forAmendment(amendment("FUNDX-1-CXL", "FUNDX-1"), "OMS->FUNDX");

            assertEquals(Optional.of(placed), found);
        }

        @Test
        void aCancelAfterAReplaceFindsTheOrderByEitherIdentifier() {
            OrderId placed = resolver.forNewOrder(order("FUNDX-1"), "OMS->FUNDX");
            // A replace introduces a second identifier for the same order.
            resolver.alsoKnownAs(placed, "OMS->FUNDX", "FUNDX-1-AMD");

            assertEquals(Optional.of(placed),
                    resolver.forAmendment(
                            amendment("CXL-A", "FUNDX-1"), "OMS->FUNDX"),
                    "the original identifier must still find it");
            assertEquals(Optional.of(placed),
                    resolver.forAmendment(
                            amendment("CXL-B", "FUNDX-1-AMD"), "OMS->FUNDX"),
                    "and so must the one the replace introduced");
        }

        @Test
        void anAmendmentNamingAnUnknownOrderResolvesToNothing() {
            // An identity is minted only when an order arrives. Building one
            // from OrigClOrdID would manufacture an order for a typo, and the
            // cancel would then appear to succeed against something that was
            // never placed.
            assertEquals(Optional.empty(),
                    resolver.forAmendment(amendment("CXL-1", "NEVER-PLACED"), "OMS->FUNDX"));
        }

        @Test
        void anAmendmentWithoutOrigClOrdIdNamesNothing() {
            assertEquals(Optional.empty(),
                    resolver.forAmendment(
                            FixMessage.of("F", Map.of(CL_ORD_ID, "CXL-1")), "OMS->FUNDX"));
        }

        @Test
        void twoSessionsUsingTheSameClOrdIdStaySeparate() {
            OrderId onX = resolver.forNewOrder(order("SHARED-1"), "OMS->FUNDX");
            OrderId onY = resolver.forNewOrder(order("SHARED-1"), "OMS->FUNDY");

            assertNotEquals(onX, onY);
            assertEquals(Optional.of(onX),
                    resolver.forAmendment(amendment("C", "SHARED-1"), "OMS->FUNDX"));
            assertEquals(Optional.of(onY),
                    resolver.forAmendment(amendment("C", "SHARED-1"), "OMS->FUNDY"));
        }

        @Test
        void forgettingAnOrderReleasesItsIdentifiers() {
            OrderId placed = resolver.forNewOrder(order("FUNDX-1"), "OMS->FUNDX");
            assertEquals(1, resolver.knownAliases());

            resolver.forget(placed, "OMS->FUNDX", "FUNDX-1");
            assertEquals(0, resolver.knownAliases());
        }
    }

    // ------------------------------------------------------------------

    private static FixMessage order(String clOrdId) {
        return FixMessage.of("D", Map.of(CL_ORD_ID, clOrdId, 55, "VOD", 38, "1000"));
    }

    private static FixMessage amendment(String clOrdId, String origClOrdId) {
        return FixMessage.of("F", Map.of(
                CL_ORD_ID, clOrdId, ORIG_CL_ORD_ID, origClOrdId));
    }
}
