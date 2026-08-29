package io.nexum.message;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Baseline group templates per {@link FixVersion}.
 *
 * <p>Loaded from a QuickFIX {@code FIXnn.xml} on the classpath when one is
 * present, since that is the file everyone already has. Absent that, a built-in
 * set covers the groups that appear in ordinary order flow, so the system runs
 * without shipping dictionaries.
 *
 * <p>This is a baseline, not a contract with any counterparty. A session that
 * deviates layers a {@link DialectOverlay} over it.
 */
public final class StandardDialects {

    private static final Map<FixVersion, Dialect> CACHE = new ConcurrentHashMap<>();

    private StandardDialects() {}

    public static Dialect of(FixVersion version) {
        return CACHE.computeIfAbsent(version, StandardDialects::build);
    }

    private static Dialect build(FixVersion version) {
        InputStream xml = StandardDialects.class
                .getClassLoader()
                .getResourceAsStream(version.dictionaryResource());
        if (xml != null) {
            return DictionaryDialect.load(version.beginString(), xml);
        }
        return new BuiltIn(version);
    }

    /**
     * Groups that carry ordinary order flow. Deliberately not the whole
     * standard — a deployment that needs the rest points at the real dictionary.
     */
    private record BuiltIn(FixVersion version) implements Dialect {

        private static final GroupTemplate PARTY_SUB_IDS =
                GroupTemplate.of(FixTags.NO_PARTY_SUB_IDS, FixTags.PARTY_SUB_ID, FixTags.PARTY_SUB_ID, FixTags.PARTY_SUB_ID_TYPE);

        private static final GroupTemplate PARTIES =
                new GroupTemplate(
                        FixTags.NO_PARTY_IDS,
                        FixTags.PARTY_ID,
                        List.of(FixTags.PARTY_ID, FixTags.PARTY_ID_SOURCE, FixTags.PARTY_ROLE, FixTags.NO_PARTY_SUB_IDS),
                        Map.of(FixTags.NO_PARTY_SUB_IDS, PARTY_SUB_IDS));

        private static final GroupTemplate CONTRA_BROKERS =
                GroupTemplate.of(
                        FixTags.NO_CONTRA_BROKERS,
                        FixTags.CONTRA_BROKER,
                        FixTags.CONTRA_BROKER,
                        FixTags.CONTRA_TRADER,
                        FixTags.CONTRA_TRADE_QTY,
                        FixTags.CONTRA_TRADE_TIME);

        private static final GroupTemplate ALLOCS =
                GroupTemplate.of(FixTags.NO_ALLOCS, FixTags.ALLOC_ACCOUNT, FixTags.ALLOC_ACCOUNT, FixTags.ALLOC_QTY);

        private static final GroupTemplate MD_ENTRIES =
                GroupTemplate.of(
                        FixTags.NO_MD_ENTRIES, FixTags.MD_ENTRY_TYPE, FixTags.MD_ENTRY_TYPE, FixTags.MD_ENTRY_PX, FixTags.MD_ENTRY_SIZE);

        private static final GroupTemplate MD_ENTRY_TYPES =
                GroupTemplate.of(FixTags.NO_MD_ENTRY_TYPES, FixTags.MD_ENTRY_TYPE, FixTags.MD_ENTRY_TYPE);

        private static final GroupTemplate RELATED_SYM =
                GroupTemplate.of(FixTags.NO_RELATED_SYM, FixTags.SYMBOL, FixTags.SYMBOL, FixTags.SECURITY_ID);

        public String name() {
            return version.beginString() + " (built-in)";
        }

        public Map<Integer, GroupTemplate> groupsFor(String msgType) {
            Map<Integer, GroupTemplate> groups = new LinkedHashMap<>();
            switch (msgType) {
                case "D", "F", "G" -> {          // NewOrderSingle, Cancel, Replace
                    groups.put(FixTags.NO_PARTY_IDS, PARTIES);
                    groups.put(FixTags.NO_ALLOCS, ALLOCS);
                }
                case "8" -> {                     // ExecutionReport
                    groups.put(FixTags.NO_PARTY_IDS, PARTIES);
                    groups.put(FixTags.NO_CONTRA_BROKERS, CONTRA_BROKERS);
                }
                case "9" -> groups.put(FixTags.NO_PARTY_IDS, PARTIES);   // OrderCancelReject
                case "V" -> {                     // MarketDataRequest
                    groups.put(FixTags.NO_MD_ENTRY_TYPES, MD_ENTRY_TYPES);
                    groups.put(FixTags.NO_RELATED_SYM, RELATED_SYM);
                }
                case "W", "X" -> groups.put(FixTags.NO_MD_ENTRIES, MD_ENTRIES);
                default -> {
                    // no groups known for this message type
                }
            }
            return Map.copyOf(groups);
        }
    }
}
