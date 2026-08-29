package io.nexum.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed configuration tree: which plugins to mount, and what each is given.
 *
 * <p>Kept deliberately small — a nested map of strings and lists. Wiring is
 * expressed by naming plugins and their configuration, never by embedded
 * expressions or scripting, so a deployment can be read and diffed without
 * running it.
 */
public final class Config {

    private final Map<String, Object> values;

    Config(Map<String, Object> values) {
        this.values = values;
    }

    public static Config empty() {
        return new Config(Map.of());
    }

    @SuppressWarnings("unchecked")
    public Config section(String key) {
        Object value = values.get(key);
        return value instanceof Map<?, ?> map
                ? new Config((Map<String, Object>) map)
                : empty();
    }

    @SuppressWarnings("unchecked")
    public List<Config> sections(String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Config> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new Config((Map<String, Object>) map));
            }
        }
        return result;
    }

    public String string(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public String string(String key, String fallback) {
        String value = string(key);
        return value == null ? fallback : value;
    }

    /** @throws IllegalStateException when the key is absent — a config error, not a default */
    public String require(String key) {
        String value = string(key);
        if (value == null) {
            throw new IllegalStateException("missing required config key \"" + key + "\"");
        }
        return value;
    }

    public int integer(String key, int fallback) {
        String value = string(key);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }

    public boolean flag(String key, boolean fallback) {
        String value = string(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    public List<String> strings(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            list.forEach(item -> result.add(String.valueOf(item)));
            return result;
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    public Map<String, Object> raw() {
        return values;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /**
     * Layer another config over this one. Maps merge key by key; anything else
     * replaces outright, so an overlay states a difference rather than restating
     * everything it leaves alone.
     */
    @SuppressWarnings("unchecked")
    public Config overlay(Config other) {
        Map<String, Object> merged = new LinkedHashMap<>(values);
        other.values.forEach((key, value) -> {
            Object existing = merged.get(key);
            if (existing instanceof Map<?, ?> && value instanceof Map<?, ?>) {
                merged.put(key, new Config((Map<String, Object>) existing)
                        .overlay(new Config((Map<String, Object>) value))
                        .raw());
            } else {
                merged.put(key, value);
            }
        });
        return new Config(merged);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
