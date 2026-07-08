package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiKnowledgeProperties;
import org.dromara.ai.service.AiRealtimeTelephonyGateway;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
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
    private final FreeSwitchEslCommandGateway eslCommandGateway;

    @Override
    public void speak(Long nodeId, String customerLegUuid, String text, String voice) {
        requireCallId(customerLegUuid);
        String command = render(properties.getUnimrcp().getSpeakCommandTemplate(), customerLegUuid, text, voice);
        long startNanos = System.nanoTime();
        eslCommandGateway.sendRawCommand(endpoint(nodeId), command);
        log.info("AI UniMRCP 播报命令已提交，nodeId={}，customerLegUuid={}，voice={}，textLength={}，costMs={}，command={}",
            nodeId, customerLegUuid, voice, text == null ? 0 : text.length(), elapsedMillis(startNanos), command);
    }

    @Override
    public void recognize(Long nodeId, String customerLegUuid) {
        requireCallId(customerLegUuid);
        long startNanos = System.nanoTime();
        EslEndpoint endpoint = endpoint(nodeId);
        eslCommandGateway.sendRawCommand(endpoint, "api uuid_setvar " + customerLegUuid + " fire_asr_events true");
        eslCommandGateway.sendRawCommand(endpoint, "api uuid_setvar " + customerLegUuid + " callnexus_unimrcp_profile "
            + safe(properties.getUnimrcp().getProfile()));
        eslCommandGateway.sendRawCommand(endpoint, "api uuid_setvar " + customerLegUuid + " callnexus_unimrcp_grammar "
            + safeGrammar(properties.getUnimrcp().getGrammar()));
        String command = render(properties.getUnimrcp().getRecognizeCommandTemplate(), customerLegUuid, null, null);
        eslCommandGateway.sendRawCommand(endpoint, command);
        log.info("AI UniMRCP 识别命令已提交，nodeId={}，customerLegUuid={}，costMs={}，command={}",
            nodeId, customerLegUuid, elapsedMillis(startNanos), command);
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

    private String safeGrammar(String value) {
        if (StringUtils.isBlank(value) || value.length() > 512 || value.contains("\r") || value.contains("\n")
            || value.contains("\"") || value.contains("'")) {
            throw new ServiceException("AI UniMRCP 识别语法配置不合法");
        }
        return value;
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
