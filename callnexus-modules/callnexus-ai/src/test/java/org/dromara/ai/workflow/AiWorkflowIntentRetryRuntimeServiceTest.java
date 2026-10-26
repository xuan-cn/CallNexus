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
import org.dromara.ai.workflow.handler.FlowControlNodeHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiWorkflowIntentRetryRuntimeServiceTest {

    @Test
    void shouldWaitAtSameIntentNodeAfterInternalRetry() {
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

        AiWorkflowExecution[] executionStore = new AiWorkflowExecution[1];
        when(executionMapper.insert(any(AiWorkflowExecution.class))).thenAnswer(invocation -> {
            executionStore[0] = invocation.getArgument(0);
            executionStore[0].setId(101L);
            return 1;
        });
        when(executionMapper.updateById(any(AiWorkflowExecution.class))).thenReturn(1);
        when(executionMapper.selectOne(any())).thenAnswer(invocation -> executionStore[0]);

        List<AiWorkflowWait> waits = new ArrayList<>();
        AtomicLong waitId = new AtomicLong(200);
        when(waitMapper.insert(any(AiWorkflowWait.class))).thenAnswer(invocation -> {
            AiWorkflowWait wait = invocation.getArgument(0);
            wait.setId(waitId.incrementAndGet());
            waits.add(wait);
            return 1;
        });
        when(waitMapper.updateById(any(AiWorkflowWait.class))).thenReturn(1);
        when(waitMapper.selectOne(any())).thenAnswer(invocation -> {
            for (int index = waits.size() - 1; index >= 0; index--) {
                if ("WAITING".equals(waits.get(index).getStatus())) return waits.get(index);
            }
            return null;
        });
        when(nodeLogMapper.insert(any(AiWorkflowNodeLog.class))).thenReturn(1);
        when(nodeLogMapper.selectList(any())).thenReturn(List.of());

        AiWorkflowNodeHandler intentHandler = new AiWorkflowNodeHandler() {
            @Override
            public Set<String> nodeTypes() {
                return Set.of("INTENT_ROUTE");
            }

            @Override
            public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
                if ("是的".equals(context.currentInput())) return AiWorkflowNodeResult.continueWith("CONFIRM");
                if ("转人工".equals(context.currentInput())) {
                    return new AiWorkflowNodeResult("SPEAK_ACTION", "1001", "正在为您转接", "TRANSFER_EXTENSION", Map.of());
                }
                return new AiWorkflowNodeResult("SPEAK_RETRY", null, "请再确认一次", "TTS", Map.of());
            }
        };
        AiWorkflowNodeHandlerRegistry registry = new AiWorkflowNodeHandlerRegistry(List.of(
            new FlowControlNodeHandler(), intentHandler
        ));
        AiWorkflowRuntimeServiceImpl service = new AiWorkflowRuntimeServiceImpl(
            workflowMapper, versionMapper, executionMapper, nodeLogMapper, waitMapper, bindingMapper, registry);

        AiWorkflowTestExecutionResponse current = service.startTest(1L, new AiWorkflowTestStartRequest());
        current = input(service, current, "turn-1", "不清楚");

        assertThat(current.getStatus()).isEqualTo("WAITING_INPUT");
        assertThat(current.getCurrentNodeId()).isEqualTo("intent");
        assertThat(current.getOutputMessages()).containsExactly("请再确认一次");

        current = input(service, current, "turn-2", "是的");

        assertThat(current.getStatus()).isEqualTo("COMPLETED");
        assertThat(current.getTurnNo()).isEqualTo(2);

        AiWorkflowVoiceExecutionResponse voice = service.startVoice(8L, "call-1", "VOICE_INBOUND", Map.of()).orElseThrow();
        assertThat(voice.getStatus()).isEqualTo("WAITING_INPUT");
        voice = service.voiceInput(voice.getExecutionId(), "voice-1", "转人工");
        assertThat(voice.getStatus()).isEqualTo("WAITING_TTS");
        assertThat(voice.getActionType()).isEqualTo("SPEAK");
        assertThat(voice.getText()).isEqualTo("正在为您转接");

        voice = service.voiceTtsCompleted(voice.getExecutionId());
        assertThat(voice.getStatus()).isEqualTo("TRANSFERRED");
        assertThat(voice.getActionType()).isEqualTo("TRANSFER_EXTENSION");
        assertThat(voice.getTarget()).isEqualTo("1001");
    }

    private AiWorkflowTestExecutionResponse input(AiWorkflowRuntimeServiceImpl service,
                                                   AiWorkflowTestExecutionResponse execution,
                                                   String inputId, String text) {
        AiWorkflowTestInputRequest request = new AiWorkflowTestInputRequest();
        request.setInputId(inputId);
        request.setText(text);
        return service.input(execution.getExecutionId(), request);
    }

    private AiWorkflowVersion version() {
        AiWorkflowVersion version = new AiWorkflowVersion();
        version.setId(11L);
        version.setWorkflowId(1L);
        version.setStatus("PUBLISHED");
        version.setDefinitionJson("""
            {"nodes":[
              {"id":"start","type":"START","name":"开始","config":{}},
              {"id":"wait","type":"WAIT_INPUT","name":"等待输入","config":{"timeoutSeconds":15}},
              {"id":"intent","type":"INTENT_ROUTE","name":"意图判断","config":{}},
              {"id":"end","type":"END","name":"结束","config":{}}
            ],"edges":[
              {"source":"start","target":"wait"},
              {"source":"wait","target":"intent"},
              {"source":"intent","target":"end","condition":"CONFIRM"}
            ]}
            """);
        return version;
    }
}
