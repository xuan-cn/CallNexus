package org.dromara.resource.freeswitch.xml.dialplan;

import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.voicemail.domain.response.VoiceMailDialplanResponse;
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

    public String renderQueueRoute(PhoneNumberDialplanRouteResponse route, CallQueueDialplanResponse queue, String context) {
        String number = FreeSwitchXmlRenderer.escape(route.getNumber());
        String queueName = FreeSwitchXmlRenderer.escape(queue.getQueueCode() + "@default");
        String dialplanContext = FreeSwitchXmlRenderer.escape(context == null || context.isBlank() ? "public" : context);
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Dynamic Dialplan">
                <context name="%s">
                  <extension name="callnexus_inbound_queue_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_id=%s"/>
                      <action application="set" data="callnexus_route_type=QUEUE"/>
                      <action application="set" data="callnexus_queue_id=%s"/>
                      <action application="set" data="callnexus_queue_code=%s"/>
                      <action application="set" data="callnexus_node_id=%s"/>
                      <action application="export" data="callnexus_business_call_id=${uuid}"/>
                      <action application="export" data="callnexus_direction=INBOUND"/>
                      <action application="export" data="callnexus_original_caller=${caller_id_number}"/>
                      <action application="export" data="callnexus_original_called=%s"/>
                      <action application="set" data="callnexus_recording_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}.wav"/>
                      <action application="export" data="callnexus_recording_path=${callnexus_recording_path}"/>
                      <action application="set" data="api_hangup_hook=bg_system /opt/callnexus/bin/upload-recording.sh ${callnexus_business_call_id} ${callnexus_recording_path}"/>
                      <action application="record_session" data="${callnexus_recording_path}"/>
                      <action application="answer"/>
                      %s%s
                      <action application="set" data="hangup_after_bridge=true"/>
                      <action application="callcenter" data="%s"/>
                      %s
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, number, number, route.getId(), queue.getId(), queueName, route.getNodeId(), number,
            maskCallerActions(queue), forceWaitAction(queue), queueName, queueExitActions(queue, queueName, context));
    }

    public String renderInternalVoiceMailRoute(String destinationNumber, VoiceMailDialplanResponse box, String context) {
        String number = FreeSwitchXmlRenderer.escape(destinationNumber);
        String dialplanContext = FreeSwitchXmlRenderer.escape(context == null || context.isBlank() ? "public" : context);
        String promptPath = FreeSwitchXmlRenderer.escape(box.getPromptPath());
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Dynamic Dialplan">
                <context name="%s">
                  <extension name="callnexus_internal_voicemail_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_type=VOICEMAIL"/>
                      <action application="set" data="callnexus_voicemail_box_id=%s"/>
                      <action application="answer"/>
                      <action application="sleep" data="300"/>
                      <action application="playback" data="%s"/>
                      <action application="playback" data="tone_stream://%%(1000,0,640)"/>
                      <action application="set" data="callnexus_voicemail_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}-voicemail.wav"/>
                      <action application="set" data="api_hangup_hook=bg_system /opt/callnexus/bin/upload-voicemail.sh ${callnexus_business_call_id} ${callnexus_voicemail_path} %s ${caller_id_number} ${callnexus_original_called}"/>
                      <action application="record" data="${callnexus_voicemail_path} %s %s %s"/>
                      <action application="hangup" data="NORMAL_CLEARING"/>
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, number, number, box.getId(), promptPath, box.getId(),
            box.getMaxSeconds(), box.getSilenceThreshold(), box.getSilenceHits());
    }

    private String maskCallerActions(CallQueueDialplanResponse queue) {
        if (!Boolean.TRUE.equals(queue.getMaskCallerNumber())) {
            return "";
        }
        return """
                      <action application="set" data="effective_caller_id_number=anonymous"/>
                      <action application="set" data="effective_caller_id_name=匿名来电"/>
                      """;
    }

    private String forceWaitAction(CallQueueDialplanResponse queue) {
        StringBuilder builder = new StringBuilder();
        Integer seconds = queue.getForceWaitSeconds();
        if (seconds != null && seconds > 0) {
            builder.append("                      <action application=\"sleep\" data=\"")
                .append(seconds * 1000)
                .append("\"/>\n");
        }
        if (queue.getForceWaitMediaPath() != null && !queue.getForceWaitMediaPath().isBlank()) {
            builder.append("                      <action application=\"playback\" data=\"")
                .append(FreeSwitchXmlRenderer.escape(queue.getForceWaitMediaPath()))
                .append("\"/>\n");
        }
        return builder.toString();
    }

    private String queueExitActions(CallQueueDialplanResponse queue, String currentQueueName, String context) {
        String action = queue.getTimeoutAction() == null || queue.getTimeoutAction().isBlank() ? "HANGUP" : queue.getTimeoutAction();
        String target = queue.getTimeoutTarget();
        String targetQueueCode = queue.getTimeoutTargetQueueCode();
        if (queue.getNoAgentAction() != null && !queue.getNoAgentAction().isBlank() && !"WAIT".equals(queue.getNoAgentAction())) {
            action = queue.getNoAgentAction();
            target = queue.getNoAgentTarget();
            targetQueueCode = queue.getNoAgentTargetQueueCode();
        }
        return renderQueuePostAction(action, target, targetQueueCode, currentQueueName, context);
    }

    private String renderQueuePostAction(String action, String target, String targetQueueCode, String currentQueueName, String context) {
        return switch (action) {
            case "CONTINUE" -> "                      <action application=\"callcenter\" data=\"" + currentQueueName + "\"/>\n"
                + "                      <action application=\"hangup\" data=\"NORMAL_CLEARING\"/>\n";
            case "VOICEMAIL" -> internalTransfer("callnexus_queue_voicemail_" + safeTarget(target), context);
            case "IVR" -> internalTransfer("callnexus_queue_ivr_" + safeTarget(target), context);
            case "EXTENSION" -> "                      <action application=\"bridge\" data=\"user/"
                + FreeSwitchXmlRenderer.escape(safeTarget(target)) + "@${domain_name}\"/>\n"
                + "                      <action application=\"hangup\" data=\"NORMAL_CLEARING\"/>\n";
            case "QUEUE" -> "                      <action application=\"callcenter\" data=\""
                + FreeSwitchXmlRenderer.escape(targetQueueCode + "@default") + "\"/>\n"
                + "                      <action application=\"hangup\" data=\"NORMAL_CLEARING\"/>\n";
            default -> "                      <action application=\"hangup\" data=\"NORMAL_CLEARING\"/>\n";
        };
    }

    private String internalTransfer(String destination, String context) {
        return "                      <action application=\"set\" data=\"callnexus_internal_transfer=QUEUE\"/>\n"
            + "                      <action application=\"transfer\" data=\"" + FreeSwitchXmlRenderer.escape(destination)
            + " XML " + FreeSwitchXmlRenderer.escape(context == null || context.isBlank() ? "public" : context) + "\"/>\n";
    }

    private String safeTarget(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_#*+-]", "");
    }

    public String renderVoiceMailRoute(PhoneNumberDialplanRouteResponse route, VoiceMailDialplanResponse box, String context) {
        String number = FreeSwitchXmlRenderer.escape(route.getNumber());
        String dialplanContext = FreeSwitchXmlRenderer.escape(context == null || context.isBlank() ? "public" : context);
        String promptPath = FreeSwitchXmlRenderer.escape(box.getPromptPath());
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Dynamic Dialplan">
                <context name="%s">
                  <extension name="callnexus_inbound_voicemail_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_id=%s"/>
                      <action application="set" data="callnexus_route_type=VOICEMAIL"/>
                      <action application="set" data="callnexus_voicemail_box_id=%s"/>
                      <action application="export" data="callnexus_business_call_id=${uuid}"/>
                      <action application="export" data="callnexus_direction=INBOUND"/>
                      <action application="export" data="callnexus_original_caller=${caller_id_number}"/>
                      <action application="export" data="callnexus_original_called=%s"/>
                      <action application="answer"/>
                      <action application="sleep" data="300"/>
                      <action application="playback" data="%s"/>
                      <action application="playback" data="tone_stream://%%(1000,0,640)"/>
                      <action application="set" data="callnexus_voicemail_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}-voicemail.wav"/>
                      <action application="set" data="api_hangup_hook=bg_system /opt/callnexus/bin/upload-voicemail.sh ${callnexus_business_call_id} ${callnexus_voicemail_path} %s ${caller_id_number} %s"/>
                      <action application="record" data="${callnexus_voicemail_path} %s %s %s"/>
                      <action application="hangup" data="NORMAL_CLEARING"/>
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, number, number, route.getId(), box.getId(), number, promptPath, box.getId(), number,
            box.getMaxSeconds(), box.getSilenceThreshold(), box.getSilenceHits());
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
                      <action application="bridge" data="[absolute_codec_string=PCMA,codec_string=PCMA]sofia/gateway/%s/%s"/>
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
