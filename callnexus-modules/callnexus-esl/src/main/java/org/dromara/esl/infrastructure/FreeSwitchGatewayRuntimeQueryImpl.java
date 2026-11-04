package org.dromara.esl.infrastructure;

import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.EslEndpoint;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayRuntimeResponse;
import org.dromara.resource.gateway.service.FreeSwitchGatewayRuntimeQuery;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FreeSwitchGatewayRuntimeQueryImpl implements FreeSwitchGatewayRuntimeQuery {
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final FreeSwitchEslCommandGateway commandGateway;

    @Override
    public List<FreeSwitchGatewayRuntimeResponse> query(List<FreeSwitchGateway> gateways) {
        List<FreeSwitchGatewayRuntimeResponse> result = new ArrayList<>();
        Map<Long, List<FreeSwitchGateway>> byNode = new HashMap<>();
        for (FreeSwitchGateway gateway : gateways) {
            if (!Boolean.TRUE.equals(gateway.getEnabled())) {
                result.add(response(gateway, "DISABLED", null, null, "配置已停用，未查询运行态"));
            } else if (gateway.getNodeId() == null) {
                result.add(response(gateway, "NODE_UNAVAILABLE", null, null, "未绑定 话务服务 节点"));
            } else {
                byNode.computeIfAbsent(gateway.getNodeId(), ignored -> new ArrayList<>()).add(gateway);
            }
        }
        byNode.forEach((nodeId, entries) -> queryNode(nodeId, entries, result));
        return result;
    }

    private void queryNode(Long nodeId, List<FreeSwitchGateway> entries, List<FreeSwitchGatewayRuntimeResponse> result) {
        EslEndpoint endpoint;
        try {
            var node = nodeQueryService.getEnabledConnection(nodeId);
            endpoint = new EslEndpoint(node.getEslHost(), node.getEslPort(), node.getEslPassword(), node.getSipDomain());
        } catch (RuntimeException exception) {
            entries.forEach(gateway -> result.add(response(gateway, "NODE_UNAVAILABLE", null, null, "节点停用或连接配置不可用")));
            return;
        }
        List<FreeSwitchGateway> trunks = entries.stream().filter(gateway -> !"DEVICE_REGISTER".equals(gateway.getAccessMode())).toList();
        if (!trunks.isEmpty()) {
            String xml;
            try {
                xml = commandGateway.executeApiCommandForResult(endpoint, "api sofia xmlstatus gateway");
            } catch (RuntimeException exception) {
                entries.forEach(gateway -> result.add(response(gateway, "NODE_UNAVAILABLE", null, null, "无法通过 ESL 查询节点，请检查连接和认证")));
                return;
            }
            try {
                var snapshot = GatewayStatusXml.gateways(xml);
                trunks.forEach(gateway -> result.add(classify(gateway, snapshot.get(gateway.getGatewayCode()))));
            } catch (IllegalArgumentException exception) {
                trunks.forEach(gateway -> result.add(response(gateway, "QUERY_FAILED", null, null, "话务服务 网关状态返回无效，未判定为正常")));
            }
        }
        Map<String, List<FreeSwitchGateway>> byProfile = new HashMap<>();
        entries.stream().filter(gateway -> "DEVICE_REGISTER".equals(gateway.getAccessMode())).forEach(gateway -> {
            String profile = gateway.getSipProfile();
            if (profile == null || profile.isBlank()) profile = "internal";
            byProfile.computeIfAbsent(profile, ignored -> new ArrayList<>()).add(gateway);
        });
        byProfile.forEach((profile, devices) -> queryDevices(endpoint, profile, devices, result));
    }

    private void queryDevices(EslEndpoint endpoint, String profile, List<FreeSwitchGateway> devices,
                              List<FreeSwitchGatewayRuntimeResponse> result) {
        if (!profile.matches("[A-Za-z0-9_.-]{1,64}")) {
            devices.forEach(gateway -> result.add(response(gateway, "QUERY_FAILED", null, null, "SIP Profile 配置不合法")));
            return;
        }
        String xml;
        try {
            xml = commandGateway.executeApiCommandForResult(endpoint, "api sofia xmlstatus profile " + profile + " reg");
        } catch (RuntimeException exception) {
            devices.forEach(gateway -> result.add(response(gateway, "NODE_UNAVAILABLE", null, null, "无法通过 ESL 读取设备注册表")));
            return;
        }
        try {
            Set<String> registered = GatewayStatusXml.registrations(xml, endpoint.sipDomain());
            devices.forEach(gateway -> {
                boolean online = registered.contains(gateway.getRegisteredIdentity());
                result.add(response(gateway, online ? "REGISTERED" : "UNREGISTERED", online ? "REGED" : "UNREGED", null,
                    "Profile：" + profile + (online ? "，存在有效注册；不代表通话一定成功" : "，未找到有效的设备注册")));
            });
        } catch (IllegalArgumentException exception) {
            devices.forEach(gateway -> result.add(response(gateway, "QUERY_FAILED", null, null, "注册表返回无效或 Profile 未运行")));
        }
    }

    static FreeSwitchGatewayRuntimeResponse classify(FreeSwitchGateway gateway, GatewayStatusXml.Gateway runtime) {
        if (runtime == null) return response(gateway, "NOT_LOADED", null, null, "话务服务 中未找到该网关，请检查同步和 Profile");
        String state = runtime.state();
        String probe = runtime.status();
        String detail = "FreeSWITCH：" + state + " / " + probe
            + (runtime.retry().isBlank() ? "" : "，重试：" + runtime.retry());
        boolean register = "OUTBOUND_REGISTER".equals(gateway.getAccessMode())
            || (gateway.getAccessMode() == null && Boolean.TRUE.equals(gateway.getRegisterEnabled()));
        if (register) {
            if (Set.of("FAILED", "FAIL_WAIT", "EXPIRED").contains(state)) return response(gateway, "REGISTRATION_FAILED", state, probe, detail);
            if (Set.of("TRYING", "REGISTER", "REGED_PENDING").contains(state)) return response(gateway, "REGISTERING", state, probe, detail);
            if (!"REGED".equals(state)) return response(gateway, "UNREGISTERED", state, probe, detail);
        } else if (!"NOREG".equals(state)) {
            return response(gateway, "QUERY_FAILED", state, probe, detail + "，实际注册模式与配置不符");
        }
        if (runtime.pingSeconds() > 0 && "DOWN".equals(probe)) return response(gateway, "UNREACHABLE", state, probe, detail + "，SIP 探测异常");
        if (runtime.pingSeconds() > 0 && !"UP".equals(probe)) return response(gateway, "QUERY_FAILED", state, probe, detail + "，探测结果未知");
        if (register) return response(gateway, "REGISTERED", state, probe, detail + "；已注册不保证通话及媒体正常");
        if (runtime.pingSeconds() <= 0) return response(gateway, "NO_PROBE", state, probe, detail + "，免注册且未开启探测，无法确认对端可达");
        if ("UP".equals(probe)) return response(gateway, "UP", state, probe, detail + "，SIP 探测正常，不代表通话及媒体一定正常");
        return response(gateway, "QUERY_FAILED", state, probe, detail + "，探测结果未知");
    }

    private static FreeSwitchGatewayRuntimeResponse response(FreeSwitchGateway gateway, String status, String state, String probe, String detail) {
        return new FreeSwitchGatewayRuntimeResponse(gateway.getId(), status, state, probe, detail, Instant.now());
    }
}
