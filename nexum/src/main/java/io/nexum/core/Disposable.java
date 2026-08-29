package io.nexum.core;

/**
 * The undo half of a registration.
 *
 * <p>Every side effect a plugin installs hands back one of these. The container
 * runs them in reverse order when the plugin unloads, which is what lets a
 * plugin be pulled out cleanly at runtime.
 */
@FunctionalInterface
public interface Disposable {

    void dispose();

    Disposable NOOP = () -> {};

    /** Combine several disposables into one, disposed in reverse order. */
    static Disposable of(Disposable... parts) {
        return () -> {
            for (int i = parts.length - 1; i >= 0; i--) {
                parts[i].dispose();
            }
        };
    }
}
