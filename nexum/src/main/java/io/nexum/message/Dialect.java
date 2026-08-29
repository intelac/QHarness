package io.nexum.message;

import java.util.Map;

/**
 * One counterparty's reading of FIX: which repeating groups exist in which
 * message types, and what each looks like.
 *
 * <p>Dialects are held per session (what arrives) and per destination (what goes
 * out), because the same tag can mean different things on either side. Client
 * layers share the session's dialect — a client is a logical partition of a
 * socket, not a separate wire format.
 *
 * <p>Implementations load from QuickFIX DataDictionary XML, which most venues
 * publish, or are assembled in code for tests.
 */
public interface Dialect {

    String name();

    /**
     * Repeating groups declared for a message type, keyed by counter tag.
     * An empty map means the message has no groups.
     */
    Map<Integer, GroupTemplate> groupsFor(String msgType);

    /** True when the tag is a group counter in this message type. */
    default boolean isGroupCounter(String msgType, int tag) {
        return groupsFor(msgType).containsKey(tag);
    }

    /** A dialect that declares no groups; every message parses as flat fields. */
    static Dialect flat(String name) {
        return new Dialect() {
            public String name() {
                return name;
            }

            public Map<Integer, GroupTemplate> groupsFor(String msgType) {
                return Map.of();
            }
        };
    }
}
