package io.nexum.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("the versions this harness can speak")
class ProbeFixVersionTest {

    @Test
    @DisplayName("a 4.x version names itself in BeginString and needs no ApplVerID")
    void fourPointXNamesItself() {
        assertEquals("FIX.4.2", ProbeFixVersion.FIX42.beginString());
        assertEquals("FIX.4.4", ProbeFixVersion.FIX44.beginString());
        assertNull(ProbeFixVersion.FIX44.applVerID(),
                "BeginString already says which version, so nothing rides in ApplVerID");
        assertFalse(ProbeFixVersion.FIX44.isFixt());
    }

    @Test
    @DisplayName("the 5.0 series speaks FIXT.1.1 and names its application version separately")
    void fiveNamesItsApplicationVersion() {
        assertEquals("FIXT.1.1", ProbeFixVersion.FIX50.beginString(),
                "the session layer is FIXT.1.1, not the version");
        assertEquals("7", ProbeFixVersion.FIX50.applVerID(),
                "ApplVerID 7 is FIX 5.0; without it the counterparty parses against its own default");
        assertTrue(ProbeFixVersion.FIX50.isFixt());
    }

    @Test
    @DisplayName("a version is named the several ways a scenario writes it")
    void aVersionIsNamedSeveralWays() {
        for (String spelling : new String[] { "FIX.4.2", "FIX42", "fix42", "4.2", "42" }) {
            assertEquals(ProbeFixVersion.FIX42, ProbeFixVersion.of(spelling),
                    () -> "\"" + spelling + "\" names FIX 4.2");
        }
        assertEquals(ProbeFixVersion.FIX50, ProbeFixVersion.of("FIX.5.0"));
        assertEquals(ProbeFixVersion.FIX50, ProbeFixVersion.of("50"));
    }

    @Test
    @DisplayName("an unknown version says what this harness does speak")
    void anUnknownVersionSaysWhatIsKnown() {
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> ProbeFixVersion.of("FIX.9.9"));

        assertTrue(refused.getMessage().contains("FIX.9.9"), refused.getMessage());
        // A scenario that named a version by hand is owed the list, not just a refusal.
        assertTrue(refused.getMessage().contains("FIX42"), refused.getMessage());
        assertTrue(refused.getMessage().contains("FIX50"), refused.getMessage());
    }

    @Test
    @DisplayName("every version names a dictionary on the classpath")
    void everyVersionHasItsDictionary() {
        for (ProbeFixVersion version : ProbeFixVersion.values()) {
            String resource = "/" + version.dictionary() + ".xml";
            assertTrue(getClass().getResource(resource) != null,
                    () -> version + " parses against " + resource + ", which is not on the classpath");
        }
        // The 5.0 series parses its session layer against FIXT11 as well.
        assertTrue(getClass().getResource("/FIXT11.xml") != null,
                "the 5.0 series needs the FIXT.1.1 session dictionary too");
    }
}
