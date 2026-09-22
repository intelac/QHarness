package io.nexum.probe;

/**
 * A FIX version an endpoint of this harness can speak.
 *
 * <p>The probe carries its own list rather than the engine's: it stands in for
 * somebody else's counterparty, and what it can speak is a fact about this
 * process, not about any engine under test. Adding a version here is adding a
 * dictionary to the probe's classpath and nothing more.
 *
 * <p>The 4.x series names itself in BeginString. The 5.0 series does not: the
 * session layer is FIXT.1.1 for all of them, and the application version rides
 * in ApplVerID, so those entries carry both.
 */
public enum ProbeFixVersion {

    /** FIX 4.2, still the floor for a good deal of venue connectivity. */
    FIX42("FIX.4.2", null, "FIX42"),

    /** FIX 4.4, the usual default. */
    FIX44("FIX.4.4", null, "FIX44"),

    /** FIX 5.0 over the FIXT.1.1 session layer. */
    FIX50("FIXT.1.1", "7", "FIX50");

    private final String beginString;
    private final String applVerID;
    private final String dictionary;

    ProbeFixVersion(String beginString, String applVerID, String dictionary) {
        this.beginString = beginString;
        this.applVerID = applVerID;
        this.dictionary = dictionary;
    }

    /** Value of BeginString(8): the version itself for 4.x, FIXT.1.1 for the 5.0 series. */
    public String beginString() {
        return beginString;
    }

    /** Value of ApplVerID(1128) for a 5.0-series version, or null where BeginString says it all. */
    public String applVerID() {
        return applVerID;
    }

    /** The data dictionary this version parses against, e.g. {@code FIX44}. */
    public String dictionary() {
        return dictionary;
    }

    /** Whether this version's session layer is FIXT.1.1 rather than the version itself. */
    public boolean isFixt() {
        return applVerID != null;
    }

    /**
     * The version a caller named.
     *
     * @param name what was asked for, as a version string or an enum name:
     *     {@code FIX.4.2}, {@code FIX42}, {@code 4.2} and {@code fix42} all
     *     name the same version, because a scenario is written by hand.
     * @return the version.
     * @throws IllegalArgumentException if no version matches.
     */
    public static ProbeFixVersion of(String name) {
        String wanted = name.trim().toUpperCase().replace(".", "").replace("FIX", "");
        for (ProbeFixVersion version : values()) {
            if (version.name().replace("FIX", "").equals(wanted)) {
                return version;
            }
        }
        StringBuilder known = new StringBuilder();
        for (ProbeFixVersion version : values()) {
            known.append(known.isEmpty() ? "" : ", ").append(version.name());
        }
        throw new IllegalArgumentException(
                "unknown FIX version \"" + name + "\"; this harness speaks " + known);
    }
}
