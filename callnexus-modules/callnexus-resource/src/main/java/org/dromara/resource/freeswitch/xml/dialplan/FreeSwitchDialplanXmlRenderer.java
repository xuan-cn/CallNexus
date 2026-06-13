package org.dromara.resource.freeswitch.xml.dialplan;

import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
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
                      <action application="export" data="callnexus_business_call_id=${uuid}"/>
                      <action application="export" data="callnexus_direction=INBOUND"/>
                      <action application="export" data="callnexus_original_caller=${caller_id_number}"/>
                      <action application="export" data="callnexus_original_called=%s"/>
                      <action application="set" data="callnexus_recording_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}.wav"/>
                      <action application="export" data="callnexus_recording_path=${callnexus_recording_path}"/>
                      <action application="set" data="api_hangup_hook=bg_system /opt/callnexus/bin/upload-recording.sh ${callnexus_business_call_id} ${callnexus_recording_path}"/>
                      <action application="record_session" data="${callnexus_recording_path}"/>
                      <action application="set" data="domain_name=%s"/>
                      <action application="bridge" data="user/%s@%s"/>
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, number, number, route.getId(), number, number, domain, extension, domain);
    }

    public String renderOutboundRoute(PhoneNumberOutboundRouteResponse route, String context, String destinationNumber) {
        String dialplanContext = FreeSwitchXmlRenderer.escape(context == null || context.isBlank() ? "default" : context);
        String destination = FreeSwitchXmlRenderer.escape(destinationNumber);
        String gatewayCode = FreeSwitchXmlRenderer.escape(route.getGatewayCode());
        String callerIdNumber = FreeSwitchXmlRenderer.escape(route.getNumber());
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Dynamic Dialplan">
                <context name="%s">
                  <extension name="callnexus_outbound_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_type=OUTBOUND_GATEWAY"/>
                      <action application="set" data="callnexus_gateway_code=%s"/>
                      <action application="export" data="callnexus_business_call_id=${uuid}"/>
                      <action application="export" data="callnexus_direction=OUTBOUND"/>
                      <action application="export" data="callnexus_original_caller=${caller_id_number}"/>
                      <action application="export" data="callnexus_original_called=%s"/>
                      <action application="set" data="effective_caller_id_number=%s"/>
                      <action application="set" data="effective_caller_id_name=%s"/>
                      <action application="set" data="callnexus_recording_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}.wav"/>
                      <action application="export" data="callnexus_recording_path=${callnexus_recording_path}"/>
                      <action application="set" data="api_hangup_hook=bg_system /opt/callnexus/bin/upload-recording.sh ${callnexus_business_call_id} ${callnexus_recording_path}"/>
                      <action application="record_session" data="${callnexus_recording_path}"/>
                      <action application="bridge" data="sofia/gateway/%s/%s"/>
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, destination, destination, gatewayCode, destination,
                callerIdNumber, callerIdNumber, gatewayCode, destination);
    }

    public String renderInternalExtensionRoute(SipDirectoryAccountResponse account, String context) {
        String dialplanContext = FreeSwitchXmlRenderer.escape(context == null || context.isBlank() ? "default" : context);
        String extension = FreeSwitchXmlRenderer.escape(account.getExtension());
        String domain = FreeSwitchXmlRenderer.escape(account.getDomain());
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Dynamic Dialplan">
                <context name="%s">
                  <extension name="callnexus_internal_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_type=INTERNAL_EXTENSION"/>
                      <action application="export" data="callnexus_business_call_id=${uuid}"/>
                      <action application="export" data="callnexus_direction=INTERNAL"/>
                      <action application="export" data="callnexus_original_caller=${caller_id_number}"/>
                      <action application="export" data="callnexus_original_called=%s"/>
                      <action application="set" data="callnexus_recording_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}.wav"/>
                      <action application="export" data="callnexus_recording_path=${callnexus_recording_path}"/>
                      <action application="set" data="api_hangup_hook=bg_system /opt/callnexus/bin/upload-recording.sh ${callnexus_business_call_id} ${callnexus_recording_path}"/>
                      <action application="record_session" data="${callnexus_recording_path}"/>
                      <action application="set" data="domain_name=%s"/>
                      <action application="bridge" data="user/%s@%s"/>
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, extension, extension, extension, domain, extension, domain);
    }
}
