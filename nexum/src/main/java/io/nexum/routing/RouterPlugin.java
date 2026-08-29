package io.nexum.routing;

import io.nexum.config.Config;
import io.nexum.config.FingerprintParser;
import io.nexum.core.Context;
import io.nexum.core.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes routing rules read from configuration as the {@code router} service.
 *
 * <p>Rules keep their configured order, since first match wins. A rule that
 * matches nothing is a configuration mistake worth catching at startup, so the
 * plugin refuses to mount an empty rule set rather than silently routing
 * nothing.
 */
public final class RouterPlugin implements Plugin {

    private final Config config;

    public RouterPlugin(Config config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "router";
    }

    @Override
    public void apply(Context ctx) {
        List<Router.Rule> clientRules = new ArrayList<>();
        for (Config client : config.sections("clients")) {
            clientRules.add(new Router.Rule(
                    client.require("id"), FingerprintParser.parse(client, "fingerprint")));
        }

        List<Router.Rule> destinationRules = new ArrayList<>();
        for (Config route : config.sections("routes")) {
            destinationRules.add(new Router.Rule(
                    route.require("destination"), FingerprintParser.parse(route, "fingerprint")));
        }

        if (clientRules.isEmpty()) {
            throw new IllegalStateException("no clients configured; nothing can be routed");
        }
        if (destinationRules.isEmpty()) {
            throw new IllegalStateException("no routes configured; nothing can be sent");
        }

        ctx.register("router", new Router(clientRules, destinationRules));
    }
}
