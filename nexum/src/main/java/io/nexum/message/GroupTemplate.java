package io.nexum.message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of one repeating group. A group cannot be parsed from the wire
 * without this — nothing in the bytes says where one entry ends and the next
 * begins, so the layout has to be declared up front.
 *
 * <p>The delimiter is the group's first field. Encountering it starts a new
 * entry; encountering a tag outside {@link #fields()} ends the group.
 *
 * <pre>
 *   382=2|375=BRK1|437=100|375=BRK2|437=200|
 *   |     |________________|________________|
 *   |     entry 1          entry 2
 *   counter=382, delimiter=375, fields=[375, 437]
 * </pre>
 */
public record GroupTemplate(
        int counterTag,
        int delimiterTag,
        List<Integer> fields,
        Map<Integer, GroupTemplate> nested) {

    public GroupTemplate {
        fields = List.copyOf(fields);
        nested = Map.copyOf(nested);
    }

    public static GroupTemplate of(int counterTag, int delimiterTag, Integer... fields) {
        return new GroupTemplate(counterTag, delimiterTag, List.of(fields), Map.of());
    }

    public GroupTemplate withNested(GroupTemplate child) {
        Map<Integer, GroupTemplate> merged = new LinkedHashMap<>(nested);
        merged.put(child.counterTag(), child);
        return new GroupTemplate(counterTag, delimiterTag, fields, merged);
    }

    /** True when the tag belongs to this group — either a plain field or a nested counter. */
    public boolean owns(int tag) {
        return fields.contains(tag) || nested.containsKey(tag);
    }
}
