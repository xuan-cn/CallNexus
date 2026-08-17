package org.dromara.ivr.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.ivr.compiler.IvrNodeContext;
import org.dromara.resource.number.domain.request.PhoneNumberNormalizeRequest;
import org.dromara.resource.number.domain.response.PhoneNumberNormalizeResponse;
import org.dromara.resource.number.service.PhoneNumberNormalizationService;
import org.dromara.resource.outboundline.service.OutboundLinePolicyService;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.gateway.support.OutboundGatewayDialString;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class IvrExternalNumberRouteService {

    private static final String STRATEGY_ORDER = "ORDER";
    private static final String STRATEGY_ROUND_ROBIN = "ROUND_ROBIN";
    private static final String STRATEGY_MEMORY = "MEMORY";
    private static final String RR_KEY_PREFIX = "callnexus:ivr:external-number:rr:";
    private static final String MEMORY_KEY_PREFIX = "callnexus:ivr:external-number:memory:";
    private static final Duration MEMORY_TTL = Duration.ofDays(180);

    private final OutboundLinePolicyService outboundLinePolicyService;
    private final PhoneNumberQueryService phoneNumberQueryService;
    private final PhoneNumberNormalizationService numberNormalizationService;

    public BridgePlan buildBridgePlan(IvrNodeContext context) {
        JsonNode config = externalConfig(context.node().config());
        List<ExternalTarget> targets = orderedTargets(context, config);
        if (targets.isEmpty()) {
            throw new ServiceException("IVR 外线号码组未配置可用号码");
        }
        PhoneNumberOutboundRouteResponse route = selectOutboundRoute(context, config);
        if (route == null || route.getGatewayCode() == null || route.getGatewayCode().isBlank()) {
            throw new ServiceException("IVR 外线号码组没有可用外呼线路");
        }
        int timeoutSeconds = timeoutSeconds(config);
        boolean failoverEnabled = !config.has("failoverEnabled") || config.path("failoverEnabled").asBoolean(true);
        List<ExternalTarget> dialTargets = failoverEnabled ? targets : List.of(targets.get(0));
        String bridgeData = buildBridgeData(context, config, route, dialTargets, timeoutSeconds);
        rememberSelectedTarget(context, config, targets.get(0));
        return new BridgePlan(bridgeData, targets.get(0).number(), strategy(config), route, timeoutSeconds);
    }

    public JsonNode externalConfig(JsonNode nodeConfig) {
        if (nodeConfig != null && nodeConfig.has("externalNumberGroup") && nodeConfig.path("externalNumberGroup").isObject()) {
            return nodeConfig.path("externalNumberGroup");
        }
        return nodeConfig;
    }

    public List<ExternalTarget> parseTargets(JsonNode config) {
        List<ExternalTarget> targets = new ArrayList<>();
        JsonNode numbers = config == null ? null : config.path("numbers");
        if (numbers == null || !numbers.isArray()) {
            return targets;
        }
        int index = 0;
        for (JsonNode item : numbers) {
            index++;
            if (item.has("enabled") && !item.path("enabled").asBoolean(true)) {
                continue;
            }
            String number = normalizeNumber(item.path("number").asText(""));
            if (!validNumber(number)) {
                continue;
            }
            String name = item.path("name").asText("");
            int sortOrder = item.has("sortOrder") ? item.path("sortOrder").asInt(index) : index;
            targets.add(new ExternalTarget(number, name, sortOrder, true));
        }
        targets.sort(Comparator.comparingInt(ExternalTarget::sortOrder).thenComparing(ExternalTarget::number));
        return targets;
    }

    private List<ExternalTarget> orderedTargets(IvrNodeContext context, JsonNode config) {
        List<ExternalTarget> targets = parseTargets(config);
        if (targets.size() <= 1) {
            return targets;
        }
        return switch (strategy(config)) {
            case STRATEGY_ROUND_ROBIN -> rotateForRoundRobin(context, targets);
            case STRATEGY_MEMORY -> orderForMemory(context, targets);
            default -> targets;
        };
    }

    private List<ExternalTarget> rotateForRoundRobin(IvrNodeContext context, List<ExternalTarget> targets) {
        long current = RedisUtils.incrAtomicValue(RR_KEY_PREFIX + context.tenantId() + ":" + context.flow().getId() + ":" + context.node().id());
        int index = Math.floorMod(current - 1, targets.size());
        List<ExternalTarget> result = new ArrayList<>(targets.size());
        result.addAll(targets.subList(index, targets.size()));
        result.addAll(targets.subList(0, index));
        return result;
    }

    private List<ExternalTarget> orderForMemory(IvrNodeContext context, List<ExternalTarget> targets) {
        String callerNumber = normalizeNumber(context.callerNumber());
        if (callerNumber.isBlank()) {
            return targets;
        }
        String remembered = RedisUtils.getCacheObject(memoryKey(context, callerNumber));
        if (remembered == null || remembered.isBlank()) {
            return targets;
        }
        ExternalTarget preferred = targets.stream()
            .filter(target -> target.number().equals(remembered))
            .findFirst()
            .orElse(null);
        if (preferred == null) {
            return targets;
        }
        List<ExternalTarget> result = new ArrayList<>();
        result.add(preferred);
        targets.stream().filter(target -> !target.number().equals(preferred.number())).forEach(result::add);
        return result;
    }

    private void rememberSelectedTarget(IvrNodeContext context, JsonNode config, ExternalTarget target) {
        if (!STRATEGY_MEMORY.equals(strategy(config))) {
            return;
        }
        String callerNumber = normalizeNumber(context.callerNumber());
        if (callerNumber.isBlank()) {
            return;
        }
        RedisUtils.setCacheObject(memoryKey(context, callerNumber), target.number(), MEMORY_TTL);
    }

    private String memoryKey(IvrNodeContext context, String callerNumber) {
        return MEMORY_KEY_PREFIX + context.tenantId() + ":" + context.flow().getId() + ":" + context.node().id() + ":" + callerNumber;
    }

    private PhoneNumberOutboundRouteResponse selectOutboundRoute(IvrNodeContext context, JsonNode config) {
        Long policyId = longValue(config.path("outboundPolicyId"));
        if (policyId != null) {
            return outboundLinePolicyService.selectRouteByPolicy(context.tenantId(), context.freeSwitchNodeId(), policyId);
        }
        PhoneNumberOutboundRouteResponse route = outboundLinePolicyService.selectRoute(context.tenantId(), context.freeSwitchNodeId());
        if (route != null) {
            return route;
        }
        return phoneNumberQueryService.findDefaultOutboundRoute(context.tenantId(), context.freeSwitchNodeId());
    }

    private String buildBridgeData(IvrNodeContext context, JsonNode config, PhoneNumberOutboundRouteResponse route,
                                   List<ExternalTarget> targets, int timeoutSeconds) {
        String callerId = safeDialValue(route.getNumber());
        List<String> endpoints = new ArrayList<>();
        for (ExternalTarget target : targets) {
            PhoneNumberNormalizeResponse normalized = normalizeDialTarget(context, config, target.number());
            endpoints.add("{ignore_early_media=true,originate_timeout=" + timeoutSeconds
                + ",origination_caller_id_number=" + callerId
                + ",origination_caller_id_name=" + callerId
                + "}" + OutboundGatewayDialString.build(route.getGatewayAccessMode(), route.getGatewayCode(),
                    route.getRegisteredIdentity(), route.getGatewaySipProfile(), route.getSipDomain(),
                    safeDialValue(normalized.getDialNumber())));
        }
        return String.join("|", endpoints);
    }

    private PhoneNumberNormalizeResponse normalizeDialTarget(IvrNodeContext context, JsonNode config, String rawNumber) {
        PhoneNumberNormalizeRequest request = new PhoneNumberNormalizeRequest();
        request.setRawNumber(rawNumber);
        request.setUsage("IVR_EXTERNAL_NUMBER");
        request.setLocalAreaCode(textValue(config.path("localAreaCode")));
        request.setAddLocalAreaCode(config.has("addLocalAreaCode") && config.path("addLocalAreaCode").asBoolean(false));
        request.setStripChinaCountryCode(!config.has("stripChinaCountryCode") || config.path("stripChinaCountryCode").asBoolean(true));
        request.setOutboundPrefix(textValue(config.path("outboundPrefix")));
        return numberNormalizationService.normalize(context.tenantId(), request);
    }

    private String strategy(JsonNode config) {
        String value = config == null ? "" : config.path("strategy").asText("");
        if (STRATEGY_ROUND_ROBIN.equals(value) || STRATEGY_MEMORY.equals(value)) {
            return value;
        }
        return STRATEGY_ORDER;
    }

    private int timeoutSeconds(JsonNode config) {
        int value = config == null ? 20 : config.path("ringTimeoutSeconds").asInt(20);
        return Math.max(5, Math.min(value, 120));
    }

    private Long longValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String value = node.asText("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new ServiceException("外呼线路策略 ID 不合法");
        }
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        return value.isBlank() ? null : value;
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

    private boolean validNumber(String number) {
        return number.matches("^\\+?\\d{3,32}$");
    }

    private String safeDialValue(String value) {
        if (value == null || value.isBlank()) {
            throw new ServiceException("外线拨号参数为空");
        }
        String normalized = value.trim();
        if (!normalized.matches("^[A-Za-z0-9_+\\-#*.@]+$")) {
            throw new ServiceException("外线拨号参数包含非法字符");
        }
        return normalized;
    }

    public record BridgePlan(
        String bridgeData,
        String firstNumber,
        String strategy,
        PhoneNumberOutboundRouteResponse outboundRoute,
        int timeoutSeconds
    ) {
    }

    public record ExternalTarget(String number, String name, int sortOrder, boolean enabled) {
    }
}
