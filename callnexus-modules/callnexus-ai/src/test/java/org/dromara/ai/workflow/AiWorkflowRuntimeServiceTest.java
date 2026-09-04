package org.dromara.ai.workflow;

import org.dromara.ai.domain.AiWorkflow;
import org.dromara.ai.domain.AiAgentWorkflowBinding;
import org.dromara.ai.domain.AiWorkflowExecution;
import org.dromara.ai.domain.AiWorkflowNodeLog;
import org.dromara.ai.domain.AiWorkflowVersion;
import org.dromara.ai.domain.AiWorkflowWait;
import org.dromara.ai.domain.request.AiWorkflowTestInputRequest;
import org.dromara.ai.domain.request.AiWorkflowTestStartRequest;
import org.dromara.ai.domain.response.AiWorkflowTestExecutionResponse;
import org.dromara.ai.domain.response.AiWorkflowVoiceExecutionResponse;
import org.dromara.ai.mapper.AiAgentWorkflowBindingMapper;
import org.dromara.ai.mapper.AiWorkflowExecutionMapper;
import org.dromara.ai.mapper.AiWorkflowMapper;
import org.dromara.ai.mapper.AiWorkflowNodeLogMapper;
import org.dromara.ai.mapper.AiWorkflowVersionMapper;
import org.dromara.ai.mapper.AiWorkflowWaitMapper;
import org.dromara.ai.service.impl.AiWorkflowRuntimeServiceImpl;
import org.dromara.ai.workflow.handler.ConditionNodeHandler;
import org.dromara.ai.workflow.handler.FlowControlNodeHandler;
import org.dromara.ai.workflow.handler.TemplateReplyNodeHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiWorkflowRuntimeServiceTest {

    @Test
    void shouldResumeFromInputWaitAndKeepDuplicateInputIdempotent() {
        AiWorkflowMapper workflowMapper = mock(AiWorkflowMapper.class);
        AiWorkflowVersionMapper versionMapper = mock(AiWorkflowVersionMapper.class);
        AiWorkflowExecutionMapper executionMapper = mock(AiWorkflowExecutionMapper.class);
        AiWorkflowNodeLogMapper nodeLogMapper = mock(AiWorkflowNodeLogMapper.class);
        AiWorkflowWaitMapper waitMapper = mock(AiWorkflowWaitMapper.class);
        AiAgentWorkflowBindingMapper bindingMapper = mock(AiAgentWorkflowBindingMapper.class);

        AiWorkflow workflow = new AiWorkflow();
        workflow.setId(1L);
        workflow.setEnabled(true);
        AiWorkflowVersion version = version();
        AiAgentWorkflowBinding binding = new AiAgentWorkflowBinding();
        binding.setWorkflowId(1L);
        binding.setWorkflowVersionId(11L);
        when(workflowMapper.selectById(1L)).thenReturn(workflow);
        when(versionMapper.selectOne(any())).thenReturn(version);
        when(versionMapper.selectById(11L)).thenReturn(version);
        when(bindingMapper.selectOne(any())).thenReturn(binding);

        AiWorkflowExecution[] storedExecution = new AiWorkflowExecution[1];
        when(executionMapper.insert(any(AiWorkflowExecution.class))).thenAnswer(invocation -> {
            storedExecution[0] = invocation.getArgument(0);
            storedExecution[0].setId(101L);
            return 1;
        });
        when(executionMapper.updateById(any(AiWorkflowExecution.class))).thenReturn(1);
        when(executionMapper.selectOne(any())).thenAnswer(invocation -> storedExecution[0]);

        AiWorkflowWait[] storedWait = new AiWorkflowWait[1];
        when(waitMapper.insert(any(AiWorkflowWait.class))).thenAnswer(invocation -> {
            storedWait[0] = invocation.getArgument(0);
            storedWait[0].setId(201L);
            return 1;
        });
        when(waitMapper.updateById(any(AiWorkflowWait.class))).thenReturn(1);
        when(waitMapper.selectOne(any())).thenAnswer(invocation ->
            storedWait[0] != null && "WAITING".equals(storedWait[0].getStatus()) ? storedWait[0] : null);

        List<AiWorkflowNodeLog> logs = new ArrayList<>();
        AtomicLong logId = new AtomicLong(300);
        when(nodeLogMapper.insert(any(AiWorkflowNodeLog.class))).thenAnswer(invocation -> {
            AiWorkflowNodeLog log = invocation.getArgument(0);
            log.setId(logId.incrementAndGet());
            logs.add(log);
            return 1;
        });
        when(nodeLogMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(logs));

        AiWorkflowNodeHandlerRegistry registry = new AiWorkflowNodeHandlerRegistry(List.of(
            new FlowControlNodeHandler(),
            new TemplateReplyNodeHandler(new AiWorkflowTemplateResolver()),
            new ConditionNodeHandler()
        ));
        AiWorkflowRuntimeServiceImpl service = new AiWorkflowRuntimeServiceImpl(
            workflowMapper, versionMapper, executionMapper, nodeLogMapper, waitMapper, bindingMapper, registry);

        AiWorkflowTestStartRequest startRequest = new AiWorkflowTestStartRequest();
        startRequest.setVariables(Map.of("customer.salutation", "张先生"));
        AiWorkflowTestExecutionResponse started = service.startTest(1L, startRequest);

        assertThat(started.getStatus()).isEqualTo("WAITING_INPUT");
        assertThat(started.getWaitingType()).isEqualTo("INPUT");
        assertThat(started.getVariables()).containsEntry("workflow.clarifyCount", 0);
        assertThat(started.getOutputMessages()).containsExactly("您好张先生，请问需要了解产品吗？");
        assertThat(started.getTraces()).extracting("nodeType")
            .containsExactly("START", "TEMPLATE_REPLY", "WAIT_INPUT");

        AiWorkflowTestInputRequest input = new AiWorkflowTestInputRequest();
        input.setInputId("turn-1");
        input.setText("需要");
        AiWorkflowTestExecutionResponse completed = service.input(started.getExecutionId(), input);

        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getOutputMessages()).containsExactly(
            "您好张先生，请问需要了解产品吗？", "好的，我来为您介绍。"
        );
        assertThat(completed.getVariables()).containsEntry("conversation.currentInput", "需要");
        assertThat(completed.getTraces()).extracting("branchValue").contains("TRUE");

        int logCount = logs.size();
        AiWorkflowTestExecutionResponse duplicate = service.input(started.getExecutionId(), input);
        assertThat(duplicate.getStatus()).isEqualTo("COMPLETED");
        assertThat(logs).hasSize(logCount);

        AiWorkflowVoiceExecutionResponse voiceStarted = service.startVoice(9L, "call-1", "VOICE_INBOUND",
            Map.of("customer.salutation", "李先生")).orElseThrow();
        assertThat(voiceStarted.getStatus()).isEqualTo("WAITING_TTS");
        assertThat(voiceStarted.getActionType()).isEqualTo("SPEAK");
        assertThat(voiceStarted.getText()).isEqualTo("您好李先生，请问需要了解产品吗？");

        AiWorkflowVoiceExecutionResponse waitingInput = service.voiceTtsCompleted(voiceStarted.getExecutionId());
        assertThat(waitingInput.getStatus()).isEqualTo("WAITING_INPUT");
        assertThat(waitingInput.getActionType()).isEqualTo("WAIT_INPUT");

        AiWorkflowVoiceExecutionResponse secondSpeak = service.voiceInput(
            voiceStarted.getExecutionId(), "voice-turn-1", "需要");
        assertThat(secondSpeak.getStatus()).isEqualTo("WAITING_TTS");
        assertThat(secondSpeak.getText()).isEqualTo("好的，我来为您介绍。");

        AiWorkflowVoiceExecutionResponse voiceCompleted = service.voiceTtsCompleted(voiceStarted.getExecutionId());
        assertThat(voiceCompleted.getStatus()).isEqualTo("COMPLETED");
        assertThat(voiceCompleted.getActionType()).isEqualTo("COMPLETED");
    }

    private AiWorkflowVersion version() {
        AiWorkflowVersion version = new AiWorkflowVersion();
        version.setId(11L);
        version.setWorkflowId(1L);
        version.setVersionNo(3);
        version.setStatus("PUBLISHED");
        version.setDefinitionJson("""
            {
              "nodes": [
                {"id":"start","type":"START","name":"开始"},
                {"id":"hello","type":"TEMPLATE_REPLY","name":"开场白","config":{"text":"您好{{customer.salutation}}，请问需要了解产品吗？"}},
                {"id":"wait","type":"WAIT_INPUT","name":"等待回答","config":{"timeoutSeconds":15}},
                {"id":"condition","type":"CONDITION","name":"判断回答","config":{"variable":"conversation.currentInput","operator":"EQ","compareValue":"需要"}},
                {"id":"yes","type":"TEMPLATE_REPLY","name":"继续介绍","config":{"text":"好的，我来为您介绍。"}},
                {"id":"no","type":"TEMPLATE_REPLY","name":"礼貌结束","config":{"text":"好的，感谢接听。"}},
                {"id":"end","type":"END","name":"结束"}
              ],
              "edges": [
                {"source":"start","target":"hello"},
                {"source":"hello","target":"wait"},
                {"source":"wait","target":"condition"},
                {"source":"condition","target":"yes","condition":"TRUE"},
                {"source":"condition","target":"no","condition":"FALSE"},
                {"source":"yes","target":"end"},
                {"source":"no","target":"end"}
              ]
            }
            """);
        return version;
    }
}
