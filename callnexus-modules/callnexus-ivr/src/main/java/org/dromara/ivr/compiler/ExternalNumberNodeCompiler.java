package org.dromara.ivr.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.service.IvrExternalNumberRouteService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExternalNumberNodeCompiler implements IvrNodeCompiler {

    private final ObjectProvider<IvrExternalNumberRouteService> routeServiceProvider;

    @Override
    public String nodeType() {
        return "EXTERNAL_NUMBER";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        JsonNode config = externalConfig(context.node().config());
        String strategy = config.path("strategy").asText("ORDER");
        if (!"ORDER".equals(strategy) && !"ROUND_ROBIN".equals(strategy) && !"MEMORY".equals(strategy)) {
            throw new ServiceException("外线号码组策略不合法");
        }
        int timeoutSeconds = config.path("ringTimeoutSeconds").asInt(20);
        if (timeoutSeconds < 5 || timeoutSeconds > 120) {
            throw new ServiceException("外线号码组振铃超时时间必须在 5 到 120 秒之间");
        }
        if (!hasEnabledNumber(config)) {
            throw new ServiceException("请至少配置一个可用的外线号码");
        }
        context.requireTerminal();
    }

    @Override
    public void compile(IvrNodeContext context) {
        IvrExternalNumberRouteService.BridgePlan plan = routeServiceProvider.getObject().buildBridgePlan(context);
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        appendSet(context, "callnexus_ivr_external_number_group", "true");
        appendSet(context, "callnexus_ivr_external_strategy", plan.strategy());
        appendSet(context, "callnexus_ivr_external_selected_number", plan.firstNumber());
        appendSet(context, "callnexus_ivr_external_gateway_code", plan.outboundRoute().getGatewayCode());
        appendSet(context, "callnexus_ivr_external_outbound_number", plan.outboundRoute().getNumber());
        appendSet(context, "callnexus_ivr_external_timeout", String.valueOf(plan.timeoutSeconds()));
        appendSet(context, "continue_on_fail", "true");
        appendSet(context, "hangup_after_bridge", "true");
        context.xml().append("      <action application=\"bridge\" data=\"")
            .append(context.renderSupport().escape(plan.bridgeData()))
            .append("\"/>\n");
        context.renderSupport().appendHangup(context.xml(), "NORMAL_CLEARING");
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private void appendSet(IvrNodeContext context, String key, String value) {
        context.xml().append("      <action application=\"set\" data=\"")
            .append(context.renderSupport().escape(key))
            .append("=")
            .append(context.renderSupport().escape(value))
            .append("\"/>\n");
    }

    private JsonNode externalConfig(JsonNode nodeConfig) {
        if (nodeConfig != null && nodeConfig.has("externalNumberGroup") && nodeConfig.path("externalNumberGroup").isObject()) {
            return nodeConfig.path("externalNumberGroup");
        }
        return nodeConfig;
    }

    private boolean hasEnabledNumber(JsonNode config) {
        JsonNode numbers = config == null ? null : config.path("numbers");
        if (numbers == null || !numbers.isArray()) {
            return false;
        }
        for (JsonNode item : numbers) {
            if (item.has("enabled") && !item.path("enabled").asBoolean(true)) {
                continue;
            }
            String number = normalizeNumber(item.path("number").asText(""));
            if (number.matches("^\\+?\\d{3,32}$")) {
                return true;
            }
        }
        return false;
    }

    private String normalizeNumber(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "");
    }
}
