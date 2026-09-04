package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiWorkflow;
import org.dromara.ai.domain.AiAgentWorkflowBinding;
import org.dromara.ai.domain.AiWorkflowExecution;
import org.dromara.ai.domain.AiWorkflowNodeLog;
import org.dromara.ai.domain.AiWorkflowVersion;
import org.dromara.ai.domain.AiWorkflowWait;
import org.dromara.ai.domain.request.AiWorkflowTestInputRequest;
import org.dromara.ai.domain.request.AiWorkflowTestStartRequest;
import org.dromara.ai.domain.response.AiWorkflowNodeTraceResponse;
import org.dromara.ai.domain.response.AiWorkflowTestExecutionResponse;
import org.dromara.ai.domain.response.AiWorkflowVoiceExecutionResponse;
import org.dromara.ai.mapper.AiAgentWorkflowBindingMapper;
import org.dromara.ai.mapper.AiWorkflowExecutionMapper;
import org.dromara.ai.mapper.AiWorkflowMapper;
import org.dromara.ai.mapper.AiWorkflowNodeLogMapper;
import org.dromara.ai.mapper.AiWorkflowVersionMapper;
import org.dromara.ai.mapper.AiWorkflowWaitMapper;
import org.dromara.ai.service.AiWorkflowRuntimeService;
import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandlerRegistry;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiWorkflowRuntimeServiceImpl implements AiWorkflowRuntimeService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_NODES_PER_RESUME = 50;
    private static final int MAX_NODES_PER_EXECUTION = 500;

    private final AiWorkflowMapper workflowMapper;
    private final AiWorkflowVersionMapper versionMapper;
    private final AiWorkflowExecutionMapper executionMapper;
    private final AiWorkflowNodeLogMapper nodeLogMapper;
    private final AiWorkflowWaitMapper waitMapper;
    private final AiAgentWorkflowBindingMapper bindingMapper;
    private final AiWorkflowNodeHandlerRegistry handlerRegistry;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiWorkflowTestExecutionResponse startTest(Long workflowId, AiWorkflowTestStartRequest request) {
        AiWorkflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) throw new ServiceException("AI 编排不存在");
        AiWorkflowVersion version = versionMapper.selectOne(new LambdaQueryWrapper<AiWorkflowVersion>()
            .eq(AiWorkflowVersion::getWorkflowId, workflowId)
            .eq(AiWorkflowVersion::getStatus, "DRAFT")
            .orderByDesc(AiWorkflowVersion::getVersionNo).last("limit 1"));
        if (version == null) throw new ServiceException("AI 编排没有可测试的草稿版本");
        JsonNode definition = definition(version);
        String startNodeId = findStartNode(definition);
        RuntimeContext context = new RuntimeContext();
        context.variables.putAll(request.getVariables() == null ? Map.of() : request.getVariables());
        context.variables.putIfAbsent("workflow.testMode", true);
        context.variables.putIfAbsent("workflow.clarifyCount", 0);

        LocalDateTime now = LocalDateTime.now();
        AiWorkflowExecution execution = new AiWorkflowExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setWorkflowId(workflowId);
        execution.setWorkflowVersionId(version.getId());
        execution.setAiAgentId(request.getAgentId());
        execution.setChannelType("TEST");
        execution.setStatus("RUNNING");
        execution.setCurrentNodeId(startNodeId);
        execution.setContextJson(writeContext(context));
        execution.setTurnNo(0);
        execution.setTotalNodeCount(0);
        execution.setStartedAt(now);
        execution.setLastActiveAt(now);
        execution.setLockVersion(0);
        executionMapper.insert(execution);
        run(execution, version, definition, context);
        return response(execution, version, context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiWorkflowTestExecutionResponse input(String executionId, AiWorkflowTestInputRequest request) {
        AiWorkflowExecution execution = requireExecution(executionId);
        AiWorkflowVersion version = requireVersion(execution.getWorkflowVersionId());
        RuntimeContext context = readContext(execution.getContextJson());
        if (request.getInputId().equals(execution.getLastInputId())) return response(execution, version, context);
        resumeInput(execution, version, context, request.getInputId(), request.getText());
        return response(execution, version, context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<AiWorkflowVoiceExecutionResponse> startVoice(Long agentId, String businessCallId, String sceneType,
                                                                 Map<String, Object> variables) {
        AiAgentWorkflowBinding binding = bindingMapper.selectOne(new LambdaQueryWrapper<AiAgentWorkflowBinding>()
            .eq(AiAgentWorkflowBinding::getAiAgentId, agentId)
            .eq(AiAgentWorkflowBinding::getSceneType, sceneType)
            .eq(AiAgentWorkflowBinding::getEnabled, true)
            .last("limit 1"));
        if (binding == null) return Optional.empty();
        AiWorkflow workflow = workflowMapper.selectById(binding.getWorkflowId());
        if (workflow == null || !Boolean.TRUE.equals(workflow.getEnabled())) return Optional.empty();
        AiWorkflowVersion version = requireVersion(binding.getWorkflowVersionId());
        if (!"PUBLISHED".equals(version.getStatus())) throw new ServiceException("AI 助手绑定的工作流版本未发布");
        JsonNode definition = definition(version);
        RuntimeContext context = new RuntimeContext();
        context.variables.putAll(variables == null ? Map.of() : variables);
        context.variables.put("workflow.sceneType", sceneType);
        context.variables.putIfAbsent("workflow.clarifyCount", 0);

        LocalDateTime now = LocalDateTime.now();
        AiWorkflowExecution execution = new AiWorkflowExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setWorkflowId(binding.getWorkflowId());
        execution.setWorkflowVersionId(version.getId());
        execution.setAiAgentId(agentId);
        execution.setBusinessCallId(businessCallId);
        execution.setChannelType(sceneType);
        execution.setStatus("RUNNING");
        execution.setCurrentNodeId(findStartNode(definition));
        execution.setContextJson(writeContext(context));
        execution.setTurnNo(0);
        execution.setTotalNodeCount(0);
        execution.setStartedAt(now);
        execution.setLastActiveAt(now);
        execution.setLockVersion(0);
        executionMapper.insert(execution);
        run(execution, version, definition, context);
        return Optional.of(voiceResponse(execution, context));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiWorkflowVoiceExecutionResponse voiceInput(String executionId, String inputId, String text) {
        AiWorkflowExecution execution = requireExecution(executionId);
        AiWorkflowVersion version = requireVersion(execution.getWorkflowVersionId());
        RuntimeContext context = readContext(execution.getContextJson());
        if (inputId.equals(execution.getLastInputId())) return voiceResponse(execution, context);
        if ("WAITING_TTS".equals(execution.getStatus())) {
            resumeWait(executionId, "TTS", "INTERRUPTED");
            clearPendingAction(context);
            execution.setStatus("RUNNING");
            run(execution, version, definition(version), context);
            if (!"WAITING_INPUT".equals(execution.getStatus())) return voiceResponse(execution, context);
        }
        resumeInput(execution, version, context, inputId, text);
        return voiceResponse(execution, context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiWorkflowVoiceExecutionResponse voiceTtsCompleted(String executionId) {
        AiWorkflowExecution execution = requireExecution(executionId);
        RuntimeContext context = readContext(execution.getContextJson());
        if (!"WAITING_TTS".equals(execution.getStatus())) return voiceResponse(execution, context);
        resumeWait(executionId, "TTS", "RESUMED");
        clearPendingAction(context);
        execution.setStatus("RUNNING");
        AiWorkflowVersion version = requireVersion(execution.getWorkflowVersionId());
        run(execution, version, definition(version), context);
        return voiceResponse(execution, context);
    }

    @Override
    public AiWorkflowTestExecutionResponse execution(String executionId) {
        AiWorkflowExecution execution = requireExecution(executionId);
        return response(execution, requireVersion(execution.getWorkflowVersionId()), readContext(execution.getContextJson()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void terminate(String executionId, String reason) {
        AiWorkflowExecution execution = requireExecution(executionId);
        if (isTerminal(execution.getStatus())) return;
        execution.setStatus("TERMINATED");
        execution.setFailureMessage(trim(reason, 1000));
        execution.setEndedAt(LocalDateTime.now());
        execution.setLastActiveAt(LocalDateTime.now());
        updateExecution(execution);
        AiWorkflowWait wait = activeWait(executionId);
        if (wait != null) {
            wait.setStatus("CANCELLED");
            wait.setResumedAt(LocalDateTime.now());
            waitMapper.updateById(wait);
        }
    }

    private void run(AiWorkflowExecution execution, AiWorkflowVersion version, JsonNode definition, RuntimeContext context) {
        int steps = 0;
        while ("RUNNING".equals(execution.getStatus())) {
            if (++steps > MAX_NODES_PER_RESUME || execution.getTotalNodeCount() >= MAX_NODES_PER_EXECUTION) {
                fail(execution, "EXECUTION_LIMIT", "工作流执行节点数超过安全限制");
                break;
            }
            JsonNode node = findNode(definition, execution.getCurrentNodeId());
            String nodeType = node.path("type").asText();
            LocalDateTime startedAt = LocalDateTime.now();
            AiWorkflowNodeResult result;
            try {
                result = handlerRegistry.require(nodeType).execute(new AiWorkflowNodeContext(
                    node, context.variables, context.lastInput, execution.getAiAgentId()));
                context.variables.putAll(result.variableUpdates());
                if ("SPEAK".equals(result.status()) && result.output() != null) context.outputMessages.add(result.output());
                logNode(execution, node, result, context.lastInput, startedAt, null);
            } catch (Exception exception) {
                logNode(execution, node, null, context.lastInput, startedAt, exception);
                fail(execution, "NODE_EXECUTION_FAILED", exception.getMessage());
                break;
            }
            execution.setTotalNodeCount(execution.getTotalNodeCount() + 1);
            execution.setLastActiveAt(LocalDateTime.now());
            switch (result.status()) {
                case "CONTINUE" -> execution.setCurrentNodeId(nextNodeId(definition, node.path("id").asText(), result.branchValue()));
                case "SPEAK" -> {
                    execution.setCurrentNodeId(nextNodeId(definition, node.path("id").asText(), result.branchValue()));
                    if (!"TEST".equals(execution.getChannelType())) {
                        context.pendingActionType = "SPEAK";
                        context.pendingActionText = result.output();
                        execution.setStatus("WAITING_TTS");
                        createWait(execution, node, "TTS", 120);
                    }
                }
                case "WAIT_INPUT" -> {
                    execution.setStatus("WAITING_INPUT");
                    context.pendingActionType = "WAIT_INPUT";
                    long timeout = "TEST".equals(execution.getChannelType())
                        ? 3600 : node.path("config").path("timeoutSeconds").asLong(15);
                    createWait(execution, node, "INPUT", timeout);
                }
                case "WAIT_ASYNC" -> {
                    execution.setStatus("WAITING_ASYNC");
                    createWait(execution, node, result.waitType(), 120);
                }
                case "TRANSFERRED" -> {
                    context.pendingActionType = nodeType;
                    context.pendingActionTarget = result.output();
                    finish(execution, "TRANSFERRED");
                }
                case "HANGUP" -> {
                    context.pendingActionType = "HANGUP";
                    finish(execution, "COMPLETED");
                }
                case "COMPLETED" -> {
                    context.pendingActionType = "COMPLETED";
                    finish(execution, "COMPLETED");
                }
                default -> fail(execution, "INVALID_NODE_RESULT", "节点返回了无效状态：" + result.status());
            }
        }
        execution.setContextJson(writeContext(context));
        updateExecution(execution);
    }

    private void resumeInput(AiWorkflowExecution execution, AiWorkflowVersion version, RuntimeContext context,
                             String inputId, String text) {
        if (!"WAITING_INPUT".equals(execution.getStatus())) throw new ServiceException("当前工作流不在等待客户输入状态");
        AiWorkflowWait wait = activeWait(execution.getExecutionId());
        if (wait == null || !"INPUT".equals(wait.getWaitType())) throw new ServiceException("工作流输入等待令牌不存在或已失效");
        if (wait.getExpiresAt() != null && wait.getExpiresAt().isBefore(LocalDateTime.now())) throw new ServiceException("等待客户输入已超时");
        wait.setStatus("RESUMED");
        wait.setResumedAt(LocalDateTime.now());
        waitMapper.updateById(wait);
        clearPendingAction(context);
        context.lastInput = text.trim();
        context.variables.put("conversation.currentInput", context.lastInput);
        execution.setLastInputId(inputId);
        execution.setTurnNo(execution.getTurnNo() + 1);
        JsonNode definition = definition(version);
        execution.setCurrentNodeId(nextNodeId(definition, execution.getCurrentNodeId(), null));
        execution.setStatus("RUNNING");
        run(execution, version, definition, context);
    }

    private void resumeWait(String executionId, String waitType, String status) {
        AiWorkflowWait wait = activeWait(executionId);
        if (wait == null || !waitType.equals(wait.getWaitType())) throw new ServiceException("工作流等待令牌不存在或已失效");
        wait.setStatus(status);
        wait.setResumedAt(LocalDateTime.now());
        waitMapper.updateById(wait);
    }

    private void clearPendingAction(RuntimeContext context) {
        context.pendingActionType = null;
        context.pendingActionText = null;
        context.pendingActionTarget = null;
    }

    private void createWait(AiWorkflowExecution execution, JsonNode node, String waitType, long timeoutSeconds) {
        AiWorkflowWait wait = new AiWorkflowWait();
        wait.setExecutionId(execution.getExecutionId());
        wait.setNodeId(node.path("id").asText());
        wait.setWaitType(waitType);
        wait.setWaitToken(UUID.randomUUID().toString());
        wait.setExpectedInputType("INPUT".equals(waitType) ? "FINAL_TEXT" : "CALLBACK");
        wait.setStatus("WAITING");
        wait.setExpiresAt(LocalDateTime.now().plusSeconds(Math.max(1, timeoutSeconds)));
        waitMapper.insert(wait);
    }

    private void logNode(AiWorkflowExecution execution, JsonNode node, AiWorkflowNodeResult result, String currentInput,
                         LocalDateTime startedAt, Exception exception) {
        AiWorkflowNodeLog log = new AiWorkflowNodeLog();
        log.setExecutionId(execution.getExecutionId());
        log.setNodeId(node.path("id").asText());
        log.setNodeType(node.path("type").asText());
        log.setNodeName(node.path("name").asText(node.path("type").asText()));
        log.setTurnNo(execution.getTurnNo());
        log.setAttemptNo(1);
        log.setInputSummary(trim(currentInput, 1000));
        log.setOutputSummary(trim(result == null ? null : result.output(), 2000));
        log.setExecutionStatus(exception == null ? result.status() : "FAILED");
        log.setBranchValue(result == null ? null : result.branchValue());
        if (exception != null) {
            log.setErrorCode("NODE_EXECUTION_FAILED");
            log.setErrorMessage(trim(exception.getMessage(), 1000));
        }
        log.setStartedAt(startedAt);
        log.setEndedAt(LocalDateTime.now());
        log.setDurationMs(Duration.between(startedAt, log.getEndedAt()).toMillis());
        nodeLogMapper.insert(log);
    }

    private String nextNodeId(JsonNode definition, String source, String branch) {
        List<JsonNode> outgoing = new ArrayList<>();
        definition.path("edges").forEach(edge -> {
            if (source.equals(edge.path("source").asText())) outgoing.add(edge);
        });
        JsonNode selected = branch == null
            ? outgoing.stream().filter(edge -> edge.path("condition").asText("").isBlank()).findFirst()
                .orElse(outgoing.size() == 1 ? outgoing.get(0) : null)
            : outgoing.stream().filter(edge -> branch.equals(edge.path("condition").asText())).findFirst().orElse(null);
        if (selected == null) throw new ServiceException("节点没有可执行的后续分支：" + source + (branch == null ? "" : " / " + branch));
        return selected.path("target").asText();
    }

    private JsonNode findNode(JsonNode definition, String nodeId) {
        for (JsonNode node : definition.path("nodes")) if (nodeId.equals(node.path("id").asText())) return node;
        throw new ServiceException("工作流节点不存在：" + nodeId);
    }

    private String findStartNode(JsonNode definition) {
        for (JsonNode node : definition.path("nodes")) if ("START".equals(node.path("type").asText())) return node.path("id").asText();
        throw new ServiceException("工作流没有开始节点");
    }

    private JsonNode definition(AiWorkflowVersion version) {
        try {
            return OBJECT_MAPPER.readTree(version.getDefinitionJson());
        } catch (Exception exception) {
            throw new ServiceException("AI 编排定义无法解析：" + exception.getMessage());
        }
    }

    private String writeContext(RuntimeContext context) {
        try {
            return OBJECT_MAPPER.writeValueAsString(context);
        } catch (Exception exception) {
            throw new ServiceException("工作流上下文无法保存：" + exception.getMessage());
        }
    }

    private RuntimeContext readContext(String json) {
        if (json == null || json.isBlank()) return new RuntimeContext();
        try {
            return OBJECT_MAPPER.readValue(json, RuntimeContext.class);
        } catch (Exception exception) {
            throw new ServiceException("工作流上下文无法读取：" + exception.getMessage());
        }
    }

    private AiWorkflowExecution requireExecution(String executionId) {
        AiWorkflowExecution value = executionMapper.selectOne(new LambdaQueryWrapper<AiWorkflowExecution>()
            .eq(AiWorkflowExecution::getExecutionId, executionId).last("limit 1"));
        if (value == null) throw new ServiceException("工作流测试执行不存在");
        return value;
    }

    private AiWorkflowVersion requireVersion(Long id) {
        AiWorkflowVersion value = versionMapper.selectById(id);
        if (value == null) throw new ServiceException("工作流版本不存在");
        return value;
    }

    private AiWorkflowWait activeWait(String executionId) {
        return waitMapper.selectOne(new LambdaQueryWrapper<AiWorkflowWait>()
            .eq(AiWorkflowWait::getExecutionId, executionId).eq(AiWorkflowWait::getStatus, "WAITING")
            .orderByDesc(AiWorkflowWait::getId).last("limit 1"));
    }

    private void updateExecution(AiWorkflowExecution execution) {
        if (executionMapper.updateById(execution) == 0) throw new ServiceException("工作流执行状态已被其他请求更新，请刷新后重试");
    }

    private void fail(AiWorkflowExecution execution, String code, String message) {
        execution.setStatus("FAILED");
        execution.setFailureCode(code);
        execution.setFailureMessage(trim(message, 1000));
        execution.setEndedAt(LocalDateTime.now());
    }

    private void finish(AiWorkflowExecution execution, String status) {
        execution.setStatus(status);
        execution.setEndedAt(LocalDateTime.now());
    }

    private boolean isTerminal(String status) {
        return List.of("COMPLETED", "TRANSFERRED", "FAILED", "TERMINATED").contains(status);
    }

    private AiWorkflowTestExecutionResponse response(AiWorkflowExecution execution, AiWorkflowVersion version, RuntimeContext context) {
        AiWorkflowTestExecutionResponse result = new AiWorkflowTestExecutionResponse();
        result.setExecutionId(execution.getExecutionId());
        result.setWorkflowId(execution.getWorkflowId());
        result.setWorkflowVersionId(execution.getWorkflowVersionId());
        result.setWorkflowVersionNo(version.getVersionNo());
        result.setStatus(execution.getStatus());
        result.setCurrentNodeId(execution.getCurrentNodeId());
        result.setTurnNo(execution.getTurnNo());
        result.setOutputMessages(new ArrayList<>(context.outputMessages));
        result.setVariables(new LinkedHashMap<>(context.variables));
        result.setFailureMessage(execution.getFailureMessage());
        AiWorkflowWait wait = activeWait(execution.getExecutionId());
        if (wait != null) {
            result.setWaitingType(wait.getWaitType());
            result.setWaitingToken(wait.getWaitToken());
        }
        result.setTraces(nodeLogMapper.selectList(new LambdaQueryWrapper<AiWorkflowNodeLog>()
                .eq(AiWorkflowNodeLog::getExecutionId, execution.getExecutionId()).orderByAsc(AiWorkflowNodeLog::getId))
            .stream().map(this::traceResponse).toList());
        return result;
    }

    private AiWorkflowVoiceExecutionResponse voiceResponse(AiWorkflowExecution execution, RuntimeContext context) {
        AiWorkflowVoiceExecutionResponse result = new AiWorkflowVoiceExecutionResponse();
        result.setExecutionId(execution.getExecutionId());
        result.setWorkflowId(execution.getWorkflowId());
        result.setWorkflowVersionId(execution.getWorkflowVersionId());
        result.setStatus(execution.getStatus());
        result.setActionType(context.pendingActionType);
        result.setText(context.pendingActionText);
        result.setTarget(context.pendingActionTarget);
        result.setFailureMessage(execution.getFailureMessage());
        AiWorkflowWait wait = activeWait(execution.getExecutionId());
        if (wait != null) result.setWaitingToken(wait.getWaitToken());
        return result;
    }

    private AiWorkflowNodeTraceResponse traceResponse(AiWorkflowNodeLog value) {
        AiWorkflowNodeTraceResponse result = new AiWorkflowNodeTraceResponse();
        result.setId(value.getId());
        result.setNodeId(value.getNodeId());
        result.setNodeType(value.getNodeType());
        result.setNodeName(value.getNodeName());
        result.setTurnNo(value.getTurnNo());
        result.setStatus(value.getExecutionStatus());
        result.setBranchValue(value.getBranchValue());
        result.setInputSummary(value.getInputSummary());
        result.setOutputSummary(value.getOutputSummary());
        result.setDurationMs(value.getDurationMs());
        result.setStartedAt(value.getStartedAt());
        return result;
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Data
    private static class RuntimeContext {
        private Map<String, Object> variables = new LinkedHashMap<>();
        private List<String> outputMessages = new ArrayList<>();
        private String lastInput;
        private String pendingActionType;
        private String pendingActionText;
        private String pendingActionTarget;
    }
}
