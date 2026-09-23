package io.nexum.message;

/**
 * A standard FIX version. Every session declares one, and it selects the
 * baseline group templates that session starts from.
 *
 * <p>Most counterparties follow the standard closely enough that the baseline
 * is all they need. A session that genuinely deviates layers a dialect plugin
 * on top rather than carrying a hand-maintained copy of the whole dictionary.
 *
 * <p>Through 4.4 a version names itself on the wire and there is one thing to
 * know. The 5.0 series separated the session layer from the application layer:
 * all of them run over FIXT.1.1, and which application version is in force is
 * stated in ApplVerID instead. So a version here answers three questions that
 * used to have one answer — what goes in BeginString, which dictionary parses
 * the messages, and what ApplVerID says — and a caller has to ask the one it
 * means.
 */
public enum FixVersion {
    FIX40("FIX.4.0", "FIX.4.0", "FIX40", null),
    FIX41("FIX.4.1", "FIX.4.1", "FIX41", null),
    FIX42("FIX.4.2", "FIX.4.2", "FIX42", null),
    FIX43("FIX.4.3", "FIX.4.3", "FIX43", null),
    FIX44("FIX.4.4", "FIX.4.4", "FIX44", null),
    FIX50("FIX.5.0", "FIXT.1.1", "FIX50", "7"),
    FIX50SP1("FIX.5.0SP1", "FIXT.1.1", "FIX50SP1", "8"),
    FIX50SP2("FIX.5.0SP2", "FIXT.1.1", "FIX50SP2", "9"),
    /**
     * The session layer the 5.0 series runs over, declared on its own.
     *
     * <p>A session that names this and nothing else carries no application
     * version, so it can negotiate one per message rather than fixing it for
     * the session. Nothing here supplies a default for it: a counterparty that
     * wants one declares a 5.0 version instead.
     */
    FIXT11("FIXT.1.1", "FIXT.1.1", "FIXT11", null);

    private final String label;
    private final String beginString;
    private final String dictionary;
    private final String applVerID;

    FixVersion(String label, String beginString, String dictionary, String applVerID) {
        this.label = label;
        this.beginString = beginString;
        this.dictionary = dictionary;
        this.applVerID = applVerID;
    }

    /**
     * What this version is called: {@code FIX.5.0} for a 5.0 session, whatever
     * its BeginString says.
     */
    public String label() {
        return label;
    }

    /**
     * Value of BeginString(8) on the wire.
     *
     * <p>FIXT.1.1 for every 5.0-series version: putting the application version
     * here instead is a session a counterparty will not accept.
     */
    public String beginString() {
        return beginString;
    }

    /**
     * Value of ApplVerID(1128), or null where BeginString already says which
     * version is in force.
     */
    public String applVerID() {
        return applVerID;
    }

    /**
     * Whether an execution report in this version carries ExecTransType(20)
     * and reports a fill as ExecType 1 or 2 rather than F.
     *
     * <p>True through 4.2. 4.3 removed ExecTransType from the message and
     * added F, and 5.0SP1 removed 1 and 2 altogether.
     */
    public boolean usesExecTransType() {
        return this == FIX40 || this == FIX41 || this == FIX42;
    }

    /** Whether this version's session layer is FIXT.1.1 rather than the version itself. */
    public boolean isFixt() {
        return "FIXT.1.1".equals(beginString);
    }

    /**
     * The version a caller named, by label or by enum name.
     *
     * @param name {@code FIX.5.0}, {@code FIX50} and {@code fix50} all name the
     *     same version.
     * @return the version.
     * @throws IllegalArgumentException if no version matches.
     */
    public static FixVersion of(String name) {
        for (FixVersion version : values()) {
            if (version.name().equalsIgnoreCase(name) || version.label.equalsIgnoreCase(name)) {
                return version;
            }
        }
        throw new IllegalArgumentException("unknown FIX version: " + name);
    }

    /**
     * The version a session declared as its label.
     *
     * <p>Named for BeginString by history rather than by what it reads: a
     * configuration writes {@code FIX.5.0}, which is the label, and the wire
     * writes FIXT.1.1, which is not what a configuration is naming.
     *
     * @param beginString the version as a configuration spells it.
     * @return the version.
     * @throws IllegalArgumentException if no version matches.
     */
    public static FixVersion ofBeginString(String beginString) {
        for (FixVersion version : values()) {
            if (version.label.equals(beginString)) {
                return version;
            }
        }
        throw new IllegalArgumentException("unknown FIX version: " + beginString);
    }

    /**
     * The data dictionary this version's messages parse against, e.g.
     * {@code FIX50.xml} for FIX 5.0 — not FIXT11.xml, which holds its session
     * layer and none of its business messages.
     */
    public String dictionaryResource() {
        return dictionary + ".xml";
    }

    /**
     * The session-layer dictionary, for a version that has one separate from
     * its application dictionary.
     *
     * @return {@code FIXT11.xml} for the 5.0 series, or null where one
     *     dictionary holds both layers.
     */
    public String transportDictionaryResource() {
        return isFixt() && this != FIXT11 ? "FIXT11.xml" : null;
    }
}
