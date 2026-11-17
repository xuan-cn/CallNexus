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
import org.dromara.ai.domain.response.AiWorkflowTicketPreview;
import org.dromara.ai.domain.response.AiWorkflowVoiceExecutionResponse;
import org.dromara.ai.mapper.AiAgentWorkflowBindingMapper;
import org.dromara.ai.mapper.AiWorkflowExecutionMapper;
import org.dromara.ai.mapper.AiWorkflowMapper;
import org.dromara.ai.mapper.AiWorkflowNodeLogMapper;
import org.dromara.ai.mapper.AiWorkflowVersionMapper;
import org.dromara.ai.mapper.AiWorkflowWaitMapper;
import org.dromara.ai.service.impl.AiWorkflowRuntimeServiceImpl;
import org.dromara.ai.service.model.AiWorkflowTicketDraftEvent;
import org.dromara.ai.workflow.handler.ConditionNodeHandler;
import org.dromara.ai.workflow.handler.DtmfCaptureNodeHandler;
import org.dromara.ai.workflow.handler.FlowControlNodeHandler;
import org.dromara.ai.workflow.handler.TemplateReplyNodeHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiWorkflowRuntimeServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "ARCHIVED"})
    void shouldResumeFromInputWaitAndKeepDuplicateInputIdempotent(String status) {
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
        version.setStatus(status);
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

        var preview = new AiWorkflowTicketPreview("123", "报修工单",
            List.of(new AiWorkflowTicketPreview.Field("address", "地址", true, true, "阳光小区")));
        AiWorkflowNodeHandler ticketHandler = mock(AiWorkflowNodeHandler.class);
        when(ticketHandler.nodeTypes()).thenReturn(Set.of("TICKET_CREATE", "TICKET_DRAFT_PREPARE"));
        when(ticketHandler.execute(any())).thenAnswer(invocation -> {
            AiWorkflowNodeContext node = invocation.getArgument(0);
            return "TICKET_CREATE".equals(node.node().path("type").asText())
                ? new AiWorkflowNodeResult("CONTINUE", null, "测试预览", null, Map.of("ticket.created", true), preview)
                : AiWorkflowNodeResult.continueWith(null);
        });
        AiWorkflowNodeHandlerRegistry registry = new AiWorkflowNodeHandlerRegistry(List.of(
            new FlowControlNodeHandler(),
            new TemplateReplyNodeHandler(new AiWorkflowTemplateResolver()),
            new ConditionNodeHandler(),
            new DtmfCaptureNodeHandler(),
            ticketHandler
        ));
        AiWorkflowRuntimeServiceImpl service = new AiWorkflowRuntimeServiceImpl(
            workflowMapper, versionMapper, executionMapper, nodeLogMapper, waitMapper, bindingMapper, registry, WorkflowRuleTestSupport.matcher());
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        service.setApplicationEventPublisher(events);

        AiWorkflowTestStartRequest startRequest = new AiWorkflowTestStartRequest();
        startRequest.setVariables(Map.of("customer.salutation", "张先生"));
        AiWorkflowTestExecutionResponse started = service.startTest(1L, startRequest);

        assertThat(started.getStatus()).isEqualTo("WAITING_INPUT");
        assertThat(started.getWaitingType()).isEqualTo("INPUT");
        assertThat(started.getVariables()).containsEntry("workflow.clarifyCount", 0);
        assertThat(started.getOutputMessages()).containsExactly("您好张先生，请问需要了解产品吗？");
        assertThat(started.getTraces()).extracting("nodeType")
            .containsExactly("START", "TICKET_DRAFT_PREPARE", "TEMPLATE_REPLY", "WAIT_INPUT");

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
        assertThat(completed.getTraces()).filteredOn(trace -> "TICKET_CREATE".equals(trace.getNodeType()))
            .singleElement().satisfies(trace -> assertThat(trace.getTicketPreview()).isEqualTo(preview));

        int logCount = logs.size();
        AiWorkflowTestExecutionResponse duplicate = service.input(started.getExecutionId(), input);
        assertThat(duplicate.getStatus()).isEqualTo("COMPLETED");
        assertThat(logs).hasSize(logCount);
        assertThat(duplicate.getTraces()).filteredOn(trace -> "TICKET_CREATE".equals(trace.getNodeType()))
            .singleElement().satisfies(trace -> assertThat(trace.getTicketPreview()).isEqualTo(preview));

        AiWorkflowVoiceExecutionResponse voiceStarted = service.startVoice(9L, "call-1", "VOICE_INBOUND",
            Map.of("customer.salutation", "李先生")).orElseThrow();
        assertThat(voiceStarted.getStatus()).isEqualTo("WAITING_TTS");
        assertThat(voiceStarted.getActionType()).isEqualTo("SPEAK");
        assertThat(voiceStarted.getText()).isEqualTo("您好李先生，请问需要了解产品吗？");

        AiWorkflowVoiceExecutionResponse waitingInput = service.voiceTtsCompleted(voiceStarted.getExecutionId());
        assertThat(waitingInput.getStatus()).isEqualTo("WAITING_INPUT");
        assertThat(waitingInput.getActionType()).isEqualTo("WAIT_INPUT");

        // Final ASR text can arrive just after the configured input deadline. As long as the
        // voice execution is still waiting on the same token, it must remain processable.
        storedWait[0].setExpiresAt(LocalDateTime.now().minusSeconds(1));

        AiWorkflowVoiceExecutionResponse secondSpeak = service.voiceInput(
            voiceStarted.getExecutionId(), "voice-turn-1", "需要");
        assertThat(secondSpeak.getStatus()).isEqualTo("WAITING_TTS");
        assertThat(secondSpeak.getText()).isEqualTo("好的，我来为您介绍。");
        assertThat(storedExecution[0].getContextJson()).doesNotContain("阳光小区");

        AiWorkflowVoiceExecutionResponse voiceCompleted = service.voiceTtsCompleted(voiceStarted.getExecutionId());
        assertThat(voiceCompleted.getStatus()).isEqualTo("COMPLETED");
        assertThat(voiceCompleted.getActionType()).isEqualTo("COMPLETED");
        verifyNoInteractions(events);

        var interrupted = service.startVoice(9L, "call-2", "VOICE_INBOUND", Map.of("customer.salutation", "张先生")).orElseThrow();
        service.terminate(interrupted.getExecutionId(), "CHANNEL_HANGUP");
        service.terminate(interrupted.getExecutionId(), "CHANNEL_HANGUP");
        verify(events).publishEvent(new AiWorkflowTicketDraftEvent("call-2", 9L, 7L, Map.of("contact", "张先生")));

        version.setDefinitionJson("""
            {"nodes":[
              {"id":"start","type":"START"},
              {"id":"digits","type":"DTMF_CAPTURE","config":{"digitCount":4,"timeoutSeconds":15,"outputVariable":"workflow.custom_number"}},
              {"id":"ok","type":"TEMPLATE_REPLY","config":{"text":"编号 {{workflow.custom_number}}"}},
              {"id":"timeout","type":"TEMPLATE_REPLY","config":{"text":"输入超时"}},
              {"id":"end","type":"END"}
            ],"edges":[
              {"source":"start","target":"digits"},
              {"source":"digits","target":"ok","condition":"SUCCESS"},
              {"source":"digits","target":"timeout","condition":"TIMEOUT"},
              {"source":"ok","target":"end"},
              {"source":"timeout","target":"end"}
            ]}
            """);
        var collecting = service.startVoice(9L, "call-3", "VOICE_INBOUND", Map.of()).orElseThrow();
        assertThat(collecting.getActionType()).isEqualTo("DTMF_CAPTURE");
        assertThat(collecting.getDigitCount()).isEqualTo(4);
        assertThat(service.voiceInput(collecting.getExecutionId(), "late-asr", "0012").getStatus()).isEqualTo("WAITING_DTMF");
        assertThat(service.voiceDigits(collecting.getExecutionId(), "stale-token", "9999")).isNull();
        assertThat(service.voiceDigits(collecting.getExecutionId(), collecting.getWaitingToken(), "0012").getText())
            .isEqualTo("编号 0012");
        assertThat(service.voiceDigits(collecting.getExecutionId(), collecting.getWaitingToken(), "9999")).isNull();

        var testCollecting = service.startTest(1L, startRequest);
        var silence = new AiWorkflowTestInputRequest();
        silence.setInputId("timeout-1");
        silence.setSignal("SILENCE");
        var timedOut = service.input(testCollecting.getExecutionId(), silence);
        assertThat(timedOut.getOutputMessages()).containsExactly("输入超时");
        assertThat(timedOut.getVariables()).doesNotContainKey("workflow.custom_number");
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
                {"id":"prepare","type":"TICKET_DRAFT_PREPARE","name":"准备草稿","config":{"templateId":"7","fieldMappings":{"contact":"customer.salutation"}}},
                {"id":"hello","type":"TEMPLATE_REPLY","name":"开场白","config":{"text":"您好{{customer.salutation}}，请问需要了解产品吗？"}},
                {"id":"wait","type":"WAIT_INPUT","name":"等待回答","config":{"timeoutSeconds":15}},
                {"id":"condition","type":"CONDITION","name":"判断回答","config":{"variable":"conversation.currentInput","operator":"EQ","compareValue":"需要"}},
                {"id":"ticket","type":"TICKET_CREATE","name":"工单"},
                {"id":"yes","type":"TEMPLATE_REPLY","name":"继续介绍","config":{"text":"好的，我来为您介绍。"}},
                {"id":"no","type":"TEMPLATE_REPLY","name":"礼貌结束","config":{"text":"好的，感谢接听。"}},
                {"id":"end","type":"END","name":"结束"}
              ],
              "edges": [
                {"source":"start","target":"prepare"},
                {"source":"prepare","target":"hello"},
                {"source":"hello","target":"wait"},
                {"source":"wait","target":"condition"},
                {"source":"condition","target":"ticket","condition":"TRUE"},
                {"source":"ticket","target":"yes"},
                {"source":"condition","target":"no","condition":"FALSE"},
                {"source":"yes","target":"end"},
                {"source":"no","target":"end"}
              ]
            }
            """);
        return version;
    }
}
