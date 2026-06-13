package org.dromara.ivr.support;

import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.graph.IvrNodeDefinition;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.springframework.stereotype.Component;

@Component
public class IvrDialplanRenderSupport {

    public void appendDocumentStart(StringBuilder xml, String context) {
        xml.append("""
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus IVR Dialplan">
                <context name="%s">
            """.formatted(escape(blankDefault(context, "public"))));
    }

    public void appendDocumentEnd(StringBuilder xml) {
        xml.append("""
                </context>
              </section>
            </document>
            """);
    }

    public void appendEntry(StringBuilder xml, IvrFlow flow, String number, String startNodeId) {
        String escapedNumber = escape(number);
        xml.append("""
                  <extension name="callnexus_ivr_entry_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_type=IVR"/>
                      <action application="set" data="callnexus_ivr_flow_id=%s"/>
                      <action application="export" data="callnexus_business_call_id=${uuid}"/>
                      <action application="export" data="callnexus_direction=INBOUND"/>
                      <action application="export" data="callnexus_original_caller=${caller_id_number}"/>
                      <action application="export" data="callnexus_original_called=%s"/>
                      <action application="answer"/>
                      <action application="sleep" data="300"/>
                      <action application="set" data="callnexus_recording_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}.wav"/>
                      <action application="export" data="callnexus_recording_path=${callnexus_recording_path}"/>
                      <action application="set" data="api_hangup_hook=bg_system /opt/callnexus/bin/upload-recording.sh ${callnexus_business_call_id} ${callnexus_recording_path}"/>
                      <action application="record_session" data="${callnexus_recording_path}"/>
                      <action application="transfer" data="%s XML ${context}"/>
                    </condition>
                  </extension>
            """.formatted(flow.getId(), escapedNumber, flow.getId(), escapedNumber, extension(flow.getId(), startNodeId)));
    }

    public void appendNodeStart(StringBuilder xml, Long flowId, IvrNodeDefinition node) {
        String extension = extension(flowId, node.id());
        xml.append("""
                  <extension name="%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
            """.formatted(extension, extension));
    }

    public void appendNodeEnd(StringBuilder xml) {
        xml.append("""
                    </condition>
                  </extension>
            """);
    }

    public void appendTransfer(StringBuilder xml, Long flowId, String targetNodeId) {
        if (targetNodeId == null) {
            appendHangup(xml, "NO_ROUTE_DESTINATION");
            return;
        }
        xml.append("      <action application=\"transfer\" data=\"")
            .append(extension(flowId, targetNodeId))
            .append(" XML ${context}\"/>\n");
    }

    public void appendHangup(StringBuilder xml, String cause) {
        xml.append("      <action application=\"hangup\" data=\"")
            .append(escape(cause))
            .append("\"/>\n");
    }

    public String extension(Long flowId, String nodeId) {
        return "callnexus_ivr_" + flowId + "_" + nodeId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public String escape(String value) {
        return FreeSwitchXmlRenderer.escape(value);
    }

    public String escapeRegex(String value) {
        return escape(value.replace("\\", "\\\\").replace(".", "\\.").replace("+", "\\+")
            .replace("?", "\\?").replace("*", "\\*").replace("#", "\\#"));
    }

    private String blankDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
