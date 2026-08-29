package io.nexum.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tools an agent can reach, and the gate in front of the ones that act.
 *
 * <p>The gate is deterministic code, outside anything a model influences.
 * Published benchmarks put refusal rates for prompt-injected tool calls in the
 * low single digits, so a model instructed to be careful is not a control; a
 * check it cannot reason its way past is.
 *
 * <p>Read-only tools are always available. Anything that reaches a counterparty
 * needs an unlock that a person granted, for a stated scope and a stated length
 * of time — a permission that expires on its own rather than one someone has to
 * remember to withdraw.
 */
public final class ToolRegistry {

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    /**
     * The venue a tool reaches when it names none of its own.
     *
     * <p>Needed to decide what to offer: without it a tool bound for the venue
     * has to be judged against "any unlock at all", so permitting a test
     * harness would put the order tools in front of a model that must not have
     * them. Null leaves that older behaviour for a caller that never set it.
     */
    private volatile String venue;
    private final Map<String, Unlock> unlocks = new ConcurrentHashMap<>();
    private final List<CallRecord> audit = new ArrayList<>();

    /**
     * Permission to use acting tools, for a limited scope and time.
     *
     * @param destinations venues this unlock covers; empty means none
     * @param maxCalls how many acting calls it permits in total
     * @param expiresAt when it lapses regardless of use
     */
    public record Unlock(
            String grantedBy,
            List<String> destinations,
            int maxCalls,
            long expiresAt,
            AtomicInteger used) {

        public static Unlock granted(
                String grantedBy, List<String> destinations, int maxCalls, Duration validFor) {
            return new Unlock(
                    grantedBy,
                    List.copyOf(destinations),
                    maxCalls,
                    System.currentTimeMillis() + validFor.toMillis(),
                    new AtomicInteger());
        }

        public boolean covers(String destination, long now) {
            return now < expiresAt
                    && used.get() < maxCalls
                    && destinations.contains(destination);
        }

        public int remaining() {
            return Math.max(0, maxCalls - used.get());
        }

        public long secondsLeft(long now) {
            return Math.max(0, (expiresAt - now) / 1000);
        }
    }

    /** One call, recorded whether it was allowed or refused. */
    public record CallRecord(
            long at,
            String tool,
            AiTool.Effect effect,
            Map<String, Object> arguments,
            boolean allowed,
            String outcome) {}

    // ------------------------------------------------------------------

    /** Name the venue that tools reach by default, so offering can be judged. */
    public void servingVenue(String venue) {
        this.venue = venue;
    }

    public void register(AiTool tool) {
        AiTool previous = tools.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw new IllegalStateException(
                    "tool \"" + tool.name() + "\" is already registered");
        }
    }

    public void unregister(String name) {
        tools.remove(name);
    }

    /**
     * Tools an agent may be told about.
     *
     * <p>Acting tools are withheld entirely while nothing is unlocked, rather
     * than offered and refused. A tool a model cannot see is one it cannot be
     * talked into calling.
     */
    public List<AiTool> visible() {
        long now = System.currentTimeMillis();
        return tools.values().stream()
                .filter(tool -> tool.effect() != AiTool.Effect.SENDS_TO_VENUE
                        || permitted(tool, now))
                .toList();
    }

    /**
     * Whether any unlock covers what this tool reaches.
     *
     * <p>Per destination rather than per unlock: a deployment that permitted a
     * test harness has not permitted trading, and offering the order tools
     * because *some* unlock exists puts them in front of a model that would
     * then be refused — or worse, would not be.
     *
     * <p>A tool naming no destination of its own reaches the deployment's
     * venue, which only the caller knows, so it is offered when any unlock is
     * live and refused at the call if that unlock does not cover the venue.
     */
    private boolean permitted(AiTool tool, long now) {
        String reached = tool.destination() != null ? tool.destination() : venue;
        if (reached == null) {
            // No venue was named, so nothing can be judged against it; fall
            // back to whether anything at all is unlocked.
            return unlocks.values().stream().anyMatch(unlock ->
                    now < unlock.expiresAt() && unlock.used().get() < unlock.maxCalls());
        }
        return unlocks.values().stream().anyMatch(unlock -> unlock.covers(reached, now));
    }

    public Optional<AiTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    // ------------------------------------------------------------------
    // Unlocking
    // ------------------------------------------------------------------

    /** Grant permission to act. Granted by a person, for a scope, with an expiry. */
    public void unlock(String key, Unlock unlock) {
        unlocks.put(key, unlock);
    }

    /** Withdraw one unlock. Anything already in flight is unaffected. */
    public void lock(String key) {
        unlocks.remove(key);
    }

    /** Withdraw everything. The state the system should sit in by default. */
    public void lockAll() {
        unlocks.clear();
    }

    public Map<String, Unlock> activeUnlocks() {
        long now = System.currentTimeMillis();
        Map<String, Unlock> active = new LinkedHashMap<>();
        unlocks.forEach((key, unlock) -> {
            if (now < unlock.expiresAt()) {
                active.put(key, unlock);
            }
        });
        return active;
    }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    /**
     * Call a tool, subject to the gate.
     *
     * @param destination the venue an acting call would reach, or null for reads
     */
    public AiTool.Result call(
            String name, Map<String, Object> arguments, String destination) {

        long now = System.currentTimeMillis();
        AiTool tool = tools.get(name);

        if (tool == null) {
            return record(now, name, null, arguments, false, "no such tool");
        }

        // Arguments are checked before an unlock is spent: a malformed call
        // should not consume a budget a person granted.
        String malformed = checkArguments(tool, arguments);
        if (malformed != null) {
            return record(now, name, tool.effect(), arguments, false, malformed);
        }

        if (tool.effect() == AiTool.Effect.SENDS_TO_VENUE) {
            // A tool that reaches somewhere other than the deployment's venue
            // names it, and is gated on that rather than on the venue it never
            // touches.
            String reached = tool.destination() != null ? tool.destination() : destination;
            if (reached == null) {
                return record(now, name, tool.effect(), arguments, false,
                        "an acting call must name the destination it reaches");
            }
            Optional<Unlock> permitting = unlocks.values().stream()
                    .filter(unlock -> unlock.covers(reached, now))
                    .findFirst();

            if (permitting.isEmpty()) {
                return record(now, name, tool.effect(), arguments, false,
                        "no unlock covers " + reached
                                + "; a person must grant one, with a scope and an expiry");
            }
            permitting.get().used().incrementAndGet();
        }

        try {
            AiTool.Result result = tool.call(arguments);
            record(now, name, tool.effect(), arguments, true,
                    result.ok() ? "ok" : result.content());
            return result;
        } catch (RuntimeException failure) {
            // Recorded as attempted, reported as failed. A model told a call
            // succeeded when it threw will act as though an order went out.
            record(now, name, tool.effect(), arguments, true, "threw: " + failure);
            return AiTool.Result.failed("the tool failed: " + failure);
        }
    }

    private static String checkArguments(AiTool tool, Map<String, Object> arguments) {
        for (Map.Entry<String, AiTool.Parameter> entry : tool.parameters().entrySet()) {
            AiTool.Parameter parameter = entry.getValue();
            Object value = arguments.get(entry.getKey());

            if (parameter.required() && value == null) {
                return "missing required argument \"" + entry.getKey() + "\"";
            }
            if (value != null && !parameter.allowedValues().isEmpty()
                    && !parameter.allowedValues().contains(String.valueOf(value))) {
                return "\"" + entry.getKey() + "\" must be one of "
                        + parameter.allowedValues();
            }
        }
        return null;
    }

    private AiTool.Result record(
            long at,
            String name,
            AiTool.Effect effect,
            Map<String, Object> arguments,
            boolean allowed,
            String outcome) {

        synchronized (audit) {
            audit.add(new CallRecord(at, name, effect, Map.copyOf(arguments), allowed, outcome));
            // Bounded: the audit here is for answering "what did the agent just
            // try"; the durable record is the order journal.
            if (audit.size() > 1000) {
                audit.remove(0);
            }
        }
        return allowed
                ? AiTool.Result.of(outcome)
                : AiTool.Result.failed(outcome);
    }

    /** What the agent has attempted, most recent last. */
    public List<CallRecord> recentCalls(int limit) {
        synchronized (audit) {
            int from = Math.max(0, audit.size() - limit);
            return List.copyOf(audit.subList(from, audit.size()));
        }
    }
}
