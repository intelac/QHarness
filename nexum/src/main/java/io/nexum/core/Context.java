package io.nexum.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The container every plugin talks to. Two jobs: hold services under stable
 * names, and route events between plugins that know nothing about each other.
 *
 * <p>A plugin receives a <em>scoped</em> view of this container. Everything it
 * registers through that view is tracked, so unloading the plugin removes its
 * services and listeners and leaves nothing behind.
 *
 * <p>Thread safety: registration is expected during startup and hot-reload;
 * dispatch is expected on transport threads. Services and listener lists are
 * concurrent structures, so dispatch never blocks on registration.
 */
public class Context {

    private final Map<String, Object> services = new ConcurrentHashMap<>();
    private final Map<String, List<Registration<?>>> listeners = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /** One listener plus the ordering key that keeps dispatch deterministic. */
    private record Registration<T>(Object listener, long order, String owner) {}

    // ------------------------------------------------------------------
    // Services
    // ------------------------------------------------------------------

    /**
     * Publish a service under a name. Plugins depending on it declare that name
     * in {@link Plugin#inject()} and fetch it with {@link #get}.
     *
     * @throws IllegalStateException if the name is already taken — two providers
     *     for one name is a configuration error, not something to resolve silently
     */
    public Disposable register(String name, Object service) {
        Object previous = services.putIfAbsent(name, service);
        if (previous != null) {
            throw new IllegalStateException(
                    "service \"" + name + "\" is already registered by another plugin; "
                            + "disable one of them in configuration");
        }
        return () -> services.remove(name, service);
    }

    /** Look up a service by name. */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        Object service = services.get(name);
        if (service == null) {
            throw new IllegalStateException(
                    "service \"" + name + "\" is not available; declare it in inject()");
        }
        return (T) service;
    }

    /** Look up a service that may legitimately be absent. */
    @SuppressWarnings("unchecked")
    public <T> java.util.Optional<T> find(String name) {
        return java.util.Optional.ofNullable((T) services.get(name));
    }

    public boolean has(String name) {
        return services.containsKey(name);
    }

    // ------------------------------------------------------------------
    // Effects
    // ------------------------------------------------------------------

    /**
     * Install a side effect that knows how to undo itself.
     *
     * <p>Use this for anything that touches state outside the plugin — opening a
     * connection, adding entries to a shared dictionary, starting a thread. Put
     * work whose teardown order matters inside a single effect; across effects
     * only reverse order is guaranteed.
     */
    public Disposable effect(Supplier<Disposable> setup) {
        return setup.get();
    }

    // ------------------------------------------------------------------
    // Event registration
    // ------------------------------------------------------------------

    /** Observe an {@code emit} or {@code parallel} event. */
    public <T> Disposable onEvent(String event, Events.Observer<T> observer) {
        return add(event, observer);
    }

    /**
     * Observe a declared event.
     *
     * <p>The key carries the payload type, so a listener written against the
     * wrong shape fails to compile rather than throwing inside a dispatch loop
     * that swallows exceptions.
     */
    public <T> Disposable on(EventKey<T> event, Events.Observer<T> observer) {
        return add(event.name(), observer);
    }

    /** Answer a {@code serial} event. Return null to abstain. */
    public <T, R> Disposable onQuery(String event, Events.Responder<T, R> responder) {
        return add(event, responder);
    }

    /**
     * Add a gate to the global chain of a {@code waterfall} event. Gates run in
     * registration order, each wrapping the ones after it.
     */
    public <T> Disposable onGate(String event, Events.Gate<T> gate) {
        return onGate(event, Scope.global(), gate);
    }

    /**
     * Add a gate to one scope's chain.
     *
     * <p>A gate mounted on {@code Scope.session("BROKER_A")} runs only for
     * traffic on that session; one mounted on {@code Scope.destination("LSE")}
     * only for orders routed there. This is how two counterparties get entirely
     * different behaviour without either being aware of the other.
     */
    public <T> Disposable onGate(String event, Scope scope, Events.Gate<T> gate) {
        return add(event + "@" + scope.key(), gate);
    }

    private Disposable add(String event, Object listener) {
        Registration<?> registration =
                new Registration<>(listener, sequence.getAndIncrement(), currentOwner());
        listeners.computeIfAbsent(event, key -> new CopyOnWriteArrayList<>()).add(registration);
        return () -> {
            List<Registration<?>> chain = listeners.get(event);
            if (chain != null) {
                chain.remove(registration);
            }
        };
    }

    /** Overridden by the scoped view so registrations can name their plugin. */
    protected String currentOwner() {
        return "root";
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    /**
     * Notify observers and move on. Never blocks the caller on listener work, so
     * a slow logger cannot stall a transport thread. A listener that throws is
     * isolated: the failure is reported and the remaining observers still run.
     */
    /** Publish on a declared event. */
    public <T> void emit(EventKey<T> event, T payload) {
        emit(event.name(), payload);
    }

    public <T> void emit(String event, T payload) {
        for (Events.Observer<T> observer : this.<Events.Observer<T>>chain(event)) {
            try {
                observer.observe(payload);
            } catch (RuntimeException failure) {
                reportListenerFailure(event, failure);
            }
        }
    }

    /**
     * Ask each listener in turn; the first non-null answer wins and the rest are
     * not consulted.
     */
    public <T, R> R query(String event, T payload) {
        for (Events.Responder<T, R> responder : this.<Events.Responder<T, R>>chain(event)) {
            R answer = responder.respond(payload);
            if (answer != null) {
                return answer;
            }
        }
        return null;
    }

    /**
     * Run a value through a chain of gates, innermost last.
     *
     * <p>This is the mode that carries validation, enrichment and rejection. The
     * chain is built back-to-front so the first-registered gate wraps everything
     * after it and sees both the inbound value and the final result.
     *
     * @param terminal what the innermost step produces when every gate delegates
     */
    public <T> T waterfall(String event, T value, Events.Next<T> terminal) {
        return waterfall(event, Scope.global(), value, terminal);
    }

    /**
     * Run a value through one scope's chain, with the global chain wrapped
     * around it.
     *
     * <p>Global gates run outermost, so an audit or kill-switch gate sees every
     * message at every layer and observes the scoped result on the way back out.
     */
    public <T> T waterfall(String event, Scope scope, T value, Events.Next<T> terminal) {
        Events.Next<T> next = build(event + "@" + scope.key(), terminal);
        if (scope.layer() != Scope.Layer.GLOBAL) {
            next = build(event + "@" + Scope.Layer.GLOBAL.name(), next);
        }
        return next.apply(value);
    }

    /** Fold one chain's gates around a terminal, innermost last. */
    private <T> Events.Next<T> build(String key, Events.Next<T> terminal) {
        List<Events.Gate<T>> gates = chain(key);
        Events.Next<T> next = terminal;
        for (int i = gates.size() - 1; i >= 0; i--) {
            Events.Gate<T> gate = gates.get(i);
            Events.Next<T> downstream = next;
            next = input -> gate.pass(input, downstream);
        }
        return next;
    }

    @SuppressWarnings("unchecked")
    private <L> List<L> chain(String event) {
        List<Registration<?>> registrations = listeners.get(event);
        if (registrations == null || registrations.isEmpty()) {
            return List.of();
        }
        List<Registration<?>> ordered = new ArrayList<>(registrations);
        ordered.sort(Comparator.comparingLong(Registration::order));
        List<L> result = new ArrayList<>(ordered.size());
        for (Registration<?> registration : ordered) {
            result.add((L) registration.listener());
        }
        return result;
    }

    /**
     * Where an observer's failure goes. Overridden once a logging plugin is
     * loaded; until then failures surface on stderr rather than disappearing.
     */
    protected void reportListenerFailure(String event, RuntimeException failure) {
        System.err.println("listener failed on event \"" + event + "\": " + failure);
        failure.printStackTrace(System.err);
    }
}
