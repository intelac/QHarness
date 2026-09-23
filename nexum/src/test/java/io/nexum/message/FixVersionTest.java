package io.nexum.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("a FIX version answers for the wire, the dictionary and the label separately")
class FixVersionTest {

    @Test
    @DisplayName("through 4.4 one answer serves all three")
    void fourPointXSaysItOnce() {
        assertEquals("FIX.4.4", FixVersion.FIX44.label());
        assertEquals("FIX.4.4", FixVersion.FIX44.beginString());
        assertEquals("FIX44.xml", FixVersion.FIX44.dictionaryResource());
        assertNull(FixVersion.FIX44.applVerID(),
                "BeginString already names the version; ApplVerID would be a second answer");
        assertNull(FixVersion.FIX44.transportDictionaryResource(),
                "one dictionary holds both layers");
        assertFalse(FixVersion.FIX44.isFixt());
    }

    @Test
    @DisplayName("the 5.0 series goes out as FIXT.1.1 while staying itself")
    void fiveSeriesGoesOutAsFixt() {
        // The bug this pins: BeginString=FIX.5.0 is a session no counterparty
        // accepts, and it used to be what a FIX.5.0 declaration produced.
        assertEquals("FIXT.1.1", FixVersion.FIX50.beginString());
        assertEquals("FIXT.1.1", FixVersion.FIX50SP2.beginString());

        // and it is still FIX.5.0 to everything that is not the wire
        assertEquals("FIX.5.0", FixVersion.FIX50.label());
        assertEquals("FIX50.xml", FixVersion.FIX50.dictionaryResource());
        assertEquals("FIX50SP2.xml", FixVersion.FIX50SP2.dictionaryResource());
    }

    @Test
    @DisplayName("a 5.0 session names its application version and its session dictionary")
    void fiveSeriesNamesBothLayers() {
        assertEquals("7", FixVersion.FIX50.applVerID());
        assertEquals("8", FixVersion.FIX50SP1.applVerID());
        assertEquals("9", FixVersion.FIX50SP2.applVerID());
        assertEquals("FIXT11.xml", FixVersion.FIX50.transportDictionaryResource(),
                "the session layer is a second dictionary, not the same one");
        assertTrue(FixVersion.FIX50.isFixt());
    }

    @Test
    @DisplayName("FIXT.1.1 on its own carries no application version")
    void bareFixtCarriesNoApplicationVersion() {
        assertEquals("FIXT.1.1", FixVersion.FIXT11.beginString());
        assertEquals("FIXT11.xml", FixVersion.FIXT11.dictionaryResource());
        assertNull(FixVersion.FIXT11.applVerID(),
                "a session that declared only the transport negotiates per message");
        assertNull(FixVersion.FIXT11.transportDictionaryResource(),
                "its own dictionary is the session layer; there is no second one");
    }

    @Test
    @DisplayName("a version is named by label or by enum name")
    void aVersionIsNamedEitherWay() {
        assertEquals(FixVersion.FIX50, FixVersion.of("FIX.5.0"));
        assertEquals(FixVersion.FIX50, FixVersion.of("FIX50"));
        assertEquals(FixVersion.FIX50, FixVersion.of("fix50"));
        assertEquals(FixVersion.FIX50SP2, FixVersion.ofBeginString("FIX.5.0SP2"),
                "a configuration declares the label, not what goes on the wire");
        assertThrows(IllegalArgumentException.class, () -> FixVersion.of("FIX.9.9"));
        assertThrows(IllegalArgumentException.class, () -> FixVersion.ofBeginString("FIX.9.9"));
    }

    @Test
    @DisplayName("every version's dictionaries are on the classpath")
    void everyDictionaryIsPresent() {
        for (FixVersion version : FixVersion.values()) {
            assertNotNull(
                    FixVersionTest.class.getClassLoader()
                            .getResourceAsStream(version.dictionaryResource()),
                    () -> version + " parses against " + version.dictionaryResource()
                            + ", which is not on the classpath");
            String transport = version.transportDictionaryResource();
            if (transport != null) {
                assertNotNull(
                        FixVersionTest.class.getClassLoader().getResourceAsStream(transport),
                        () -> version + " needs " + transport + " for its session layer");
            }
        }
    }
}
