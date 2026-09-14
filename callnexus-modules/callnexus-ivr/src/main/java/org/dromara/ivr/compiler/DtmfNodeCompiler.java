package org.dromara.ivr.compiler;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.graph.IvrEdgeDefinition;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DtmfNodeCompiler implements IvrNodeCompiler {

    private static final String FAILURE_CONDITION = "FAILURE";
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    @Override
    public String nodeType() {
        return "DTMF";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        context.mediaPathResolver().validatePublishedForGroup(mediaId(context), context.flow().getNodeGroupId());
        Long invalidMediaId = invalidMediaId(context);
        if (invalidMediaId != null) {
            context.mediaPathResolver().validatePublishedForGroup(invalidMediaId, context.flow().getNodeGroupId());
        }
        validateRange("等待按键时间", timeoutSeconds(context), 3, 30, "秒");
        validateRange("最大尝试次数", maxAttempts(context), 1, 5, "次");
        if (context.outgoing().isEmpty()) {
            throw new ServiceException("请为 DTMF 节点配置按键路由");
        }
        Set<String> conditions = new HashSet<>();
        int digitRouteCount = 0;
        int failureRouteCount = 0;
        for (IvrEdgeDefinition edge : context.outgoing()) {
            if (edge.condition() == null || (!edge.condition().matches("^[0-9]$") && !FAILURE_CONDITION.equals(edge.condition()))) {
                throw new ServiceException("DTMF 按键不合法");
            }
            if (!conditions.add(edge.condition())) {
                throw new ServiceException("DTMF 按键重复");
            }
            if (FAILURE_CONDITION.equals(edge.condition())) {
                failureRouteCount++;
            } else {
                digitRouteCount++;
            }
        }
        if (digitRouteCount == 0) {
            throw new ServiceException("请至少配置一个数字按键路由");
        }
        if (failureRouteCount != 1) {
            throw new ServiceException("请为按键菜单配置一条失败处理路由");
        }
    }

    @Override
    public void compile(IvrNodeContext context) {
        String prompt = context.renderSupport().escape(
            context.mediaPathResolver().resolveTargetPath(mediaId(context), context.freeSwitchNodeId())
        );
        Long invalidMediaId = invalidMediaId(context);
        String invalidPrompt = invalidMediaId == null
            ? "silence_stream://250"
            : context.renderSupport().escape(context.mediaPathResolver().resolveTargetPath(invalidMediaId, context.freeSwitchNodeId()));
        List<IvrEdgeDefinition> outgoing = context.graph().outgoing(context.node().id());
        String validDigits = outgoing.stream()
            .map(IvrEdgeDefinition::condition)
            .filter(condition -> condition != null && condition.matches("^[0-9]$"))
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.joining());
        String variableName = "ivr_digit_" + context.node().id().replaceAll("[^A-Za-z0-9_]", "_");
        String routeBase = context.renderSupport().extension(context.flow().getId(), context.node().id());
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"unset\" data=\"").append(variableName).append("\"/>\n");
        context.xml().append("      <action application=\"play_and_get_digits\" data=\"1 1 ")
            .append(maxAttempts(context)).append(" ")
            .append(timeoutSeconds(context) * 1000).append(" # ")
            .append(prompt)
            .append(" ").append(invalidPrompt).append(" ")
            .append(variableName).append(" [").append(validDigits).append("] 2000 '")
            .append(routeBase).append("_FAILURE XML ${context}'\"/>\n");
        context.xml().append("      <action application=\"transfer\" data=\"")
            .append(routeBase).append("_${").append(variableName).append("} XML ${context}\"/>\n");
        context.renderSupport().appendNodeEnd(context.xml());

        outgoing.stream()
            .filter(edge -> !FAILURE_CONDITION.equals(edge.condition()))
            .forEach(edge -> appendRoute(context, edge));
        outgoing.stream()
            .filter(edge -> FAILURE_CONDITION.equals(edge.condition()))
            .findFirst()
            .ifPresent(edge -> appendFailureRoute(context, routeBase, edge));
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

    private void appendFailureRoute(IvrNodeContext context, String routeBase, IvrEdgeDefinition edge) {
        context.xml().append("""
              <extension name="%s_failure" continue="false">
                <condition field="destination_number" expression="^%s_FAILURE$">
                  <action application="transfer" data="%s XML ${context}"/>
                </condition>
              </extension>
            """.formatted(
            routeBase,
            context.renderSupport().escapeRegex(routeBase),
            context.renderSupport().extension(context.flow().getId(), edge.target())
        ));
    }

    private Long mediaId(IvrNodeValidationContext context) {
        return parseMediaId(context.node().config().path("mediaId").asText());
    }

    private Long mediaId(IvrNodeContext context) {
        return parseMediaId(context.node().config().path("mediaId").asText());
    }

    private Long invalidMediaId(IvrNodeValidationContext context) {
        return parseMediaId(context.node().config().path("invalidMediaId").asText());
    }

    private Long invalidMediaId(IvrNodeContext context) {
        return parseMediaId(context.node().config().path("invalidMediaId").asText());
    }

    private int timeoutSeconds(IvrNodeValidationContext context) {
        return context.node().config().path("inputTimeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS);
    }

    private int timeoutSeconds(IvrNodeContext context) {
        return context.node().config().path("inputTimeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS);
    }

    private int maxAttempts(IvrNodeValidationContext context) {
        return context.node().config().path("maxAttempts").asInt(DEFAULT_MAX_ATTEMPTS);
    }

    private int maxAttempts(IvrNodeContext context) {
        return context.node().config().path("maxAttempts").asInt(DEFAULT_MAX_ATTEMPTS);
    }

    private void validateRange(String label, int value, int min, int max, String unit) {
        if (value < min || value > max) {
            throw new ServiceException(label + "必须在 " + min + " 到 " + max + " " + unit + "之间");
        }
    }

    private Long parseMediaId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            return null;
        }
    }
}
