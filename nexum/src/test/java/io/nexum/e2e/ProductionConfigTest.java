package io.nexum.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the shipped example configuration actually starts.
 *
 * <p>It did not: {@code Main} mounted a monitor and the configuration's
 * {@code web.port} mounted another, and the loader refused the duplicate. The
 * failure only appeared on the server, because nothing here had ever started
 * the system from a production-shaped file — every other test writes its own
 * config inline and none of them set {@code web.port}.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ProductionConfigTest {

    @Test
    void theExampleConfigurationStarts() throws Exception {
        // The repository's one deployment example, which lives beside the rest
        // of what a deployment is run from rather than under this module.
        Path example = Path.of("../deploy/nexum.yaml.example");
        assertTrue(Files.isReadable(example),
                "the deployment example should exist at " + example.toAbsolutePath());

        // Ports and paths that do not collide with a developer's machine. The
        // rest of the file — including web.port, which is what broke — is used
        // exactly as shipped.
        Path journal = Files.createTempDirectory("nexum-config-test");
        journal.toFile().deleteOnExit();

        String config = Files.readString(example)
                .replace("port: 9880", "port: " + freePort())
                .replace("port: 8080", "port: " + freePort())
                .replace("/var/lib/nexum/journal", journal.resolve("journal").toString())
                .replace("/var/lib/nexum/logs", journal.resolve("logs").toString())
                .replace("CHANGE-ME", "test-password");

        Path file = journal.resolve("nexum.yaml");
        Files.writeString(file, config);

        // Through Main, not Bootstrap: the duplicate monitor was added by Main,
        // so a test that calls Bootstrap directly passes against the broken
        // code. This one was written that way first and did exactly that.
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "io.nexum.app.Main",
                file.toString())
                .redirectErrorStream(true)
                .start();

        try {
            String output = readUntilStartedOrDead(process);

            assertTrue(output.contains("nexum started"),
                    "the example configuration should start, but Main said:\n" + output);
            assertFalse(output.contains("Exception"),
                    "starting from the shipped example threw:\n" + output);
        } finally {
            process.destroy();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Read until the process says it started, or dies trying.
     *
     * <p>It runs until stopped, so waiting for it to exit would hang; and
     * reading a fixed number of lines would block when it dies early with
     * fewer.
     */
    private static String readUntilStartedOrDead(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        try (var reader = process.inputReader()) {
            while (System.nanoTime() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    output.append(line).append('\n');
                    if (line.contains("nexum started")) {
                        return output.toString();
                    }
                } else if (!process.isAlive()) {
                    break;
                } else {
                    Thread.sleep(50);
                }
            }
        }
        return output.toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
