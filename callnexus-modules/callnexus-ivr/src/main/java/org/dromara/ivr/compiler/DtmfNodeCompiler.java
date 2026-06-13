package org.dromara.ivr.compiler;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.graph.IvrEdgeDefinition;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class DtmfNodeCompiler implements IvrNodeCompiler {

    @Override
    public String nodeType() {
        return "DTMF";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        context.mediaPathResolver().validatePublishedForGroup(mediaId(context), context.flow().getNodeGroupId());
        if (context.outgoing().isEmpty()) {
            throw new ServiceException("请为 DTMF 节点配置按键路由");
        }
        Set<String> conditions = new HashSet<>();
        for (IvrEdgeDefinition edge : context.outgoing()) {
            if (edge.condition() == null || !edge.condition().matches("^[0-9]$")) {
                throw new ServiceException("DTMF 按键不合法");
            }
            if (!conditions.add(edge.condition())) {
                throw new ServiceException("DTMF 按键重复");
            }
        }
    }

    @Override
    public void compile(IvrNodeContext context) {
        String prompt = context.renderSupport().escape(
            context.mediaPathResolver().resolveTargetPath(mediaId(context), context.freeSwitchNodeId())
        );
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"play_and_get_digits\" data=\"1 1 3 5000 # ")
            .append(prompt)
            .append(" silence_stream://250 ivr_digit [0-9*#]\"/>\n");
        context.xml().append("      <action application=\"transfer\" data=\"")
            .append(context.renderSupport().extension(context.flow().getId(), context.node().id()))
            .append("_${ivr_digit} XML ${context}\"/>\n");
        context.renderSupport().appendNodeEnd(context.xml());

        for (IvrEdgeDefinition edge : context.graph().outgoing(context.node().id())) {
            appendRoute(context, edge);
        }
    }

    private void appendRoute(IvrNodeContext context, IvrEdgeDefinition edge) {
        String routeExtension = context.renderSupport().extension(context.flow().getId(), context.node().id())
            + "_" + edge.condition().replaceAll("[^A-Za-z0-9_-]", "_");
        context.xml().append("""
              <extension name="%s" continue="false">
                <condition field="destination_number" expression="^%s$">
                  <action application="transfer" data="%s XML ${context}"/>
                </condition>
              </extension>
            """.formatted(
            routeExtension,
            context.renderSupport().escapeRegex(routeExtension),
            context.renderSupport().extension(context.flow().getId(), edge.target())
        ));
    }

    private Long mediaId(IvrNodeValidationContext context) {
        return parseMediaId(context.node().config().path("mediaId").asText());
    }

    private Long mediaId(IvrNodeContext context) {
        return parseMediaId(context.node().config().path("mediaId").asText());
    }

    private Long parseMediaId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            return null;
        }
    }
}
