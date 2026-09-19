package io.nexum.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("a probe announces itself so a reader need not guess ports")
class ProbeRegistrationTest {

    @Test
    @DisplayName("a registered probe is listed with the port it is on")
    void aRegisteredProbeIsListed(@TempDir Path directory) {
        try (ProbeRegistration registration = new ProbeRegistration(directory, 18099)) {
            List<ProbeRegistration.Record> live = ProbeRegistration.live(directory);

            assertEquals(1, live.size(), () -> "expected one probe: " + live);
            assertEquals(18099, live.getFirst().port());
            assertEquals(ProcessHandle.current().pid(), live.getFirst().pid(),
                    "the record names the process that can be asked about it");
        }
    }

    @Test
    @DisplayName("closing withdraws the registration")
    void closingWithdrawsIt(@TempDir Path directory) {
        try (ProbeRegistration ignored = new ProbeRegistration(directory, 18099)) {
            assertEquals(1, ProbeRegistration.live(directory).size());
        }

        assertTrue(ProbeRegistration.live(directory).isEmpty(),
                "a probe that has stopped is not a probe a reader can reach");
    }

    @Test
    @DisplayName("several probes on several ports are all listed, in port order")
    void severalProbesAreAllListed(@TempDir Path directory) {
        try (ProbeRegistration first = new ProbeRegistration(directory, 18100);
                ProbeRegistration second = new ProbeRegistration(directory, 18099)) {
            List<Integer> ports = ProbeRegistration.live(directory).stream()
                    .map(ProbeRegistration.Record::port)
                    .toList();

            // One probe per counterparty being simulated is the ordinary case,
            // so a listing that only ever showed one would be useless.
            assertEquals(List.of(18099, 18100), ports);
        }
    }

    @Test
    @DisplayName("a record whose process is gone is dropped, and its file with it")
    void aDeadProbeIsDropped(@TempDir Path directory) throws IOException {
        // A pid that cannot be alive: a probe killed outright runs no shutdown
        // hook, so the file outliving the process is the expected case.
        Path orphan = directory.resolve("18099.json");
        Files.writeString(orphan,
                "{\"port\":18099,\"pid\":2147483646,\"startedAt\":1}", StandardCharsets.UTF_8);

        List<ProbeRegistration.Record> live = ProbeRegistration.live(directory);

        assertTrue(live.isEmpty(), () -> "a probe that is not running is not listed: " + live);
        assertFalse(Files.exists(orphan),
                "and the file goes, so every later listing need not re-decide it");
    }

    @Test
    @DisplayName("a malformed record hides none of the others")
    void aMalformedRecordHidesNothing(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("18100.json"), "{ truncated",
                StandardCharsets.UTF_8);

        try (ProbeRegistration ignored = new ProbeRegistration(directory, 18099)) {
            List<ProbeRegistration.Record> live = ProbeRegistration.live(directory);

            assertEquals(1, live.size(), () -> "the good record survives: " + live);
            assertEquals(18099, live.getFirst().port());
        }
    }

    @Test
    @DisplayName("registering twice on one port replaces the record rather than doubling it")
    void registeringTwiceReplaces(@TempDir Path directory) {
        try (ProbeRegistration ignored = new ProbeRegistration(directory, 18099);
                ProbeRegistration again = new ProbeRegistration(directory, 18099)) {
            assertEquals(1, ProbeRegistration.live(directory).size(),
                    "one port is one probe, whatever a stale file claims");
        }
    }

    @Test
    @DisplayName("listing a directory that does not exist is empty, not a failure")
    void listingNothingIsEmpty(@TempDir Path directory) {
        assertTrue(ProbeRegistration.live(directory.resolve("never-created")).isEmpty());
    }
}
