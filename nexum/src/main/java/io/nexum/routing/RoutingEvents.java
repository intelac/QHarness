package io.nexum.routing;

import io.nexum.core.EventKey;

import java.util.List;

/** What routing publishes when it cannot place a message. */
public final class RoutingEvents {

    private RoutingEvents() {}

    /**
     * No rule matched, so the message went nowhere.
     *
     * <p>Carries why each rule failed. "This order did not go where I expected"
     * is a routine question, and answering it from the rules beats
     * reconstructing it from a log.
     */
    public static final EventKey<Unmatched> RULE_UNMATCHED =
            EventKey.emit("routing/rule-unmatched", Unmatched.class);

    /**
     * @param stage which hop failed — recognising the client, or choosing the
     *     destination
     */
    public record Unmatched(
            Stage stage, String sessionId, String clientId, List<String> reasons, long at) {

        public enum Stage {
            CLIENT,
            DESTINATION
        }
    }

    /** A message type nothing is registered to handle. */
    public static final EventKey<Unhandled> MESSAGE_UNHANDLED =
            EventKey.emit("routing/message-unhandled", Unhandled.class);

    public record Unhandled(String sessionId, String msgType, long at) {}
}
