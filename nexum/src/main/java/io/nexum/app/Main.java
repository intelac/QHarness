package io.nexum.app;

import io.nexum.config.Bootstrap;
import io.nexum.core.Context;
import io.nexum.core.PluginLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Runs NEXUM from a configuration file.
 *
 * <p>Everything this process does is decided by the YAML it is given: which
 * sessions exist, which clients are recognised, where orders are routed. There
 * is no behaviour compiled in here that a deployment cannot change — the point
 * of the plugin design is that this file stays this short.
 *
 * <pre>
 *   java -jar nexum.jar /etc/nexum/nexum.yaml
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: nexum <config.yaml>");
            System.err.println();
            System.err.println("The monitor listens on the port the file's"
                    + " web.port declares.");
            System.exit(2);
            return;
        }

        Path config = Path.of(args[0]);
        if (!Files.isReadable(config)) {
            // Named rather than described: an operator needs the path it tried,
            // not the fact that something was unreadable.
            System.err.println("cannot read configuration: " + config.toAbsolutePath());
            System.exit(2);
            return;
        }

        // The monitor is mounted by Bootstrap when the configuration declares a
        // web.port. Adding one here as well registers the same service twice,
        // which the loader refuses — correctly, since which of them answered
        // would otherwise depend on load order.
        Context ctx = new Context();
        PluginLoader loader = Bootstrap.from(Files.readString(config)).start(ctx);

        System.out.println("nexum started from " + config.toAbsolutePath());

        // A FIX engine that dies without logging out leaves the counterparty
        // holding a session it believes is live, and the next logon argues
        // about sequence numbers. Shutting down cleanly is not optional.
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("stopping");
            try {
                loader.unloadAll();
            } catch (RuntimeException failure) {
                System.err.println("unclean shutdown: " + failure);
            } finally {
                stopped.countDown();
            }
        }, "nexum-shutdown"));

        stopped.await();
        System.out.println("stopped");
    }

}
