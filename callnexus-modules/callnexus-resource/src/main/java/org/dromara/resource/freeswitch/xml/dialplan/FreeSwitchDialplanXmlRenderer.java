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
        // 记忆坐席命中：客户号码在 Redis 中绑定了上次接听坐席，且坐席当前 IDLE，直接 bridge 分机，跳过 mod_callcenter。
        if (Boolean.TRUE.equals(queue.getStickyAgentEnabled()) && queue.getStickyAgentTarget() != null
            && !queue.getStickyAgentTarget().isBlank()) {
            return renderQueueStickyBridgeRoute(route, queue, dialplanContext, number);
        }
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
                      %s%s%s
                      <action application="set" data="hangup_after_bridge=true"/>
                      <action application="callcenter" data="%s"/>
                      %s
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, number, number, route.getId(), queue.getId(), queueName, route.getNodeId(), number,
            queueAnswerAction(queue), maskCallerActions(queue), forceWaitAction(queue), queueName, queueExitActions(queue, queueName, context));
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

    /**
     * 队列入口客户腿接通方式。
     *
     * <p>"手动接听"打开时返回 {@code pre_answer}，客户腿进入早媒体即可播放等待音/提醒音，
     * 但运营商侧仍处于"振铃中"，不计费；当 mod_callcenter 把客户腿桥接到坐席时
     * FreeSWITCH 会自动 answer 客户腿，此时才进入正式接通状态。
     *
     * <p>关闭时保持历史行为，直接 {@code answer} 客户腿。
     *
     * <p>同时设置 {@code callnexus_queue_manual_answer} 通道变量，
     * 便于后续 ESL 事件处理时识别该通话采用了手动接听模式。
     */
    private String queueAnswerAction(CallQueueDialplanResponse queue) {
        boolean manualAnswer = Boolean.TRUE.equals(queue.getManualAnswer());
        String flag = "                      <action application=\"set\" data=\"callnexus_queue_manual_answer=" + manualAnswer + "\"/>\n";
        String connect = manualAnswer
            ? "                      <action application=\"pre_answer\"/>\n"
            : "                      <action application=\"answer\"/>\n";
        return flag + connect;
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
        // 转手机能力依靠 mod_callcenter 退出后的 ${cc_cause} 分支决定；
        // 命中转手机时跳过传统 HANGUP/IVR/QUEUE 的 noAgentAction，避免重复执行。
        StringBuilder builder = new StringBuilder();
        String mobileFallback = mobileTransferActions(queue);
        if (!mobileFallback.isEmpty()) {
            builder.append(mobileFallback);
        }
        builder.append(renderQueuePostAction(action, target, targetQueueCode, currentQueueName, context));
        return builder.toString();
    }

    /**
     * 转手机分支：mod_callcenter 退出时 FreeSWITCH 会设置 {@code ${cc_cause}}，
     * 我们在原 noAgentAction 之前追加 {@code <action application="bridge" data="${cond(...)}"/>}，
     * 命中 {@code max-wait}（无可用坐席）或 {@code max-no-answer}（所有坐席均未接）时桥接到手机。
     *
     * <p>说明：{@code hangup_after_bridge=true} 已在主流程设置，坐席接通后通话自然结束，
     * 不会进入此分支；只有 mod_callcenter 主动退出且原通道仍存活时才会触发。
     */
    private String mobileTransferActions(CallQueueDialplanResponse queue) {
        boolean busy = Boolean.TRUE.equals(queue.getBusyTransferMobile())
            && queue.getBusyTransferNumber() != null && !queue.getBusyTransferNumber().isBlank();
        boolean timeoutMobile = Boolean.TRUE.equals(queue.getAgentTimeoutTransferMobile())
            && queue.getAgentTimeoutTransferNumber() != null && !queue.getAgentTimeoutTransferNumber().isBlank();
        if (!busy && !timeoutMobile) {
            return "";
        }
        if (queue.getOutboundGatewayCode() == null || queue.getOutboundGatewayCode().isBlank()) {
            // 没有可用外呼网关：保留配置但忽略动作，避免渲染出错误的 dialplan。
            return "";
        }
        String gateway = FreeSwitchXmlRenderer.escape(queue.getOutboundGatewayCode());
        StringBuilder builder = new StringBuilder();
        if (busy) {
            builder.append(mobileBranch("max-wait", gateway, queue.getBusyTransferNumber()));
        }
        if (timeoutMobile) {
            builder.append(mobileBranch("max-no-answer", gateway, queue.getAgentTimeoutTransferNumber()));
        }
        return builder.toString();
    }

    private String mobileBranch(String ccCause, String gatewayCode, String mobile) {
        String mobileEscaped = FreeSwitchXmlRenderer.escape(mobile);
        // 使用 cond() 内联条件：仅当 ${cc_cause} 等于目标值时返回真实桥接串，
        // 否则返回当前 channel 自身的 originate string（user/${destination_number}）让 bridge 立即失败但不阻塞下一动作。
        // 这里通过 ${cond(... ? real : '')} + execute_string 模式实现"满足条件才执行 bridge"。
        return "                      <action application=\"execute_string\" data=\"${cond(${cc_cause} == '"
            + ccCause + "' ? 'bridge sofia/gateway/" + gatewayCode + "/" + mobileEscaped + "' : 'log NOTICE [CallNexus] skip mobile branch ccCause=${cc_cause}')}\"/>\n";
    }

    /**
     * 记忆坐席命中时直接桥接分机的拨号计划。
     *
     * <p>跳过 mod_callcenter，因此遇忙/超时转手机、挂机按键采集等队列动作均不生效；
     * 这些能力仅作用于队列分配路径。桥接目标必须包含 {@code 分机@域名}，由 {@code StickyAgentRegistry} 校验。
     */
    private String renderQueueStickyBridgeRoute(PhoneNumberDialplanRouteResponse route, CallQueueDialplanResponse queue,
                                                String dialplanContext, String number) {
        String stickyTarget = FreeSwitchXmlRenderer.escape(queue.getStickyAgentTarget());
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Dynamic Dialplan">
                <context name="%s">
                  <extension name="callnexus_inbound_queue_sticky_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_id=%s"/>
                      <action application="set" data="callnexus_route_type=QUEUE"/>
                      <action application="set" data="callnexus_queue_id=%s"/>
                      <action application="set" data="callnexus_queue_code=%s"/>
                      <action application="set" data="callnexus_node_id=%s"/>
                      <action application="set" data="callnexus_queue_sticky_hit=true"/>
                      <action application="export" data="callnexus_business_call_id=${uuid}"/>
                      <action application="export" data="callnexus_direction=INBOUND"/>
                      <action application="export" data="callnexus_original_caller=${caller_id_number}"/>
                      <action application="export" data="callnexus_original_called=%s"/>
                      <action application="set" data="callnexus_recording_path=/var/lib/freeswitch/recordings/${callnexus_business_call_id}.wav"/>
                      <action application="export" data="callnexus_recording_path=${callnexus_recording_path}"/>
                      <action application="set" data="api_hangup_hook=bg_system /opt/callnexus/bin/upload-recording.sh ${callnexus_business_call_id} ${callnexus_recording_path}"/>
                      <action application="record_session" data="${callnexus_recording_path}"/>
                      %s%s
                      <action application="set" data="hangup_after_bridge=true"/>
                      <action application="bridge" data="user/%s"/>
                      <action application="hangup" data="NORMAL_CLEARING"/>
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, number, number, route.getId(), queue.getId(),
                FreeSwitchXmlRenderer.escape(queue.getQueueCode() + "@default"), route.getNodeId(), number,
                queueAnswerAction(queue), maskCallerActions(queue), stickyTarget);
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
