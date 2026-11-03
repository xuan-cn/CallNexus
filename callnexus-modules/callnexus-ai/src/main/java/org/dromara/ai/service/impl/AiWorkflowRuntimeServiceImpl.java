package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.dromara.ai.domain.response.AiChatTurnResult;
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
import org.dromara.ai.workflow.AiWorkflowConversationRules;
import org.dromara.ai.workflow.AiWorkflowAnswerPolicy;
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
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
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
    private final org.dromara.ai.workflow.AiConversationRuleMatcher ruleMatcher;

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
        if ("PLAYBACK_COMPLETED".equals(request.getSignal()) && "WAITING_TTS".equals(execution.getStatus())) {
            execution.setLastInputId(request.getInputId());
            updateExecution(execution);
            voiceTtsCompleted(executionId);
            return execution(executionId);
        }
        if ("SILENCE".equals(request.getSignal())) {
            signal(execution, version, context, request.getInputId(), "SILENCE");
            return response(execution, version, context);
        }
        if ("INTERRUPT".equals(request.getSignal()) && "WAITING_TTS".equals(execution.getStatus())) {
            voiceInput(executionId, request.getInputId(), request.getText());
            return execution(executionId);
        }
        if (request.getSignal() != null && !request.getSignal().isBlank()) throw new ServiceException("不支持的测试事件");
        if (request.getText() == null || request.getText().isBlank()) throw new ServiceException("客户输入不能为空");
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
        if (isTerminal(execution.getStatus())) return voiceResponse(execution, context);
        if (text == null || text.isBlank()) throw new ServiceException("客户输入不能为空");
        if ("WAITING_TTS".equals(execution.getStatus())) {
            if (context.disclosureNodeId != null) {
                if ("DISABLED".equals(context.playbackConfig.getOrDefault("disclosureBargeInMode", "DISABLED"))) return voiceResponse(execution, context);
                resumeWait(executionId, "TTS", "INTERRUPTED");
                context.variables.put("workflow.disclosure." + context.disclosureNodeId, "INTERRUPTED");
                execution.setLastInputId(inputId);
                execution.setTurnNo(execution.getTurnNo() + 1);
                context.lastInput = text.trim();
                JsonNode node = findNode(definition(version), context.disclosureNodeId);
                if (!applyRule(execution, version, node, context, null, text)) replayDisclosure(execution, node, context, text);
                persist(execution, context);
                return voiceResponse(execution, context);
            }
            resumeWait(executionId, "TTS", "INTERRUPTED");
            String afterTtsActionType = context.afterTtsActionType;
            String afterTtsActionTarget = context.afterTtsActionTarget;
            clearPendingAction(context);
            if (afterTtsActionType != null && !afterTtsActionType.isBlank()) {
                completeDirectAction(execution, context, afterTtsActionType, afterTtsActionTarget);
                execution.setContextJson(writeContext(context));
                updateExecution(execution);
                return voiceResponse(execution, context);
            } else if (context.resumeSameNode) {
                execution.setStatus("WAITING_INPUT");
                createWait(execution, findNode(definition(version), execution.getCurrentNodeId()), "INPUT", 15);
            } else {
                execution.setStatus("RUNNING");
                run(execution, version, definition(version), context);
            }
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
        if (context.disclosureNodeId != null) {
            context.variables.put("workflow.disclosure." + context.disclosureNodeId,
                "TEST".equals(execution.getChannelType()) ? "SIMULATED_COMPLETED" : "PLAYBACK_COMPLETED");
            context.disclosureNodeId = null;
            context.disclosureText = null;
        }
        String afterTtsActionType = context.afterTtsActionType;
        String afterTtsActionTarget = context.afterTtsActionTarget;
        clearPendingAction(context);
        AiWorkflowVersion version = requireVersion(execution.getWorkflowVersionId());
        if (afterTtsActionType != null && !afterTtsActionType.isBlank()) {
            completeDirectAction(execution, context, afterTtsActionType, afterTtsActionTarget);
            execution.setContextJson(writeContext(context));
            updateExecution(execution);
        } else if (context.resumeSameNode) {
            execution.setStatus("WAITING_INPUT");
            context.pendingActionType = "WAIT_INPUT";
            createWait(execution, findNode(definition(version), execution.getCurrentNodeId()), "INPUT", 15);
            execution.setContextJson(writeContext(context));
            updateExecution(execution);
        } else {
            execution.setStatus("RUNNING");
            run(execution, version, definition(version), context);
        }
        return voiceResponse(execution, context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiWorkflowVoiceExecutionResponse voiceSignal(String executionId, String inputId, String event) {
        AiWorkflowExecution execution = requireExecution(executionId);
        RuntimeContext context = readContext(execution.getContextJson());
        AiWorkflowVersion version = requireVersion(execution.getWorkflowVersionId());
        if (!isTerminal(execution.getStatus()) && AiWorkflowConversationRules.match(AiWorkflowConversationRules.effective(definition(version),
            findNode(definition(version), execution.getCurrentNodeId())), event, null) == null) return null;
        signal(execution, version, context, inputId, event);
        return voiceResponse(execution, context);
    }

    private void signal(AiWorkflowExecution execution, AiWorkflowVersion version, RuntimeContext context, String inputId, String event) {
        if (!"SILENCE".equals(event)) throw new ServiceException("不支持的语音事件");
        if (!"WAITING_INPUT".equals(execution.getStatus()) || inputId.equals(execution.getLastInputId())) return;
        JsonNode node = findNode(definition(version), execution.getCurrentNodeId());
        JsonNode rules = AiWorkflowConversationRules.effective(definition(version), node);
        if (AiWorkflowConversationRules.match(rules, event, null) == null) return;
        resumeWait(execution.getExecutionId(), "INPUT", "RESUMED");
        execution.setLastInputId(inputId);
        execution.setTurnNo(execution.getTurnNo() + 1);
        applyRule(execution, version, node, context, event, "");
        persist(execution, context);
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
        RuntimeContext context = readContext(execution.getContextJson());
        if (context.disclosureNodeId != null) {
            context.variables.put("workflow.disclosure." + context.disclosureNodeId, "ABORTED");
            execution.setContextJson(writeContext(context));
        }
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
                    node, context.variables, context.lastInput, execution.getAiAgentId(), execution.getChannelType(),
                    workflowSettings(definition)));
                context.variables.putAll(result.variableUpdates());
                if ("DIALOGUE_STEP".equals(nodeType) && context.lastInput != null) {
                    Object decision = result.variableUpdates().get("workflow.dialogue." + node.path("id").asText() + ".decision");
                    String event = "OUT_OF_SCOPE".equals(decision) ? "OUT_OF_SCOPE" : "UNCLEAR".equals(decision) ? "INVALID" : null;
                    if (decision != null && Set.of("EXACT", "KEYWORD", "INTENT", "SELECT", "CONFIRM", "DECLINE", "QUESTION").contains(decision)) {
                        context.variables.put("workflow.rules." + node.path("id").asText() + ".INVALID.count", 0);
                        context.variables.put("workflow.rules." + node.path("id").asText() + ".OUT_OF_SCOPE.count", 0);
                    }
                    if (event != null && applyRule(execution, version, node, context, event, context.lastInput)) {
                        persist(execution, context);
                        return;
                    }
                }
                if (Set.of("SPEAK", "SPEAK_RETRY", "SPEAK_ACTION").contains(result.status()) && result.output() != null) {
                    context.outputMessages.add(result.output());
                    context.variables.put("workflow.ruleQuestion", result.output());
                }
                logNode(execution, node, result, context.lastInput, startedAt, null);
            } catch (Exception exception) {
                logNode(execution, node, null, context.lastInput, startedAt, exception);
                fail(execution, "NODE_EXECUTION_FAILED", exception.getMessage());
                break;
            }
            execution.setTotalNodeCount(execution.getTotalNodeCount() + 1);
            execution.setLastActiveAt(LocalDateTime.now());
            if (Set.of("SPEAK", "SPEAK_RETRY", "SPEAK_ACTION", "STREAM_AI", "STREAM_AI_RETRY").contains(result.status())) {
                context.playbackNodeId = node.path("id").asText();
                context.playbackConfig = OBJECT_MAPPER.convertValue(node.path("config"), Map.class);
            }
            switch (result.status()) {
                case "CONTINUE" -> execution.setCurrentNodeId(nextNodeId(definition, node.path("id").asText(), result.branchValue()));
                case "SPEAK" -> {
                    execution.setCurrentNodeId(nextNodeId(definition, node.path("id").asText(), result.branchValue()));
                    boolean required = node.path("config").path("requiredDisclosure").asBoolean(false);
                    if (required) {
                        context.disclosureNodeId = node.path("id").asText();
                        context.disclosureText = result.output();
                        context.disclosureInterruptions = 0;
                        context.variables.put("workflow.disclosure." + context.disclosureNodeId, "PENDING");
                    }
                    if (required || !"TEST".equals(execution.getChannelType())) {
                        context.pendingActionType = "SPEAK";
                        context.pendingActionText = result.output();
                        execution.setStatus("WAITING_TTS");
                        createWait(execution, node, "TTS", 120);
                    }
                }
                case "SPEAK_RETRY" -> waitForRetryInput(execution, node, context, result.output(), false);
                case "SPEAK_ACTION" -> speakThenAction(execution, node, context, result);
                case "PLAY_MEDIA" -> {
                    execution.setCurrentNodeId(nextNodeId(definition, node.path("id").asText(), result.branchValue()));
                    if (!"TEST".equals(execution.getChannelType())) {
                        context.pendingActionType = "PLAY_MEDIA";
                        context.pendingActionTarget = result.output();
                        execution.setStatus("WAITING_TTS");
                        createWait(execution, node, "MEDIA", 120);
                    }
                }
                case "STREAM_AI" -> {
                    execution.setCurrentNodeId(nextNodeId(definition, node.path("id").asText(), result.branchValue()));
                    if ("TEST".equals(execution.getChannelType())) {
                        fail(execution, "INVALID_NODE_RESULT", "测试流程不能返回流式语音动作");
                        break;
                    }
                    context.pendingActionType = "STREAM_AI";
                    context.pendingActionText = result.output();
                    context.pendingActionTarget = result.waitType();
                    execution.setStatus("WAITING_TTS");
                    createWait(execution, node, "TTS", 120);
                }
                case "STREAM_AI_RETRY" -> waitForRetryInput(execution, node, context, result.output(), true);
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
                    context.pendingActionType = result.waitType() == null ? nodeType : result.waitType();
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
                case "DIRECT_ACTION" -> completeDirectAction(execution, context, result.waitType(), result.output());
                default -> fail(execution, "INVALID_NODE_RESULT", "节点返回了无效状态：" + result.status());
            }
        }
        execution.setContextJson(writeContext(context));
        updateExecution(execution);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voiceStreamCompleted(String executionId, AiChatTurnResult result) {
        AiWorkflowExecution execution = requireExecution(executionId);
        RuntimeContext context = readContext(execution.getContextJson());
        if (!"WAITING_TTS".equals(execution.getStatus()) || !"STREAM_AI".equals(context.pendingActionType)) {
            throw new ServiceException("当前工作流不在流式回答状态");
        }
        if (result.conversationId() != null) {
            context.variables.put("ai.conversationId", result.conversationId());
        }
        context.variables.put("ai.answerSource", result.sourceType());
        context.variables.put("ai.lastAnswer", result.answer());
        context.outputMessages.add(result.answer());
        if ("KNOWLEDGE_QUERY".equals(context.pendingActionTarget)) {
            Map<String, Object> retrieval = result.retrieval() == null ? Map.of() : result.retrieval();
            context.variables.put("knowledge.source", result.sourceType());
            context.variables.put("knowledge.answer", result.answer());
            copyVariable(retrieval, context.variables, "hit", "knowledge.hit");
            copyVariable(retrieval, context.variables, "score", "knowledge.score");
            copyVariable(retrieval, context.variables, "threshold", "knowledge.threshold");
            copyVariable(retrieval, context.variables, "hitCount", "knowledge.hitCount");
            copyVariable(retrieval, context.variables, "fallback", "knowledge.fallback");
            copyVariable(retrieval, context.variables, "reason", "knowledge.reason");
            copyVariable(retrieval, context.variables, "bestFaqScore", "knowledge.bestFaqScore");
            copyVariable(retrieval, context.variables, "faqThreshold", "knowledge.faqThreshold");
            copyVariable(retrieval, context.variables, "bestDocumentScore", "knowledge.bestDocumentScore");
            copyVariable(retrieval, context.variables, "documentThreshold", "knowledge.documentThreshold");
        }
        execution.setContextJson(writeContext(context));
        execution.setLastActiveAt(LocalDateTime.now());
        updateExecution(execution);
    }

    private void copyVariable(Map<String, Object> source, Map<String, Object> target,
                              String sourceKey, String targetKey) {
        if (source.containsKey(sourceKey) && source.get(sourceKey) != null) {
            target.put(targetKey, source.get(sourceKey));
        }
    }

    private void resumeInput(AiWorkflowExecution execution, AiWorkflowVersion version, RuntimeContext context,
                             String inputId, String text) {
        if (!"WAITING_INPUT".equals(execution.getStatus())) throw new ServiceException("当前工作流不在等待客户输入状态");
        AiWorkflowWait wait = activeWait(execution.getExecutionId());
        if (wait == null || !"INPUT".equals(wait.getWaitType())) throw new ServiceException("工作流输入等待令牌不存在或已失效");
        LocalDateTime now = LocalDateTime.now();
        if (wait.getExpiresAt() != null && wait.getExpiresAt().isBefore(now)) {
            if (!isVoiceChannel(execution.getChannelType())) {
                throw new ServiceException("等待客户输入已超时");
            }
            // 语音终端可能在等待窗口内已经开始说话，但最终 ASR 结果在窗口后才到达。
            // 执行仍处于 WAITING_INPUT 且令牌仍为 WAITING 时，接受这条有效语音，避免直接终止通话。
            log.warn("语音工作流收到迟到的有效输入，继续执行，executionId={}，waitId={}，overdueMs={}",
                execution.getExecutionId(), wait.getId(), Duration.between(wait.getExpiresAt(), now).toMillis());
        }
        wait.setStatus("RESUMED");
        wait.setResumedAt(LocalDateTime.now());
        waitMapper.updateById(wait);
        clearPendingAction(context);
        context.lastInput = text.trim();
        context.variables.put("conversation.currentInput", context.lastInput);
        execution.setLastInputId(inputId);
        execution.setTurnNo(execution.getTurnNo() + 1);
        JsonNode definition = definition(version);
        JsonNode waitingNode = findNode(definition, execution.getCurrentNodeId());
        if (applyRule(execution, version, waitingNode, context, null, text)) {
            persist(execution, context);
            return;
        }
        context.variables.put("workflow.rules." + waitingNode.path("id").asText() + ".SILENCE.count", 0);
        if (context.resumeSameNode) context.resumeSameNode = false;
        else execution.setCurrentNodeId(nextNodeId(definition, execution.getCurrentNodeId(), null));
        execution.setStatus("RUNNING");
        run(execution, version, definition, context);
    }

    private void persist(AiWorkflowExecution execution, RuntimeContext context) {
        execution.setContextJson(writeContext(context));
        execution.setLastActiveAt(LocalDateTime.now());
        updateExecution(execution);
    }

    private void replayDisclosure(AiWorkflowExecution execution, JsonNode node, RuntimeContext context, String input) {
        String answer = "好的，我先把这段重要说明补充完整。";
        String mode = AiWorkflowAnswerPolicy.mode(node.path("config"), true);
        try {
            boolean acknowledgement = Set.of("你好", "您好", "嗯", "嗯你好", "嗯您好", "喂", "嗯嗯", "好", "好的", "我在听", "在听").contains(
                org.dromara.ai.workflow.handler.DialogueStepNodeHandler.normalize(input));
            if (acknowledgement) log.info("必要告知收到明确问候或附和，跳过知识检索和 Chat 模型，executionId={}，nodeId={}", execution.getExecutionId(), context.disclosureNodeId);
            if (!acknowledgement && !"OFF".equals(mode)) {
                var query = OBJECT_MAPPER.createObjectNode().put("id", "disclosure-question").put("type", "KNOWLEDGE_QUERY");
                query.putObject("config").put("queryTemplate", "{{conversation.currentInput}}").put("knowledgeOnly", "KNOWLEDGE_ONLY".equals(mode));
                var reply = handlerRegistry.require("KNOWLEDGE_QUERY").execute(new AiWorkflowNodeContext(query, Map.of("conversation.currentInput", input), input, execution.getAiAgentId(), "TEST"));
                Object source = reply.variableUpdates().get("ai.answerSource");
                if (("MODEL_FALLBACK".equals(mode) && "MODEL".equals(source) || Set.of("FAQ_EXACT", "FAQ_SEMANTIC", "DOCUMENT", "DOCUMENT_DIRECT").contains(source)
                    && Boolean.TRUE.equals(reply.variableUpdates().get("knowledge.hit")) && !Boolean.TRUE.equals(reply.variableUpdates().get("knowledge.fallback"))
                    ) && reply.output() != null && !reply.output().isBlank()) answer = reply.output();
            }
        } catch (Exception ignored) { /* No provider errors or unsupported answers are spoken. */ }
        context.disclosureInterruptions++;
        if (context.disclosureInterruptions >= node.path("config").path("disclosureMaxInterruptions").asInt(2)) context.playbackConfig.put("disclosureBargeInMode", "DISABLED");
        context.pendingActionType = "SPEAK";
        context.pendingActionText = answer + "\n" + context.disclosureText;
        context.outputMessages.add(context.pendingActionText);
        execution.setStatus("WAITING_TTS");
        createWait(execution, node, "TTS", 120);
    }

    private boolean applyRule(AiWorkflowExecution execution, AiWorkflowVersion version, JsonNode node, RuntimeContext context, String event, String text) {
        LocalDateTime ruleStartedAt = LocalDateTime.now();
        JsonNode rules = AiWorkflowConversationRules.effective(definition(version), node);
        Map<String, Object> ruleDetails = new LinkedHashMap<>();
        JsonNode rule;
        if (event == null) {
            var match = ruleMatcher.match(rules, node, text, execution.getAiAgentId(), context.variables);
            rule = match.rule();
            ruleDetails.putAll(match.details());
            if (rule == null && !ruleDetails.isEmpty()) logNode(execution, node, new AiWorkflowNodeResult("RULE_CHECK", null,
                "未触发公共规则，继续当前流程", null, Map.of("workflow.rule.details", ruleDetails)), text, ruleStartedAt, null);
        } else {
            rule = AiWorkflowConversationRules.match(rules, event, text);
            ruleDetails.put("matchReason", "EVENT");
            ruleDetails.put("aiCalled", false);
        }
        if (rule == null) return false;
        String type = rule.path("event").asText();
        String counter = "workflow.rules." + node.path("id").asText() + "." + type + ".count";
        int count = context.variables.get(counter) instanceof Number value ? value.intValue() + 1 : 1;
        context.variables.put(counter, count);
        boolean limit = event == null || count >= rule.path("maxCount").asInt(2);
        if (!limit && !"SILENCE".equals(event)) return false;
        String output = limit ? rule.path("replyText").asText("") : rule.path("retryText").asText("请问您还在听吗？");
        context.variables.put("workflow.lastRule", type);
        ruleDetails.put("event", type);
        ruleDetails.put("eventLabel", AiWorkflowConversationRules.label(type));
        ruleDetails.put("count", count);
        ruleDetails.put("maxCount", rule.path("maxCount").asInt(2));
        ruleDetails.put("action", limit ? rule.path("action").asText("HANGUP") : "RETRY");
        logNode(execution, node, new AiWorkflowNodeResult("RULE", null, "公共规则：" + AiWorkflowConversationRules.label(type), null,
            Map.of("workflow.rule.details", ruleDetails)), text, ruleStartedAt, null);
        context.playbackNodeId = node.path("id").asText();
        context.playbackConfig = new LinkedHashMap<>(Map.of("nodeBargeInMode", "DISABLED"));
        if (limit) {
            if (context.disclosureNodeId != null) context.variables.put("workflow.disclosure." + context.disclosureNodeId, "CANCELLED_BY_RULE");
            context.disclosureNodeId = null;
            context.disclosureText = null;
            context.resumeSameNode = false;
            clearPendingAction(context);
        }
        if (limit && (output.isBlank() || "TEST".equals(execution.getChannelType()))) {
            if (!output.isBlank()) context.outputMessages.add(output);
            completeDirectAction(execution, context, rule.path("action").asText("HANGUP"), rule.path("queueCode").asText(""));
        } else {
            context.pendingActionType = "SPEAK";
            context.pendingActionText = output;
            context.outputMessages.add(output);
            if (limit) { context.afterTtsActionType = rule.path("action").asText("HANGUP"); context.afterTtsActionTarget = rule.path("queueCode").asText(""); }
            execution.setStatus("WAITING_TTS");
            createWait(execution, node, "TTS", 120);
        }
        return true;
    }

    private boolean isVoiceChannel(String channelType) {
        return channelType != null && channelType.startsWith("VOICE_");
    }

    private void waitForRetryInput(AiWorkflowExecution execution, JsonNode node, RuntimeContext context,
                                   String output, boolean streamAi) {
        context.resumeSameNode = true;
        if ("TEST".equals(execution.getChannelType())) {
            execution.setStatus("WAITING_INPUT");
            context.pendingActionType = "WAIT_INPUT";
            createWait(execution, node, "INPUT", 3600);
            return;
        }
        context.pendingActionType = streamAi ? "STREAM_AI" : "SPEAK";
        context.pendingActionText = output;
        context.pendingActionTarget = streamAi ? "MODEL_REPLY" : null;
        execution.setStatus("WAITING_TTS");
        createWait(execution, node, "TTS", 120);
    }

    private void speakThenAction(AiWorkflowExecution execution, JsonNode node, RuntimeContext context,
                                 AiWorkflowNodeResult result) {
        if ("TEST".equals(execution.getChannelType())) {
            completeDirectAction(execution, context, result.waitType(), result.branchValue());
            return;
        }
        context.pendingActionType = "SPEAK";
        context.pendingActionText = result.output();
        context.afterTtsActionType = result.waitType();
        context.afterTtsActionTarget = result.branchValue();
        execution.setStatus("WAITING_TTS");
        createWait(execution, node, "TTS", 120);
    }

    private void completeDirectAction(AiWorkflowExecution execution, RuntimeContext context,
                                      String actionType, String target) {
        switch (actionType == null ? "" : actionType) {
            case "TRANSFER_QUEUE", "TRANSFER_EXTENSION", "TRANSFER_IVR" -> {
                context.pendingActionType = actionType;
                context.pendingActionTarget = target;
                finish(execution, "TRANSFERRED");
            }
            case "HANGUP" -> {
                context.pendingActionType = "HANGUP";
                finish(execution, "COMPLETED");
            }
            case "END" -> {
                context.pendingActionType = "COMPLETED";
                finish(execution, "COMPLETED");
            }
            default -> fail(execution, "INVALID_DIRECT_ACTION", "不支持的意图兜底动作：" + actionType);
        }
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
        context.afterTtsActionType = null;
        context.afterTtsActionTarget = null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> workflowSettings(JsonNode definition) {
        JsonNode settings = definition.path("settings");
        return settings.isObject() ? OBJECT_MAPPER.convertValue(settings, Map.class) : Map.of();
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
        if (result != null && "DIALOGUE_STEP".equals(node.path("type").asText())) {
            Map<String, Object> details = new LinkedHashMap<>();
            String prefix = "workflow.dialogue." + node.path("id").asText() + ".";
            for (String field : List.of("aiCalled", "decision", "confidence", "evidence", "optionCode", "reason", "matchReason", "timings", "missingFields", "limitReached")) {
                if (result.variableUpdates().containsKey(prefix + field)) {
                    Object value = result.variableUpdates().get(prefix + field);
                    details.put(field, value instanceof String text ? trim(text, 200) : value);
                }
            }
            var config = node.path("config");
            if (result.branchValue() != null) details.put("branchLabel", trim(org.dromara.ai.workflow.handler.DialogueStepNodeHandler.branchLabel(config, result.branchValue()), 60));
            if (details.get("optionCode") instanceof String code && !code.isBlank()) details.put("optionLabel", trim(org.dromara.ai.workflow.handler.DialogueStepNodeHandler.branchLabel(config, "OPTION_" + code), 60));
            if (result.variableUpdates().get(prefix + "matchedBranches") instanceof List<?> matches) {
                details.put("matchedLabels", matches.stream().limit(10).map(value -> trim(org.dromara.ai.workflow.handler.DialogueStepNodeHandler.branchLabel(config, String.valueOf(value)), 30)).toList());
            }
            try {
                String output = trim(result.output() == null ? "" : result.output(), 600);
                String payload = OBJECT_MAPPER.writeValueAsString(Map.of("output", output, "details", details));
                if (payload.length() > 1980) {
                    details.put("detailsTruncated", true);
                    details.remove("missingFields");
                    details.remove("evidence");
                    payload = OBJECT_MAPPER.writeValueAsString(Map.of("output", trim(output, 200), "details", details));
                }
                if (payload.length() > 1980) {
                    details.remove("matchedLabels");
                    details.put("detailsTruncated", true);
                    payload = OBJECT_MAPPER.writeValueAsString(Map.of("output", "", "details", details));
                }
                if (payload.length() <= 1980) log.setOutputSummary("DIALOGUE_TRACE:" + payload);
            } catch (Exception ignored) { /* Keep ordinary output if trace encoding fails. */ }
        }
        if (result != null && result.variableUpdates().get("workflow.rule.details") instanceof Map<?, ?> details) {
            try {
                String payload = OBJECT_MAPPER.writeValueAsString(Map.of("output", result.output(), "details", details));
                if (payload.length() <= 1980) log.setOutputSummary("RULE_TRACE:" + payload);
            } catch (Exception ignored) { /* Keep the plain rule output if encoding fails. */ }
        }
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
        result.setPlaybackNodeId(context.playbackNodeId);
        result.setPlaybackConfig(context.playbackConfig);
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
        if (value.getOutputSummary() != null && value.getOutputSummary().startsWith("RULE_TRACE:")) {
            try {
                JsonNode payload = OBJECT_MAPPER.readTree(value.getOutputSummary().substring("RULE_TRACE:".length()));
                result.setOutputSummary(payload.path("output").asText());
                result.setRuleDetails(OBJECT_MAPPER.convertValue(payload.path("details"), Map.class));
            } catch (Exception ignored) { /* Older traces remain plain text. */ }
        }
        if ("DIALOGUE_STEP".equals(value.getNodeType()) && value.getOutputSummary() != null && value.getOutputSummary().startsWith("DIALOGUE_TRACE:")) {
            try {
                JsonNode payload = OBJECT_MAPPER.readTree(value.getOutputSummary().substring("DIALOGUE_TRACE:".length()));
                result.setOutputSummary(payload.path("output").asText());
                result.setDialogueDetails(OBJECT_MAPPER.convertValue(payload.path("details"), Map.class));
            } catch (Exception ignored) { /* Older or truncated traces remain readable as plain text. */ }
        }
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
        private String playbackNodeId;
        private Map<String, Object> playbackConfig;
        private String pendingActionType;
        private String pendingActionText;
        private String pendingActionTarget;
        private boolean resumeSameNode;
        private String disclosureNodeId;
        private String disclosureText;
        private int disclosureInterruptions;
        private String afterTtsActionType;
        private String afterTtsActionTarget;
    }
}
