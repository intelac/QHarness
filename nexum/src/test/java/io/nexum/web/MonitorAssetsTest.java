package io.nexum.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the monitor renders without reaching the internet.
 *
 * <p>The grid used to load from a CDN. This runs in a network where that is not
 * reliably reachable, and a monitor that renders a blank page during an
 * incident is worse than useless — so the assets ship in the jar, and these
 * tests hold them there.
 */
class MonitorAssetsTest {

    @Test
    @DisplayName("the grid's assets are bundled, not fetched")
    void assetsArePresentInTheJar() throws IOException {
        for (String name : new String[] {
                "ag-grid.css", "ag-theme-quartz.css", "ag-grid-community.min.js"}) {

            try (InputStream in = MonitorApi.class.getResourceAsStream("/web/" + name)) {
                assertNotNull(in, name + " should be packaged under /web/");
                assertTrue(in.readAllBytes().length > 1000, name + " looks truncated");
            }
        }
    }

    @Test
    @DisplayName("the page references nothing external")
    void thePageHasNoExternalReferences() {
        assertTrue(WebPage.HTML.contains("vendor/ag-grid.css"));
        assertEquals(-1, WebPage.HTML.indexOf("https://"),
                "the page should not reference anything off this host");
    }

    @Test
    @DisplayName("the page works under a path prefix")
    void thePageUsesRelativePaths() {
        // Absolute /api/ paths break behind a proxy that mounts this under a
        // prefix — and on the target host /api/ already belongs to another
        // application, so those requests would reach the wrong service.
        assertTrue(WebPage.HTML.contains("<base href=\"./\">"),
                "a base tag is what makes relative paths safe without a trailing slash");
        assertEquals(-1, WebPage.HTML.indexOf("fetch('/api"),
                "API calls should be relative so the page can be mounted anywhere");
    }

    @Test
    @DisplayName("vendor serves only what was shipped")
    void vendorRefusesAnythingElse() throws Exception {
        MonitorApi api = new MonitorApi(0, null);
        io.nexum.core.Context ctx = new io.nexum.core.Context();
        io.nexum.core.PluginLoader loader = new io.nexum.core.PluginLoader(ctx);

        ctx.register("transport", new io.nexum.transport.RecordingTransport("S"));
        ctx.register("monitor", new io.nexum.monitor.OrderMonitor(60_000));
        loader.load(java.util.List.of(api));

        try {
            int port = api.port();
            assertTrue(port > 0, "the monitor should report the port it bound");

            assertEquals(200, statusOf(port, "/vendor/ag-grid.css"));

            // Anything not on the list, including a traversal attempt.
            assertEquals(404, statusOf(port, "/vendor/secrets.txt"));
        } finally {
            loader.unloadAll();
        }
    }

    @Test
    @DisplayName("the read endpoints can be read from a page served elsewhere")
    void readEndpointsAllowCrossOriginReads() throws Exception {
        MonitorApi api = new MonitorApi(0, null);
        io.nexum.core.Context ctx = new io.nexum.core.Context();
        io.nexum.core.PluginLoader loader = new io.nexum.core.PluginLoader(ctx);

        ctx.register("transport", new io.nexum.transport.RecordingTransport("S"));
        ctx.register("monitor", new io.nexum.monitor.OrderMonitor(60_000));
        loader.load(java.util.List.of(api));

        try {
            // A harness UI on another port reads these. Without the header the
            // browser refuses the response and the panel is empty with nothing
            // in any log to say why.
            HttpURLConnection connection = (HttpURLConnection)
                    URI.create("http://127.0.0.1:" + api.port() + "/api/sessions")
                            .toURL().openConnection();
            try {
                assertEquals(200, connection.getResponseCode());
                assertEquals("*",
                        connection.getHeaderField("Access-Control-Allow-Origin"));
            } finally {
                connection.disconnect();
            }
        } finally {
            loader.unloadAll();
        }
    }

    private static int statusOf(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
