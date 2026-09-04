package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.service.AiRealtimeTelephonyGateway;
import org.dromara.ai.service.VoiceTransport;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FreeSwitchAiRealtimeMrcpGateway implements AiRealtimeTelephonyGateway {
    private final AiKnowledgeProperties properties;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final IvrDialplanQueryService ivrDialplanQueryService;
    private final FreeSwitchEslCommandGateway eslCommandGateway;

    @Override
    public void speak(Long nodeId, String customerLegUuid, String text, String voice,
                      String turnId, int seq, boolean turnEnd) {
        requireCallId(customerLegUuid);
        EslEndpoint endpoint = endpoint(nodeId);
        // 下发轮次上下文，供 callnexussynth 插件按 (callId,turnId) 复用同一条 TTS WebSocket。
        // callId 使用业务通话腿 UUID（即 customerLegUuid），与插件 start 帧对齐。
        StringBuilder variables = new StringBuilder(192)
            .append("callnexus_ai_call_id=").append(customerLegUuid);
        if (StringUtils.isNotBlank(turnId)) {
            variables.append(";callnexus_ai_turn_id=").append(safeVar(turnId));
        }
        variables.append(";callnexus_ai_segment_seq=").append(Math.max(1, seq));
        variables.append(";callnexus_ai_turn_end=").append(turnEnd);
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_setvar_multi " + customerLegUuid + " " + variables);
        String command = render(properties.getUnimrcp().getSpeakCommandTemplate(), customerLegUuid, text, voice);
        long startNanos = System.nanoTime();
        eslCommandGateway.sendRawCommand(endpoint, command);
        log.info("AI UniMRCP 播报命令已提交，nodeId={}，customerLegUuid={}，voice={}，turnId={}，seq={}，turnEnd={}，textLength={}，costMs={}，command={}",
            nodeId, customerLegUuid, voice, turnId, seq, turnEnd, text == null ? 0 : text.length(),
            elapsedMillis(startNanos), command);
    }

    @Override
    public void recognize(Long nodeId, String customerLegUuid, boolean bargeInEnabled, String bargeInMode) {
        requireCallId(customerLegUuid);
        long startNanos = System.nanoTime();
        EslEndpoint endpoint = endpoint(nodeId);
        eslCommandGateway.sendRawCommand(endpoint, "api uuid_setvar " + customerLegUuid + " fire_asr_events true");
        eslCommandGateway.sendRawCommand(endpoint, "api uuid_setvar " + customerLegUuid + " callnexus_unimrcp_profile "
            + safe(properties.getUnimrcp().getProfile()));
        eslCommandGateway.sendRawCommand(endpoint, "api uuid_setvar " + customerLegUuid + " callnexus_ai_barge_in_enabled "
            + bargeInEnabled);
        eslCommandGateway.sendRawCommand(endpoint, "api uuid_setvar " + customerLegUuid + " callnexus_ai_barge_in_mode "
            + safeBargeInMode(bargeInMode));
        eslCommandGateway.sendRawCommand(endpoint, "api uuid_setvar " + customerLegUuid + " callnexus_unimrcp_grammar "
            + safeGrammar(withSensitivity(properties.getUnimrcp().getGrammar(), bargeInEnabled, bargeInMode)));
        String command = render(properties.getUnimrcp().getRecognizeCommandTemplate(), customerLegUuid, null, null);
        eslCommandGateway.sendRawCommand(endpoint, command);
        log.info("AI UniMRCP 识别命令已提交，nodeId={}，customerLegUuid={}，bargeInEnabled={}，bargeInMode={}，costMs={}，command={}",
            nodeId, customerLegUuid, bargeInEnabled, safeBargeInMode(bargeInMode), elapsedMillis(startNanos), command);
    }

    @Override
    public void stopPlayback(Long nodeId, String customerLegUuid) {
        requireCallId(customerLegUuid);
        eslCommandGateway.sendRawCommand(endpoint(nodeId), "api uuid_break " + customerLegUuid + " all");
        log.info("AI intent stopped channel playback, nodeId={}, customerLegUuid={}", nodeId, customerLegUuid);
    }

    @Override
    public void transferToExtension(Long nodeId, String customerLegUuid, String targetExtension) {
        requireCallId(customerLegUuid);
        requireRouteTarget(targetExtension, "target extension");
        EslEndpoint endpoint = endpoint(nodeId);
        prepareForTransfer(endpoint, customerLegUuid);
        eslCommandGateway.blindTransfer(endpoint, customerLegUuid, targetExtension);
        log.info("AI intent transferred call to extension, nodeId={}, customerLegUuid={}, targetExtension={}",
            nodeId, customerLegUuid, targetExtension);
    }

    @Override
    public void transferToQueue(Long nodeId, String customerLegUuid, String queueCode) {
        requireCallId(customerLegUuid);
        requireRouteTarget(queueCode, "queue code");
        EslEndpoint endpoint = endpoint(nodeId);
        prepareForTransfer(endpoint, customerLegUuid);
        String queueName = queueCode.endsWith("@default") ? queueCode : queueCode + "@default";
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_transfer " + customerLegUuid + " 'callcenter:" + queueName + "' inline");
        log.info("AI intent transferred call to queue, nodeId={}, customerLegUuid={}, queueName={}",
            nodeId, customerLegUuid, queueName);
    }

    @Override
    public void transferToIvr(String tenantId, Long nodeId, String customerLegUuid, String flowId) {
        requireCallId(customerLegUuid);
        if (StringUtils.isBlank(flowId) || !flowId.matches("^[0-9]{1,20}$")) {
            throw new ServiceException("AI 意图的 IVR 流程 ID 不合法");
        }
        Long targetFlowId;
        try {
            targetFlowId = Long.valueOf(flowId);
        } catch (NumberFormatException exception) {
            throw new ServiceException("AI 意图的 IVR 流程 ID 不合法");
        }
        String destination = ivrDialplanQueryService.resolvePublishedStartDestination(tenantId, targetFlowId, nodeId);
        if (StringUtils.isBlank(destination)) {
            throw new ServiceException("目标 IVR 流程未发布、未启用或不适用于当前 FreeSWITCH 节点");
        }
        EslEndpoint endpoint = endpoint(nodeId);
        prepareForTransfer(endpoint, customerLegUuid);
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_setvar " + customerLegUuid + " callnexus_ivr_flow_id " + targetFlowId);
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_setvar " + customerLegUuid + " callnexus_route_type IVR");
        String context = resolveDialplanContext(endpoint, customerLegUuid);
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_transfer " + customerLegUuid + " " + destination + " XML " + context);
        log.info("AI intent transferred call to IVR, tenantId={}, nodeId={}, customerLegUuid={}, flowId={}, destination={}, context={}",
            tenantId, nodeId, customerLegUuid, targetFlowId, destination, context);
    }

    @Override
    public void hangup(Long nodeId, String customerLegUuid) {
        requireCallId(customerLegUuid);
        eslCommandGateway.hangup(endpoint(nodeId), customerLegUuid);
        log.info("AI intent hung up call, nodeId={}, customerLegUuid={}", nodeId, customerLegUuid);
    }

    @Override
    public boolean callExists(Long nodeId, String customerLegUuid) {
        requireCallId(customerLegUuid);
        return eslCommandGateway.callExists(endpoint(nodeId), customerLegUuid);
    }

    @Override
    public Map<String, String> getChannelVariables(Long nodeId, String customerLegUuid, String... names) {
        requireCallId(customerLegUuid);
        Map<String, String> result = new LinkedHashMap<>();
        if (names == null || names.length == 0) {
            return result;
        }
        EslEndpoint endpoint = endpoint(nodeId);
        long startNanos = System.nanoTime();
        for (String name : names) {
            if (StringUtils.isBlank(name) || !name.matches("^[A-Za-z0-9_:-]{1,128}$")) {
                continue;
            }
            String response = eslCommandGateway.executeApiCommandForResult(endpoint, "api uuid_getvar " + customerLegUuid + " " + name);
            String value = normalizeGetvarResponse(response);
            if (StringUtils.isNotBlank(value)) {
                result.put(name, value);
            }
        }
        log.info("AI UniMRCP 已拉取通道变量，nodeId={}，customerLegUuid={}，variables={}，costMs={}",
            nodeId, customerLegUuid, result.keySet(), elapsedMillis(startNanos));
        return result;
    }

    @Override
    public void applyVoiceTransport(Long nodeId, String customerLegUuid, VoiceTransport transport, String wsUrl) {
        requireCallId(customerLegUuid);
        VoiceTransport mode = transport == null ? VoiceTransport.HTTP : transport;
        EslEndpoint endpoint = endpoint(nodeId);
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_setvar " + customerLegUuid + " callnexus_ai_voice_transport " + mode.name());
        if (mode == VoiceTransport.WS && StringUtils.isNotBlank(wsUrl)) {
            if (!safeWsUrl(wsUrl)) {
                log.warn("AI UniMRCP WS 地址不合法，忽略下发，nodeId={}，customerLegUuid={}，wsUrl={}", nodeId, customerLegUuid, wsUrl);
                return;
            }
            eslCommandGateway.sendRawCommand(endpoint,
                "api uuid_setvar " + customerLegUuid + " callnexus_ai_voice_transport_ws_url " + wsUrl);
        }
        log.info("AI UniMRCP 已下发语音传输模式，nodeId={}，customerLegUuid={}，transport={}，wsUrl={}",
            nodeId, customerLegUuid, mode.name(), StringUtils.isBlank(wsUrl) ? "-" : wsUrl);
    }

    private boolean safeWsUrl(String value) {
        return value != null
            && value.length() <= 256
            && !value.contains("\r")
            && !value.contains("\n")
            && !value.contains(" ")
            && !value.contains("\"")
            && !value.contains("'")
            && (value.startsWith("ws://") || value.startsWith("wss://"));
    }

    private void prepareForTransfer(EslEndpoint endpoint, String customerLegUuid) {
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_setvar " + customerLegUuid + " callnexus_ai_active false");
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_setvar " + customerLegUuid + " callnexus_satisfaction_skip true");
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_setvar " + customerLegUuid + " hangup_after_bridge true");
        eslCommandGateway.sendRawCommand(endpoint,
            "api uuid_setvar " + customerLegUuid + " park_after_bridge false");
    }

    private void requireRouteTarget(String value, String field) {
        if (StringUtils.isBlank(value) || !value.matches("^[A-Za-z0-9_.@+-]{1,64}$")) {
            throw new ServiceException("Invalid AI intent " + field);
        }
    }

    private String resolveDialplanContext(EslEndpoint endpoint, String customerLegUuid) {
        String response = eslCommandGateway.executeApiCommandForResult(
            endpoint, "api uuid_getvar " + customerLegUuid + " context");
        String context = normalizeGetvarResponse(response);
        return StringUtils.isNotBlank(context) && context.matches("^[A-Za-z0-9_.-]{1,64}$")
            ? context : "public";
    }

    private EslEndpoint endpoint(Long nodeId) {
        FreeSwitchNodeConnectionResponse node = nodeQueryService.getEnabledConnection(nodeId);
        return new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
    }

    private String render(String template, String uuid, String text, String voice) {
        if (template == null || template.isBlank()) {
            throw new ServiceException("AI UniMRCP 命令模板未配置");
        }
        String resolvedVoice = StringUtils.isBlank(voice) ? properties.getUnimrcp().getVoice() : voice;
        return template
            .replace("{uuid}", uuid)
            .replace("{profile}", safe(properties.getUnimrcp().getProfile()))
            .replace("{voice}", safe(resolvedVoice))
            .replace("{detectScript}", safeScript(properties.getUnimrcp().getDetectScript()))
            .replace("{grammar}", safeGrammar(properties.getUnimrcp().getGrammar()))
            .replace("{text}", safeText(text));
    }

    private String safe(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9._:/#+=-]{1,256}$")) {
            throw new ServiceException("AI UniMRCP 配置参数不合法");
        }
        return value;
    }

    /**
     * 用于 uuid_setvar 值的白名单校验：仅允许安全字符，防止命令注入。
     * turnId 可能是纯数字（sequenceNo）或形如 turn-2 的字符串。
     */
    private String safeVar(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9._:#+=-]{1,128}$")) {
            throw new ServiceException("AI UniMRCP 轮次变量不合法");
        }
        return value;
    }

    private String safeGrammar(String value) {
        if (StringUtils.isBlank(value) || value.length() > 512 || value.contains("\r") || value.contains("\n")
            || value.contains("\"") || value.contains("'")) {
            throw new ServiceException("AI UniMRCP 识别语法配置不合法");
        }
        return value;
    }

    private String safeBargeInMode(String value) {
        String mode = StringUtils.blankToDefault(value, "STANDARD").trim().toUpperCase();
        return switch (mode) {
            case "SENSITIVE", "STANDARD", "NOISY" -> mode;
            default -> "STANDARD";
        };
    }

    private String withSensitivity(String grammar, boolean bargeInEnabled, String mode) {
        if (!bargeInEnabled || StringUtils.isBlank(grammar) || grammar.contains("sensitivity-level=")) {
            return grammar;
        }
        String sensitivity = switch (safeBargeInMode(mode)) {
            case "SENSITIVE" -> "0.75";
            case "NOISY" -> "0.35";
            default -> "0.55";
        };
        return grammar.startsWith("{")
            ? "{sensitivity-level=" + sensitivity + "," + grammar.substring(1)
            : "{sensitivity-level=" + sensitivity + "}" + grammar;
    }

    private String safeScript(String value) {
        if (value == null || !value.matches("^/[A-Za-z0-9._/+-]{1,256}\\.lua$")) {
            throw new ServiceException("AI UniMRCP 检测脚本路径不合法");
        }
        return value;
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .replace(" ", "\\s")
            .replace("\"", "");
    }

    private String normalizeGetvarResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return null;
        }
        String value = response.trim();
        if ("_undef_".equalsIgnoreCase(value) || "null".equalsIgnoreCase(value) || value.startsWith("-ERR")) {
            return null;
        }
        return value;
    }

    private void requireCallId(String callId) {
        if (callId == null || !callId.matches("^[0-9a-fA-F-]{36}$")) {
            throw new ServiceException("通话 ID 不合法");
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
