package io.nexum.message;

/**
 * A standard FIX version. Every session declares one, and it selects the
 * baseline group templates that session starts from.
 *
 * <p>Most counterparties follow the standard closely enough that the baseline
 * is all they need. A session that genuinely deviates layers a dialect plugin
 * on top rather than carrying a hand-maintained copy of the whole dictionary.
 */
public enum FixVersion {
    FIX40("FIX.4.0"),
    FIX41("FIX.4.1"),
    FIX42("FIX.4.2"),
    FIX43("FIX.4.3"),
    FIX44("FIX.4.4"),
    FIX50("FIX.5.0"),
    FIX50SP1("FIX.5.0SP1"),
    FIX50SP2("FIX.5.0SP2"),
    FIXT11("FIXT.1.1");

    private final String beginString;

    FixVersion(String beginString) {
        this.beginString = beginString;
    }

    /** Value of BeginString(8) for this version. */
    public String beginString() {
        return beginString;
    }

    public static FixVersion ofBeginString(String beginString) {
        for (FixVersion version : values()) {
            if (version.beginString.equals(beginString)) {
                return version;
            }
        }
        throw new IllegalArgumentException("unknown FIX version: " + beginString);
    }

    /** Conventional dictionary resource name, e.g. {@code FIX44.xml}. */
    public String dictionaryResource() {
        return beginString.replace(".", "").replace("FIX", "FIX") + ".xml";
    }
}
