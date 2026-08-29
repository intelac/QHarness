package io.nexum.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Starts plugins in an order derived from what they declare they need, and
 * unloads them cleanly.
 *
 * <p>There is no startup sequence written anywhere. A plugin states the service
 * names it depends on; the loader turns those declarations into a topological
 * order. Adding a plugin never means editing a list of steps.
 *
 * <p>Unloading reverses every registration a plugin made, in reverse order, so a
 * plugin can be swapped at runtime without leaving stale services or listeners
 * behind.
 */
public final class PluginLoader {

    private final Context root;
    private final Map<String, Loaded> loaded = new LinkedHashMap<>();

    private record Loaded(Plugin plugin, List<Disposable> undo) {}

    public PluginLoader(Context root) {
        this.root = root;
    }

    /**
     * Start the given plugins. Order among the arguments does not matter — the
     * loader derives the real order from {@link Plugin#inject()}.
     *
     * @throws IllegalStateException on a dependency cycle or a dependency that
     *     nothing in this set provides
     */
    public void load(List<Plugin> plugins) {
        for (Plugin plugin : order(plugins)) {
            start(plugin);
        }
    }

    private void start(Plugin plugin) {
        if (loaded.containsKey(plugin.name())) {
            throw new IllegalStateException("plugin \"" + plugin.name() + "\" is already loaded");
        }
        List<Disposable> undo = new ArrayList<>();
        Scoped scope = new Scoped(root, plugin.name(), undo);
        try {
            plugin.apply(scope);
        } catch (RuntimeException failure) {
            // A plugin that fails halfway has already registered part of itself;
            // roll that back so a failed start cannot leave the container dirty.
            dispose(undo);
            throw new IllegalStateException(
                    "plugin \"" + plugin.name() + "\" failed to start", failure);
        }
        loaded.put(plugin.name(), new Loaded(plugin, undo));
    }

    /**
     * Stop one plugin and undo everything it registered.
     *
     * <p>Nothing here checks whether other plugins are still using the services
     * this one provided — the caller decides that. Unloading a dependency out
     * from under a live consumer surfaces as a missing-service error on its next
     * lookup.
     */
    public void unload(String name) {
        Loaded entry = loaded.remove(name);
        if (entry == null) {
            return;
        }
        dispose(entry.undo());
    }

    /** Stop everything, most recently started first. */
    public void unloadAll() {
        List<String> names = new ArrayList<>(loaded.keySet());
        for (int i = names.size() - 1; i >= 0; i--) {
            unload(names.get(i));
        }
    }

    public Set<String> loadedNames() {
        return new LinkedHashSet<>(loaded.keySet());
    }

    private static void dispose(List<Disposable> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) {
            try {
                undo.get(i).dispose();
            } catch (RuntimeException failure) {
                // One bad teardown must not strand the rest of the registrations.
                System.err.println("teardown failed: " + failure);
            }
        }
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    /**
     * Depth-first topological sort over service dependencies.
     *
     * <p>A dependency already present in the container counts as satisfied, which
     * lets a set of plugins be loaded in stages.
     */
    private List<Plugin> order(List<Plugin> plugins) {
        // Indexed by service, not by plugin name: a plugin's identity and the
        // services it publishes are different things, and dependencies are
        // expressed in terms of services.
        Map<String, Plugin> byService = new LinkedHashMap<>();
        for (Plugin plugin : plugins) {
            for (String service : plugin.provides()) {
                Plugin previous = byService.putIfAbsent(service, plugin);
                if (previous != null && previous != plugin) {
                    throw new IllegalStateException(
                            "service \"" + service + "\" is provided by both \""
                                    + previous.name() + "\" and \"" + plugin.name()
                                    + "\"; disable one in configuration");
                }
            }
        }

        List<Plugin> ordered = new ArrayList<>(plugins.size());
        Set<String> done = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();

        for (Plugin plugin : plugins) {
            visit(plugin, byService, ordered, done, visiting, path);
        }
        return ordered;
    }

    private void visit(
            Plugin plugin,
            Map<String, Plugin> byService,
            List<Plugin> ordered,
            Set<String> done,
            Set<String> visiting,
            Deque<String> path) {

        String name = plugin.name();
        if (done.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            path.push(name);
            throw new IllegalStateException(
                    "dependency cycle among plugins: " + String.join(" -> ", path));
        }
        path.push(name);

        for (String required : plugin.inject()) {
            if (root.has(required) || done.contains(required)) {
                continue;
            }
            Plugin provider = byService.get(required);
            if (provider == null) {
                throw new IllegalStateException(
                        "plugin \"" + name + "\" needs service \"" + required
                                + "\", which no loaded plugin provides");
            }
            visit(provider, byService, ordered, done, visiting, path);
        }

        path.pop();
        visiting.remove(name);
        done.add(name);
        ordered.add(plugin);
    }

    // ------------------------------------------------------------------
    // Per-plugin view
    // ------------------------------------------------------------------

    /**
     * The container as one plugin sees it. Delegates to the root for lookup and
     * dispatch, but records every registration so the loader can reverse it.
     */
    private static final class Scoped extends Context {

        private final Context delegate;
        private final String owner;
        private final List<Disposable> undo;

        Scoped(Context delegate, String owner, List<Disposable> undo) {
            this.delegate = delegate;
            this.owner = owner;
            this.undo = undo;
        }

        private Disposable track(Disposable disposable) {
            undo.add(disposable);
            return disposable;
        }

        @Override
        protected String currentOwner() {
            return owner;
        }

        @Override
        public Disposable register(String name, Object service) {
            return track(delegate.register(name, service));
        }

        @Override
        public <T> T get(String name) {
            return delegate.get(name);
        }

        @Override
        public <T> java.util.Optional<T> find(String name) {
            return delegate.find(name);
        }

        @Override
        public boolean has(String name) {
            return delegate.has(name);
        }

        @Override
        public Disposable effect(Supplier<Disposable> setup) {
            return track(setup.get());
        }

        @Override
        public <T> Disposable onEvent(String event, Events.Observer<T> observer) {
            return track(delegate.onEvent(event, observer));
        }

        @Override
        public <T> Disposable on(EventKey<T> event, Events.Observer<T> observer) {
            return track(delegate.on(event, observer));
        }

        @Override
        public <T, R> Disposable onQuery(String event, Events.Responder<T, R> responder) {
            return track(delegate.onQuery(event, responder));
        }

        @Override
        public <T> Disposable onGate(String event, Events.Gate<T> gate) {
            return track(delegate.onGate(event, gate));
        }

        @Override
        public <T> Disposable onGate(String event, Scope scope, Events.Gate<T> gate) {
            return track(delegate.onGate(event, scope, gate));
        }

        @Override
        public <T> void emit(String event, T payload) {
            delegate.emit(event, payload);
        }

        @Override
        public <T> void emit(EventKey<T> event, T payload) {
            delegate.emit(event, payload);
        }

        @Override
        public <T, R> R query(String event, T payload) {
            return delegate.query(event, payload);
        }

        @Override
        public <T> T waterfall(String event, T value, Events.Next<T> terminal) {
            return delegate.waterfall(event, value, terminal);
        }

        @Override
        public <T> T waterfall(String event, Scope scope, T value, Events.Next<T> terminal) {
            return delegate.waterfall(event, scope, value, terminal);
        }
    }
}
