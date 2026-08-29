package io.nexum.probe;

import io.nexum.ai.AiTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The endpoints stood up against whatever is being tested.
 *
 * <p>A router accepts orders from clients and sends them on to a market, so
 * testing one means holding both ends of it at once: a client side that dials
 * in as a client would, a market side that listens for the system to dial out.
 * Those two are what most scenarios need, and {@link #CLIENT} and
 * {@link #MARKET} name them.
 *
 * <p>They are not the limit, though, and being the limit was a real one. A
 * router that serves several clients over one session decides between them by
 * what their orders carry, and a harness that can only be one client at a time
 * cannot test that it decides correctly. So endpoints are named, and a name is
 * whatever the scenario calls it — {@code client}, {@code desk-a}, {@code
 * venue-xlon}. Two of them pointed at each other check a configuration before
 * any system under test is involved.
 *
 * <p>Each endpoint is a separate connection to a separate port, sharing nothing
 * but this owner. Starting one over a name already in use is refused rather
 * than silently taking the running connection down: a scenario that meant to
 * add a second client and instead replaced the first would go on reading
 * traffic from an endpoint that no longer exists.
 */
public final class HarnessRig {

    /** The side that sends requests into the system under test. */
    public static final String CLIENT = "client";

    /** The side that answers as a market. */
    public static final String MARKET = "market";

    private final Map<String, CounterpartyHarness> endpoints = new ConcurrentHashMap<>();
    private final HarnessMessages messages = new HarnessMessages();

    /**
     * Bring an endpoint up under a name.
     *
     * @param name what this endpoint is called; {@link #CLIENT} and
     *     {@link #MARKET} are the usual two, and any other name is equally
     *     valid
     * @param dials whether this endpoint dials out (a client) or waits to be
     *     dialled (a market)
     * @throws IllegalStateException when the name is already running, so a
     *     scenario adding an endpoint cannot silently replace one
     */
    public void connect(String name, boolean dials, String host, int port,
                        String senderCompId, String targetCompId) throws Exception {

        if (endpoints.containsKey(name)) {
            throw new IllegalStateException(
                    "\"" + name + "\" is already connected; disconnect it first,"
                            + " or bring the new one up under another name");
        }
        CounterpartyHarness endpoint = new CounterpartyHarness(
                name, dials ? CounterpartyHarness.Role.INITIATOR
                            : CounterpartyHarness.Role.ACCEPTOR);
        endpoint.start(host, port, senderCompId, targetCompId);
        endpoints.put(name, endpoint);
    }

    /**
     * Bring up one of the two usual sides, taking its role from its name.
     *
     * <p>The roles follow from the system under test's own shape: it listens
     * for clients, so a client dials in; it dials out to a market, so a market
     * listens.
     */
    public void connect(String side, String host, int port,
                        String senderCompId, String targetCompId) throws Exception {
        connect(side, dialsFor(side), host, port, senderCompId, targetCompId);
    }

    /** Take an endpoint down; doing so twice is not an error. */
    public void disconnect(String name) {
        CounterpartyHarness existing = endpoints.remove(name);
        if (existing != null) {
            existing.stop();
        }
    }

    /** Take everything down. */
    public void stopAll() {
        for (String name : Map.copyOf(endpoints).keySet()) {
            disconnect(name);
        }
    }

    /** One endpoint, or null when it has not been started. */
    public CounterpartyHarness side(String name) {
        return endpoints.get(name);
    }

    /** Every endpoint currently up. */
    public List<String> names() {
        return new ArrayList<>(endpoints.keySet());
    }

    /** Run something on the client side, reporting plainly when it is not usable. */
    public AiTool.Result onClient(Function<CounterpartyHarness, AiTool.Result> action) {
        return on(CLIENT, action);
    }

    /** Run something on the market side, reporting plainly when it is not usable. */
    public AiTool.Result onMarket(Function<CounterpartyHarness, AiTool.Result> action) {
        return on(MARKET, action);
    }

    /** The message builders every endpoint sends through. */
    public HarnessMessages messages() {
        return messages;
    }

    /** Forget the traffic on every endpoint. */
    public void clearTraffic() {
        endpoints.values().forEach(CounterpartyHarness::clearTraffic);
    }

    // ------------------------------------------------------------------

    /**
     * Run something on one endpoint, saying plainly when it cannot be run.
     *
     * <p>Naming the endpoints that do exist matters more once they can be
     * called anything: "no endpoint called desk-b" leaves a caller guessing
     * whether it was never started or is spelled differently.
     */
    public AiTool.Result on(String name, Function<CounterpartyHarness, AiTool.Result> action) {
        CounterpartyHarness endpoint = endpoints.get(name);
        if (endpoint == null) {
            List<String> running = names();
            // Name the one that was asked for either way. "Nothing is
            // connected" alone leaves a caller who asked for desk-b unsure
            // whether it named the endpoint wrongly or never started it.
            return AiTool.Result.failed(running.isEmpty()
                    ? name + " is not started; call harness_connect first"
                    : name + " is not started; connected: "
                            + String.join(", ", running));
        }
        if (!endpoint.isLoggedOn()) {
            return AiTool.Result.failed("the " + name + " endpoint is not logged on yet; "
                    + "check harness_status, and that the system under test is running");
        }
        try {
            return action.apply(endpoint);
        } catch (IllegalArgumentException badArgument) {
            return AiTool.Result.failed(badArgument.getMessage());
        } catch (IllegalStateException unusable) {
            return AiTool.Result.failed(unusable.getMessage());
        }
    }

    /**
     * Which way one of the two usual sides connects.
     *
     * <p>Only the two named sides have an answer that follows from anything. A
     * name the scenario invented has to say for itself, because nothing about
     * the word "desk-a" implies which end of a connection it holds.
     */
    private static boolean dialsFor(String side) {
        return switch (side) {
            case CLIENT -> true;
            case MARKET -> false;
            default -> throw new IllegalArgumentException(
                    "\"" + side + "\" is not one of the two usual sides, so it does not"
                            + " imply a role; say whether it dials out");
        };
    }
}
