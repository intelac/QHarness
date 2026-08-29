package io.nexum.transport;

import io.nexum.core.Context;
import io.nexum.core.Disposable;
import io.nexum.core.Plugin;
import io.nexum.message.DialectRegistry;

import quickfix.Connector;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageStoreFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

import java.io.InputStream;
import java.util.List;

/**
 * Mounts QuickFIX/J as the {@code transport} service.
 *
 * <p>The connector is started inside an effect, so unloading the plugin stops
 * it and takes the service registration with it. That is what makes a transport
 * swap a configuration change rather than a restart.
 *
 * <p>Choosing initiator or acceptor is the whole difference between connecting
 * out to a broker and accepting connections from clients; both mount the same
 * way and publish the same service.
 */
public final class QuickFixPlugin implements Plugin {

    public enum Role {
        INITIATOR,
        ACCEPTOR
    }

    private final String id;
    private final Role role;
    private final SessionSettings settings;
    private final boolean persistent;

    public QuickFixPlugin(String id, Role role, SessionSettings settings, boolean persistent) {
        this.id = id;
        this.role = role;
        this.settings = settings;
        this.persistent = persistent;
    }

    public static QuickFixPlugin initiator(String id, InputStream config) {
        return initiator(id, config, true);
    }

    public static QuickFixPlugin acceptor(String id, InputStream config) {
        return acceptor(id, config, true);
    }

    /**
     * @param persistent keep sequence numbers on disk. Off only for tests — a
     *     session that forgets its sequence numbers across a restart will be
     *     rejected or asked to resend everything.
     */
    public static QuickFixPlugin initiator(String id, InputStream config, boolean persistent) {
        return new QuickFixPlugin(id, Role.INITIATOR, read(config), persistent);
    }

    public static QuickFixPlugin acceptor(String id, InputStream config, boolean persistent) {
        return new QuickFixPlugin(id, Role.ACCEPTOR, read(config), persistent);
    }

    private static SessionSettings read(InputStream config) {
        try {
            return new SessionSettings(config);
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot read session settings", failure);
        }
    }

    @Override
    public String name() {
        return "transport-" + id;
    }

    @Override
    public List<String> inject() {
        // Dialects decide how an inbound message is parsed, and the hub is where
        // this engine's sessions become reachable; both precede any socket.
        return List.of("dialects", "transport");
    }

    @Override
    public void apply(Context ctx) {
        DialectRegistry dialects = ctx.get("dialects");
        TransportHub hub = ctx.get("transport");
        // The settings go in so a session can report the port it is reached
        // on: the plugin holds them anyway, and without them that answer lives
        // only in a configuration file.
        QuickFixTransport transport =
                new QuickFixTransport(ctx, dialects, role == Role.ACCEPTOR, settings);

        ctx.effect(() -> {
            MessageStoreFactory stores = persistent
                    ? new FileStoreFactory(settings)
                    : new MemoryStoreFactory();
            // A file log per session, always. The wire is the record of what a
            // counterparty was actually told, and it is the first thing asked
            // for when a trade is disputed — it does not belong only on a
            // console that scrolls away.
            LogFactory logs = new FileLogFactory(settings);
            Connector connector;
            try {
                connector = role == Role.INITIATOR
                        ? new SocketInitiator(
                                transport, stores, settings, logs, new DefaultMessageFactory())
                        : new SocketAcceptor(
                                transport, stores, settings, logs, new DefaultMessageFactory());
                connector.start();
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "cannot start " + role + " \"" + id + "\"", failure);
            }
            // Sessions exist once the connector has read its settings, so the
            // hub can only learn about them here.
            Disposable detach = hub.attach(transport.sessions(), transport);
            // The session announced itself while the connector was starting,
            // before the line above put it within the hub's reach — so anything
            // watching the hub was told a session existed and could learn
            // nothing about it. Announcing again now carries the state.
            transport.announceExisting();
            // Stopping the connector logs sessions out cleanly rather than
            // dropping sockets, so the counterparty sees a Logout and not a gap.
            return () -> {
                detach.dispose();
                connector.stop();
            };
        });
    }
}
