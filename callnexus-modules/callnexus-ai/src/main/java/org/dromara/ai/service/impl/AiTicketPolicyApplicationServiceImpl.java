package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiTicketPolicy;
import org.dromara.ai.domain.AiTicketPromptVersion;
import org.dromara.ai.domain.request.AiTicketPolicyRequest;
import org.dromara.ai.domain.request.AiTicketPromptRequest;
import org.dromara.ai.domain.response.AiTicketPolicyResponse;
import org.dromara.ai.domain.response.AiTicketPromptResponse;
import org.dromara.ai.domain.response.AiTicketPromptValidationResponse;
import org.dromara.ai.domain.response.AiTicketPromptVersionResponse;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiTicketPolicyMapper;
import org.dromara.ai.mapper.AiTicketPromptVersionMapper;
import org.dromara.ai.service.AiTicketPolicyApplicationService;
import org.dromara.ai.service.AiTicketPromptProtocol;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AiTicketPolicyApplicationServiceImpl implements AiTicketPolicyApplicationService {

    private static final String PROTOCOL_VERSION = "AI_TICKET_JSON_V1";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");
    private static final List<String> AVAILABLE_VARIABLES = List.of(
        "agentName", "conversation", "intent", "customerProfile", "ticketTemplateSchema", "policyDefaults", "callContext");
    private static final List<String> REQUIRED_VARIABLES = List.of("conversation", "ticketTemplateSchema");
    private static final String DEFAULT_PROMPT = """
        你是 CallNexus 的 AI 工单整理助手。请根据本次完整对话和工单模板，提取可核实的信息并生成待审核工单草稿。

        AI助手：{{agentName}}
        识别意图：{{intent}}
        通话上下文：{{callContext}}
        已知客户资料：{{customerProfile}}
        策略默认值：{{policyDefaults}}

        工单模板字段：
        {{ticketTemplateSchema}}

        完整对话：
        {{conversation}}

        处理要求：
        1. 只提取对话中明确出现或系统上下文已经提供的信息，不得推测。
        2. title 应简洁概括客户诉求；summary 应保留关键事实、时间、对象和期望结果。
        3. formData 的键必须来自工单模板字段编码，未知字段不要输出。
        4. 必填字段缺失时放入 missingFields，不得编造补齐。
        5. evidence 中记录字段值对应的原始对话依据，便于人工审核。
        6. 最终只输出系统要求的 JSON，不要输出 Markdown、解释或额外文字。
        """;
    private static final String JSON_SCHEMA = """
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": false,
          "required": ["shouldCreate", "title", "summary", "formData", "missingFields", "evidence", "confidence"],
          "properties": {
            "shouldCreate": { "type": "boolean" },
            "title": { "type": "string", "maxLength": 256 },
            "summary": { "type": "string", "maxLength": 4000 },
            "formData": { "type": "object" },
            "missingFields": { "type": "array", "items": { "type": "string" }, "uniqueItems": true },
            "evidence": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["field", "quote"],
                "properties": {
                  "field": { "type": "string" },
                  "quote": { "type": "string", "maxLength": 1000 }
                }
              }
            },
            "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
          }
        }
        """;
    private static final String SAFETY_CONSTRAINTS = """
        系统固定安全约束（只读）：
        1. 输出必须是一个可解析的 JSON 对象，不能包含代码块或额外说明。
        2. formData 只能使用当前工单模板允许的字段编码，越权字段将被丢弃。
        3. 不得编造客户身份、联系方式、金额、地址、时间、承诺或处理结果。
        4. 缺失的必填字段必须进入 missingFields，不能使用猜测值占位。
        5. evidence 必须来自当前会话或系统提供的可信上下文。
        6. 模型输出还会经过 JSON Schema、字段类型、必填项和长度的服务端校验。
        7. 本阶段只生成待审核草稿，不直接创建正式工单或客户资料。
        """;

    private final AiAgentMapper agentMapper;
    private final AiTicketPolicyMapper policyMapper;
    private final AiTicketPromptVersionMapper promptMapper;

    @Override
    public AiTicketPolicyResponse policy(Long agentId) {
        requireAgent(agentId);
        AiTicketPolicy policy = findPolicy(agentId);
        return policy == null ? defaultPolicy(agentId) : response(policy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePolicy(Long agentId, AiTicketPolicyRequest request) {
        requireAgent(agentId);
        validatePolicy(request);
        AiTicketPolicy policy = findPolicy(agentId);
        boolean create = policy == null;
        if (create) {
            policy = new AiTicketPolicy();
            policy.setAiAgentId(agentId);
            policy.setVersion(0);
        }
        fill(policy, request);
        if (create) policyMapper.insert(policy); else policyMapper.updateById(policy);
        return policy.getId();
    }

    @Override
    public AiTicketPromptResponse prompt(Long agentId) {
        requireAgent(agentId);
        AiTicketPolicy policy = findPolicy(agentId);
        if (policy == null) return promptResponse(null, null, DEFAULT_PROMPT, false);
        AiTicketPromptVersion version = latest(policy.getId(), "DRAFT");
        if (version == null && policy.getActivePromptVersionId() != null) {
            version = promptMapper.selectById(policy.getActivePromptVersionId());
        }
        return version == null
            ? promptResponse(policy.getId(), null, DEFAULT_PROMPT, false)
            : promptResponse(policy.getId(), version, version.getPromptContent(), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePromptDraft(Long agentId, AiTicketPromptRequest request) {
        AiTicketPolicy policy = requirePolicy(agentId);
        ensureValid(request.getPromptContent());
        AiTicketPromptVersion draft = latest(policy.getId(), "DRAFT");
        if (draft == null) {
            draft = new AiTicketPromptVersion();
            draft.setPolicyId(policy.getId());
            draft.setVersionNo(nextVersion(policy.getId()));
            draft.setStatus("DRAFT");
        }
        draft.setVersionName(StringUtils.isBlank(request.getVersionName()) ? "未发布草稿" : request.getVersionName().trim());
        draft.setPromptContent(request.getPromptContent().trim());
        draft.setProtocolVersion(PROTOCOL_VERSION);
        draft.setPromptHash(hash(draft.getPromptContent()));
        if (draft.getId() == null) promptMapper.insert(draft); else promptMapper.updateById(draft);
        return draft.getId();
    }

    @Override
    public AiTicketPromptValidationResponse validatePrompt(Long agentId, AiTicketPromptRequest request) {
        requireAgent(agentId);
        List<String> errors = validate(request.getPromptContent());
        return new AiTicketPromptValidationResponse(errors.isEmpty(), errors,
            errors.isEmpty() ? compilePreview(request.getPromptContent()) : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishPrompt(Long agentId) {
        AiTicketPolicy policy = requirePolicy(agentId);
        AiTicketPromptVersion draft = latest(policy.getId(), "DRAFT");
        if (draft == null) throw new ServiceException("请先保存提示词草稿");
        ensureValid(draft.getPromptContent());
        promptMapper.update(null, new LambdaUpdateWrapper<AiTicketPromptVersion>()
            .eq(AiTicketPromptVersion::getPolicyId, policy.getId())
            .eq(AiTicketPromptVersion::getStatus, "PUBLISHED")
            .set(AiTicketPromptVersion::getStatus, "ARCHIVED"));
        draft.setStatus("PUBLISHED");
        draft.setPublishedBy(LoginHelper.getUserId());
        draft.setPublishedAt(new Date());
        promptMapper.updateById(draft);
        policy.setActivePromptVersionId(draft.getId());
        policyMapper.updateById(policy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long restoreDefaultPrompt(Long agentId) {
        AiTicketPromptRequest request = new AiTicketPromptRequest();
        request.setPromptContent(DEFAULT_PROMPT);
        request.setVersionName("恢复系统默认");
        return savePromptDraft(agentId, request);
    }

    @Override
    public List<AiTicketPromptVersionResponse> promptVersions(Long agentId) {
        requireAgent(agentId);
        AiTicketPolicy policy = findPolicy(agentId);
        if (policy == null) return List.of();
        return promptMapper.selectList(new LambdaQueryWrapper<AiTicketPromptVersion>()
                .eq(AiTicketPromptVersion::getPolicyId, policy.getId())
                .orderByDesc(AiTicketPromptVersion::getVersionNo)).stream()
            .map(this::versionResponse).toList();
    }

    private void requireAgent(Long agentId) {
        AiAgent agent = agentMapper.selectById(agentId);
        if (agent == null) throw new ServiceException("AI 助手不存在");
    }

    private AiTicketPolicy requirePolicy(Long agentId) {
        requireAgent(agentId);
        AiTicketPolicy policy = findPolicy(agentId);
        if (policy != null) return policy;
        AiTicketPolicyRequest defaults = new AiTicketPolicyRequest();
        savePolicy(agentId, defaults);
        return findPolicy(agentId);
    }

    private AiTicketPolicy findPolicy(Long agentId) {
        return policyMapper.selectOne(new LambdaQueryWrapper<AiTicketPolicy>().eq(AiTicketPolicy::getAiAgentId, agentId));
    }

    private AiTicketPromptVersion latest(Long policyId, String status) {
        return promptMapper.selectOne(new LambdaQueryWrapper<AiTicketPromptVersion>()
            .eq(AiTicketPromptVersion::getPolicyId, policyId)
            .eq(AiTicketPromptVersion::getStatus, status)
            .orderByDesc(AiTicketPromptVersion::getVersionNo)
            .last("LIMIT 1"));
    }

    private int nextVersion(Long policyId) {
        return promptMapper.selectList(new LambdaQueryWrapper<AiTicketPromptVersion>()
                .eq(AiTicketPromptVersion::getPolicyId, policyId)
                .select(AiTicketPromptVersion::getVersionNo)).stream()
            .map(AiTicketPromptVersion::getVersionNo).filter(Objects::nonNull).max(Integer::compareTo).orElse(0) + 1;
    }

    private void validatePolicy(AiTicketPolicyRequest request) {
        if (Boolean.TRUE.equals(request.getEnabled()) && request.getTicketTemplateId() == null) {
            throw new ServiceException("启用自动工单前必须选择工单模板");
        }
        if (!Set.of("DRAFT_REVIEW", "AUTO_CREATE").contains(request.getCreationMode())) throw new ServiceException("工单创建模式无效");
        if (!Set.of("KEEP_DRAFT", "REJECT_DRAFT").contains(request.getMissingRequiredAction())) throw new ServiceException("必填字段缺失策略无效");
        if (!Set.of("MERGE_PENDING", "ALLOW", "SKIP").contains(request.getDuplicatePolicy())) throw new ServiceException("重复工单策略无效");
        if (!Set.of("CREATE_ONLY", "KEEP_PENDING", "SUBMIT", "RESOLVE").contains(request.getAfterCreateAction())) {
            throw new ServiceException("建单后动作无效");
        }
        if ("AUTO_CREATE".equals(request.getCreationMode()) && "SUBMIT".equals(request.getAfterCreateAction())
            && request.getTicketTemplateId() == null) {
            throw new ServiceException("自动提交工作流前必须选择工单模板");
        }
    }

    private void fill(AiTicketPolicy policy, AiTicketPolicyRequest request) {
        policy.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        policy.setCreationMode(request.getCreationMode());
        policy.setTicketTemplateId(request.getTicketTemplateId());
        policy.setTriggerTypesJson(JsonUtils.toJsonString(nullToEmpty(request.getTriggerTypes())));
        policy.setIncludeIntentsJson(JsonUtils.toJsonString(nullToEmpty(request.getIncludeIntents())));
        policy.setExcludeIntentsJson(JsonUtils.toJsonString(nullToEmpty(request.getExcludeIntents())));
        policy.setConfidenceThreshold(request.getConfidenceThreshold());
        policy.setMissingRequiredAction(request.getMissingRequiredAction());
        policy.setDuplicatePolicy(request.getDuplicatePolicy());
        policy.setDuplicateWindowHours(request.getDuplicateWindowHours());
        policy.setAfterCreateAction(request.getAfterCreateAction());
        policy.setCustomerTemplateId(request.getCustomerTemplateId());
        policy.setDefaultSkillGroupId(request.getDefaultSkillGroupId());
        policy.setDefaultValuesJson(JsonUtils.toJsonString(request.getDefaultValues() == null ? Map.of() : request.getDefaultValues()));
    }

    private AiTicketPolicyResponse defaultPolicy(Long agentId) {
        AiTicketPolicyRequest defaults = new AiTicketPolicyRequest();
        AiTicketPolicyResponse response = new AiTicketPolicyResponse();
        response.setAiAgentId(agentId);
        response.setEnabled(false);
        response.setCreationMode(defaults.getCreationMode());
        response.setTriggerTypes(defaults.getTriggerTypes());
        response.setConfidenceThreshold(defaults.getConfidenceThreshold());
        response.setMissingRequiredAction(defaults.getMissingRequiredAction());
        response.setDuplicatePolicy(defaults.getDuplicatePolicy());
        response.setDuplicateWindowHours(defaults.getDuplicateWindowHours());
        response.setAfterCreateAction(defaults.getAfterCreateAction());
        response.setVersion(0);
        return response;
    }

    private AiTicketPolicyResponse response(AiTicketPolicy policy) {
        AiTicketPolicyResponse response = new AiTicketPolicyResponse();
        response.setId(policy.getId()); response.setAiAgentId(policy.getAiAgentId()); response.setEnabled(policy.getEnabled());
        response.setCreationMode(policy.getCreationMode()); response.setTicketTemplateId(policy.getTicketTemplateId());
        response.setTriggerTypes(readList(policy.getTriggerTypesJson())); response.setIncludeIntents(readList(policy.getIncludeIntentsJson()));
        response.setExcludeIntents(readList(policy.getExcludeIntentsJson())); response.setConfidenceThreshold(policy.getConfidenceThreshold());
        response.setMissingRequiredAction(policy.getMissingRequiredAction()); response.setDuplicatePolicy(policy.getDuplicatePolicy());
        response.setDuplicateWindowHours(policy.getDuplicateWindowHours()); response.setAfterCreateAction(policy.getAfterCreateAction());
        response.setCustomerTemplateId(policy.getCustomerTemplateId()); response.setDefaultSkillGroupId(policy.getDefaultSkillGroupId());
        response.setDefaultValues(readMap(policy.getDefaultValuesJson())); response.setActivePromptVersionId(policy.getActivePromptVersionId());
        response.setVersion(policy.getVersion());
        return response;
    }

    private AiTicketPromptResponse promptResponse(Long policyId, AiTicketPromptVersion version, String content, boolean custom) {
        return new AiTicketPromptResponse(policyId, version == null ? null : version.getId(), version == null ? null : version.getVersionNo(),
            version == null ? "系统默认" : version.getVersionName(), version == null ? "DEFAULT" : version.getStatus(), content,
            AiTicketPromptProtocol.VERSION, AiTicketPromptProtocol.JSON_SCHEMA, AiTicketPromptProtocol.SAFETY,
            AiTicketPromptProtocol.VARIABLES, AiTicketPromptProtocol.REQUIRED_VARIABLES, custom);
    }

    private AiTicketPromptVersionResponse versionResponse(AiTicketPromptVersion value) {
        AiTicketPromptVersionResponse response = new AiTicketPromptVersionResponse();
        response.setId(value.getId()); response.setVersionNo(value.getVersionNo()); response.setVersionName(value.getVersionName());
        response.setStatus(value.getStatus()); response.setProtocolVersion(value.getProtocolVersion()); response.setPromptHash(value.getPromptHash());
        response.setPublishedBy(value.getPublishedBy()); response.setPublishedAt(value.getPublishedAt()); response.setCreateTime(value.getCreateTime());
        return response;
    }

    private List<String> validate(String content) {
        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(content)) return List.of("业务提示词不能为空");
        if (content.length() > 30000) errors.add("业务提示词不能超过30000个字符");
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        Set<String> used = new LinkedHashSet<>();
        while (matcher.find()) used.add(matcher.group(1));
        used.stream().filter(variable -> !AVAILABLE_VARIABLES.contains(variable))
            .forEach(variable -> errors.add("未知变量：{{" + variable + "}}"));
        REQUIRED_VARIABLES.stream().filter(variable -> !used.contains(variable))
            .forEach(variable -> errors.add("缺少必需变量：{{" + variable + "}}"));
        return errors;
    }

    private void ensureValid(String content) {
        List<String> errors = validate(content);
        if (!errors.isEmpty()) throw new ServiceException(String.join("；", errors));
    }

    private String compilePreview(String content) {
        Map<String, String> samples = Map.of(
            "agentName", "售后服务助手", "conversation", "客户：商品收到后破损。\nAI：请提供订单号。\n客户：订单号 CNX20260828001。",
            "intent", "售后投诉", "customerProfile", "姓名：张三；手机号：138****0000",
            "ticketTemplateSchema", "orderNo（订单号，文本，必填）；problem（问题描述，长文本，必填）",
            "policyDefaults", "优先级：普通", "callContext", "入呼；通话时长 02:35");
        String compiled = content;
        for (Map.Entry<String, String> entry : samples.entrySet()) compiled = compiled.replace("{{" + entry.getKey() + "}}", entry.getValue());
        return compiled + "\n\n# 系统固定输出协议\n" + AiTicketPromptProtocol.JSON_SCHEMA + "\n" + AiTicketPromptProtocol.SAFETY;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ServiceException("提示词摘要计算失败");
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (StringUtils.isBlank(json)) return new LinkedHashMap<>();
        try {
            return JsonUtils.getObjectMapper().readValue(json, LinkedHashMap.class);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> nullToEmpty(List<String> value) {
        return value == null ? List.of() : value;
    }
}
