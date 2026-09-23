package io.nexum.sim;

import io.nexum.message.FixVersion;
import io.nexum.probe.CounterpartyHarness;
import io.nexum.probe.HarnessMessages;
import io.nexum.probe.ProbeFixVersion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import quickfix.Connector;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The simulators speak whichever version they are started in.
 *
 * <p>A probe stands on the other side of each one, so what is checked is what
 * crossed the wire rather than what the simulator meant to send.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SimVersionTest {

    private static final char SOH = '\u0001';

    private Connector sim;
    private CounterpartyHarness probe;

    @AfterEach
    void tearDown() {
        if (probe != null) probe.stop();
        if (sim != null) sim.stop(true);
        SimVenue.reset();
    }

    @Test
    @DisplayName("a 4.2 venue reports a fill the way 4.2 does")
    void fourTwoVenue() throws Exception {
        String fill = fillFromVenue(FixVersion.FIX42, ProbeFixVersion.FIX42);

        assertEquals("FIX.4.2", field(fill, "8"));
        assertEquals("0", field(fill, "20"), "ExecTransType is required through 4.2");
        assertEquals("2", field(fill, "150"), "a full fill; 4.2 has no Trade (F)");
    }

    @Test
    @DisplayName("a 4.4 venue reports a fill as a Trade, without ExecTransType")
    void fourFourVenue() throws Exception {
        String fill = fillFromVenue(FixVersion.FIX44, ProbeFixVersion.FIX44);

        assertEquals("FIX.4.4", field(fill, "8"));
        assertEquals("F", field(fill, "150"));
        assertFalse(fill.contains(SOH + "20="), fill);
    }

    @Test
    @DisplayName("a 5.0 venue speaks over FIXT.1.1 and names its application version")
    void fiveVenue() throws Exception {
        String fill = fillFromVenue(FixVersion.FIX50, ProbeFixVersion.FIX50);

        assertEquals("FIXT.1.1", field(fill, "8"));
        assertEquals("7", field(fill, "1128"));
        assertEquals("F", field(fill, "150"));
    }

    @Test
    @DisplayName("a 4.2 client sends its orders in 4.2")
    void fourTwoClient() throws Exception {
        int port = freePort();
        probe = new CounterpartyHarness("market", CounterpartyHarness.Role.ACCEPTOR);
        probe.start("127.0.0.1", port, "OMS", "FUNDX", ProbeFixVersion.FIX42);
        sim = SimClient.start(port, "FUNDX", "OMS", FixVersion.FIX42);
        awaitLoggedOn();
        awaitTrue(SimClient::isLoggedOn, "the simulated client never logged on");

        SimClient.sendOrder("SC-1", "BP", 100, "XLON");

        String order = awaitReceived("D", null);
        assertEquals("FIX.4.2", field(order, "8"));
        assertEquals("SC-1", field(order, "11"));
    }

    // ------------------------------------------------------------------

    /** Place one order with a venue speaking one version, and return the fill it sends back. */
    private String fillFromVenue(FixVersion venueVersion, ProbeFixVersion probeVersion)
            throws Exception {
        int port = freePort();
        sim = SimVenue.start(port, "LSE", "OMS", venueVersion);
        probe = new CounterpartyHarness("client", CounterpartyHarness.Role.INITIATOR);
        probe.start("127.0.0.1", port, "OMS", "LSE", probeVersion);
        awaitLoggedOn();

        probe.send(new HarnessMessages(probeVersion)
                .newOrderSingle("V-1", "BP", '1', 100, 50.0, null, null));

        // 39=2: the order is filled, whichever ExecType the version spells it with.
        return awaitReceived("8", SOH + "39=2" + SOH);
    }

    private void awaitLoggedOn() throws Exception {
        awaitTrue(probe::isLoggedOn, "the probe never logged on to the simulator");
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String why)
            throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail(why);
    }

    private String awaitReceived(String msgType, String needle) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            for (CounterpartyHarness.Traffic entry : probe.received(msgType)) {
                if (needle == null || entry.raw().contains(needle)) {
                    return entry.raw();
                }
            }
            Thread.sleep(50);
        }
        return fail("no " + msgType + (needle == null ? "" : " containing " + needle)
                + "; saw " + probe.traffic());
    }

    private static String field(String raw, String tag) {
        for (String part : raw.split(String.valueOf(SOH))) {
            if (part.startsWith(tag + "=")) {
                return part.substring(tag.length() + 1);
            }
        }
        return fail("no tag " + tag + " in " + raw);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
