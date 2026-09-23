package io.nexum.order;

import io.nexum.message.FixVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every OrdStatus this engine can put on the wire is one each supported
 * version defines.
 *
 * <p>An order's state goes out as OrdStatus(39) on a cancel reject, and a
 * value the counterparty's dictionary does not define is a session-level
 * reject of the message that was meant to tell it why its request failed.
 * FIX 4.0 and 4.1 are not listed: neither defines PendingReplace (E), and
 * neither is a version this engine is used against.
 */
class OrdStatusVersionTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = FixVersion.class, names = { "FIX42", "FIX44", "FIX50", "FIX50SP1", "FIX50SP2" })
    void everyStateMapsToAValueTheVersionDefines(FixVersion version) throws Exception {
        Set<String> defined = ordStatusValues(version.dictionaryResource());

        for (OrderState state : OrderState.values()) {
            String sent = state.fixOrdStatus();
            assertTrue(defined.contains(sent),
                    () -> state + " goes out as OrdStatus=" + sent + ", which "
                            + version.label() + " does not define (it has " + defined + ")");
        }
    }

    private static Set<String> ordStatusValues(String resource) throws Exception {
        try (InputStream xml = OrdStatusVersionTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml, resource);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            NodeList fields = factory.newDocumentBuilder().parse(xml).getElementsByTagName("field");
            Set<String> values = new HashSet<>();
            for (int i = 0; i < fields.getLength(); i++) {
                Element field = (Element) fields.item(i);
                if ("39".equals(field.getAttribute("number"))) {
                    NodeList enums = field.getElementsByTagName("value");
                    for (int j = 0; j < enums.getLength(); j++) {
                        values.add(((Element) enums.item(j)).getAttribute("enum"));
                    }
                }
            }
            return values;
        }
    }
}
