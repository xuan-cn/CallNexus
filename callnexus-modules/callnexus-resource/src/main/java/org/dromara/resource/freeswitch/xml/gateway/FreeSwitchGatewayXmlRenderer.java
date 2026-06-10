package org.dromara.resource.freeswitch.xml.gateway;

import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayDirectoryResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FreeSwitchGatewayXmlRenderer {
    public String render(List<FreeSwitchGatewayDirectoryResponse> gateways) {
        StringBuilder users = new StringBuilder();
        for (FreeSwitchGatewayDirectoryResponse gateway : gateways) {
            appendGatewayUser(users, gateway);
        }
        return """
            <document type="freeswitch/xml">
              <section name="directory">
                <domain name="all">
                  <params>
                    <param name="dial-string" value="{presence_id=${dialed_user}@${dialed_domain}}${sofia_contact(${dialed_user}@${dialed_domain})}"/>
                  </params>
                  <groups>
                    <group name="default">
                      <users>
            %s          </users>
                    </group>
                  </groups>
                </domain>
              </section>
            </document>
            """.formatted(users);
    }

    private void appendGatewayUser(StringBuilder xml, FreeSwitchGatewayDirectoryResponse gateway) {
        String gatewayCode = FreeSwitchXmlRenderer.escape(gateway.getGatewayCode());
        xml.append("            <user id=\"").append(gatewayCode).append("\">\n");
        xml.append("              <gateways>\n");
        xml.append("                <gateway name=\"").append(gatewayCode).append("\">\n");
        appendParam(xml, "proxy", gateway.getProxy());
        appendParam(xml, "realm", StringUtils.isBlank(gateway.getRealm()) ? gateway.getProxy() : gateway.getRealm());
        appendParam(xml, "username", gateway.getUsername());
        appendParam(xml, "password", gateway.getPassword());
        appendParam(xml, "register", Boolean.TRUE.equals(gateway.getRegisterEnabled()) ? "true" : "false");
        appendParam(xml, "transport", gateway.getTransport() == null ? null : gateway.getTransport().toLowerCase());
        appendParam(xml, "caller-id-in-from", "true");
        appendParam(xml, "from-user", StringUtils.isBlank(gateway.getCallerIdNumber()) ? gateway.getUsername() : gateway.getCallerIdNumber());
        appendParam(xml, "from-domain", StringUtils.isBlank(gateway.getRealm()) ? gateway.getProxy() : gateway.getRealm());
        appendParam(xml, "extension", "auto_to_user");
        appendParam(xml, "context", "public");
        appendParam(xml, "ping", String.valueOf(gateway.getPing() == null ? 0 : gateway.getPing()));
        xml.append("                </gateway>\n");
        xml.append("              </gateways>\n");
        xml.append("              <params>\n");
        appendParam(xml, "password", "any");
        xml.append("              </params>\n");
        xml.append("            </user>\n");
    }

    private void appendParam(StringBuilder xml, String name, String value) {
        if (StringUtils.isBlank(value)) return;
        xml.append("                  <param name=\"")
            .append(FreeSwitchXmlRenderer.escape(name))
            .append("\" value=\"")
            .append(FreeSwitchXmlRenderer.escape(value))
            .append("\"/>\n");
    }
}
