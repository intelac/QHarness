package io.nexum.probe;

import io.nexum.ai.AiTool;
import io.nexum.ai.McpHost;
import io.nexum.ai.McpServer;
import io.nexum.ai.ToolRegistry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Runs the probe on its own.
 *
 * <p>It takes a port and nothing else. There is no configuration file because
 * there is nothing to configure ahead of time: which system is being tested,
 * on which port, as which CompID, are answers a scenario gives when it calls
 * {@code harness_connect}, and they change from one run to the next.
 *
 * <pre>
 *   java -jar fixprobe.jar            # MCP on 127.0.0.1:18099
 *   java -jar fixprobe.jar 18100      # somewhere else
 * </pre>
 *
 * <p>Several can run at once on different ports — one per counterparty being
 * simulated, or two pointed at each other to check CompIDs and ports before a
 * system under test is involved at all.
 */
public final class Main {

    private static final String BIND = "127.0.0.1";
    private static final int DEFAULT_PORT = 18099;

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length == 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException notAPort) {
                System.err.println("usage: fixprobe [port]");
                System.exit(2);
                return;
            }
        } else if (args.length > 1) {
            System.err.println("usage: fixprobe [port]");
            System.exit(2);
            return;
        }

        HarnessRig rig = new HarnessRig();
        ToolRegistry registry = new ToolRegistry();
        new HarnessTools(rig).tools().forEach(registry::register);

        // Everything here reaches a system under test and nothing else, which
        // is why running the probe at all is the grant: there is no venue to
        // protect it from, and no order tool it could be confused with. The
        // engine's own gate is a separate thing in a separate process.
        registry.unlock("probe", ToolRegistry.Unlock.granted(
                "running the probe",
                List.of(HarnessTools.DESTINATION),
                Integer.MAX_VALUE,
                Duration.ofDays(365)));

        McpServer mcp = new McpServer(
                registry, "fixprobe", "0.1.0", HarnessTools.DESTINATION);

        try (McpHost host = new McpHost(mcp, BIND, port)) {
            System.out.println("fixprobe on " + BIND + ":" + host.port() + "/mcp");
            System.out.println("nothing is connected yet; harness_connect brings an endpoint up");

            // A FIX endpoint that disappears without logging out leaves the
            // counterparty holding a session it believes is live, and the next
            // logon argues about sequence numbers.
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("stopping");
                rig.stopAll();
                stopped.countDown();
            }, "fixprobe-shutdown"));

            stopped.await();
        }
        System.out.println("stopped");
    }
}
