package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.AiTicketBusinessContextProvider;
import org.dromara.ai.service.AiTicketPromptProtocol;
import org.dromara.ai.service.AiAgentAssistStreamService;
import org.dromara.ai.domain.response.AiTicketDraftResponse;
import org.dromara.ai.service.model.AiTicketModelOutput;
import org.dromara.ai.service.model.AiTicketTemplateContext;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.dromara.common.tenant.helper.TenantHelper;

import java.math.BigDecimal;
import java.util.*;

@Component
@RequiredArgsConstructor
public class AiTicketDraftGenerator {
    private final AiTicketPolicyMapper policyMapper;
    private final AiTicketPromptVersionMapper promptMapper;
    private final AiTicketDraftMapper draftMapper;
    private final AiAgentMapper agentMapper;
    private final AiModelMapper modelMapper;
    private final AiModelProviderMapper providerMapper;
    private final AiCallRecordingSourceMapper callSourceMapper;
    private final AiCallTranscriptMapper transcriptMapper;
    private final AiCallTranscriptSegmentMapper segmentMapper;
    private final AiRealtimeCallSessionMapper realtimeSessionMapper;
    private final AiIntentRecognitionLogMapper intentLogMapper;
    private final ChatProviderRegistry chatRegistry;
    private final AiTicketPromptProtocol promptProtocol;
    private final AiTicketAutoCreationService autoCreationService;
    private final AiAgentAssistStreamService assistStreamService;
    private final AiTicketDraftAuditMapper auditMapper;
    private final ObjectProvider<AiTicketBusinessContextProvider> contextProviders;

    @Transactional(rollbackFor = Exception.class)
    public void generate(AiTicketDraftTask task) {
        AiTicketPolicy policy = require(policyMapper.selectById(task.getPolicyId()), "自动工单策略不存在");
        if (!Boolean.TRUE.equals(policy.getEnabled())) throw new SkipGenerationException("自动工单策略已停用");
        AiAgent agent = require(agentMapper.selectById(task.getAiAgentId()), "AI 助手不存在");
        AiCallRecordingSource source = source(task);
        AiCallTranscript transcript = transcript(task);
        if (StringUtils.isBlank(transcript.getFullText())) throw new SkipGenerationException("本次通话没有可用的转写文本");
        AiTicketBusinessContextProvider contextProvider = contextProviders.getIfAvailable();
        if (contextProvider == null) throw new ServiceException("工单业务上下文提供器未加载");
        if (contextProvider.hasFormalTicket(task.getBusinessCallId())) {
            throw new SkipGenerationException("本次通话已经存在正式工单");
        }
        String callerNumber = callerNumber(source);
        AiTicketTemplateContext template = contextProvider.load(policy.getTicketTemplateId(), callerNumber);
        AiTicketPromptVersion promptVersion = activePrompt(policy);
        String promptContent = promptVersion == null ? AiTicketPromptProtocol.DEFAULT_PROMPT : promptVersion.getPromptContent();
        String conversation = conversation(transcript);
        IntentInfo intent = intent(task.getBusinessCallId());
        enforceIntentPolicy(policy, intent);
        Map<String, Object> defaults = readMap(policy.getDefaultValuesJson());
        String compiled = promptProtocol.compile(promptContent, Map.of(
            "agentName", StringUtils.blankToDefault(agent.getAgentName(), "AI助手"),
            "conversation", conversation,
            "intent", intent.displayName(),
            "customerProfile", StringUtils.blankToDefault(template.customerProfile(), "未识别客户"),
            "ticketTemplateSchema", templateSchema(template),
            "policyDefaults", JsonUtils.toJsonString(defaults),
            "callContext", callContext(source, task)
        ));
        AiTicketModelOutput output = invoke(agent, compiled);
        if (!output.shouldCreate()) throw new SkipGenerationException("模型判断本次通话无需创建工单");
        ValidatedOutput validated = validateOutput(output, template, defaults);
        if (!validated.missingFields().isEmpty() && "REJECT_DRAFT".equals(policy.getMissingRequiredAction())
            && !"AUTO_CREATE".equals(policy.getCreationMode())) {
            throw new SkipGenerationException("工单必填字段缺失：" + String.join(",", validated.missingFields()));
        }
        AiTicketDraft draft = new AiTicketDraft();
        draft.setPolicyId(policy.getId());
        draft.setAiAgentId(agent.getId());
        draft.setSourceCallId(task.getBusinessCallId());
        draft.setCustomerId(template.customerId());
        draft.setCallerNumber(callerNumber);
        draft.setTicketTemplateId(policy.getTicketTemplateId());
        draft.setPromptVersionId(promptVersion == null ? null : promptVersion.getId());
        draft.setConfidence(validated.confidence());
        draft.setStatus(validated.confidence().compareTo(policy.getConfidenceThreshold()) < 0
            ? "LOW_CONFIDENCE" : "PENDING_REVIEW");
        draft.setTitle(limit(output.title(), 256));
        draft.setSummary(limit(output.summary(), 4000));
        draft.setFormDataJson(JsonUtils.toJsonString(validated.formData()));
        draft.setMissingFieldsJson(JsonUtils.toJsonString(validated.missingFields()));
        draft.setEvidenceJson(JsonUtils.toJsonString(output.evidence()));
        draft.setVersion(0);
        AiTicketDraft existing = draftMapper.selectOne(new LambdaQueryWrapper<AiTicketDraft>()
            .eq(AiTicketDraft::getPolicyId, policy.getId())
            .eq(AiTicketDraft::getSourceCallId, task.getBusinessCallId()).last("LIMIT 1"));
        if (existing != null) {
            if (!List.of("GENERATING", "PENDING_REVIEW", "LOW_CONFIDENCE", "FAILED").contains(existing.getStatus())) {
                throw new SkipGenerationException("本次通话已经生成过工单草稿");
            }
            preserveManualEdits(existing, draft, template);
            draft.setId(existing.getId());
            draft.setVersion(existing.getVersion());
            if (draftMapper.updateById(draft) != 1) {
                throw new ServiceException("工单草稿已被坐席修改，将使用最新版本重新生成");
            }
            AiTicketDraft saved = draftMapper.selectById(draft.getId());
            audit(saved.getId(), "REALTIME_UPDATE".equals(task.getTriggerType()) ? "REALTIME_UPDATE" : "REGENERATE_UPDATE",
                existing, saved, "按当前通话内容更新工单草稿");
            complete(policy, task, saved);
            return;
        }
        try {
            draftMapper.insert(draft);
            audit(draft.getId(), "REALTIME_UPDATE".equals(task.getTriggerType()) ? "REALTIME_CREATE" : "GENERATE",
                null, draft, "根据通话内容生成工单草稿");
            complete(policy, task, draft);
        } catch (DuplicateKeyException exception) {
            throw new SkipGenerationException("本次通话已经生成过工单草稿");
        }
    }

    private void preserveManualEdits(AiTicketDraft existing, AiTicketDraft generated,
                                     AiTicketTemplateContext template) {
        ManualOverrides overrides = manualOverrides(existing.getId());
        if (overrides.title()) generated.setTitle(existing.getTitle());
        if (overrides.summary()) generated.setSummary(existing.getSummary());
        if (overrides.fieldCodes().isEmpty()) return;

        Map<String, Object> generatedValues = readMap(generated.getFormDataJson());
        Map<String, Object> existingValues = readMap(existing.getFormDataJson());
        for (String code : overrides.fieldCodes()) {
            if (existingValues.containsKey(code)) generatedValues.put(code, existingValues.get(code));
            else generatedValues.remove(code);
        }
        Set<String> missing = new LinkedHashSet<>(readList(generated.getMissingFieldsJson()));
        missing.removeIf(code -> !empty(generatedValues.get(code)));
        template.fields().stream().filter(AiTicketTemplateContext.Field::required)
            .filter(field -> empty(generatedValues.get(field.code())))
            .map(AiTicketTemplateContext.Field::code)
            .forEach(missing::add);
        generated.setFormDataJson(JsonUtils.toJsonString(generatedValues));
        generated.setMissingFieldsJson(JsonUtils.toJsonString(missing));
    }

    private ManualOverrides manualOverrides(Long draftId) {
        boolean title = false;
        boolean summary = false;
        Set<String> fieldCodes = new LinkedHashSet<>();
        List<AiTicketDraftAudit> edits = auditMapper.selectList(new LambdaQueryWrapper<AiTicketDraftAudit>()
            .eq(AiTicketDraftAudit::getDraftId, draftId)
            .eq(AiTicketDraftAudit::getActionType, "EDIT")
            .orderByAsc(AiTicketDraftAudit::getId));
        for (AiTicketDraftAudit edit : edits) {
            Map<String, Object> before = readMap(edit.getBeforeDataJson());
            Map<String, Object> after = readMap(edit.getAfterDataJson());
            title |= !Objects.equals(before.get("title"), after.get("title"));
            summary |= !Objects.equals(before.get("summary"), after.get("summary"));
            Map<String, Object> beforeValues = nestedFormData(before.get("formDataJson"));
            Map<String, Object> afterValues = nestedFormData(after.get("formDataJson"));
            Set<String> codes = new LinkedHashSet<>(beforeValues.keySet());
            codes.addAll(afterValues.keySet());
            codes.stream().filter(code -> !Objects.equals(beforeValues.get(code), afterValues.get(code)))
                .forEach(fieldCodes::add);
        }
        return new ManualOverrides(title, summary, fieldCodes);
    }

    private Map<String, Object> nestedFormData(Object value) {
        return value instanceof String json ? readMap(json) : new LinkedHashMap<>();
    }

    private void complete(AiTicketPolicy policy, AiTicketDraftTask task, AiTicketDraft draft) {
        if ("CALL_ENDED".equals(task.getTriggerType())) {
            autoCreationService.attempt(policy, draft);
        }
        AiTicketDraft saved = draftMapper.selectById(draft.getId());
        publishAfterCommit(task.getBusinessCallId(), response(saved));
    }

    private void publishAfterCommit(String businessCallId, AiTicketDraftResponse response) {
        String tenantId = TenantHelper.getTenantId();
        Runnable publish = () -> assistStreamService.publishTicketDraft(tenantId, businessCallId, response);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    private AiTicketDraftResponse response(AiTicketDraft value) {
        AiTicketDraftResponse response = new AiTicketDraftResponse();
        response.setId(value.getId());
        response.setAiAgentId(value.getAiAgentId());
        response.setSourceCallId(value.getSourceCallId());
        response.setCustomerId(value.getCustomerId());
        response.setCallerNumber(value.getCallerNumber());
        response.setTicketTemplateId(value.getTicketTemplateId());
        response.setStatus(value.getStatus());
        response.setConfidence(value.getConfidence());
        response.setTitle(value.getTitle());
        response.setSummary(value.getSummary());
        response.setFormData(readMap(value.getFormDataJson()));
        response.setMissingFields(readList(value.getMissingFieldsJson()));
        response.setFailureReason(value.getFailureReason());
        response.setFormalTicketId(value.getFormalTicketId());
        response.setVersion(value.getVersion());
        return response;
    }

    private void audit(Long draftId, String action, Object before, Object after, String remark) {
        AiTicketDraftAudit audit = new AiTicketDraftAudit();
        audit.setDraftId(draftId);
        audit.setActionType(action);
        audit.setBeforeDataJson(JsonUtils.toJsonString(before));
        audit.setAfterDataJson(JsonUtils.toJsonString(after));
        audit.setRemark(remark);
        auditMapper.insert(audit);
    }

    private AiTicketModelOutput invoke(AiAgent agent, String prompt) {
        AiModel model = require(modelMapper.selectById(agent.getChatModelId()), "AI 助手未配置聊天模型");
        if (!Boolean.TRUE.equals(model.getEnabled()) || !"CHAT".equals(model.getCapability())) {
            throw new ServiceException("AI 助手聊天模型不可用");
        }
        AiModelProvider provider = require(providerMapper.selectById(model.getProviderId()), "AI 模型服务商不存在");
        ChatResult result = chatRegistry.get(provider.getProviderType()).chat(new ChatRequest(provider, model,
            List.of(new ChatMessage("system", prompt), new ChatMessage("user", "请生成本次通话的待审核工单草稿。")),
            agent.getTemperature(), agent.getMaxOutputTokens()));
        return parse(result.content());
    }

    private AiTicketModelOutput parse(String content) {
        if (StringUtils.isBlank(content)) throw new ServiceException("AI 工单模型返回为空");
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) throw new ServiceException("AI 工单模型未返回 JSON 对象");
        try {
            JsonNode root = JsonUtils.getObjectMapper().readTree(json.substring(start, end + 1));
            if (!root.isObject()) throw new ServiceException("AI 工单模型返回格式无效");
            Map<String, Object> formData = root.path("formData").isObject()
                ? JsonUtils.getObjectMapper().convertValue(root.path("formData"), LinkedHashMap.class) : new LinkedHashMap<>();
            List<String> missing = root.path("missingFields").isArray()
                ? JsonUtils.getObjectMapper().convertValue(root.path("missingFields"), List.class) : new ArrayList<>();
            List<Map<String, Object>> evidence = root.path("evidence").isArray()
                ? JsonUtils.getObjectMapper().convertValue(root.path("evidence"), List.class) : new ArrayList<>();
            BigDecimal confidence = root.has("confidence") && root.get("confidence").isNumber()
                ? root.get("confidence").decimalValue() : BigDecimal.ZERO;
            return new AiTicketModelOutput(root.path("shouldCreate").asBoolean(false), root.path("title").asText(""),
                root.path("summary").asText(""), formData, missing, evidence, confidence);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("AI 工单模型 JSON 解析失败：" + exception.getMessage());
        }
    }

    private ValidatedOutput validateOutput(AiTicketModelOutput output, AiTicketTemplateContext template,
                                           Map<String, Object> policyDefaults) {
        Map<String, AiTicketTemplateContext.Field> allowed = template.fieldMap();
        Map<String, Object> values = new LinkedHashMap<>();
        for (AiTicketTemplateContext.Field field : template.fields()) {
            Object defaultValue = policyDefaults.containsKey(field.code()) ? policyDefaults.get(field.code()) : field.defaultValue();
            if (!empty(defaultValue)) values.put(field.code(), defaultValue);
        }
        output.formData().forEach((code, value) -> {
            AiTicketTemplateContext.Field field = allowed.get(code);
            if (field != null && validType(field, value)) values.put(code, value);
        });
        Set<String> missing = new LinkedHashSet<>();
        output.missingFields().stream().filter(allowed::containsKey).forEach(missing::add);
        template.fields().stream().filter(AiTicketTemplateContext.Field::required)
            .filter(field -> empty(values.get(field.code()))).map(AiTicketTemplateContext.Field::code).forEach(missing::add);
        missing.removeIf(code -> !empty(values.get(code)));
        BigDecimal confidence = output.confidence() == null ? BigDecimal.ZERO
            : output.confidence().max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return new ValidatedOutput(values, new ArrayList<>(missing), confidence);
    }

    private boolean validType(AiTicketTemplateContext.Field field, Object value) {
        if (empty(value) || "FILE".equals(field.type())) return false;
        if ("NUMBER".equals(field.type())) {
            try { new BigDecimal(String.valueOf(value)); return true; } catch (Exception ignored) { return false; }
        }
        if (Set.of("CHECKBOX", "MULTI_SELECT").contains(field.type()) && !(value instanceof Collection<?>)) return false;
        if (Set.of("RADIO", "SELECT").contains(field.type()) && !field.options().isEmpty()
            && !field.options().contains(String.valueOf(value))) return false;
        return true;
    }

    private String conversation(AiCallTranscript transcript) {
        List<AiCallTranscriptSegment> segments = segmentMapper.selectList(new LambdaQueryWrapper<AiCallTranscriptSegment>()
            .eq(AiCallTranscriptSegment::getTranscriptId, transcript.getId())
            .eq(AiCallTranscriptSegment::getFinalResult, true)
            .orderByAsc(AiCallTranscriptSegment::getSentenceIndex).orderByAsc(AiCallTranscriptSegment::getMessageTime));
        if (segments.isEmpty()) return transcript.getFullText();
        return segments.stream().map(segment -> speaker(segment.getSpeaker()) + "：" + segment.getTextContent())
            .reduce((left, right) -> left + "\n" + right).orElse(transcript.getFullText());
    }

    private IntentInfo intent(String businessCallId) {
        AiRealtimeCallSession session = realtimeSessionMapper.selectOne(new LambdaQueryWrapper<AiRealtimeCallSession>()
            .eq(AiRealtimeCallSession::getBusinessCallId, businessCallId)
            .orderByDesc(AiRealtimeCallSession::getCreateTime).last("LIMIT 1"));
        if (session == null || session.getConversationId() == null) return new IntentInfo(null, "未识别");
        AiIntentRecognitionLog log = intentLogMapper.selectOne(new LambdaQueryWrapper<AiIntentRecognitionLog>()
            .eq(AiIntentRecognitionLog::getConversationId, session.getConversationId())
            .eq(AiIntentRecognitionLog::getRecognitionStatus, "MATCHED")
            .orderByDesc(AiIntentRecognitionLog::getCreateTime).last("LIMIT 1"));
        return log == null ? new IntentInfo(null, "未识别")
            : new IntentInfo(log.getIntentCode(), StringUtils.blankToDefault(log.getIntentName(), log.getIntentCode()));
    }

    private void enforceIntentPolicy(AiTicketPolicy policy, IntentInfo intent) {
        List<String> included = readList(policy.getIncludeIntentsJson());
        List<String> excluded = readList(policy.getExcludeIntentsJson());
        if (intent.code() != null && excluded.contains(intent.code())) {
            throw new SkipGenerationException("本次通话命中排除意图：" + intent.displayName());
        }
        if (!included.isEmpty() && (intent.code() == null || !included.contains(intent.code()))) {
            throw new SkipGenerationException("本次通话未命中允许生成工单的意图");
        }
    }

    private String speaker(String speaker) {
        if ("CUSTOMER".equals(speaker) || "USER".equals(speaker)) return "客户";
        if ("AGENT".equals(speaker)) return "坐席";
        if ("AI".equals(speaker) || "ASSISTANT".equals(speaker)) return "AI助手";
        return "未知角色";
    }

    private String templateSchema(AiTicketTemplateContext template) {
        List<Map<String, Object>> fields = template.fields().stream().map(field -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("code", field.code()); value.put("name", field.name()); value.put("type", field.type());
            value.put("required", field.required()); value.put("options", field.options());
            return value;
        }).toList();
        return JsonUtils.toJsonString(Map.of("templateName", template.templateName(), "fields", fields));
    }

    private String callContext(AiCallRecordingSource source, AiTicketDraftTask task) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("direction", source == null ? null : source.getDirection());
        value.put("callerNumber", source == null ? null : source.getCallerNumber());
        value.put("calledNumber", source == null ? null : source.getCalledNumber());
        value.put("durationSeconds", source == null ? null : source.getDurationSeconds());
        if (StringUtils.isNotBlank(task.getContextJson())) value.put("completion", readMap(task.getContextJson()));
        return JsonUtils.toJsonString(value);
    }

    private AiCallRecordingSource source(AiTicketDraftTask task) {
        AiCallRecordingSource source = task.getCallSessionId() == null ? null : callSourceMapper.selectById(task.getCallSessionId());
        if (source == null) source = callSourceMapper.selectOne(new LambdaQueryWrapper<AiCallRecordingSource>()
            .eq(AiCallRecordingSource::getBusinessCallId, task.getBusinessCallId()).last("LIMIT 1"));
        return source;
    }

    private AiCallTranscript transcript(AiTicketDraftTask task) {
        AiCallTranscript transcript = task.getTranscriptId() == null ? null : transcriptMapper.selectById(task.getTranscriptId());
        if (transcript == null) transcript = transcriptMapper.selectOne(new LambdaQueryWrapper<AiCallTranscript>()
            .eq(AiCallTranscript::getBusinessCallId, task.getBusinessCallId()).eq(AiCallTranscript::getStatus, "SUCCESS")
            .orderByDesc(AiCallTranscript::getFinishedAt).last("LIMIT 1"));
        return require(transcript, "通话转写不存在");
    }

    private AiTicketPromptVersion activePrompt(AiTicketPolicy policy) {
        if (policy.getActivePromptVersionId() == null) return null;
        AiTicketPromptVersion version = promptMapper.selectById(policy.getActivePromptVersionId());
        return version != null && "PUBLISHED".equals(version.getStatus()) ? version : null;
    }

    private String callerNumber(AiCallRecordingSource source) {
        if (source == null) return null;
        return "OUTBOUND".equals(source.getDirection()) ? source.getCalledNumber() : source.getCallerNumber();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (StringUtils.isBlank(json)) return new LinkedHashMap<>();
        try { return JsonUtils.getObjectMapper().readValue(json, LinkedHashMap.class); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private List<String> readList(String json) {
        if (StringUtils.isBlank(json)) return List.of();
        try {
            return JsonUtils.getObjectMapper().readValue(json,
                JsonUtils.getObjectMapper().getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean empty(Object value) {
        return value == null || value instanceof String text && StringUtils.isBlank(text)
            || value instanceof Collection<?> collection && collection.isEmpty();
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private <T> T require(T value, String message) {
        if (value == null) throw new ServiceException(message);
        return value;
    }

    private record ValidatedOutput(Map<String, Object> formData, List<String> missingFields, BigDecimal confidence) {
    }

    private record ManualOverrides(boolean title, boolean summary, Set<String> fieldCodes) {
    }

    private record IntentInfo(String code, String displayName) {
    }

    public static class SkipGenerationException extends RuntimeException {
        public SkipGenerationException(String message) { super(message); }
    }
}
