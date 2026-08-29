package io.nexum.routing;

import io.nexum.message.FixLayers;
import io.nexum.message.FixMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reading an order's own fields out of a message. */
public final class OrderFields {

    private OrderFields() {}

    /**
     * The order's own fields, with the session layer's stripped out.
     *
     * <p>The classification comes from {@link FixLayers}, which the transport
     * also derives header placement from. Two hand-kept copies of it disagreed
     * once, and a field that changes on every hop was journalled as though it
     * described the order.
     */
    public static Map<Integer, String> business(FixMessage message) {
        Map<Integer, String> business = new LinkedHashMap<>();
        message.flatFields().forEach((tag, value) -> {
            if (FixLayers.isBusiness(tag)) {
                business.put(tag, value);
            }
        });
        return business;
    }

    /** Tag-keyed fields as the journal stores them. */
    public static Map<String, String> asStrings(Map<Integer, String> fields) {
        Map<String, String> result = new LinkedHashMap<>();
        fields.forEach((tag, value) -> result.put(String.valueOf(tag), value));
        return result;
    }

    /**
     * A number from a field, or a fallback.
     *
     * <p>A malformed value yields the fallback rather than throwing: routing
     * must not be brought down by one bad message.
     */
    public static double number(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** Null rather than zero when absent: "no quantity left" and "not told" differ. */
    public static Double optionalNumber(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
