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
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        if (realtimeDialplanService.isUniMrcpTransport()) {
            AiRealtimeDialplanService.UniMrcpOpeningPrompt opening = realtimeDialplanService.buildUniMrcpOpeningPrompt(agentId);
            context.xml().append("      <action application=\"export\" data=\"callnexus_ai_active=true\"/>\n")
                .append("      <action application=\"export\" data=\"callnexus_ai_transport=UNIMRCP\"/>\n")
                .append("      <action application=\"export\" data=\"callnexus_ai_agent_id=")
                .append(agentId)
                .append("\"/>\n")
                .append("      <action application=\"export\" data=\"callnexus_ai_flow_id=")
                .append(context.flow().getId())
                .append("\"/>\n")
                .append("      <action application=\"export\" data=\"callnexus_ai_node_id=")
                .append(context.node().id())
                .append("\"/>\n")
                .append("      <action application=\"export\" data=\"callnexus_ai_customer_leg_uuid=${uuid}\"/>\n");
            if (opening.hasText()) {
                context.xml().append("      <action application=\"export\" data=\"callnexus_ai_opening_preplayed=true\"/>\n")
                    .append("      <action application=\"speak\" data=\"")
                    .append(context.renderSupport().escape(speakData(opening)))
                    .append("\"/>\n");
            }
            context.xml().append("      <action application=\"park\"/>\n");
        } else {
            String streamUrl = realtimeDialplanService.buildStreamUrl(agentId, context.flow().getId(), context.freeSwitchNodeId());
            context.xml().append("      <action application=\"set\" data=\"STREAM_BUFFER_SIZE=100\"/>\n")
                .append("      <action application=\"set\" data=\"STREAM_HEART_BEAT=30\"/>\n")
                .append("      <action application=\"set\" data=\"callnexus_ai_agent_id=")
                .append(agentId)
                .append("\"/>\n")
                .append("      <action application=\"set\" data=\"callnexus_ai_stream_result=${api(uuid_audio_stream ${uuid} start ")
                .append(context.renderSupport().escape(streamUrl))
                .append(" mono 16k)}\"/>\n")
                .append("      <action application=\"park\"/>\n");
        }
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

    private String speakData(AiRealtimeDialplanService.UniMrcpOpeningPrompt opening) {
        return cleanSegment(opening.profile()) + "|" + cleanSegment(opening.voice()) + "|" + cleanSegment(opening.text());
    }

    private String cleanSegment(String value) {
        return value == null ? "" : value.replace('|', '，').trim();
    }
}
