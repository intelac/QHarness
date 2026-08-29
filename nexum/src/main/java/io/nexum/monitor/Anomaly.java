package io.nexum.monitor;

/**
 * Something about an order that warrants a look.
 *
 * <p>Carries the evidence rather than a message: whoever reacts — a console, an
 * alert route, a report — decides how to phrase it, and a rule that has to guess
 * at presentation ends up producing text nobody can filter on.
 */
public record Anomaly(
        String rule,
        Severity severity,
        String orderId,
        String summary,
        long detectedAt,
        java.util.Map<String, String> evidence) {

    public enum Severity {
        /** Worth knowing; nothing is stuck. */
        INFO,
        /** Something is late or unusual and may resolve on its own. */
        WARNING,
        /** An order is stuck, lost, or in a state that needs intervention. */
        CRITICAL
    }

    /** Identity for suppression: the same rule on the same order is one condition. */
    public String key() {
        return rule + ":" + orderId;
    }
}
