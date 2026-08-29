package io.nexum.routing;

import io.nexum.core.Context;
import io.nexum.transport.TransportEvents;

import java.util.Set;

/**
 * Handles one or more FIX message types.
 *
 * <p>Message dispatch used to be a switch inside the pipeline, which made a new
 * message type a change to the pipeline rather than an addition beside it.
 * Handlers register themselves, so supporting OrderMassCancel or a
 * counterparty's proprietary message is a plugin someone writes without
 * touching what is already working.
 *
 * <p>A handler receives the message after the session-layer chain has run and
 * is responsible for everything from there: routing it, applying it to an
 * order, journalling, and answering the client. {@link OrderServices} carries
 * what it needs to do that.
 */
public interface MessageHandler {

    /**
     * MsgType(35) values this handles.
     *
     * <p>Two handlers claiming the same type is a configuration error the
     * registry refuses rather than resolving — silently preferring one would
     * make which of them runs depend on load order.
     */
    Set<String> handles();

    /**
     * Deal with one message.
     *
     * @param arrival the message, with whatever the session chain made of it
     */
    void handle(Context ctx, OrderServices services, TransportEvents.InFlight arrival);

    /**
     * Ordering hint when a deployment layers behaviour on a message type.
     *
     * <p>Lower runs first. Rarely needed — most handlers claim distinct types.
     */
    default int order() {
        return 0;
    }
}
