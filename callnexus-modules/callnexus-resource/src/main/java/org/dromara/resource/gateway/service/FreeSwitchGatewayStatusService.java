package org.dromara.resource.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayRuntimeResponse;
import org.dromara.resource.gateway.mapper.FreeSwitchGatewayMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FreeSwitchGatewayStatusService {
    private final FreeSwitchGatewayMapper mapper;
    private final ObjectProvider<FreeSwitchGatewayRuntimeQuery> runtimeQuery;

    public List<FreeSwitchGatewayRuntimeResponse> query(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ServiceException("每次请选择 1 到 100 条线路查询状态");
        }
        // Keep tenant and logical-delete filtering on the normal mapper path; never accept node IDs from the caller.
        List<FreeSwitchGateway> gateways = mapper.selectList(new LambdaQueryWrapper<FreeSwitchGateway>()
            .select(FreeSwitchGateway::getId, FreeSwitchGateway::getNodeId, FreeSwitchGateway::getGatewayCode,
                FreeSwitchGateway::getAccessMode, FreeSwitchGateway::getRegisterEnabled, FreeSwitchGateway::getEnabled,
                FreeSwitchGateway::getPing, FreeSwitchGateway::getSipProfile, FreeSwitchGateway::getRegisteredIdentity)
            .in(FreeSwitchGateway::getId, ids));
        FreeSwitchGatewayRuntimeQuery query = runtimeQuery.getIfAvailable();
        if (query != null) return query.query(gateways);
        return gateways.stream().map(gateway -> new FreeSwitchGatewayRuntimeResponse(gateway.getId(), "QUERY_FAILED",
            null, null, "运行态查询服务不可用", Instant.now())).toList();
    }
}
