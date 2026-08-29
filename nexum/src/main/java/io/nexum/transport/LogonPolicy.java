package io.nexum.transport;

import java.util.List;
import java.util.Optional;

/**
 * Who is allowed to log on.
 *
 * <p>A FIX acceptor on a public address will be found and connected to. CompIDs
 * are not secrets — they are in the counterparty's own onboarding document — so
 * an acceptor that checks nothing accepts orders from anyone who reads one.
 *
 * <p>This is a seam rather than a fixed check because the answer is per
 * counterparty: some send a password in 554, some are identified by address
 * alone, some require both, and some sit behind a VPN where neither is wanted.
 * A deployment states which, and a plugin can replace this wholesale for a
 * counterparty whose scheme is its own.
 */
public interface LogonPolicy {

    /**
     * Decide whether a logon is allowed.
     *
     * @param sessionId the session the logon arrived for
     * @param password Password(554), or null when the message carries none
     * @param remoteAddress where the connection came from, or null when the
     *     engine does not report it
     * @return empty when the logon is allowed; the reason to refuse otherwise.
     *     The reason reaches this system's own log, never the wire — telling a
     *     caller which of its guesses was wrong is how it learns to guess.
     */
    Optional<String> refuse(String sessionId, String password, String remoteAddress);

    /** Accepts every logon. What an acceptor behind a private network wants. */
    static LogonPolicy open() {
        return new LogonPolicy() {
            @Override
            public Optional<String> refuse(String s, String password, String remote) {
                return Optional.empty();
            }

            @Override
            public String toString() {
                return "LogonPolicy.open()";
            }
        };
    }

    /**
     * Requires a password, an address, or both.
     *
     * @param expectedPassword Password(554) the counterparty must send; null to
     *     not check one
     * @param allowedAddresses addresses permitted to connect; empty to not
     *     check. Matched by prefix, so "10.0." admits that whole range.
     */
    static LogonPolicy require(String expectedPassword, List<String> allowedAddresses) {
        List<String> allowed = List.copyOf(allowedAddresses);

        return new LogonPolicy() {
            @Override
            public Optional<String> refuse(
                    String sessionId, String password, String remoteAddress) {

                if (expectedPassword != null && !constantTimeEquals(expectedPassword, password)) {
                    return Optional.of("wrong or missing password");
                }
                if (!allowed.isEmpty()) {
                    if (remoteAddress == null) {
                        return Optional.of("address not reported by the engine");
                    }
                    boolean permitted = allowed.stream()
                            .anyMatch(prefix -> remoteAddress.contains(prefix));
                    if (!permitted) {
                        return Optional.of("address " + remoteAddress + " is not permitted");
                    }
                }
                return Optional.empty();
            }

            @Override
            public String toString() {
                return "LogonPolicy.require(password=" + (expectedPassword != null)
                        + ", addresses=" + allowed + ")";
            }
        };
    }

    /**
     * Compare without revealing where two strings diverge.
     *
     * <p>A byte-by-byte comparison that returns early leaks the length of the
     * matching prefix through timing, which is enough to recover a password one
     * character at a time.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
