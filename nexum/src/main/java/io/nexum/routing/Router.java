package io.nexum.routing;

import io.nexum.core.Fingerprint;
import io.nexum.message.FixMessage;

import java.util.List;
import java.util.Optional;

/**
 * Recognises which client an inbound message belongs to, and which destination
 * an order should go out on.
 *
 * <p>Both hops are decided from message content, because neither identity is
 * carried by the transport: a socket may serve several clients, and the venue is
 * a business decision rather than a property of the connection.
 *
 * <p>Rules are tried in order and the first match wins, so specific rules belong
 * above general ones and a catch-all belongs last.
 */
public final class Router {

    private final List<Rule> clientRules;
    private final List<Rule> destinationRules;

    public record Rule(String target, Fingerprint fingerprint) {}

    public Router(List<Rule> clientRules, List<Rule> destinationRules) {
        this.clientRules = List.copyOf(clientRules);
        this.destinationRules = List.copyOf(destinationRules);
    }

    public Optional<String> toClient(FixMessage message) {
        return first(clientRules, message);
    }

    public Optional<String> toDestination(FixMessage message) {
        return first(destinationRules, message);
    }

    private static Optional<String> first(List<Rule> rules, FixMessage message) {
        for (Rule rule : rules) {
            if (rule.fingerprint().matches(message.flatFields())) {
                return Optional.of(rule.target());
            }
        }
        return Optional.empty();
    }

    /**
     * Why no rule matched — the first failing condition of every rule.
     *
     * <p>"This order did not go where I expected" is a routine question, and it
     * is cheaper to answer from the rules than to reconstruct from a log.
     */
    public List<String> explainNoClient(FixMessage message) {
        return explain(clientRules, message);
    }

    public List<String> explainNoDestination(FixMessage message) {
        return explain(destinationRules, message);
    }

    private static List<String> explain(List<Rule> rules, FixMessage message) {
        return rules.stream()
                .map(rule -> rule.target() + ": "
                        + String.join("; ", rule.fingerprint().explainFailure(message.flatFields())))
                .toList();
    }

    public List<Rule> clientRules() {
        return clientRules;
    }

    public List<Rule> destinationRules() {
        return destinationRules;
    }
}
