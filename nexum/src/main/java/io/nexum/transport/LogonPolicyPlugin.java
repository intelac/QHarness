package io.nexum.transport;

import io.nexum.core.Context;
import io.nexum.core.Plugin;

import java.util.List;

/**
 * Publishes the {@link LogonPolicy} the transport consults.
 *
 * <p>A plugin so a deployment with a scheme of its own can register a different
 * one under the same name and this never runs.
 */
public final class LogonPolicyPlugin implements Plugin {

    private final LogonPolicy policy;

    public LogonPolicyPlugin(LogonPolicy policy) {
        this.policy = policy;
    }

    /**
     * The policy a configuration describes.
     *
     * <p>Absent both a password and an address list this returns
     * {@link LogonPolicy#open()} — which is right for an acceptor on a private
     * network, and dangerous on a public one. The caller is expected to have
     * decided which it has.
     */
    public static LogonPolicy from(String password, List<String> allowedAddresses) {
        return password == null && allowedAddresses.isEmpty()
                ? LogonPolicy.open()
                : LogonPolicy.require(password, allowedAddresses);
    }

    @Override
    public String name() {
        return "logon-policy";
    }

    @Override
    public List<String> provides() {
        return List.of("logon-policy");
    }

    @Override
    public void apply(Context ctx) {
        ctx.register("logon-policy", policy);
    }
}
