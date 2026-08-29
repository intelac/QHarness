package io.nexum.message;

import io.nexum.core.Context;
import io.nexum.core.Plugin;

import java.util.List;

/**
 * Publishes the dialect registry as the {@code dialects} service.
 *
 * <p>Sessions are declared here with their FIX version and nothing more.
 * Deviations arrive as separate {@link SessionDialectPlugin} instances, so the
 * common case — a counterparty that follows the standard — needs no dialect
 * configuration at all.
 */
public final class DialectPlugin implements Plugin {

    private final List<SessionDeclaration> sessions;

    public record SessionDeclaration(String sessionId, FixVersion version) {}

    public DialectPlugin(List<SessionDeclaration> sessions) {
        this.sessions = List.copyOf(sessions);
    }

    @Override
    public String name() {
        return "dialects";
    }

    @Override
    public List<String> provides() {
        return List.of("dialects");
    }

    @Override
    public void apply(Context ctx) {
        DialectRegistry registry = new DialectRegistry();
        ctx.register("dialects", registry);
        for (SessionDeclaration declaration : sessions) {
            ctx.effect(() -> registry.declareSession(
                    declaration.sessionId(), declaration.version()));
        }
    }
}
