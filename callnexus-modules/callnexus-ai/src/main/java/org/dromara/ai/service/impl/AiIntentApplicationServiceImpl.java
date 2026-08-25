package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.AiIntentRecognitionRequest;
import org.dromara.ai.domain.request.AiIntentRequest;
import org.dromara.ai.domain.request.AiIntentUtteranceRequest;
import org.dromara.ai.domain.response.AiIntentRecognitionResponse;
import org.dromara.ai.domain.response.AiIntentResponse;
import org.dromara.ai.domain.response.AiIntentUtteranceResponse;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.AiIntentApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiIntentApplicationServiceImpl implements AiIntentApplicationService {
    private static final Set<String> INTENT_TYPES = Set.of("CONVERSATION", "CONTROL", "ROUTING", "BUSINESS");
    private static final Set<String> ACTION_TYPES = Set.of(
        "NONE", "CHAT_REPLY", "REPEAT_LAST_REPLY", "STOP_PLAYBACK",
        "TRANSFER_QUEUE", "TRANSFER_EXTENSION", "TRANSFER_IVR", "TRANSFER_ONLINE_SERVICE", "CREATE_TICKET", "END_CALL", "KNOWLEDGE_QUERY");
    private static final Set<String> UTTERANCE_TYPES = Set.of("POSITIVE", "NEGATIVE");

    private final AiIntentMapper intentMapper;
    private final AiIntentUtteranceMapper utteranceMapper;
    private final AiAgentIntentMapper agentIntentMapper;
    private final AiIntentRecognitionLogMapper recognitionLogMapper;
    private final AiAgentMapper agentMapper;
    private final AiModelMapper modelMapper;
    private final AiModelProviderMapper providerMapper;
    private final ChatProviderRegistry chatProviderRegistry;

    @Override
    public List<AiIntentResponse> intents() {
        return intentMapper.selectList(new LambdaQueryWrapper<AiIntent>()
                .orderByAsc(AiIntent::getPriority).orderByAsc(AiIntent::getIntentCode))
            .stream().map(this::response).toList();
    }

    @Override
    public AiIntentResponse intent(Long id) {
        return response(requireIntent(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createIntent(AiIntentRequest request) {
        ensureCode(request.getIntentCode(), null);
        AiIntent intent = new AiIntent();
        fill(intent, request);
        intentMapper.insert(intent);
        saveUtterances(intent.getId(), request.getUtterances());
        saveBindings(intent.getId(), request.getAgentIds());
        return intent.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateIntent(Long id, AiIntentRequest request) {
        AiIntent intent = requireIntent(id);
        ensureCode(request.getIntentCode(), id);
        fill(intent, request);
        intentMapper.updateById(intent);
        String tenantId = TenantHelper.getTenantId();
        utteranceMapper.deletePhysicallyByIntentId(tenantId, id);
        agentIntentMapper.deletePhysicallyByIntentId(tenantId, id);
        saveUtterances(id, request.getUtterances());
        saveBindings(id, request.getAgentIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteIntent(Long id) {
        requireIntent(id);
        String tenantId = TenantHelper.getTenantId();
        utteranceMapper.deletePhysicallyByIntentId(tenantId, id);
        agentIntentMapper.deletePhysicallyByIntentId(tenantId, id);
        intentMapper.deleteById(id);
    }

    @Override
    public AiIntentRecognitionResponse recognize(AiIntentRecognitionRequest request) {
        long started = System.currentTimeMillis();
        AiAgent agent = agentMapper.selectById(request.getAgentId());
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) {
            throw new ServiceException("AI 助手不存在或未启用");
        }
        String normalized = normalize(request.getText());
        List<AiIntent> candidates = boundIntents(agent.getId());
        if (candidates.isEmpty()) {
            return persist(request, normalized, unmatched("NONE", "当前 AI 助手未绑定可用意图"), started, null);
        }
        Map<Long, List<AiIntentUtterance>> utteranceCatalog = utterancesByIntent(candidates);

        Set<Long> negativeMatches = new HashSet<>();
        for (AiIntent intent : candidates) {
            List<AiIntentUtterance> utterances = utteranceCatalog.getOrDefault(intent.getId(), List.of());
            boolean negative = utterances.stream().anyMatch(item ->
                "NEGATIVE".equals(item.getUtteranceType()) && normalized.equals(item.getNormalizedText()));
            if (negative) {
                negativeMatches.add(intent.getId());
                continue;
            }
            boolean positive = utterances.stream().anyMatch(item ->
                "POSITIVE".equals(item.getUtteranceType()) && normalized.equals(item.getNormalizedText()));
            if (positive) {
                return persist(request, normalized, matched(intent, BigDecimal.ONE, "EXACT",
                    "与正例话术精确匹配", null), started, null);
            }
        }

        List<AiIntent> modelCandidates = candidates.stream()
            .filter(item -> !negativeMatches.contains(item.getId())).toList();
        if (modelCandidates.isEmpty()) {
            return persist(request, normalized, unmatched("NONE", "文本命中反例话术，已排除全部候选意图"), started, null);
        }
        if (Boolean.FALSE.equals(request.getModelFallbackEnabled())) {
            return persist(request, normalized, unmatched("LOCAL",
                "本地话术未命中，实时模式跳过模型分类"), started, null);
        }
        try {
            AiModel model = modelMapper.selectById(agent.getChatModelId());
            if (model == null || !"CHAT".equals(model.getCapability()) || !Boolean.TRUE.equals(model.getEnabled())) {
                throw new ServiceException("AI 助手的 Chat 模型不可用");
            }
            AiModelProvider provider = providerMapper.selectById(model.getProviderId());
            if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
                throw new ServiceException("AI 助手的模型服务商不可用");
            }
            ChatResult result = chatProviderRegistry.get(provider.getProviderType()).chat(new ChatRequest(
                provider, model, classificationPrompt(modelCandidates, utteranceCatalog, request.getText()),
                new BigDecimal("0.10"), 300));
            AiIntentRecognitionResponse recognition = parseModelResult(result.content(), modelCandidates);
            return persist(request, normalized, recognition, started, model.getId());
        } catch (Exception exception) {
            log.warn("AI 意图识别模型分类失败，agentId={}，error={}", request.getAgentId(), exception.getMessage());
            AiIntentRecognitionResponse failed = unmatched("MODEL", "模型分类失败：" + limit(exception.getMessage(), 800));
            failed.setRawResponse(null);
            return persist(request, normalized, failed, started, agent.getChatModelId(), "FAILED");
        }
    }

    private List<ChatMessage> classificationPrompt(List<AiIntent> candidates,
                                                   Map<Long, List<AiIntentUtterance>> utteranceCatalog,
                                                   String text) {
        StringBuilder catalog = new StringBuilder();
        for (AiIntent intent : candidates) {
            List<AiIntentUtterance> examples = utteranceCatalog.getOrDefault(intent.getId(), List.of());
            catalog.append("\n- code=").append(intent.getIntentCode())
                .append(", name=").append(intent.getIntentName())
                .append(", description=").append(Objects.toString(intent.getDescription(), ""))
                .append(", threshold=").append(intent.getConfidenceThreshold())
                .append("\n  positive=").append(examples.stream().filter(it -> "POSITIVE".equals(it.getUtteranceType()))
                    .map(AiIntentUtterance::getUtteranceText).limit(12).toList())
                .append("\n  negative=").append(examples.stream().filter(it -> "NEGATIVE".equals(it.getUtteranceType()))
                    .map(AiIntentUtterance::getUtteranceText).limit(12).toList());
        }
        String system = """
            你是意图分类器，只能从候选意图中选择一个，不能执行任何业务动作。
            如果文本不属于任何候选意图，intentCode 返回 null。
            只返回 JSON，不要使用 Markdown：
            {"intentCode":"编码或null","confidence":0到1的小数,"reason":"简短判断依据"}
            """;
        return List.of(
            new ChatMessage("system", system + "\n候选意图：" + catalog),
            new ChatMessage("user", text));
    }

    private AiIntentRecognitionResponse parseModelResult(String raw, List<AiIntent> candidates) throws Exception {
        String json = stripCodeFence(raw);
        JsonNode root = JsonUtils.getObjectMapper().readTree(json);
        String code = root.path("intentCode").isNull() ? null : root.path("intentCode").asText(null);
        BigDecimal confidence = BigDecimal.valueOf(root.path("confidence").asDouble(0D));
        String reason = root.path("reason").asText("模型未提供判断依据");
        if (StringUtils.isBlank(code)) {
            AiIntentRecognitionResponse result = unmatched("MODEL", reason);
            result.setConfidence(confidence);
            result.setRawResponse(raw);
            return result;
        }
        AiIntent intent = candidates.stream().filter(item -> item.getIntentCode().equalsIgnoreCase(code)).findFirst().orElse(null);
        if (intent == null) {
            AiIntentRecognitionResponse result = unmatched("MODEL", "模型返回了候选列表之外的意图：" + code);
            result.setConfidence(confidence);
            result.setRawResponse(raw);
            return result;
        }
        if (confidence.compareTo(intent.getConfidenceThreshold()) < 0) {
            AiIntentRecognitionResponse result = unmatched("MODEL",
                "置信度 " + confidence + " 低于意图阈值 " + intent.getConfidenceThreshold());
            result.setConfidence(confidence);
            result.setRawResponse(raw);
            return result;
        }
        return matched(intent, confidence, "MODEL", reason, raw);
    }

    private AiIntentRecognitionResponse persist(AiIntentRecognitionRequest request, String normalized,
                                                AiIntentRecognitionResponse result, long started, Long modelId) {
        return persist(request, normalized, result, started, modelId, result.isMatched() ? "MATCHED" : "UNMATCHED");
    }

    private AiIntentRecognitionResponse persist(AiIntentRecognitionRequest request, String normalized,
                                                AiIntentRecognitionResponse result, long started, Long modelId,
                                                String status) {
        result.setLatencyMs(System.currentTimeMillis() - started);
        AiIntentRecognitionLog value = new AiIntentRecognitionLog();
        value.setAgentId(request.getAgentId());
        value.setInputText(request.getText());
        value.setNormalizedText(normalized);
        value.setIntentId(result.getIntentId());
        value.setIntentCode(result.getIntentCode());
        value.setIntentName(result.getIntentName());
        value.setConfidence(result.getConfidence());
        value.setMatchMethod(result.getMatchMethod());
        value.setRecognitionStatus(status);
        value.setReason(result.getReason());
        value.setLatencyMs(result.getLatencyMs());
        value.setModelId(modelId);
        value.setRawResponse(result.getRawResponse());
        recognitionLogMapper.insert(value);
        return result;
    }

    private AiIntentRecognitionResponse matched(AiIntent intent, BigDecimal confidence, String method,
                                                String reason, String raw) {
        AiIntentRecognitionResponse value = new AiIntentRecognitionResponse();
        value.setMatched(true);
        value.setIntentId(intent.getId());
        value.setIntentCode(intent.getIntentCode());
        value.setIntentName(intent.getIntentName());
        value.setIntentType(intent.getIntentType());
        value.setActionType(intent.getActionType());
        value.setActionConfigJson(intent.getActionConfigJson());
        value.setResponseTemplate(intent.getResponseTemplate());
        value.setConfirmationRequired(intent.getConfirmationRequired());
        value.setConfidence(confidence);
        value.setMatchMethod(method);
        value.setReason(reason);
        value.setRawResponse(raw);
        return value;
    }

    private AiIntentRecognitionResponse unmatched(String method, String reason) {
        AiIntentRecognitionResponse value = new AiIntentRecognitionResponse();
        value.setMatched(false);
        value.setConfidence(BigDecimal.ZERO);
        value.setMatchMethod(method);
        value.setReason(reason);
        return value;
    }

    private List<AiIntent> boundIntents(Long agentId) {
        List<AiAgentIntent> bindings = agentIntentMapper.selectList(new LambdaQueryWrapper<AiAgentIntent>()
            .eq(AiAgentIntent::getAgentId, agentId).eq(AiAgentIntent::getEnabled, true)
            .orderByAsc(AiAgentIntent::getPriority));
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, AiIntent> intents = intentMapper.selectBatchIds(bindings.stream()
                .map(AiAgentIntent::getIntentId).distinct().toList())
            .stream()
            .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
            .collect(Collectors.toMap(AiIntent::getId, item -> item));
        return bindings.stream()
            .map(binding -> intents.get(binding.getIntentId()))
            .filter(Objects::nonNull)
            .toList();
    }

    private List<AiIntentUtterance> utterances(Long intentId) {
        return utteranceMapper.selectList(new LambdaQueryWrapper<AiIntentUtterance>()
            .eq(AiIntentUtterance::getIntentId, intentId).orderByAsc(AiIntentUtterance::getSortOrder));
    }

    private Map<Long, List<AiIntentUtterance>> utterancesByIntent(List<AiIntent> intents) {
        if (intents.isEmpty()) {
            return Map.of();
        }
        return utteranceMapper.selectList(new LambdaQueryWrapper<AiIntentUtterance>()
                .in(AiIntentUtterance::getIntentId, intents.stream().map(AiIntent::getId).toList())
                .orderByAsc(AiIntentUtterance::getIntentId)
                .orderByAsc(AiIntentUtterance::getSortOrder))
            .stream()
            .collect(Collectors.groupingBy(
                AiIntentUtterance::getIntentId,
                LinkedHashMap::new,
                Collectors.toList()));
    }

    private void fill(AiIntent intent, AiIntentRequest request) {
        String type = upper(request.getIntentType());
        String action = upper(request.getActionType());
        if (!INTENT_TYPES.contains(type)) throw new ServiceException("未知的意图类型：" + type);
        if (!ACTION_TYPES.contains(action)) throw new ServiceException("未知的受控动作类型：" + action);
        validateActionConfig(action, request.getActionConfigJson());
        intent.setIntentCode(upper(request.getIntentCode()));
        intent.setIntentName(request.getIntentName().trim());
        intent.setIntentType(type);
        intent.setDescription(request.getDescription());
        intent.setActionType(action);
        intent.setActionConfigJson(StringUtils.isBlank(request.getActionConfigJson()) ? null : request.getActionConfigJson().trim());
        intent.setResponseTemplate(request.getResponseTemplate());
        intent.setConfidenceThreshold(request.getConfidenceThreshold() == null ? new BigDecimal("0.80") : request.getConfidenceThreshold());
        intent.setPriority(request.getPriority() == null ? 100 : request.getPriority());
        intent.setConfirmationRequired(Boolean.TRUE.equals(request.getConfirmationRequired()));
        intent.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private void validateActionConfig(String action, String actionConfigJson) {
        JsonNode config = null;
        if (StringUtils.isNotBlank(actionConfigJson)) {
            try {
                config = JsonUtils.getObjectMapper().readTree(actionConfigJson);
            } catch (Exception exception) {
                throw new ServiceException("动作参数格式不正确");
            }
        }
        if ("TRANSFER_QUEUE".equals(action)
            && (config == null || StringUtils.isBlank(config.path("queueCode").asText()))) {
            throw new ServiceException("转技能组动作必须选择目标技能组");
        }
        if ("TRANSFER_EXTENSION".equals(action)
            && (config == null || StringUtils.isBlank(config.path("extension").asText()))) {
            throw new ServiceException("转分机动作必须选择目标分机");
        }
        if ("TRANSFER_IVR".equals(action)
            && (config == null || !config.path("ivrFlowId").asText("").matches("^[0-9]{1,20}$"))) {
            throw new ServiceException("转 IVR 动作必须选择目标 IVR 流程");
        }
        if ("TRANSFER_ONLINE_SERVICE".equals(action)
            && (config == null || !config.path("skillGroupId").asText("").matches("^[0-9]{1,20}$"))) {
            throw new ServiceException("转在线客服动作必须选择目标在线客服技能组");
        }
        if ("CREATE_TICKET".equals(action)
            && (config == null || !config.path("templateId").asText("").matches("^[0-9]{1,20}$"))) {
            throw new ServiceException("创建工单动作必须选择工单模板");
        }
    }

    private void saveUtterances(Long intentId, List<AiIntentUtteranceRequest> requests) {
        if (requests == null) return;
        Set<String> unique = new HashSet<>();
        int order = 1;
        for (AiIntentUtteranceRequest request : requests) {
            String type = upper(request.getUtteranceType());
            if (!UTTERANCE_TYPES.contains(type)) throw new ServiceException("未知的话术类型：" + type);
            String normalized = normalize(request.getUtteranceText());
            if (normalized.isBlank()) continue;
            String key = type + ":" + normalized;
            if (!unique.add(key)) continue;
            AiIntentUtterance value = new AiIntentUtterance();
            value.setIntentId(intentId);
            value.setUtteranceType(type);
            value.setUtteranceText(request.getUtteranceText().trim());
            value.setNormalizedText(normalized);
            value.setTextHash(sha256(normalized));
            value.setSortOrder(order++);
            utteranceMapper.insert(value);
        }
    }

    private void saveBindings(Long intentId, List<Long> agentIds) {
        if (agentIds == null) return;
        int priority = 1;
        for (Long agentId : agentIds.stream().filter(Objects::nonNull).distinct().toList()) {
            if (agentMapper.selectById(agentId) == null) throw new ServiceException("绑定的 AI 助手不存在：" + agentId);
            AiAgentIntent value = new AiAgentIntent();
            value.setAgentId(agentId);
            value.setIntentId(intentId);
            value.setPriority(priority++);
            value.setEnabled(true);
            agentIntentMapper.insert(value);
        }
    }

    private AiIntentResponse response(AiIntent intent) {
        AiIntentResponse value = new AiIntentResponse();
        value.setId(intent.getId());
        value.setIntentCode(intent.getIntentCode());
        value.setIntentName(intent.getIntentName());
        value.setIntentType(intent.getIntentType());
        value.setDescription(intent.getDescription());
        value.setActionType(intent.getActionType());
        value.setActionConfigJson(intent.getActionConfigJson());
        value.setResponseTemplate(intent.getResponseTemplate());
        value.setConfidenceThreshold(intent.getConfidenceThreshold());
        value.setPriority(intent.getPriority());
        value.setConfirmationRequired(intent.getConfirmationRequired());
        value.setEnabled(intent.getEnabled());
        value.setVersion(intent.getVersion());
        value.setUtterances(utterances(intent.getId()).stream().map(item -> {
            AiIntentUtteranceResponse response = new AiIntentUtteranceResponse();
            response.setId(item.getId());
            response.setUtteranceType(item.getUtteranceType());
            response.setUtteranceText(item.getUtteranceText());
            return response;
        }).toList());
        List<AiAgentIntent> bindings = agentIntentMapper.selectList(new LambdaQueryWrapper<AiAgentIntent>()
            .eq(AiAgentIntent::getIntentId, intent.getId()).orderByAsc(AiAgentIntent::getPriority));
        value.setAgentIds(bindings.stream().map(AiAgentIntent::getAgentId).toList());
        Map<Long, String> names = agentMapper.selectBatchIds(value.getAgentIds()).stream()
            .collect(Collectors.toMap(AiAgent::getId, AiAgent::getAgentName));
        value.setAgentNames(value.getAgentIds().stream().map(id -> names.getOrDefault(id, String.valueOf(id))).toList());
        return value;
    }

    private AiIntent requireIntent(Long id) {
        AiIntent value = intentMapper.selectById(id);
        if (value == null) throw new ServiceException("AI 意图不存在");
        return value;
    }

    private void ensureCode(String code, Long excludeId) {
        long count = intentMapper.selectCount(new LambdaQueryWrapper<AiIntent>()
            .eq(AiIntent::getIntentCode, upper(code))
            .ne(excludeId != null, AiIntent::getId, excludeId));
        if (count > 0) throw new ServiceException("意图编码已存在");
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算话术摘要", exception);
        }
    }

    private String stripCodeFence(String value) {
        String text = Objects.toString(value, "").trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) text = text.substring(firstLine + 1, lastFence).trim();
        }
        return text;
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String limit(String value, int max) {
        String text = Objects.toString(value, "未知错误");
        return text.length() <= max ? text : text.substring(0, max);
    }
}
