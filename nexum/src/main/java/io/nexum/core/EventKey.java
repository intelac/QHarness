package io.nexum.core;

/**
 * An event's name and the shape of what travels on it, in one value.
 *
 * <p>Event names were bare strings, and a publisher and a subscriber agreeing on
 * one was a matter of the two spellings matching. They stopped matching once:
 * monitoring subscribed to an event nothing emitted and the venue identifier
 * stayed null for as long as nobody looked. Renaming an event was a grep.
 *
 * <p>Carrying the payload type turns the other half of the contract into
 * something the compiler checks too. A subscriber written against the wrong type
 * no longer compiles, where before it threw a {@code ClassCastException} inside
 * a dispatch loop that swallows exceptions.
 *
 * @param <T> what is published on this event
 */
public record EventKey<T>(String name, Class<T> payload, Mode mode) {

    /**
     * How an event is dispatched. Part of its public contract: a listener
     * registered the wrong way is silently never called.
     */
    public enum Mode {
        /** Fire and forget. Logging, metrics, anything that must not block. */
        EMIT,
        /** Await every listener. Fan-out side effects that must complete. */
        PARALLEL,
        /** Ask each in turn; the first answer wins. */
        SERIAL,
        /** Gates that wrap one another and may transform or refuse. */
        WATERFALL
    }

    public static <T> EventKey<T> emit(String name, Class<T> payload) {
        return new EventKey<>(name, payload, Mode.EMIT);
    }

    public static <T> EventKey<T> waterfall(String name, Class<T> payload) {
        return new EventKey<>(name, payload, Mode.WATERFALL);
    }

    public static <T> EventKey<T> serial(String name, Class<T> payload) {
        return new EventKey<>(name, payload, Mode.SERIAL);
    }

    @Override
    public String toString() {
        return name;
    }
}
