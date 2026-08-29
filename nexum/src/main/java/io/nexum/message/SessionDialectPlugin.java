package io.nexum.message;

import io.nexum.core.Context;
import io.nexum.core.Plugin;

import java.util.List;
import java.util.function.Function;

/**
 * One counterparty's deviations from the standard, mounted on its session.
 *
 * <p>Load one of these only for a session that actually deviates. The overlay is
 * built from whatever dialect is currently in force, so it states the
 * difference rather than a whole dictionary — and unloading it drops the session
 * back to its declared version's baseline rather than leaving it undefined.
 *
 * <pre>{@code
 * new SessionDialectPlugin("BROKER_A", base ->
 *     DialectOverlay.on(base, "BROKER_A quirks")
 *         .replaceGroup("D", GroupTemplate.of(453, 448, 448, 452, 20001))
 *         .removeGroup("8", 382)
 *         .build());
 * }</pre>
 */
public final class SessionDialectPlugin implements Plugin {

    private final String sessionId;
    private final Function<Dialect, Dialect> overlay;

    public SessionDialectPlugin(String sessionId, Function<Dialect, Dialect> overlay) {
        this.sessionId = sessionId;
        this.overlay = overlay;
    }

    @Override
    public String name() {
        return "dialect-" + sessionId;
    }

    @Override
    public List<String> inject() {
        return List.of("dialects");
    }

    @Override
    public void apply(Context ctx) {
        DialectRegistry registry = ctx.get("dialects");
        ctx.effect(() -> registry.overrideSession(
                sessionId, overlay.apply(registry.forSession(sessionId))));
    }
}
