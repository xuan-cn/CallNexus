package org.dromara.resource.gateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.gateway.domain.request.CreateFreeSwitchGatewayRequest;
import org.dromara.resource.gateway.domain.request.FreeSwitchGatewayPageQuery;
import org.dromara.resource.gateway.domain.request.UpdateFreeSwitchGatewayRequest;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayResponse;
import org.dromara.resource.gateway.mapper.FreeSwitchGatewayMapper;
import org.dromara.resource.gateway.service.FreeSwitchGatewayApplicationService;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FreeSwitchGatewayApplicationServiceImpl implements FreeSwitchGatewayApplicationService {
    private final FreeSwitchGatewayMapper mapper;
    private final FreeSwitchNodeMapper nodeMapper;

    @Override
    public TableDataInfo<FreeSwitchGatewayResponse> page(FreeSwitchGatewayPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<FreeSwitchGateway> wrapper = new LambdaQueryWrapper<FreeSwitchGateway>()
            .eq(query.getNodeId() != null, FreeSwitchGateway::getNodeId, query.getNodeId())
            .like(query.getGatewayCode() != null && !query.getGatewayCode().isBlank(), FreeSwitchGateway::getGatewayCode, query.getGatewayCode())
            .like(query.getGatewayName() != null && !query.getGatewayName().isBlank(), FreeSwitchGateway::getGatewayName, query.getGatewayName())
            .eq(query.getDirection() != null && !query.getDirection().isBlank(), FreeSwitchGateway::getDirection, query.getDirection())
            .eq(query.getEnabled() != null, FreeSwitchGateway::getEnabled, query.getEnabled())
            .orderByAsc(FreeSwitchGateway::getGatewayCode);
        Page<FreeSwitchGateway> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public FreeSwitchGatewayResponse get(Long id) {
        FreeSwitchGateway gateway = mapper.selectById(id);
        if (gateway == null) throw new ServiceException("FREESWITCH_GATEWAY_NOT_FOUND");
        return toResponse(gateway);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateFreeSwitchGatewayRequest request) {
        ensureNodeExists(request.getNodeId());
        ensureGatewayCodeUnique(request.getGatewayCode(), null);
        FreeSwitchGateway gateway = new FreeSwitchGateway();
        apply(gateway, request.getNodeId(), request.getGatewayCode(), request.getGatewayName(), request.getDirection(), request.getProxy(),
            request.getRealm(), request.getUsername(), request.getRegisterEnabled(), request.getTransport(), request.getCallerIdNumber());
        gateway.setPassword(request.getPassword());
        gateway.setEnabled(true);
        mapper.insert(gateway);
        return gateway.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdateFreeSwitchGatewayRequest request) {
        ensureNodeExists(request.getNodeId());
        ensureGatewayCodeUnique(request.getGatewayCode(), id);
        FreeSwitchGateway gateway = mapper.selectById(id);
        if (gateway == null) throw new ServiceException("FREESWITCH_GATEWAY_NOT_FOUND");
        apply(gateway, request.getNodeId(), request.getGatewayCode(), request.getGatewayName(), request.getDirection(), request.getProxy(),
            request.getRealm(), request.getUsername(), request.getRegisterEnabled(), request.getTransport(), request.getCallerIdNumber());
        if (request.getPassword() != null && !request.getPassword().isBlank()) gateway.setPassword(request.getPassword());
        gateway.setEnabled(request.getEnabled());
        gateway.setVersion(request.getVersion());
        if (mapper.updateById(gateway) != 1) throw new ServiceException("FREESWITCH_GATEWAY_UPDATE_CONFLICT");
    }

    @Override
    public void delete(Long id) {
        if (mapper.deleteById(id) != 1) throw new ServiceException("FREESWITCH_GATEWAY_NOT_FOUND");
    }

    private void ensureNodeExists(Long nodeId) {
        FreeSwitchNode node = nodeMapper.selectById(nodeId);
        if (node == null) throw new ServiceException("FREESWITCH_NODE_NOT_FOUND");
    }

    private void ensureGatewayCodeUnique(String gatewayCode, Long excludedId) {
        boolean exists = mapper.exists(new LambdaQueryWrapper<FreeSwitchGateway>()
            .eq(FreeSwitchGateway::getTenantId, LoginHelper.getTenantId())
            .eq(FreeSwitchGateway::getGatewayCode, gatewayCode)
            .ne(excludedId != null, FreeSwitchGateway::getId, excludedId));
        if (exists) throw new ServiceException("FREESWITCH_GATEWAY_CODE_ALREADY_EXISTS");
    }

    private void apply(FreeSwitchGateway gateway, Long nodeId, String code, String name, String direction, String proxy, String realm, String username,
                       Boolean registerEnabled, String transport, String callerIdNumber) {
        gateway.setNodeId(nodeId);
        gateway.setGatewayCode(code);
        gateway.setGatewayName(name);
        gateway.setDirection(direction);
        gateway.setProxy(proxy);
        gateway.setRealm(realm);
        gateway.setUsername(username);
        gateway.setRegisterEnabled(registerEnabled);
        gateway.setTransport(transport);
        gateway.setCallerIdNumber(callerIdNumber);
    }

    private FreeSwitchGatewayResponse toResponse(FreeSwitchGateway gateway) {
        FreeSwitchGatewayResponse response = new FreeSwitchGatewayResponse();
        response.setId(gateway.getId());
        response.setNodeId(gateway.getNodeId());
        FreeSwitchNode node = nodeMapper.selectById(gateway.getNodeId());
        if (node != null) response.setNodeName(node.getNodeName());
        response.setGatewayCode(gateway.getGatewayCode());
        response.setGatewayName(gateway.getGatewayName());
        response.setDirection(gateway.getDirection());
        response.setProxy(gateway.getProxy());
        response.setRealm(gateway.getRealm());
        response.setUsername(gateway.getUsername());
        response.setRegisterEnabled(gateway.getRegisterEnabled());
        response.setTransport(gateway.getTransport());
        response.setCallerIdNumber(gateway.getCallerIdNumber());
        response.setEnabled(gateway.getEnabled());
        response.setVersion(gateway.getVersion());
        response.setCreateTime(gateway.getCreateTime());
        return response;
    }
}
