package org.dromara.ivr.compiler;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.service.AiRealtimeDialplanService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiAgentNodeCompiler implements IvrNodeCompiler {
    private final AiRealtimeDialplanService realtimeDialplanService;

    @Override
    public String nodeType() {
        return "AI_AGENT";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        realtimeDialplanService.validate(agentId(context));
        context.requireTerminal();
    }

    @Override
    public void compile(IvrNodeContext context) {
        Long agentId = agentId(context);
        String streamUrl = realtimeDialplanService.buildStreamUrl(agentId, context.flow().getId(), context.freeSwitchNodeId());
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"set\" data=\"STREAM_BUFFER_SIZE=100\"/>\n")
            .append("      <action application=\"set\" data=\"STREAM_HEART_BEAT=30\"/>\n")
            .append("      <action application=\"set\" data=\"callnexus_ai_agent_id=")
            .append(agentId)
            .append("\"/>\n")
            .append("      <action application=\"set\" data=\"callnexus_ai_stream_result=${api(uuid_audio_stream ${uuid} start ")
            .append(context.renderSupport().escape(streamUrl))
            .append(" mono 16k)}\"/>\n")
            .append("      <action application=\"park\"/>\n");
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private Long agentId(IvrNodeValidationContext context) {
        return parseId(context.node().config().path("aiAgentId").asText());
    }

    private Long agentId(IvrNodeContext context) {
        return parseId(context.node().config().path("aiAgentId").asText());
    }

    private Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            return null;
        }
    }
}
