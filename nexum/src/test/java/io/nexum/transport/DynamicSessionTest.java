package io.nexum.transport;

import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.message.DialectPlugin;
import io.nexum.message.DialectRegistry;
import io.nexum.message.FixVersion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a session can be added and removed while the engine is running.
 *
 * <p>Adding a counterparty otherwise means editing the configuration file and
 * restarting, which drops every other session with it. The pieces for doing it
 * live already exist — one plugin owns one session, its connector starts inside
 * an effect, and the hub hands back a disposer when a session registers — but
 * nothing had ever exercised them that way, so this pins down that they hold
 * together before anything is built on top.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DynamicSessionTest {

    private Context ctx;
    private PluginLoader loader;

    @BeforeEach
    void bringUp() {
        ctx = new Context();
        loader = new PluginLoader(ctx);
        loader.load(List.of(new DialectPlugin(List.of()), new TransportHub.HubPlugin()));
    }

    @AfterEach
    void tearDown() {
        loader.unloadAll();
    }

    @Test
    @DisplayName("a session appears on the hub once its plugin is loaded")
    void aSessionCanBeAddedWhileRunning() throws Exception {
        Transport hub = ctx.get("transport");
        assertTrue(hub.sessions().isEmpty(), "nothing should be registered yet");

        add("OMS->LATE", freePort());

        assertTrue(hub.sessions().contains("OMS->LATE"),
                "the new session should be reachable: " + hub.sessions());
    }

    @Test
    @DisplayName("unloading a session's plugin takes it off the hub")
    void aSessionCanBeRemovedWhileRunning() throws Exception {
        Transport hub = ctx.get("transport");
        add("OMS->TEMP", freePort());
        assertTrue(hub.sessions().contains("OMS->TEMP"));

        loader.unload("transport-OMS->TEMP");

        assertFalse(hub.sessions().contains("OMS->TEMP"),
                "the session should be gone: " + hub.sessions());
    }

    @Test
    @DisplayName("adding one session leaves the others alone")
    void addingOneLeavesTheOthers() throws Exception {
        Transport hub = ctx.get("transport");
        add("OMS->FIRST", freePort());

        add("OMS->SECOND", freePort());

        assertTrue(hub.sessions().contains("OMS->FIRST"),
                "the first session should survive the second being added: " + hub.sessions());
        assertTrue(hub.sessions().contains("OMS->SECOND"), hub.sessions().toString());
    }

    @Test
    @DisplayName("removing one session leaves the others alone")
    void removingOneLeavesTheOthers() throws Exception {
        Transport hub = ctx.get("transport");
        add("OMS->KEEP", freePort());
        add("OMS->DROP", freePort());

        loader.unload("transport-OMS->DROP");

        assertTrue(hub.sessions().contains("OMS->KEEP"),
                "removing one session must not disturb another: " + hub.sessions());
        assertFalse(hub.sessions().contains("OMS->DROP"), hub.sessions().toString());
    }

    @Test
    @DisplayName("a session added, removed, and added again works each time")
    void aSessionCanComeBack() throws Exception {
        Transport hub = ctx.get("transport");
        int port = freePort();

        add("OMS->CYCLE", port);
        assertTrue(hub.sessions().contains("OMS->CYCLE"));

        loader.unload("transport-OMS->CYCLE");
        assertFalse(hub.sessions().contains("OMS->CYCLE"));

        // The port has to have been released, which is what proves the
        // connector was stopped rather than merely deregistered.
        add("OMS->CYCLE", port);
        assertTrue(hub.sessions().contains("OMS->CYCLE"),
                "it should be able to come back on the same port: " + hub.sessions());
    }

    // ------------------------------------------------------------------

    /**
     * Add one session while the engine runs.
     *
     * <p>Two steps, not one: the session's dialect is declared first, because
     * the transport asks the registry which version it speaks as it starts. A
     * session added without one fails to load rather than running undecoded.
     */
    private void add(String id, int port) throws Exception {
        DialectRegistry dialects = ctx.get("dialects");
        dialects.declareSession(id, FixVersion.FIX44);
        loader.load(List.of(acceptor(id, port)));
    }

    /** One acceptor session, named for the counterparty it serves. */
    private static QuickFixPlugin acceptor(String id, int port) {
        String target = id.substring(id.indexOf("->") + 2);
        String config = """
                [default]
                ConnectionType=acceptor
                SocketAcceptPort=%d
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                UseDataDictionary=N
                ResetOnLogon=Y
                FileLogPath=target/dynamic/logs

                [session]
                BeginString=FIX.4.4
                SenderCompID=OMS
                TargetCompID=%s
                """.formatted(port, target);
        return QuickFixPlugin.acceptor(id,
                new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)), false);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
