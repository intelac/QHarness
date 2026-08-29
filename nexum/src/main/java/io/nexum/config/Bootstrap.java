package io.nexum.config;

import io.nexum.core.Context;
import io.nexum.core.Plugin;
import io.nexum.core.PluginLoader;
import io.nexum.message.DialectPlugin;
import io.nexum.message.FixVersion;
import io.nexum.order.OrderCachePlugin;
import io.nexum.routing.OrderPipeline;
import io.nexum.routing.RouterPlugin;
import io.nexum.transport.QuickFixPlugin;
import io.nexum.transport.TransportHub;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a configuration file into a running plugin tree.
 *
 * <p>What the file declares is which sessions exist, which clients and venues
 * they serve, and which optional plugins to mount. It does not declare a startup
 * order — that is derived from what each plugin says it needs, so adding a
 * plugin never means editing a sequence.
 *
 * <p>Custom plugins are supplied programmatically rather than by class name.
 * Loading arbitrary classes named in a file is how a configuration change turns
 * into code execution; deployments that want that can wrap this and add it
 * deliberately.
 */
public final class Bootstrap {

    private final Config config;
    private final List<Plugin> extras = new ArrayList<>();

    private Bootstrap(Config config) {
        this.config = config;
    }

    public static Bootstrap from(InputStream yaml) {
        return new Bootstrap(Yaml.parse(yaml));
    }

    public static Bootstrap from(String yaml) {
        return new Bootstrap(Yaml.parse(yaml));
    }

    /**
     * Layer a second file over the first; later values win.
     *
     * <p>Plugins already supplied are carried across. Dropping them would let a
     * validation or risk gate vanish because an overlay was applied after it was
     * added — orders that should have been refused would reach the venue with
     * nothing anywhere reporting the omission.
     */
    public Bootstrap overlay(String yaml) {
        Bootstrap layered = new Bootstrap(config.overlay(Yaml.parse(yaml)));
        layered.extras.addAll(extras);
        return layered;
    }

    /** Mount a plugin the configuration cannot name — validators, enrichers, dialects. */
    public Bootstrap with(Plugin... plugins) {
        extras.addAll(List.of(plugins));
        return this;
    }

    public Config config() {
        return config;
    }

    /**
     * Build the tree and start it.
     *
     * @return the loader, so a caller can unload a plugin or shut the tree down
     */
    public PluginLoader start(Context ctx) {
        List<Plugin> plugins = new ArrayList<>();

        List<DialectPlugin.SessionDeclaration> sessions = new ArrayList<>();
        for (Config session : config.sections("sessions")) {
            sessions.add(new DialectPlugin.SessionDeclaration(
                    session.require("id"),
                    FixVersion.ofBeginString(session.require("version"))));
        }
        if (sessions.isEmpty()) {
            throw new IllegalStateException("no sessions configured");
        }
        plugins.add(new DialectPlugin(sessions));
        Config orders = config.section("orders");
        String journal = orders.string("journal");
        plugins.add(journal == null
                ? new OrderCachePlugin()
                : new OrderCachePlugin(
                        java.nio.file.Path.of(journal), orders.flag("sync", true)));
        plugins.add(new RouterPlugin(config));

        // Who may log on. Declared once for the process rather than per
        // session: an acceptor is reachable before it knows which session a
        // connection wants.
        Config security = config.section("security");
        if (security.has("password") || security.has("allowFrom")) {
            plugins.add(new io.nexum.transport.LogonPolicyPlugin(
                    io.nexum.transport.LogonPolicyPlugin.from(
                            security.string("password"),
                            security.strings("allowFrom"))));
        }
        // The transport is a replaceable provider, so a deployment — or a test
        // — can supply its own. Mounting one unconditionally would make that
        // impossible, since two providers cannot share the service name.
        if (extras.stream().noneMatch(plugin -> plugin.provides().contains("transport"))) {
            plugins.add(new TransportHub.HubPlugin());
            for (Config session : config.sections("sessions")) {
                plugins.add(transportFor(session));
            }
        }

        plugins.add(new OrderPipeline());
        if (config.section("monitor").flag("enabled", true)) {
            plugins.add(io.nexum.monitor.MonitorPlugin.withDefaults());
        }
        // The agent interface. Absent unless a deployment asks for it: the
        // tools place and cancel orders, so it is not something to have on by
        // default.
        Config mcp = config.section("mcp");
        if (mcp.has("port")) {
            plugins.add(new io.nexum.ai.McpPlugin(
                    mcp.integer("port", 8090),
                    mcp.string("bind", "127.0.0.1"),
                    journal == null ? null : java.nio.file.Path.of(journal),
                    mcp.require("session"),
                    mcp.require("destination"),
                    agentIdentity(mcp.section("identity")),
                    mcp.integer("maxCalls", 0),
                    java.time.Duration.ofMinutes(mcp.integer("validMinutes", 480)),
                    mcp.integer("harnessCalls", 0)));
        }

        Config web = config.section("web");
        if (web.has("port")) {
            plugins.add(new io.nexum.web.MonitorApi(
                    web.integer("port", 8080),
                    journal == null ? null : java.nio.file.Path.of(journal)));
        }
        plugins.addAll(extras);

        PluginLoader loader = new PluginLoader(ctx);
        // Published before anything loads: a plugin that manages other plugins
        // — bringing a session up while the engine runs — needs the loader that
        // owns them, and asking for it afterwards would leave it unreachable to
        // the very plugins that were loaded with it.
        ctx.register("loader", loader);
        loader.load(plugins);
        return loader;
    }

    /**
     * The fields an agent's orders carry, so the routing layer recognises it
     * as a client.
     *
     * <p>Written as tags because that is what a fingerprint matches on, and
     * the two have to agree — an identity declared in field names would not
     * line up with a client declared in tags.
     */
    private static Map<Integer, String> agentIdentity(Config identity) {
        Map<Integer, String> fields = new LinkedHashMap<>();
        identity.raw().forEach((key, value) -> {
            try {
                fields.put(Integer.parseInt(key.trim()), String.valueOf(value));
            } catch (NumberFormatException notATag) {
                throw new IllegalStateException(
                        "mcp.identity keys must be FIX tags, but found \"" + key + "\"");
            }
        });
        return fields;
    }

    private static QuickFixPlugin transportFor(Config session) {
        String id = session.require("id");
        String role = session.string("role", "initiator");
        String settings = quickfixSettings(session);
        ByteArrayInputStream stream =
                new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8));
        boolean persistent = session.flag("persistent", true);
        return "acceptor".equalsIgnoreCase(role)
                ? QuickFixPlugin.acceptor(id, stream, persistent)
                : QuickFixPlugin.initiator(id, stream, persistent);
    }

    /**
     * Render QuickFIX/J session settings from our own vocabulary, so a
     * deployment describes sessions once rather than maintaining a parallel
     * engine-specific file.
     */
    private static String quickfixSettings(Config session) {
        String role = session.string("role", "initiator");
        boolean acceptor = "acceptor".equalsIgnoreCase(role);
        String id = session.require("id");
        int arrow = id.indexOf("->");
        if (arrow < 0) {
            throw new IllegalStateException(
                    "session id \"" + id + "\" must read SENDER->TARGET");
        }

        StringBuilder text = new StringBuilder();
        text.append("[default]\n")
                .append("ConnectionType=").append(acceptor ? "acceptor" : "initiator").append('\n')
                .append("StartTime=").append(session.string("startTime", "00:00:00")).append('\n')
                .append("EndTime=").append(session.string("endTime", "00:00:00")).append('\n')
                .append("HeartBtInt=").append(session.integer("heartbeat", 30)).append('\n')
                .append("ReconnectInterval=")
                .append(session.integer("reconnectSeconds", 5)).append('\n')
                .append("ResetOnLogon=")
                .append(session.flag("resetOnLogon", true) ? 'Y' : 'N').append('\n')
                // Validation lives in plugins, where a rejection can carry a
                // reason the counterparty's own dictionary explains.
                .append("UseDataDictionary=N\n");

        // One directory per session. QuickFIX/J names files after the session,
        // but a shared directory still mixes every counterparty's traffic into
        // one listing — and when a broker asks for "your log for Tuesday", the
        // answer should be a directory, not a grep.
        String logRoot = session.string("logPath", "logs");
        String sessionDir = logRoot + "/" + id.replace("->", "-to-");
        text.append("FileLogPath=").append(sessionDir).append('\n')
                // The engine's own narration goes to the log file, not to the
                // console, where it drowns the business flow it is meant to
                // explain. Raise these per session while investigating one.
                .append("ScreenLogShowIncoming=")
                .append(session.flag("logToConsole", false) ? 'Y' : 'N').append('\n')
                .append("ScreenLogShowOutgoing=")
                .append(session.flag("logToConsole", false) ? 'Y' : 'N').append('\n')
                .append("ScreenLogShowEvents=")
                .append(session.flag("logToConsole", false) ? 'Y' : 'N').append('\n');

        // Sequence numbers must outlive the process. Without a file store a
        // restart resumes at 1 and the counterparty rejects or demands a resend
        // of everything it already has.
        if (session.flag("persistent", true)) {
            text.append("FileStorePath=").append(logRoot).append("/store/")
                    .append(id.replace("->", "-to-")).append('\n');
        }

        if (acceptor) {
            text.append("SocketAcceptPort=").append(session.integer("port", 0)).append('\n');
        } else {
            text.append("SocketConnectHost=")
                    .append(session.string("host", "127.0.0.1")).append('\n')
                    .append("SocketConnectPort=").append(session.integer("port", 0)).append('\n');
        }

        text.append("\n[session]\n")
                .append("BeginString=").append(session.require("version")).append('\n')
                .append("SenderCompID=").append(id, 0, arrow).append('\n')
                .append("TargetCompID=").append(id.substring(arrow + 2)).append('\n');
        return text.toString();
    }
}
