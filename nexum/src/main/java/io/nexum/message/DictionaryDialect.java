package io.nexum.message;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link Dialect} read from QuickFIX DataDictionary XML — the format venues
 * actually publish, and the one QuickFIX/J already needs at the session.
 *
 * <p>Only the group structure is taken from the file. Field types, required
 * flags and value enumerations stay with the validating engine; this class
 * answers one question — where does a repeating group start and end.
 *
 * <p>Group layouts are resolved lazily per message type and cached, since a
 * dictionary declares far more message types than a given deployment sees.
 */
public final class DictionaryDialect implements Dialect {

    private final String name;
    private final Map<String, Element> messageElements = new LinkedHashMap<>();
    private final Map<String, Element> componentElements = new LinkedHashMap<>();
    private final Map<String, String> fieldNameToTag = new LinkedHashMap<>();
    private final Map<String, Map<Integer, GroupTemplate>> cache = new ConcurrentHashMap<>();

    private DictionaryDialect(String name) {
        this.name = name;
    }

    public static DictionaryDialect load(String name, InputStream xml) {
        DictionaryDialect dialect = new DictionaryDialect(name);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Dictionaries come from counterparties; never resolve external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(xml).getDocumentElement();

            for (Element field : children(root, "fields", "field")) {
                dialect.fieldNameToTag.put(field.getAttribute("name"), field.getAttribute("number"));
            }
            for (Element component : children(root, "components", "component")) {
                dialect.componentElements.put(component.getAttribute("name"), component);
            }
            for (Element message : children(root, "messages", "message")) {
                dialect.messageElements.put(message.getAttribute("msgtype"), message);
            }
            return dialect;
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot read dictionary \"" + name + "\"", failure);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<Integer, GroupTemplate> groupsFor(String msgType) {
        return cache.computeIfAbsent(msgType, type -> {
            Element message = messageElements.get(type);
            if (message == null) {
                return Map.of();
            }
            Map<Integer, GroupTemplate> found = new LinkedHashMap<>();
            collectGroups(message, found);
            return Map.copyOf(found);
        });
    }

    /** Walk a message or component body, expanding component references. */
    private void collectGroups(Element parent, Map<Integer, GroupTemplate> into) {
        for (Element child : elements(parent)) {
            switch (child.getTagName()) {
                case "group" -> {
                    GroupTemplate template = buildGroup(child);
                    if (template != null) {
                        into.put(template.counterTag(), template);
                    }
                }
                case "component" -> {
                    Element referenced = componentElements.get(child.getAttribute("name"));
                    if (referenced != null) {
                        collectGroups(referenced, into);
                    }
                }
                default -> {
                    // plain field — nothing to collect
                }
            }
        }
    }

    private GroupTemplate buildGroup(Element group) {
        Integer counterTag = tagOf(group.getAttribute("name"));
        if (counterTag == null) {
            return null;
        }
        List<Integer> fields = new ArrayList<>();
        Map<Integer, GroupTemplate> nested = new LinkedHashMap<>();
        collectMembers(group, fields, nested);

        if (fields.isEmpty()) {
            return null;
        }
        // The first declared member is the delimiter — this is what marks a new entry.
        return new GroupTemplate(counterTag, fields.get(0), fields, nested);
    }

    private void collectMembers(
            Element parent, List<Integer> fields, Map<Integer, GroupTemplate> nested) {

        for (Element child : elements(parent)) {
            switch (child.getTagName()) {
                case "field" -> {
                    Integer tag = tagOf(child.getAttribute("name"));
                    if (tag != null) {
                        fields.add(tag);
                    }
                }
                case "group" -> {
                    GroupTemplate template = buildGroup(child);
                    if (template != null) {
                        nested.put(template.counterTag(), template);
                        fields.add(template.counterTag());
                    }
                }
                case "component" -> {
                    Element referenced = componentElements.get(child.getAttribute("name"));
                    if (referenced != null) {
                        collectMembers(referenced, fields, nested);
                    }
                }
                default -> {
                    // ignore
                }
            }
        }
    }

    private Integer tagOf(String fieldName) {
        String number = fieldNameToTag.get(fieldName);
        return number == null ? null : Integer.valueOf(number);
    }

    private static List<Element> children(Element root, String section, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList sections = root.getElementsByTagName(section);
        for (int i = 0; i < sections.getLength(); i++) {
            NodeList nodes = ((Element) sections.item(i)).getElementsByTagName(tagName);
            for (int j = 0; j < nodes.getLength(); j++) {
                result.add((Element) nodes.item(j));
            }
        }
        return result;
    }

    private static List<Element> elements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }
}
