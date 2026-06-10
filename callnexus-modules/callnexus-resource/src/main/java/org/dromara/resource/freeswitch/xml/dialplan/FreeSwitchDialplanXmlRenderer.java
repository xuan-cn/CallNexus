package org.dromara.resource.freeswitch.xml.dialplan;

import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.springframework.stereotype.Component;

@Component
public class FreeSwitchDialplanXmlRenderer {

    public String renderExtensionRoute(PhoneNumberDialplanRouteResponse route, String context) {
        String number = FreeSwitchXmlRenderer.escape(route.getNumber());
        String extension = FreeSwitchXmlRenderer.escape(route.getRouteTarget());
        String domain = FreeSwitchXmlRenderer.escape(route.getSipDomain());
        String dialplanContext = FreeSwitchXmlRenderer.escape(context == null || context.isBlank() ? "public" : context);
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Dynamic Dialplan">
                <context name="%s">
                  <extension name="callnexus_inbound_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_id=%s"/>
                      <action application="set" data="callnexus_route_type=EXTENSION"/>
                      <action application="set" data="callnexus_destination_number=%s"/>
                      <action application="set" data="domain_name=%s"/>
                      <action application="bridge" data="user/%s@%s"/>
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, number, number, route.getId(), number, domain, extension, domain);
    }
}
