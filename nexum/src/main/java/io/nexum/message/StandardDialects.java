package io.nexum.message;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Baseline group templates per {@link FixVersion}, read from that version's
 * QuickFIX {@code FIXnn.xml}.
 *
 * <p>The dictionaries ship inside quickfixj-core, which the transport cannot
 * run without, so every version's file is on any classpath this engine can
 * start from. There is deliberately no fallback when one is missing: a guessed
 * set of groups parses every message and gets some of them wrong without a
 * word, and a group lost that way stays lost until a counterparty complains.
 *
 * <p>This is a baseline, not a contract with any counterparty. A session that
 * deviates layers a {@link DialectOverlay} over it.
 */
public final class StandardDialects {

    private static final Map<FixVersion, Dialect> CACHE = new ConcurrentHashMap<>();

    private StandardDialects() {}

    public static Dialect of(FixVersion version) {
        return CACHE.computeIfAbsent(version, v -> build(v,
                resource -> StandardDialects.class.getClassLoader().getResourceAsStream(resource)));
    }

    /**
     * Read one version's dictionary.
     *
     * @param open how a resource name is opened, or null when it is absent
     * @throws IllegalStateException when the version's dictionary is not there
     */
    static Dialect build(FixVersion version, Function<String, InputStream> open) {
        InputStream xml = open.apply(version.dictionaryResource());
        if (xml == null) {
            throw new IllegalStateException(
                    version.label() + " needs " + version.dictionaryResource()
                            + " on the classpath, and it is not there. It ships in"
                            + " quickfixj-core; a build that repackages that jar has"
                            + " to keep its FIX*.xml resources.");
        }
        return DictionaryDialect.load(version.label(), xml);
    }
}
