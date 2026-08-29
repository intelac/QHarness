package io.nexum.message;

import io.nexum.core.Disposable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which dialect applies to which session or destination.
 *
 * <p>A session declares a {@link FixVersion} and is served that version's
 * baseline. A dialect plugin overrides that entry to add the counterparty's
 * deviations; removing the plugin restores the baseline, so a session is never
 * left without a usable dialect.
 *
 * <p>Clients share their session's dialect. A client is a logical partition of a
 * socket, not a separate wire format.
 */
public final class DialectRegistry {

    private final Map<String, FixVersion> sessionVersions = new ConcurrentHashMap<>();
    private final Map<String, Dialect> sessionDialects = new ConcurrentHashMap<>();
    private final Map<String, Dialect> destinationDialects = new ConcurrentHashMap<>();
    private final Map<String, FixCodec> codecs = new ConcurrentHashMap<>();

    /**
     * Declare a session's FIX version. Enough on its own — the session gets the
     * standard templates for that version until a dialect plugin says otherwise.
     */
    public Disposable declareSession(String sessionId, FixVersion version) {
        sessionVersions.put(sessionId, version);
        codecs.remove(key("SESSION", sessionId));
        return () -> {
            sessionVersions.remove(sessionId);
            codecs.remove(key("SESSION", sessionId));
        };
    }

    /**
     * Override a session's dialect with one carrying its deviations. Reversible:
     * unloading the plugin drops back to the declared version's baseline.
     */
    public Disposable overrideSession(String sessionId, Dialect dialect) {
        Dialect previous = sessionDialects.put(sessionId, dialect);
        codecs.remove(key("SESSION", sessionId));
        return () -> {
            if (previous == null) {
                sessionDialects.remove(sessionId, dialect);
            } else {
                sessionDialects.put(sessionId, previous);
            }
            codecs.remove(key("SESSION", sessionId));
        };
    }

    public Disposable overrideDestination(String destinationId, Dialect dialect) {
        Dialect previous = destinationDialects.put(destinationId, dialect);
        codecs.remove(key("DEST", destinationId));
        return () -> {
            if (previous == null) {
                destinationDialects.remove(destinationId, dialect);
            } else {
                destinationDialects.put(destinationId, previous);
            }
            codecs.remove(key("DEST", destinationId));
        };
    }

    /** Dialect in force for a session: its override, else its version baseline. */
    public Dialect forSession(String sessionId) {
        Dialect override = sessionDialects.get(sessionId);
        if (override != null) {
            return override;
        }
        FixVersion version = sessionVersions.get(sessionId);
        if (version == null) {
            throw new IllegalStateException(
                    "session \"" + sessionId + "\" has no FIX version declared");
        }
        return StandardDialects.of(version);
    }

    public Dialect forDestination(String destinationId) {
        Dialect override = destinationDialects.get(destinationId);
        if (override != null) {
            return override;
        }
        throw new IllegalStateException(
                "destination \"" + destinationId + "\" has no dialect configured");
    }

    /** Codec for a session, cached until its dialect changes. */
    public FixCodec codecForSession(String sessionId) {
        return codecs.computeIfAbsent(
                key("SESSION", sessionId), ignored -> new FixCodec(forSession(sessionId)));
    }

    public FixCodec codecForDestination(String destinationId) {
        return codecs.computeIfAbsent(
                key("DEST", destinationId), ignored -> new FixCodec(forDestination(destinationId)));
    }

    /** What is configured where, for a startup dump. */
    public Map<String, String> describe() {
        Map<String, String> lines = new LinkedHashMap<>();
        sessionVersions.forEach((sessionId, version) -> {
            Dialect override = sessionDialects.get(sessionId);
            lines.put(
                    "session:" + sessionId,
                    override == null
                            ? version.beginString() + " (standard)"
                            : version.beginString() + " + " + override.name());
        });
        destinationDialects.forEach(
                (destinationId, dialect) -> lines.put("dest:" + destinationId, dialect.name()));
        return lines;
    }

    private static String key(String kind, String id) {
        return kind + ":" + id;
    }
}
