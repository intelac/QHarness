package io.nexum.e2e;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;
import io.nexum.transport.LogonPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import quickfix.SocketInitiator;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an acceptor on a public address does not admit anyone who knows a CompID.
 *
 * <p>CompIDs are in the counterparty's onboarding document, so they are not a
 * credential. An acceptor that checks nothing accepts orders from whoever reads
 * one — which is what makes this worth a test over real sockets rather than a
 * unit test of the policy object. The refusal has to actually reach the wire.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LogonPolicyTest {

    private static final String PASSWORD = "s3cret-from-the-onboarding-pack";

    private Context ctx;
    private PluginLoader loader;
    private SocketInitiator client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.stop(true);
        }
        if (loader != null) {
            loader.unloadAll();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the policy itself")
    class Decisions {

        @Test
        void refusesAWrongPassword() {
            LogonPolicy policy = LogonPolicy.require(PASSWORD, List.of());
            assertTrue(policy.refuse("S", "guessed", "10.0.0.1").isPresent());
        }

        @Test
        void refusesAMissingPassword() {
            LogonPolicy policy = LogonPolicy.require(PASSWORD, List.of());
            assertTrue(policy.refuse("S", null, "10.0.0.1").isPresent(),
                    "a logon with no 554 at all must not pass a password check");
        }

        @Test
        void acceptsTheRightPassword() {
            LogonPolicy policy = LogonPolicy.require(PASSWORD, List.of());
            assertTrue(policy.refuse("S", PASSWORD, "10.0.0.1").isEmpty());
        }

        @Test
        void refusesAnAddressOutsideTheList() {
            LogonPolicy policy = LogonPolicy.require(null, List.of("10.0."));
            assertTrue(policy.refuse("S", null, "203.0.113.9").isPresent());
            assertTrue(policy.refuse("S", null, "10.0.0.7").isEmpty());
        }

        @Test
        void refusesWhenTheAddressIsUnknownAndAListExists() {
            LogonPolicy policy = LogonPolicy.require(null, List.of("10.0."));

            // Failing open here would make the check depend on whether the
            // engine happened to report an address.
            assertTrue(policy.refuse("S", null, null).isPresent());
        }

        @Test
        void requiresBothWhenBothAreConfigured() {
            LogonPolicy policy = LogonPolicy.require(PASSWORD, List.of("10.0."));
            assertTrue(policy.refuse("S", PASSWORD, "203.0.113.9").isPresent(),
                    "the right password from the wrong place is still refused");
            assertTrue(policy.refuse("S", "wrong", "10.0.0.7").isPresent(),
                    "the wrong password from the right place is still refused");
            assertTrue(policy.refuse("S", PASSWORD, "10.0.0.7").isEmpty());
        }

        @Test
        void acceptsEverythingWhenOpen() {
            assertTrue(LogonPolicy.open().refuse("S", null, "203.0.113.9").isEmpty());
        }

        @Test
        void doesNotRevealTheReasonInWhatItReturns() {
            // The reason exists for this system's own log. What the refusal
            // says to the caller is the engine's business, not the policy's.
            Optional<String> refusal =
                    LogonPolicy.require(PASSWORD, List.of()).refuse("S", "guessed", null);
            assertTrue(refusal.isPresent());
            assertFalse(refusal.get().contains(PASSWORD),
                    "the reason must not echo the expected password");
        }
    }

    @Nested
    @DisplayName("over a real socket")
    class OnTheWire {

        @Test
        void aClientWithoutThePasswordCannotLogOn() throws Exception {
            int port = freePort();
            start(port, """
                    security:
                      password: %s
                    """.formatted(PASSWORD));

            RecordingClient recorder = new RecordingClient();
            client = RecordingClient.start(recorder, port, "FUNDX", "OMS");

            // Give it well past a logon round trip. Staying logged out is the
            // assertion, so this waits rather than polls for a change.
            Thread.sleep(4000);

            assertFalse(recorder.isLoggedOn(),
                    "a logon with no password must not establish a session");
        }

        @Test
        void isNotToldWhyItWasRefused() throws Exception {
            int port = freePort();
            start(port, """
                    security:
                      password: %s
                    """.formatted(PASSWORD));

            // Watch what actually leaves, since the engine composes the Logout.
            List<String> outbound = new java.util.concurrent.CopyOnWriteArrayList<>();
            ctx.onEvent(io.nexum.transport.TransportEvents.WIRE,
                    (io.nexum.transport.TransportEvents.Wire wire) -> {
                        if (wire.direction()
                                == io.nexum.transport.TransportEvents.Direction.OUT) {
                            outbound.add(wire.raw());
                        }
                    });

            RecordingClient recorder = new RecordingClient();
            client = RecordingClient.start(recorder, port, "FUNDX", "OMS");
            Thread.sleep(4000);

            assertFalse(recorder.isLoggedOn());
            assertFalse(outbound.stream().anyMatch(raw -> raw.contains("password")),
                    "the refusal must not say which check failed: " + outbound);
        }

        @Test
        void aVenueWeDialIsNotCheckedAgainstOurOwnPassword() throws Exception {
            // The policy authenticates counterparties who connect to us. A
            // venue we connect to answers our Logon with its own, and checking
            // that against our password refused every destination — the whole
            // outbound leg went down the moment a password was configured.
            int acceptorPort = freePort();
            int venuePort = freePort();

            io.nexum.sim.SimVenue.restOn("VERIFY");
            quickfix.SocketAcceptor venue =
                    io.nexum.sim.SimVenue.start(venuePort, "LSE", "OMS");

            try {
                ctx = new Context();
                loader = Bootstrap.from("""
                        monitor:
                          enabled: false

                        security:
                          password: %s

                        sessions:
                          - id: OMS->FUNDX
                            version: FIX.4.4
                            role: acceptor
                            port: %d
                            logPath: target/e2e/logs
                            persistent: false

                          - id: OMS->LSE
                            version: FIX.4.4
                            role: initiator
                            host: 127.0.0.1
                            port: %d
                            logPath: target/e2e/logs
                            persistent: false

                        clients:
                          - id: FUND_X
                            fingerprint:
                              115: FUNDX

                        routes:
                          - destination: OMS->LSE
                            fingerprint: any
                        """.formatted(PASSWORD, acceptorPort, venuePort)).start(ctx);

                io.nexum.transport.Transport transport = ctx.get("transport");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline && !transport.isLoggedOn("OMS->LSE")) {
                    Thread.sleep(100);
                }

                assertTrue(transport.isLoggedOn("OMS->LSE"),
                        "a venue we dial must not be refused by our own logon policy");
            } finally {
                venue.stop(true);
            }
        }

        @Test
        void aClientIsAdmittedWhenNoPolicyIsConfigured() throws Exception {
            int port = freePort();
            start(port, "");

            RecordingClient recorder = new RecordingClient();
            client = RecordingClient.start(recorder, port, "FUNDX", "OMS");

            await(recorder);
            assertTrue(recorder.isLoggedOn(),
                    "without a policy the acceptor behaves as it always did");
        }
    }

    // ------------------------------------------------------------------

    private void start(int port, String securityBlock) {
        ctx = new Context();
        loader = Bootstrap.from(config(port) + securityBlock).start(ctx);
    }

    private static void await(RecordingClient recorder) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (recorder.isLoggedOn()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for logon");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String config(int port) {
        return """
            monitor:
              enabled: false

            sessions:
              - id: OMS->FUNDX
                version: FIX.4.4
                role: acceptor
                port: %d
                logPath: target/e2e/logs
                persistent: false

            clients:
              - id: FUND_X
                fingerprint:
                  115: FUNDX

            routes:
              - destination: OMS->FUNDX
                fingerprint: any

            """.formatted(port);
    }
}
