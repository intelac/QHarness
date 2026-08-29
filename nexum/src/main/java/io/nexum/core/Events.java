package io.nexum.core;

/**
 * The four dispatch modes. A given event name uses exactly one of them for its
 * whole lifetime — the mode is part of the event's public contract, and mixing
 * modes on one name is a bug the container will not catch for you.
 *
 * <table>
 *   <caption>Choosing a mode</caption>
 *   <tr><th>Mode</th><th>Blocks?</th><th>Order</th><th>Returns?</th><th>Use for</th></tr>
 *   <tr><td>emit</td><td>no</td><td>registration</td><td>no</td><td>logging, metrics</td></tr>
 *   <tr><td>parallel</td><td>yes</td><td>concurrent</td><td>no</td><td>fan-out side effects</td></tr>
 *   <tr><td>serial</td><td>yes</td><td>registration</td><td>yes</td><td>poll each listener for an answer</td></tr>
 *   <tr><td>waterfall</td><td>yes</td><td><b>nested</b></td><td>yes</td><td><b>validate / enrich / reject</b></td></tr>
 * </table>
 */
public final class Events {

    private Events() {}

    /** Fire-and-forget observer. */
    @FunctionalInterface
    public interface Observer<T> {
        void observe(T payload);
    }

    /** Answers a question; the first non-null answer wins and stops the chain. */
    @FunctionalInterface
    public interface Responder<T, R> {
        R respond(T payload);
    }

    /**
     * A gate in a waterfall chain — the mode that matters most here.
     *
     * <p>Each listener wraps the ones registered after it. Calling
     * {@code next.apply(value)} delegates downstream and hands you back what the
     * rest of the chain produced, so you may transform on the way in, on the way
     * out, or both. Returning without calling {@code next} short-circuits: no
     * listener after this one runs.
     *
     * <p><b>The rule:</b> only a listener with decision authority may
     * short-circuit. A validator may reject without delegating. A logger, a
     * metric, an annotator — anything that merely observes — <b>must</b> call
     * {@code next}. A listener that swallows the chain by accident produces a
     * message that silently vanishes, which is among the hardest faults to trace.
     */
    @FunctionalInterface
    public interface Gate<T> {
        T pass(T value, Next<T> next);
    }

    /** The rest of a waterfall chain, as seen from inside one gate. */
    @FunctionalInterface
    public interface Next<T> {
        T apply(T value);
    }
}
