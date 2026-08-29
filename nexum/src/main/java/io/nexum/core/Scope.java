package io.nexum.core;

/**
 * Where a plugin's gate is mounted. A message is not run through one global
 * chain — it is run through the chains belonging to the specific session,
 * client, and destination it was routed to, so two counterparties can behave
 * completely differently without either knowing about the other.
 *
 * <p>Mounting points, in the order a downstream message crosses them:
 *
 * <pre>
 *   Session      the physical socket the message arrived on
 *      |         fingerprint routing
 *   Client       the logical customer behind that socket
 *      |
 *   Routing      venue selection and any rewriting it needs
 *      |         fingerprint routing
 *   Destination  the outbound venue
 * </pre>
 *
 * <p>{@link Global} gates run at every layer — audit, metrics, kill switch.
 */
public sealed interface Scope {

    /** Layer this scope belongs to, used to look up the right chain. */
    Layer layer();

    /** The specific session/client/destination id, or null for layer-wide scopes. */
    String id();

    enum Layer {
        GLOBAL,
        SESSION,
        CLIENT,
        ROUTING,
        DESTINATION
    }

    record Global() implements Scope {
        public Layer layer() {
            return Layer.GLOBAL;
        }

        public String id() {
            return null;
        }
    }

    record Session(String id) implements Scope {
        public Layer layer() {
            return Layer.SESSION;
        }
    }

    record Client(String id) implements Scope {
        public Layer layer() {
            return Layer.CLIENT;
        }
    }

    record Routing() implements Scope {
        public Layer layer() {
            return Layer.ROUTING;
        }

        public String id() {
            return null;
        }
    }

    record Destination(String id) implements Scope {
        public Layer layer() {
            return Layer.DESTINATION;
        }
    }

    static Scope global() {
        return new Global();
    }

    static Scope session(String id) {
        return new Session(id);
    }

    static Scope client(String id) {
        return new Client(id);
    }

    static Scope routing() {
        return new Routing();
    }

    static Scope destination(String id) {
        return new Destination(id);
    }

    /** Key used to index chains: "SESSION:BROKER_A", "GLOBAL", and so on. */
    default String key() {
        return id() == null ? layer().name() : layer().name() + ":" + id();
    }
}
