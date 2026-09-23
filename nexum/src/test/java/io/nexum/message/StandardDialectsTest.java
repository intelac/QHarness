package io.nexum.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("a session's baseline comes from its version's dictionary or not at all")
class StandardDialectsTest {

    @Test
    @DisplayName("a missing dictionary stops startup and says which file")
    void aMissingDictionaryFailsLoud() {
        // The dictionaries ship inside quickfixj-core, which the transport needs
        // anyway, so this is only reachable from a broken classpath. Carrying on
        // with a guessed set of groups there would parse every message and get
        // some of them wrong without a word, which is how a lost group went
        // unnoticed in the first place.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> StandardDialects.build(FixVersion.FIX42, resource -> null));

        assertTrue(refused.getMessage().contains("FIX42.xml"), refused.getMessage());
        assertTrue(refused.getMessage().contains("FIX.4.2"), refused.getMessage());
    }

    @Test
    @DisplayName("every version builds from the dictionaries on the real classpath")
    void everyVersionBuilds() {
        for (FixVersion version : FixVersion.values()) {
            assertEquals(version.label(), StandardDialects.of(version).name(),
                    () -> version + " should load its own dictionary, not a stand-in");
        }
    }
}
