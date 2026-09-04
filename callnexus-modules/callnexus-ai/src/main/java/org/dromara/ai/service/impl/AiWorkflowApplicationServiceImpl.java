package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentWorkflowBinding;
import org.dromara.ai.domain.AiWorkflow;
import org.dromara.ai.domain.AiWorkflowVersion;
import org.dromara.ai.domain.request.AiAgentWorkflowBindingRequest;
import org.dromara.ai.domain.request.AiWorkflowDraftRequest;
import org.dromara.ai.domain.request.AiWorkflowRequest;
import org.dromara.ai.domain.response.AiAgentWorkflowBindingResponse;
import org.dromara.ai.domain.response.AiWorkflowResponse;
import org.dromara.ai.domain.response.AiWorkflowValidationResponse;
import org.dromara.ai.domain.response.AiWorkflowVersionResponse;
import org.dromara.ai.knowledge.KnowledgeTextUtils;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiAgentWorkflowBindingMapper;
import org.dromara.ai.mapper.AiWorkflowMapper;
import org.dromara.ai.mapper.AiWorkflowVersionMapper;
import org.dromara.ai.service.AiWorkflowApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiWorkflowApplicationServiceImpl implements AiWorkflowApplicationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SCENE_TYPES = Set.of("VOICE_INBOUND", "VOICE_OUTBOUND", "ONLINE_CHAT", "COMMON");
    private static final Set<String> BINDING_SCENES = Set.of("VOICE_INBOUND", "VOICE_OUTBOUND", "ONLINE_CHAT");
    private static final Set<String> NODE_TYPES = Set.of(
        "START", "END", "WAIT_INPUT", "SET_VARIABLE", "CONDITION", "TEMPLATE_REPLY",
        "KNOWLEDGE_QUERY", "MODEL_REPLY", "INTENT_ROUTE", "SLOT_EXTRACT", "CONFIRM",
        "CUSTOMER_QUERY", "CUSTOMER_UPDATE", "FOLLOW_UP_CREATE", "TICKET_DRAFT_CREATE",
        "TICKET_CREATE", "AUTO_OUTBOUND_WRITEBACK", "DO_NOT_CALL_ADD", "HTTP_REQUEST",
        "TRANSFER_QUEUE", "TRANSFER_EXTENSION", "TRANSFER_IVR", "PLAY_MEDIA", "HANGUP"
    );
    private static final Set<String> TERMINAL_TYPES = Set.of(
        "END", "TRANSFER_QUEUE", "TRANSFER_EXTENSION", "TRANSFER_IVR", "HANGUP"
    );
    private static final Set<String> CONDITION_OPERATORS = Set.of(
        "EQ", "NE", "CONTAINS", "NOT_CONTAINS", "GT", "GE", "LT", "LE", "EMPTY", "NOT_EMPTY"
    );
    private static final Set<String> OUTBOUND_RESULT_CODES = Set.of(
        "INTERESTED", "NOT_INTERESTED", "CALLBACK_REQUESTED", "TRANSFERRED",
        "NO_INPUT", "ASR_UNRECOGNIZED", "DO_NOT_CALL", "PENDING_REVIEW", "WORKFLOW_FAILED"
    );
    private static final String DEFAULT_DEFINITION = """
        {"schemaVersion":"1.0","variables":[],"nodes":[
          {"id":"start","type":"START","name":"开始","x":220,"y":160,"config":{}},
          {"id":"end","type":"END","name":"结束","x":480,"y":160,"config":{}}
        ],"edges":[{"id":"edge_start_end","source":"start","target":"end","condition":""}]}
        """;

    private final AiWorkflowMapper workflowMapper;
    private final AiWorkflowVersionMapper versionMapper;
    private final AiAgentWorkflowBindingMapper bindingMapper;
    private final AiAgentMapper agentMapper;

    @Override
    public List<AiWorkflowResponse> workflows() {
        List<AiWorkflow> workflows = workflowMapper.selectList(new LambdaQueryWrapper<AiWorkflow>()
            .orderByDesc(AiWorkflow::getUpdateTime));
        if (workflows.isEmpty()) return List.of();
        List<Long> ids = workflows.stream().map(AiWorkflow::getId).toList();
        Map<Long, List<AiWorkflowVersion>> versions = versionMapper.selectList(new LambdaQueryWrapper<AiWorkflowVersion>()
                .in(AiWorkflowVersion::getWorkflowId, ids)).stream()
            .collect(Collectors.groupingBy(AiWorkflowVersion::getWorkflowId));
        Map<Long, Integer> bindings = bindingMapper.selectList(new LambdaQueryWrapper<AiAgentWorkflowBinding>()
                .in(AiAgentWorkflowBinding::getWorkflowId, ids)).stream()
            .collect(Collectors.groupingBy(AiAgentWorkflowBinding::getWorkflowId, Collectors.summingInt(item -> 1)));
        return workflows.stream().map(item -> response(item, versions.getOrDefault(item.getId(), List.of()),
            bindings.getOrDefault(item.getId(), 0))).toList();
    }

    @Override
    public AiWorkflowResponse workflow(Long id) {
        AiWorkflow workflow = requireWorkflow(id);
        return response(workflow, workflowVersions(id), bindingCount(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiWorkflowRequest request) {
        validateRequest(request);
        ensureCode(request.getWorkflowCode(), null);
        AiWorkflow workflow = new AiWorkflow();
        fill(workflow, request);
        workflowMapper.insert(workflow);
        AiWorkflowDraftRequest draft = new AiWorkflowDraftRequest();
        draft.setVersionName("初始草稿");
        draft.setDefinitionJson(DEFAULT_DEFINITION);
        saveDraft(workflow.getId(), draft);
        return workflow.getId();
    }

    @Override
    public void update(Long id, AiWorkflowRequest request) {
        validateRequest(request);
        AiWorkflow workflow = requireWorkflow(id);
        ensureCode(request.getWorkflowCode(), id);
        if (!Objects.equals(workflow.getSceneType(), request.getSceneType()) && bindingCount(id) > 0) {
            throw new ServiceException("AI 编排已绑定助手，不能修改使用场景");
        }
        fill(workflow, request);
        workflowMapper.updateById(workflow);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiWorkflow workflow = requireWorkflow(id);
        if (bindingCount(id) > 0 || workflow.getCurrentPublishedVersionId() != null) {
            throw new ServiceException("AI 编排已发布或已绑定助手，不能删除，可改为停用");
        }
        versionMapper.delete(new LambdaQueryWrapper<AiWorkflowVersion>().eq(AiWorkflowVersion::getWorkflowId, id));
        workflowMapper.deleteById(workflow);
    }

    @Override
    public void setEnabled(Long id, boolean enabled) {
        AiWorkflow workflow = requireWorkflow(id);
        workflow.setEnabled(enabled);
        workflowMapper.updateById(workflow);
    }

    @Override
    public List<AiWorkflowVersionResponse> versions(Long workflowId) {
        requireWorkflow(workflowId);
        return workflowVersions(workflowId).stream().map(this::versionResponse).toList();
    }

    @Override
    public AiWorkflowVersionResponse draft(Long workflowId) {
        requireWorkflow(workflowId);
        AiWorkflowVersion draft = currentDraft(workflowId);
        if (draft == null) {
            AiWorkflowDraftRequest request = new AiWorkflowDraftRequest();
            AiWorkflow workflow = requireWorkflow(workflowId);
            AiWorkflowVersion published = workflow.getCurrentPublishedVersionId() == null ? null
                : versionMapper.selectById(workflow.getCurrentPublishedVersionId());
            request.setVersionName("新草稿");
            request.setDefinitionJson(published == null ? DEFAULT_DEFINITION : published.getDefinitionJson());
            return versionResponse(versionMapper.selectById(saveDraft(workflowId, request)));
        }
        return versionResponse(draft);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraft(Long workflowId, AiWorkflowDraftRequest request) {
        requireWorkflow(workflowId);
        JsonNode definition = parseDefinition(request.getDefinitionJson());
        String normalized = normalizeDefinition(definition);
        AiWorkflowVersion draft = currentDraft(workflowId);
        if (draft == null) {
            int versionNo = workflowVersions(workflowId).stream().mapToInt(AiWorkflowVersion::getVersionNo).max().orElse(0) + 1;
            draft = new AiWorkflowVersion();
            draft.setWorkflowId(workflowId);
            draft.setVersionNo(versionNo);
            draft.setStatus("DRAFT");
        }
        draft.setVersionName(request.getVersionName());
        draft.setDefinitionJson(normalized);
        draft.setDefinitionHash(KnowledgeTextUtils.sha256(normalized));
        if (draft.getId() == null) versionMapper.insert(draft); else versionMapper.updateById(draft);
        return draft.getId();
    }

    @Override
    public AiWorkflowValidationResponse validateDraft(Long workflowId) {
        requireWorkflow(workflowId);
        AiWorkflowVersion draft = currentDraft(workflowId);
        if (draft == null) throw new ServiceException("AI 编排没有可校验的草稿");
        return validateDefinition(draft.getDefinitionJson());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiWorkflowVersionResponse publish(Long workflowId) {
        AiWorkflow workflow = requireWorkflow(workflowId);
        AiWorkflowVersion draft = currentDraft(workflowId);
        if (draft == null) throw new ServiceException("AI 编排没有可发布的草稿");
        AiWorkflowValidationResponse validation = validateDefinition(draft.getDefinitionJson());
        if (!validation.isValid()) throw new ServiceException("AI 编排校验失败：" + String.join("；", validation.getErrors()));
        List<AiWorkflowVersion> published = versionMapper.selectList(new LambdaQueryWrapper<AiWorkflowVersion>()
            .eq(AiWorkflowVersion::getWorkflowId, workflowId).eq(AiWorkflowVersion::getStatus, "PUBLISHED"));
        for (AiWorkflowVersion item : published) {
            item.setStatus("ARCHIVED");
            versionMapper.updateById(item);
        }
        draft.setStatus("PUBLISHED");
        draft.setPublishedBy(LoginHelper.getUserId());
        draft.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(draft);
        workflow.setCurrentPublishedVersionId(draft.getId());
        workflowMapper.updateById(workflow);
        return versionResponse(draft);
    }

    @Override
    public List<AiAgentWorkflowBindingResponse> agentBindings(Long agentId) {
        requireAgent(agentId);
        List<AiAgentWorkflowBinding> bindings = bindingMapper.selectList(new LambdaQueryWrapper<AiAgentWorkflowBinding>()
            .eq(AiAgentWorkflowBinding::getAiAgentId, agentId).orderByAsc(AiAgentWorkflowBinding::getSceneType));
        if (bindings.isEmpty()) return List.of();
        Map<Long, AiWorkflow> workflows = workflowMapper.selectBatchIds(bindings.stream()
                .map(AiAgentWorkflowBinding::getWorkflowId).distinct().toList()).stream()
            .collect(Collectors.toMap(AiWorkflow::getId, item -> item));
        Map<Long, AiWorkflowVersion> versions = versionMapper.selectBatchIds(bindings.stream()
                .map(AiAgentWorkflowBinding::getWorkflowVersionId).distinct().toList()).stream()
            .collect(Collectors.toMap(AiWorkflowVersion::getId, item -> item));
        return bindings.stream().map(item -> bindingResponse(item, workflows.get(item.getWorkflowId()),
            versions.get(item.getWorkflowVersionId()))).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAgentBinding(Long agentId, AiAgentWorkflowBindingRequest request) {
        requireAgent(agentId);
        if (!BINDING_SCENES.contains(request.getSceneType())) throw new ServiceException("不支持的 AI 编排绑定场景");
        AiWorkflowVersion version = versionMapper.selectById(request.getWorkflowVersionId());
        if (version == null || !"PUBLISHED".equals(version.getStatus())) throw new ServiceException("只能绑定已发布的 AI 编排版本");
        AiWorkflow workflow = requireWorkflow(version.getWorkflowId());
        if (!Boolean.TRUE.equals(workflow.getEnabled())) throw new ServiceException("AI 编排已停用");
        if (!"COMMON".equals(workflow.getSceneType()) && !workflow.getSceneType().equals(request.getSceneType())) {
            throw new ServiceException("AI 编排场景与助手绑定场景不一致");
        }
        AiAgentWorkflowBinding binding = bindingMapper.selectOne(new LambdaQueryWrapper<AiAgentWorkflowBinding>()
            .eq(AiAgentWorkflowBinding::getAiAgentId, agentId).eq(AiAgentWorkflowBinding::getSceneType, request.getSceneType()));
        if (binding == null) {
            binding = new AiAgentWorkflowBinding();
            binding.setAiAgentId(agentId);
            binding.setSceneType(request.getSceneType());
        }
        binding.setWorkflowId(workflow.getId());
        binding.setWorkflowVersionId(version.getId());
        binding.setFallbackAction(request.getFallbackAction() == null ? "DEFAULT_CONVERSATION" : request.getFallbackAction());
        binding.setEnabled(request.getEnabled() == null || request.getEnabled());
        if (binding.getId() == null) bindingMapper.insert(binding); else bindingMapper.updateById(binding);
    }

    @Override
    public void deleteAgentBinding(Long agentId, String sceneType) {
        requireAgent(agentId);
        bindingMapper.delete(new LambdaQueryWrapper<AiAgentWorkflowBinding>()
            .eq(AiAgentWorkflowBinding::getAiAgentId, agentId).eq(AiAgentWorkflowBinding::getSceneType, sceneType));
    }

    private AiWorkflowValidationResponse validateDefinition(String definitionJson) {
        AiWorkflowValidationResponse result = new AiWorkflowValidationResponse();
        JsonNode root;
        try {
            root = parseDefinition(definitionJson);
        } catch (ServiceException exception) {
            result.getErrors().add(exception.getMessage());
            result.setValid(false);
            return result;
        }
        JsonNode nodesNode = root.path("nodes");
        JsonNode edgesNode = root.path("edges");
        if (!nodesNode.isArray() || nodesNode.isEmpty()) result.getErrors().add("流程至少需要一个节点");
        if (!edgesNode.isArray()) result.getErrors().add("流程连线格式无效");
        if (!result.getErrors().isEmpty()) {
            result.setValid(false);
            return result;
        }
        Set<String> customVariableKeys = new HashSet<>();
        JsonNode variablesNode = root.path("variables");
        if (variablesNode.isArray()) {
            variablesNode.forEach(variable -> {
                String key = variable.path("key").asText("").trim();
                if (!key.isEmpty()) customVariableKeys.add(key);
            });
        }
        Map<String, JsonNode> nodes = new HashMap<>();
        List<String> startIds = new ArrayList<>();
        for (JsonNode node : nodesNode) {
            String id = node.path("id").asText("").trim();
            String type = node.path("type").asText("").trim();
            if (id.isEmpty()) result.getErrors().add("存在没有 ID 的节点");
            else if (nodes.putIfAbsent(id, node) != null) result.getErrors().add("节点 ID 重复：" + id);
            if (!NODE_TYPES.contains(type)) result.getErrors().add("不支持的节点类型：" + type);
            if ("START".equals(type)) startIds.add(id);
            validateNodeConfig(id, type, node.path("config"), customVariableKeys, result);
        }
        if (startIds.size() != 1) result.getErrors().add("流程必须且只能包含一个开始节点");
        Map<String, Set<String>> outgoing = new HashMap<>();
        Map<String, Set<String>> conditions = new HashMap<>();
        for (JsonNode edge : edgesNode) {
            String source = edge.path("source").asText("");
            String target = edge.path("target").asText("");
            if (!nodes.containsKey(source) || !nodes.containsKey(target)) {
                result.getErrors().add("连线引用了不存在的节点：" + source + " -> " + target);
                continue;
            }
            outgoing.computeIfAbsent(source, key -> new HashSet<>()).add(target);
            String sourceType = nodes.get(source).path("type").asText();
            String condition = edge.path("condition").asText("").trim();
            if (("CONDITION".equals(sourceType) || "INTENT_ROUTE".equals(sourceType)) && condition.isEmpty()) {
                result.getErrors().add("判断节点的连线必须配置分支条件：" + source);
            }
            if ("CONDITION".equals(sourceType) && !condition.isEmpty() && !Set.of("TRUE", "FALSE").contains(condition)) {
                result.getErrors().add("条件判断分支只能选择“条件成立”或“条件不成立”：" + source);
            }
            if ("INTENT_ROUTE".equals(sourceType) && !condition.isEmpty() && !"FALLBACK".equals(condition)
                && !configuredIntentCodes(nodes.get(source).path("config")).contains(condition)) {
                result.getErrors().add("意图分支未包含在节点已选意图中：" + source + " / " + condition);
            }
            if (!condition.isEmpty() && !conditions.computeIfAbsent(source, key -> new HashSet<>()).add(condition)) {
                result.getErrors().add("同一节点存在重复分支条件：" + source + " / " + condition);
            }
        }
        for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
            String type = entry.getValue().path("type").asText();
            if (!TERMINAL_TYPES.contains(type) && outgoing.getOrDefault(entry.getKey(), Set.of()).isEmpty()) {
                result.getErrors().add("非终止节点没有后续连线：" + entry.getKey());
            }
            if ("INTENT_ROUTE".equals(type)) {
                Set<String> configuredCodes = configuredIntentCodes(entry.getValue().path("config"));
                Set<String> configuredBranches = conditions.getOrDefault(entry.getKey(), Set.of());
                configuredCodes.stream()
                    .filter(code -> !configuredBranches.contains(code))
                    .forEach(code -> result.getErrors().add(
                        "意图判断节点已选择意图，但未配置对应连线：" + entry.getKey() + " / " + code));
                if (!configuredBranches.contains("FALLBACK")) {
                    result.getErrors().add("意图判断节点缺少“未命中任何意图”连线：" + entry.getKey());
                }
            }
        }
        if (startIds.size() == 1) {
            Set<String> reachable = reachable(startIds.get(0), outgoing);
            for (String nodeId : nodes.keySet()) {
                if (!reachable.contains(nodeId)) result.getErrors().add("存在从开始节点不可达的节点：" + nodeId);
            }
            if (hasCycle(startIds.get(0), outgoing, new HashSet<>(), new HashSet<>())) {
                result.getErrors().add("阶段一暂不支持循环连线，请改为有限分支");
            }
        }
        if (nodes.values().stream().noneMatch(node -> TERMINAL_TYPES.contains(node.path("type").asText()))) {
            result.getWarnings().add("流程没有结束、挂机或转接节点");
        }
        result.setValid(result.getErrors().isEmpty());
        return result;
    }

    private void validateNodeConfig(String nodeId, String type, JsonNode config, Set<String> customVariableKeys,
                                    AiWorkflowValidationResponse result) {
        if ("TEMPLATE_REPLY".equals(type) && config.path("text").asText("").isBlank()) {
            result.getErrors().add("模板回复节点未填写回复内容：" + nodeId);
        }
        if ("INTENT_ROUTE".equals(type) && configuredIntentCodes(config).isEmpty()) {
            result.getErrors().add("意图判断节点至少需要选择一个意图：" + nodeId);
        }
        if ("CONDITION".equals(type)) {
            if (config.path("variable").asText("").isBlank()) result.getErrors().add("条件判断节点未选择判断字段：" + nodeId);
            String operator = config.path("operator").asText("");
            if (!CONDITION_OPERATORS.contains(operator)) result.getErrors().add("条件判断节点未选择有效判断方式：" + nodeId);
            if (!Set.of("EMPTY", "NOT_EMPTY").contains(operator) && config.path("compareValue").asText("").isBlank()) {
                result.getErrors().add("条件判断节点未填写比较值：" + nodeId);
            }
        }
        if ("SET_VARIABLE".equals(type)) {
            String key = config.path("key").asText("");
            if (key.isBlank()) result.getErrors().add("记录流程信息节点未选择记录内容：" + nodeId);
            else if (!customVariableKeys.contains(key)) result.getErrors().add("记录流程信息节点引用了不存在的流程信息：" + nodeId);
        }
        if ("CUSTOMER_QUERY".equals(type) && config.path("phoneTemplate").asText("").isBlank()) {
            result.getErrors().add("查询客户节点未配置查询号码：" + nodeId);
        }
        if ("SLOT_EXTRACT".equals(type) && !config.path("fields").isArray()) {
            result.getErrors().add("信息提取节点未选择提取字段：" + nodeId);
        } else if ("SLOT_EXTRACT".equals(type) && config.path("fields").isEmpty()) {
            result.getErrors().add("信息提取节点至少需要选择一个字段：" + nodeId);
        }
        if ("CUSTOMER_UPDATE".equals(type) && !config.path("fields").isArray()) {
            result.getErrors().add("更新客户节点未选择允许更新的字段：" + nodeId);
        } else if ("CUSTOMER_UPDATE".equals(type) && config.path("fields").isEmpty()) {
            result.getErrors().add("更新客户节点至少需要选择一个允许更新的字段：" + nodeId);
        }
        if ("TRANSFER_QUEUE".equals(type) && config.path("queueCode").asText("").isBlank()) {
            result.getErrors().add("转技能组节点未选择目标队列：" + nodeId);
        }
        if ("TRANSFER_EXTENSION".equals(type) && config.path("extension").asText("").isBlank()) {
            result.getErrors().add("转分机节点未填写目标分机：" + nodeId);
        }
        if ("TRANSFER_IVR".equals(type) && !config.path("ivrFlowId").asText("").matches("^[0-9]{1,20}$")) {
            result.getErrors().add("转 IVR 节点未填写有效的 IVR 流程 ID：" + nodeId);
        }
        if ("AUTO_OUTBOUND_WRITEBACK".equals(type)
            && !OUTBOUND_RESULT_CODES.contains(config.path("resultCode").asText(""))) {
            result.getErrors().add("外呼回写节点未选择有效外呼结果：" + nodeId);
        }
    }

    private Set<String> configuredIntentCodes(JsonNode config) {
        JsonNode codes = config.path("intentCodes");
        if (!codes.isArray()) return Set.of();
        Set<String> result = new HashSet<>();
        codes.forEach(item -> {
            String code = item.asText("").trim();
            if (!code.isEmpty()) result.add(code);
        });
        return result;
    }

    private Set<String> reachable(String start, Map<String, Set<String>> outgoing) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            queue.addAll(outgoing.getOrDefault(current, Set.of()));
        }
        return visited;
    }

    private boolean hasCycle(String node, Map<String, Set<String>> outgoing, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) return true;
        if (visited.contains(node)) return false;
        visiting.add(node);
        for (String target : outgoing.getOrDefault(node, Set.of())) {
            if (hasCycle(target, outgoing, visiting, visited)) return true;
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    private JsonNode parseDefinition(String definitionJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(definitionJson);
            if (root == null || !root.isObject()) throw new ServiceException("AI 编排定义必须是 JSON 对象");
            return root;
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("AI 编排定义 JSON 无效：" + exception.getMessage());
        }
    }

    private String normalizeDefinition(JsonNode definition) {
        try {
            return OBJECT_MAPPER.writeValueAsString(definition);
        } catch (Exception exception) {
            throw new ServiceException("AI 编排定义 JSON 序列化失败：" + exception.getMessage());
        }
    }

    private void validateRequest(AiWorkflowRequest request) {
        if (!SCENE_TYPES.contains(request.getSceneType())) throw new ServiceException("不支持的 AI 编排场景");
    }

    private void ensureCode(String code, Long excludedId) {
        long count = workflowMapper.selectCount(new LambdaQueryWrapper<AiWorkflow>()
            .eq(AiWorkflow::getWorkflowCode, code.trim().toUpperCase())
            .ne(excludedId != null, AiWorkflow::getId, excludedId));
        if (count > 0) throw new ServiceException("AI 编排编码已存在");
    }

    private void fill(AiWorkflow workflow, AiWorkflowRequest request) {
        workflow.setWorkflowCode(request.getWorkflowCode().trim().toUpperCase());
        workflow.setWorkflowName(request.getWorkflowName().trim());
        workflow.setSceneType(request.getSceneType());
        workflow.setDescription(request.getDescription());
        workflow.setEnabled(request.getEnabled());
    }

    private AiWorkflow requireWorkflow(Long id) {
        AiWorkflow workflow = workflowMapper.selectById(id);
        if (workflow == null) throw new ServiceException("AI 编排不存在");
        return workflow;
    }

    private AiAgent requireAgent(Long id) {
        AiAgent agent = agentMapper.selectById(id);
        if (agent == null) throw new ServiceException("AI 助手不存在");
        return agent;
    }

    private AiWorkflowVersion currentDraft(Long workflowId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<AiWorkflowVersion>()
            .eq(AiWorkflowVersion::getWorkflowId, workflowId).eq(AiWorkflowVersion::getStatus, "DRAFT")
            .orderByDesc(AiWorkflowVersion::getVersionNo).last("LIMIT 1"));
    }

    private List<AiWorkflowVersion> workflowVersions(Long workflowId) {
        return versionMapper.selectList(new LambdaQueryWrapper<AiWorkflowVersion>()
            .eq(AiWorkflowVersion::getWorkflowId, workflowId).orderByDesc(AiWorkflowVersion::getVersionNo));
    }

    private int bindingCount(Long workflowId) {
        return Math.toIntExact(bindingMapper.selectCount(new LambdaQueryWrapper<AiAgentWorkflowBinding>()
            .eq(AiAgentWorkflowBinding::getWorkflowId, workflowId)));
    }

    private AiWorkflowResponse response(AiWorkflow workflow, List<AiWorkflowVersion> versions, int bindingCount) {
        AiWorkflowVersion draft = versions.stream().filter(item -> "DRAFT".equals(item.getStatus())).findFirst().orElse(null);
        AiWorkflowVersion published = versions.stream().filter(item -> Objects.equals(item.getId(), workflow.getCurrentPublishedVersionId()))
            .findFirst().orElse(null);
        AiWorkflowResponse response = new AiWorkflowResponse();
        response.setId(workflow.getId());
        response.setWorkflowCode(workflow.getWorkflowCode());
        response.setWorkflowName(workflow.getWorkflowName());
        response.setSceneType(workflow.getSceneType());
        response.setDescription(workflow.getDescription());
        response.setEnabled(workflow.getEnabled());
        response.setVersion(workflow.getVersion());
        response.setDraftVersionId(draft == null ? null : draft.getId());
        response.setDraftVersionNo(draft == null ? null : draft.getVersionNo());
        response.setPublishedVersionId(published == null ? null : published.getId());
        response.setPublishedVersionNo(published == null ? null : published.getVersionNo());
        response.setBindingCount(bindingCount);
        response.setUpdateTime(workflow.getUpdateTime());
        return response;
    }

    private AiWorkflowVersionResponse versionResponse(AiWorkflowVersion version) {
        AiWorkflowVersionResponse response = new AiWorkflowVersionResponse();
        response.setId(version.getId());
        response.setWorkflowId(version.getWorkflowId());
        response.setVersionNo(version.getVersionNo());
        response.setVersionName(version.getVersionName());
        response.setStatus(version.getStatus());
        response.setDefinitionJson(version.getDefinitionJson());
        response.setDefinitionHash(version.getDefinitionHash());
        response.setPublishedBy(version.getPublishedBy());
        response.setPublishedAt(version.getPublishedAt());
        response.setCreateTime(version.getCreateTime());
        response.setUpdateTime(version.getUpdateTime());
        return response;
    }

    private AiAgentWorkflowBindingResponse bindingResponse(AiAgentWorkflowBinding binding, AiWorkflow workflow,
                                                           AiWorkflowVersion version) {
        AiAgentWorkflowBindingResponse response = new AiAgentWorkflowBindingResponse();
        response.setId(binding.getId());
        response.setAiAgentId(binding.getAiAgentId());
        response.setSceneType(binding.getSceneType());
        response.setWorkflowId(binding.getWorkflowId());
        response.setWorkflowName(workflow == null ? null : workflow.getWorkflowName());
        response.setWorkflowVersionId(binding.getWorkflowVersionId());
        response.setWorkflowVersionNo(version == null ? null : version.getVersionNo());
        response.setFallbackAction(binding.getFallbackAction());
        response.setEnabled(binding.getEnabled());
        return response;
    }
}
