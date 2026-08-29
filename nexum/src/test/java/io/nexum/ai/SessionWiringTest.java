package io.nexum.ai;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.transport.Transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a session says how it is reached.
 *
 * <p>Connecting a counterparty to a session needs three things: the port, which
 * side dials, and the two CompIDs. The id carries the CompIDs and the engine
 * knew the rest, but reported none of it — so the only way to answer "how do I
 * connect to this" was to open the configuration file the deployment runs
 * from. That is the file a reader is told not to trust, because it may not be
 * what is actually running, and it is not reachable at all from an agent
 * testing a system on another host.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SessionWiringTest {

    private PluginLoader loader;

    @AfterEach
    void tearDown() {
        if (loader != null) loader.unloadAll();
    }

    @Test
    @DisplayName("an acceptor says which port it waits on")
    void acceptorReportsItsPort() throws Exception {
        int clientPort = freePort();
        Transport transport = bringUp(clientPort, freePort());

        Transport.SessionStatus status = transport.status("OMS->FUNDX");

        assertEquals("acceptor", status.role());
        assertEquals(clientPort, status.port(),
                "the port a counterparty must dial is not reported");
        // An acceptor waits to be dialled, so it dials nowhere. Reporting a
        // host for one would describe a connection that does not exist.
        assertNull(status.host(), "an acceptor does not dial out");
    }

    @Test
    @DisplayName("an initiator says where it dials")
    void initiatorReportsWhereItDials() throws Exception {
        int venuePort = freePort();
        Transport transport = bringUp(freePort(), venuePort);

        Transport.SessionStatus status = transport.status("OMS->LSE");

        assertEquals("initiator", status.role());
        assertEquals(venuePort, status.port());
        assertEquals("127.0.0.1", status.host(),
                "where this session dials is not reported");
    }

    @Test
    @DisplayName("what a counterparty needs is readable without the config file")
    void harnessParametersCanBeDerived() throws Exception {
        int clientPort = freePort();
        Transport transport = bringUp(clientPort, freePort());

        AiTool listSessions = new SessionTools(transport).all().get(0);
        AiTool.Result result = listSessions.call(Map.of());

        // This is the whole point: standing up a counterparty against this
        // session needs the port and which side dials, and both now come from
        // the running engine rather than from a file that may not match it.
        assertTrue(result.content().contains(String.valueOf(clientPort)),
                "the port is missing, so connecting still needs the config file: "
                        + result.content());
        assertTrue(result.content().contains("acceptor"),
                "which side dials is missing: " + result.content());

        // The id carries the CompIDs, and they are crossed: a harness dialling
        // OMS->FUNDX is FUNDX talking to OMS.
        assertTrue(result.content().contains("OMS->FUNDX"),
                "the session id names both CompIDs: " + result.content());
    }

    @Test
    @DisplayName("a session nothing is known about does not invent a port")
    void unknownWiringIsNotGuessed() {
        // A transport built without settings knows the session exists and
        // nothing about how it is wired. Zero says so; a guessed port would be
        // acted on, which is worse than an admitted gap.
        Transport.SessionStatus unknown = new Transport.SessionStatus(
                "A->B", false, 1, 1, "FIX.4.4");

        assertEquals(0, unknown.port(), "an unknown port must not be invented");
        assertEquals("unknown", unknown.role());
        assertNull(unknown.host());
    }

    // ------------------------------------------------------------------

    private Transport bringUp(int clientPort, int venuePort) throws Exception {
        Context ctx = new Context();
        loader = Bootstrap.from(config(clientPort, venuePort)).start(ctx);
        return ctx.get("transport");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String config(int clientPort, int venuePort) {
        return """
                monitor:
                  enabled: false

                sessions:
                  - id: OMS->FUNDX
                    version: FIX.4.4
                    role: acceptor
                    port: %d
                    logPath: target/session-wiring/logs
                    persistent: false

                  - id: OMS->LSE
                    version: FIX.4.4
                    role: initiator
                    host: 127.0.0.1
                    port: %d
                    logPath: target/session-wiring/logs
                    persistent: false

                clients:
                  - id: FUND_X
                    fingerprint:
                      115: FUNDX

                routes:
                  - destination: OMS->LSE
                    fingerprint: any
                """.formatted(clientPort, venuePort);
    }
}
