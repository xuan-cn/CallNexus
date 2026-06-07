package org.dromara.resource.freeswitch.xml;

public final class FreeSwitchXmlRenderer {
    private FreeSwitchXmlRenderer() {
    }

    public static String notFound() {
        return """
            <document type="freeswitch/xml">
              <section name="result">
                <result status="not found"/>
              </section>
            </document>
            """;
    }

    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
