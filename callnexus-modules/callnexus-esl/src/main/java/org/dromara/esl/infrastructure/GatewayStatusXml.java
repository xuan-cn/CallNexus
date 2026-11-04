package org.dromara.esl.infrastructure;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class GatewayStatusXml {
    private static final Pattern EXPIRES = Pattern.compile("expsecs\\((-?\\d+)\\)", Pattern.CASE_INSENSITIVE);

    record Gateway(String state, String status, int pingSeconds, String retry) { }

    static Map<String, Gateway> gateways(String xml) {
        Document document = parse(xml, "gateways");
        Map<String, Gateway> result = new HashMap<>();
        var elements = document.getElementsByTagName("gateway");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String name = text(element, "name");
            if (name.isBlank()) throw new IllegalArgumentException("Missing gateway name");
            result.put(name, new Gateway(text(element, "state"), text(element, "status"),
                Integer.parseInt(text(element, "pingfreq")), text(element, "retry")));
        }
        return result;
    }

    static Set<String> registrations(String xml, String domain) {
        Document document = parse(xml, "profile");
        if (document.getElementsByTagName("registrations").getLength() != 1) {
            throw new IllegalArgumentException("Missing registrations");
        }
        Set<String> result = new HashSet<>();
        var elements = document.getElementsByTagName("registration");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            var expires = EXPIRES.matcher(text(element, "status"));
            if (!expires.find() || Long.parseLong(expires.group(1)) <= 0) continue;
            String user = text(element, "user");
            if (user.endsWith("@" + domain)) result.add(user.substring(0, user.lastIndexOf('@')));
        }
        return result;
    }

    private static Document parse(String xml, String root) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            if (!root.equals(document.getDocumentElement().getTagName())) throw new IllegalArgumentException("Unexpected root");
            return document;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid FreeSWITCH status XML", exception);
        }
    }

    private static String text(Element element, String name) {
        var nodes = element.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
