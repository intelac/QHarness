package io.nexum.routing;

import io.nexum.core.Disposable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which handler deals with which message type.
 *
 * <p>Published as the {@code handlers} service, so a plugin adds support for a
 * message type by registering rather than by editing a switch. Registration is
 * reversible, which is what lets a deployment swap one handler for another
 * without a restart.
 */
public final class HandlerRegistry {

    private final Map<String, List<MessageHandler>> byMsgType = new ConcurrentHashMap<>();

    /**
     * Register a handler for the message types it claims.
     *
     * @throws IllegalStateException when another handler already claims one of
     *     them at the same order. Two handlers for one type is a configuration
     *     mistake, and quietly preferring either would make which one runs
     *     depend on load order.
     */
    public Disposable register(MessageHandler handler) {
        for (String msgType : handler.handles()) {
            List<MessageHandler> claimed =
                    byMsgType.computeIfAbsent(msgType, key -> new ArrayList<>());
            synchronized (claimed) {
                boolean clash = claimed.stream()
                        .anyMatch(existing -> existing.order() == handler.order());
                if (clash) {
                    // Roll back what this call already claimed, so a refused
                    // registration leaves nothing half-installed.
                    unregister(handler);
                    throw new IllegalStateException(
                            "message type " + msgType + " is already handled at order "
                                    + handler.order() + "; give one a different order"
                                    + " or disable it");
                }
                claimed.add(handler);
                claimed.sort(Comparator.comparingInt(MessageHandler::order));
            }
        }
        return () -> unregister(handler);
    }

    private void unregister(MessageHandler handler) {
        for (String msgType : handler.handles()) {
            List<MessageHandler> claimed = byMsgType.get(msgType);
            if (claimed != null) {
                synchronized (claimed) {
                    claimed.remove(handler);
                }
            }
        }
    }

    /** Handlers for a message type, in order. Empty when nothing claims it. */
    public List<MessageHandler> forMsgType(String msgType) {
        List<MessageHandler> claimed = byMsgType.get(msgType);
        if (claimed == null) {
            return List.of();
        }
        synchronized (claimed) {
            return List.copyOf(claimed);
        }
    }

    public boolean handles(String msgType) {
        return !forMsgType(msgType).isEmpty();
    }

    /** What is registered, for a startup dump. */
    public Map<String, List<String>> describe() {
        Map<String, List<String>> described = new LinkedHashMap<>();
        byMsgType.forEach((msgType, handlers) -> {
            synchronized (handlers) {
                described.put(msgType, handlers.stream()
                        .map(handler -> handler.getClass().getSimpleName())
                        .toList());
            }
        });
        return described;
    }

    public Set<String> claimedTypes() {
        return Set.copyOf(byMsgType.keySet());
    }
}
