package io.nexum.message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A base dialect with a counterparty's deviations layered on top.
 *
 * <p>Sessions declare a {@link FixVersion} and get its standard templates for
 * free. Only a session that actually deviates loads an overlay, and the overlay
 * states the difference alone rather than restating a whole dictionary — the
 * difference is what anyone maintaining it needs to see.
 *
 * <p>Overlays stack. A venue-wide adjustment and a single session's quirk can be
 * separate plugins applied in order, each unaware of the other.
 *
 * <pre>{@code
 * Dialect base = StandardDialects.of(FixVersion.FIX44);
 * Dialect broker = DialectOverlay.on(base, "BROKER_A")
 *     .replaceGroup("D", GroupTemplate.of(453, 448, 448, 452, 2376))  // extra tag
 *     .removeGroup("D", 382)                                          // never sent
 *     .build();
 * }</pre>
 */
public final class DialectOverlay implements Dialect {

    private final Dialect base;
    private final String name;
    private final Map<String, Map<Integer, GroupTemplate>> replacements;
    private final Map<String, List<Integer>> removals;
    private final Map<String, Map<Integer, GroupTemplate>> cache = new ConcurrentHashMap<>();

    private DialectOverlay(
            Dialect base,
            String name,
            Map<String, Map<Integer, GroupTemplate>> replacements,
            Map<String, List<Integer>> removals) {
        this.base = base;
        this.name = name;
        this.replacements = replacements;
        this.removals = removals;
    }

    public static Builder on(Dialect base, String name) {
        return new Builder(base, name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<Integer, GroupTemplate> groupsFor(String msgType) {
        return cache.computeIfAbsent(msgType, type -> {
            Map<Integer, GroupTemplate> merged = new LinkedHashMap<>(base.groupsFor(type));
            for (Integer counterTag : removals.getOrDefault(type, List.of())) {
                merged.remove(counterTag);
            }
            merged.putAll(replacements.getOrDefault(type, Map.of()));
            return Map.copyOf(merged);
        });
    }

    /** The dialect this one is layered on, for diagnostics and config dumps. */
    public Dialect base() {
        return base;
    }

    /** What this overlay changes, rendered for a config dump. */
    public List<String> deviations() {
        List<String> lines = new ArrayList<>();
        replacements.forEach((msgType, groups) ->
                groups.keySet().forEach(counter ->
                        lines.add(msgType + ": group " + counter + " redefined")));
        removals.forEach((msgType, counters) ->
                counters.forEach(counter ->
                        lines.add(msgType + ": group " + counter + " removed")));
        return lines;
    }

    // ------------------------------------------------------------------

    public static final class Builder {
        private final Dialect base;
        private final String name;
        private final Map<String, Map<Integer, GroupTemplate>> replacements = new LinkedHashMap<>();
        private final Map<String, List<Integer>> removals = new LinkedHashMap<>();

        private Builder(Dialect base, String name) {
            this.base = base;
            this.name = name;
        }

        /** Redefine one group for one message type, or add a group the standard lacks. */
        public Builder replaceGroup(String msgType, GroupTemplate template) {
            replacements
                    .computeIfAbsent(msgType, key -> new LinkedHashMap<>())
                    .put(template.counterTag(), template);
            return this;
        }

        /** Apply the same redefinition to several message types. */
        public Builder replaceGroup(List<String> msgTypes, GroupTemplate template) {
            msgTypes.forEach(msgType -> replaceGroup(msgType, template));
            return this;
        }

        /** Declare that a standard group never appears on this session. */
        public Builder removeGroup(String msgType, int counterTag) {
            removals.computeIfAbsent(msgType, key -> new ArrayList<>()).add(counterTag);
            return this;
        }

        public DialectOverlay build() {
            Map<String, Map<Integer, GroupTemplate>> frozenReplacements = new LinkedHashMap<>();
            replacements.forEach((msgType, groups) ->
                    frozenReplacements.put(msgType, Map.copyOf(groups)));
            Map<String, List<Integer>> frozenRemovals = new LinkedHashMap<>();
            removals.forEach((msgType, counters) ->
                    frozenRemovals.put(msgType, List.copyOf(counters)));
            return new DialectOverlay(
                    base, name, Map.copyOf(frozenReplacements), Map.copyOf(frozenRemovals));
        }
    }
}
