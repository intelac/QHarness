package io.nexum.core;

import java.util.List;

/**
 * A unit of functionality. Everything in the system is a plugin — transports,
 * validators, enrichers, the order engine, monitoring, alerting.
 *
 * <p>A plugin never imports another plugin. It declares the service names it
 * needs via {@link #inject()} and looks them up on the {@link Context} by name.
 * That indirection is what makes any part of the system replaceable from
 * configuration alone.
 *
 * <p>The loader resolves a start order from the dependency declarations, so no
 * startup sequence is written by hand anywhere.
 */
public interface Plugin {

    /** Unique id of this plugin. Used in config, logs, and load-order errors. */
    String name();

    /**
     * Service names that must be registered before this plugin starts.
     * The loader topologically sorts on these.
     */
    default List<String> inject() {
        return List.of();
    }

    /**
     * Service names this plugin registers.
     *
     * <p>Declared separately from {@link #name()} because the two answer
     * different questions: the name identifies the plugin in configuration and
     * logs, while these are what other plugins depend on. A cache plugin may be
     * called {@code order-cache} and provide {@code orders}; the loader needs
     * both to resolve an order.
     *
     * <p>Defaults to the plugin's own name, which covers the common case where
     * they coincide.
     */
    default List<String> provides() {
        return List.of(name());
    }

    /**
     * Install this plugin's contributions: services, listeners, effects.
     *
     * <p>Every registration made here must be reversible — register through
     * {@link Context#effect} or the {@code on(...)} helpers so that unloading
     * this plugin leaves no residue behind. Hot-reload depends on it.
     */
    void apply(Context ctx);
}
