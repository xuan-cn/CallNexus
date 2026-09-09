package org.dromara.ai.workflow;

import org.dromara.ai.domain.AiWorkflow;
import org.dromara.ai.domain.AiWorkflowExecution;
import org.dromara.ai.domain.AiWorkflowNodeLog;
import org.dromara.ai.domain.AiWorkflowVersion;
import org.dromara.ai.domain.AiWorkflowWait;
import org.dromara.ai.domain.request.AiWorkflowTestInputRequest;
import org.dromara.ai.domain.request.AiWorkflowTestStartRequest;
import org.dromara.ai.domain.response.AiWorkflowTestExecutionResponse;
import org.dromara.ai.mapper.AiAgentWorkflowBindingMapper;
import org.dromara.ai.mapper.AiWorkflowExecutionMapper;
import org.dromara.ai.mapper.AiWorkflowMapper;
import org.dromara.ai.mapper.AiWorkflowNodeLogMapper;
import org.dromara.ai.mapper.AiWorkflowVersionMapper;
import org.dromara.ai.mapper.AiWorkflowWaitMapper;
import org.dromara.ai.service.impl.AiWorkflowRuntimeServiceImpl;
import org.dromara.ai.workflow.handler.FlowControlNodeHandler;
import org.dromara.ai.workflow.handler.LoopLimitNodeHandler;
import org.dromara.ai.workflow.handler.TemplateReplyNodeHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class AiWorkflowLoopRuntimeServiceTest {

    @Test
    void exitsConversationLoopAfterConfiguredIterations() {
        AiWorkflowMapper workflowMapper = mock(AiWorkflowMapper.class);
        AiWorkflowVersionMapper versionMapper = mock(AiWorkflowVersionMapper.class);
        AiWorkflowExecutionMapper executionMapper = mock(AiWorkflowExecutionMapper.class);
        AiWorkflowNodeLogMapper nodeLogMapper = mock(AiWorkflowNodeLogMapper.class);
        AiWorkflowWaitMapper waitMapper = mock(AiWorkflowWaitMapper.class);
        AiAgentWorkflowBindingMapper bindingMapper = mock(AiAgentWorkflowBindingMapper.class);

        AiWorkflow workflow = new AiWorkflow();
        workflow.setId(1L);
        AiWorkflowVersion version = version();
        when(workflowMapper.selectById(1L)).thenReturn(workflow);
        when(versionMapper.selectOne(any())).thenReturn(version);
        when(versionMapper.selectById(11L)).thenReturn(version);

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

        List<AiWorkflowNodeLog> logs = new ArrayList<>();
        when(nodeLogMapper.insert(any(AiWorkflowNodeLog.class))).thenAnswer(invocation -> {
            logs.add(invocation.getArgument(0));
            return 1;
        });
        when(nodeLogMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(logs));

        AiWorkflowNodeHandlerRegistry registry = new AiWorkflowNodeHandlerRegistry(List.of(
            new FlowControlNodeHandler(),
            new LoopLimitNodeHandler(),
            new TemplateReplyNodeHandler(new AiWorkflowTemplateResolver())
        ));
        AiWorkflowRuntimeServiceImpl service = new AiWorkflowRuntimeServiceImpl(
            workflowMapper, versionMapper, executionMapper, nodeLogMapper, waitMapper, bindingMapper, registry);

        AiWorkflowTestExecutionResponse current = service.startTest(1L, new AiWorkflowTestStartRequest());
        assertThat(current.getStatus()).isEqualTo("WAITING_INPUT");

        current = input(service, current, "turn-1", "产品怎么收费");
        assertThat(current.getStatus()).isEqualTo("WAITING_INPUT");
        assertThat(current.getVariables()).containsEntry("workflow.loopCount", 1);

        current = input(service, current, "turn-2", "还有其他功能吗");
        assertThat(current.getStatus()).isEqualTo("WAITING_INPUT");
        assertThat(current.getVariables()).containsEntry("workflow.loopCount", 2);

        current = input(service, current, "turn-3", "再介绍一下");
        assertThat(current.getStatus()).isEqualTo("COMPLETED");
        assertThat(current.getVariables()).containsEntry("workflow.loopCount", 3);
        assertThat(current.getOutputMessages()).containsExactly("已查询知识库", "已查询知识库");
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
        version.setVersionNo(1);
        version.setStatus("DRAFT");
        version.setDefinitionJson("""
            {"nodes":[
              {"id":"start","type":"START","name":"开始","config":{}},
              {"id":"wait","type":"WAIT_INPUT","name":"等待输入","config":{"timeoutSeconds":15}},
              {"id":"limit","type":"LOOP_LIMIT","name":"知识问答次数","config":{"maxIterations":2}},
              {"id":"reply","type":"TEMPLATE_REPLY","name":"知识库回复","config":{"text":"已查询知识库"}},
              {"id":"end","type":"END","name":"结束","config":{}}
            ],"edges":[
              {"source":"start","target":"wait"},
              {"source":"wait","target":"limit"},
              {"source":"limit","target":"reply","condition":"CONTINUE"},
              {"source":"reply","target":"wait"},
              {"source":"limit","target":"end","condition":"LIMIT"}
            ]}
            """);
        return version;
    }
}
