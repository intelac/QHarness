package io.nexum.transport;

import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.message.DialectPlugin;
import io.nexum.message.DialectRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a counterparty can be added and taken away while the engine runs.
 *
 * <p>The mechanics are covered by {@link DynamicSessionTest}; what this pins
 * down is the manager's own promises — that the two steps happen in the right
 * order, that a failure leaves nothing behind, and that a configured session is
 * not something it will remove.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SessionManagerTest {

    private Context ctx;
    private PluginLoader loader;
    private SessionManager manager;

    @BeforeEach
    void bringUp() {
        ctx = new Context();
        loader = new PluginLoader(ctx);
        loader.load(List.of(new DialectPlugin(List.of()), new TransportHub.HubPlugin()));
        manager = new SessionManager(ctx, loader);
    }

    @AfterEach
    void tearDown() {
        loader.unloadAll();
    }

    @Test
    @DisplayName("an added session is reachable straight away")
    void anAddedSessionIsReachable() throws Exception {
        SessionManager.Added added =
                manager.add("OMS->NEWVENUE", "acceptor", null, freePort(), "FIX44");

        Transport transport = ctx.get("transport");
        assertTrue(transport.sessions().contains("OMS->NEWVENUE"),
                "it should be on the hub at once: " + transport.sessions());
        assertEquals("acceptor", added.role());
        assertEquals("FIX44", added.version());
    }

    @Test
    @DisplayName("a removed session is gone and forgotten")
    void aRemovedSessionIsGone() throws Exception {
        manager.add("OMS->TEMP", "acceptor", null, freePort(), "FIX44");

        assertTrue(manager.remove("OMS->TEMP"));

        Transport transport = ctx.get("transport");
        assertFalse(transport.sessions().contains("OMS->TEMP"), transport.sessions().toString());
        assertFalse(manager.isAdded("OMS->TEMP"));
    }

    @Test
    @DisplayName("a session it did not add is not one it removes")
    void aConfiguredSessionIsLeftAlone() {
        // Nothing added, so nothing to remove — the same answer a configured
        // session gets, which is what keeps the engine and the file agreeing.
        assertFalse(manager.remove("OMS->CONFIGURED"));
    }

    @Test
    @DisplayName("a malformed id is refused before anything is started")
    void aMalformedIdIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> manager.add("NOARROW", "acceptor", null, 1234, "FIX44"));

        assertTrue(refused.getMessage().contains("SENDER->TARGET"), refused.getMessage());
        assertTrue(manager.added().isEmpty(), "nothing should have been recorded");
    }

    @Test
    @DisplayName("an unknown version is refused before anything is started")
    void anUnknownVersionIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.add("OMS->X", "acceptor", null, 1234, "FIX99"));

        assertTrue(manager.added().isEmpty());
    }

    @Test
    @DisplayName("adding the same session twice is refused")
    void aDuplicateIsRefused() throws Exception {
        int port = freePort();
        manager.add("OMS->ONCE", "acceptor", null, port, "FIX44");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> manager.add("OMS->ONCE", "acceptor", null, freePort(), "FIX44"));

        assertTrue(refused.getMessage().contains("already exists"), refused.getMessage());
    }

    @Test
    @DisplayName("a session that fails to start leaves no dialect behind")
    void aFailedStartLeavesNothing() throws Exception {
        // Two acceptors cannot hold one port, so the second fails inside the
        // loader — after its dialect was declared.
        int port = freePort();
        manager.add("OMS->HOLDER", "acceptor", null, port, "FIX44");

        assertThrows(RuntimeException.class,
                () -> manager.add("OMS->CLASH", "acceptor", null, port, "FIX44"));

        assertFalse(manager.isAdded("OMS->CLASH"), "a failed add must record nothing");

        // The dialect declared on the way in must have been taken back. Asked
        // about a session it does not know, the registry throws; a leftover
        // declaration would answer instead, leaving the engine holding a
        // dialect for a session that never started.
        DialectRegistry dialects = ctx.get("dialects");
        assertThrows(IllegalStateException.class, () -> dialects.forSession("OMS->CLASH"),
                "a failed add must leave no dialect behind");
    }

    @Test
    @DisplayName("an initiator records where it connects")
    void anInitiatorRecordsItsTarget() throws Exception {
        SessionManager.Added added =
                manager.add("OMS->REMOTE", "initiator", "10.0.0.1", 5001, "FIX44");

        assertEquals("initiator", added.role());
        assertEquals("10.0.0.1", added.host());
        assertEquals(5001, added.port());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
