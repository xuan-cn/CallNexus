package org.dromara.resource.gateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.gateway.domain.request.CreateFreeSwitchGatewayRequest;
import org.dromara.resource.gateway.domain.request.FreeSwitchGatewayPageQuery;
import org.dromara.resource.gateway.domain.request.UpdateFreeSwitchGatewayRequest;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayDirectoryResponse;
import org.dromara.resource.gateway.domain.response.FreeSwitchGatewayResponse;
import org.dromara.resource.gateway.mapper.FreeSwitchGatewayMapper;
import org.dromara.resource.gateway.service.FreeSwitchGatewayApplicationService;
import org.dromara.resource.gateway.service.FreeSwitchGatewayQueryService;
import org.dromara.resource.gateway.service.FreeSwitchGatewayRuntimeSyncService;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreeSwitchGatewayApplicationServiceImpl implements FreeSwitchGatewayApplicationService, FreeSwitchGatewayQueryService {
    private final FreeSwitchGatewayMapper mapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final ObjectProvider<FreeSwitchGatewayRuntimeSyncService> runtimeSyncServiceProvider;

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
            request.getRealm(), request.getUsername(), request.getRegisterEnabled(), request.getTransport(), request.getCallerIdNumber(), request.getPing(),
            request.getExpireSeconds(), request.getRetrySeconds(), request.getPingMax(), request.getPingMin(), request.getCallerIdInFrom(),
            request.getFromUser(), request.getFromDomain(), request.getContactParams(), request.getDialplanContext(), request.getExtension(), request.getDescription());
        gateway.setPassword(request.getPassword());
        gateway.setEnabled(true);
        mapper.insert(gateway);
        afterCommit(() -> addRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode()));
        return gateway.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdateFreeSwitchGatewayRequest request) {
        ensureNodeExists(request.getNodeId());
        ensureGatewayCodeUnique(request.getGatewayCode(), id);
        FreeSwitchGateway gateway = mapper.selectById(id);
        if (gateway == null) throw new ServiceException("FREESWITCH_GATEWAY_NOT_FOUND");
        Long oldNodeId = gateway.getNodeId();
        String oldGatewayCode = gateway.getGatewayCode();
        Boolean oldEnabled = gateway.getEnabled();
        apply(gateway, request.getNodeId(), request.getGatewayCode(), request.getGatewayName(), request.getDirection(), request.getProxy(),
            request.getRealm(), request.getUsername(), request.getRegisterEnabled(), request.getTransport(), request.getCallerIdNumber(), request.getPing(),
            request.getExpireSeconds(), request.getRetrySeconds(), request.getPingMax(), request.getPingMin(), request.getCallerIdInFrom(),
            request.getFromUser(), request.getFromDomain(), request.getContactParams(), request.getDialplanContext(), request.getExtension(), request.getDescription());
        if (request.getPassword() != null && !request.getPassword().isBlank()) gateway.setPassword(request.getPassword());
        gateway.setEnabled(request.getEnabled());
        gateway.setVersion(request.getVersion());
        if (mapper.updateById(gateway) != 1) throw new ServiceException("FREESWITCH_GATEWAY_UPDATE_CONFLICT");
        afterCommit(() -> syncAfterUpdate(oldNodeId, oldGatewayCode, oldEnabled, gateway));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        FreeSwitchGateway gateway = mapper.selectById(id);
        if (gateway == null) throw new ServiceException("FREESWITCH_GATEWAY_NOT_FOUND");
        if (mapper.deleteById(id) != 1) throw new ServiceException("FREESWITCH_GATEWAY_NOT_FOUND");
        afterCommit(() -> removeRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode()));
    }

    @Override
    public List<FreeSwitchGatewayDirectoryResponse> findEnabledDirectoryGateways(String tenantId, String domain) {
        return TenantHelper.dynamic(tenantId, () -> {
            List<FreeSwitchNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<FreeSwitchNode>()
                .eq(FreeSwitchNode::getEnabled, true)
                .eq(domain != null && !domain.isBlank(), FreeSwitchNode::getSipDomain, domain));
            if (nodes.isEmpty()) return List.of();
            Map<Long, FreeSwitchNode> nodeById = nodes.stream()
                .collect(Collectors.toMap(FreeSwitchNode::getId, Function.identity()));
            List<FreeSwitchGateway> gateways = mapper.selectList(new LambdaQueryWrapper<FreeSwitchGateway>()
                .in(FreeSwitchGateway::getNodeId, nodeById.keySet())
                .eq(FreeSwitchGateway::getEnabled, true)
                .orderByAsc(FreeSwitchGateway::getGatewayCode));
            return gateways.stream().map(gateway -> toDirectoryResponse(gateway, nodeById.get(gateway.getNodeId()))).toList();
        });
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
                       Boolean registerEnabled, String transport, String callerIdNumber, Integer ping, Integer expireSeconds, Integer retrySeconds,
                       Integer pingMax, Integer pingMin, Boolean callerIdInFrom, String fromUser, String fromDomain, String contactParams,
                       String dialplanContext, String extension, String description) {
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
        gateway.setPing(ping == null ? 0 : ping);
        gateway.setExpireSeconds(expireSeconds == null ? 60 : expireSeconds);
        gateway.setRetrySeconds(retrySeconds == null ? 30 : retrySeconds);
        gateway.setPingMax(pingMax == null ? 3 : pingMax);
        gateway.setPingMin(pingMin == null ? 1 : pingMin);
        gateway.setCallerIdInFrom(callerIdInFrom == null || callerIdInFrom);
        gateway.setFromUser(fromUser);
        gateway.setFromDomain(fromDomain);
        gateway.setContactParams(contactParams);
        gateway.setDialplanContext(dialplanContext == null || dialplanContext.isBlank() ? "public" : dialplanContext);
        gateway.setExtension(extension == null || extension.isBlank() ? "auto_to_user" : extension);
        gateway.setDescription(description);
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
        response.setPing(gateway.getPing() == null ? 0 : gateway.getPing());
        response.setExpireSeconds(gateway.getExpireSeconds() == null ? 60 : gateway.getExpireSeconds());
        response.setRetrySeconds(gateway.getRetrySeconds() == null ? 30 : gateway.getRetrySeconds());
        response.setPingMax(gateway.getPingMax() == null ? 3 : gateway.getPingMax());
        response.setPingMin(gateway.getPingMin() == null ? 1 : gateway.getPingMin());
        response.setCallerIdInFrom(gateway.getCallerIdInFrom() == null || gateway.getCallerIdInFrom());
        response.setFromUser(gateway.getFromUser());
        response.setFromDomain(gateway.getFromDomain());
        response.setContactParams(gateway.getContactParams());
        response.setDialplanContext(gateway.getDialplanContext());
        response.setExtension(gateway.getExtension());
        response.setDescription(gateway.getDescription());
        response.setEnabled(gateway.getEnabled());
        response.setVersion(gateway.getVersion());
        response.setCreateTime(gateway.getCreateTime());
        return response;
    }

    private FreeSwitchGatewayDirectoryResponse toDirectoryResponse(FreeSwitchGateway gateway, FreeSwitchNode node) {
        FreeSwitchGatewayDirectoryResponse response = new FreeSwitchGatewayDirectoryResponse();
        response.setId(gateway.getId());
        response.setDomain(node.getSipDomain());
        response.setGatewayCode(gateway.getGatewayCode());
        response.setProxy(gateway.getProxy());
        response.setRealm(gateway.getRealm());
        response.setUsername(gateway.getUsername());
        response.setPassword(gateway.getPassword());
        response.setRegisterEnabled(gateway.getRegisterEnabled());
        response.setTransport(gateway.getTransport());
        response.setCallerIdNumber(gateway.getCallerIdNumber());
        response.setPing(gateway.getPing() == null ? 0 : gateway.getPing());
        response.setExpireSeconds(gateway.getExpireSeconds() == null ? 60 : gateway.getExpireSeconds());
        response.setRetrySeconds(gateway.getRetrySeconds() == null ? 30 : gateway.getRetrySeconds());
        response.setPingMax(gateway.getPingMax() == null ? 3 : gateway.getPingMax());
        response.setPingMin(gateway.getPingMin() == null ? 1 : gateway.getPingMin());
        response.setCallerIdInFrom(gateway.getCallerIdInFrom() == null || gateway.getCallerIdInFrom());
        response.setFromUser(gateway.getFromUser());
        response.setFromDomain(gateway.getFromDomain());
        response.setContactParams(gateway.getContactParams());
        response.setDialplanContext(gateway.getDialplanContext() == null ? "public" : gateway.getDialplanContext());
        response.setExtension(gateway.getExtension() == null ? "auto_to_user" : gateway.getExtension());
        return response;
    }

    private void syncAfterUpdate(Long oldNodeId, String oldGatewayCode, Boolean oldEnabled, FreeSwitchGateway gateway) {
        boolean codeChanged = !oldGatewayCode.equals(gateway.getGatewayCode());
        boolean nodeChanged = !oldNodeId.equals(gateway.getNodeId());
        if (codeChanged || nodeChanged) {
            if (Boolean.TRUE.equals(oldEnabled)) removeRuntimeGateway(oldNodeId, oldGatewayCode);
            if (Boolean.TRUE.equals(gateway.getEnabled())) addRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
            return;
        }
        if (Boolean.TRUE.equals(oldEnabled) && !Boolean.TRUE.equals(gateway.getEnabled())) {
            removeRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
        } else if (!Boolean.TRUE.equals(oldEnabled) && Boolean.TRUE.equals(gateway.getEnabled())) {
            addRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
        } else if (Boolean.TRUE.equals(gateway.getEnabled())) {
            updateRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
        }
    }

    private void addRuntimeGateway(Long nodeId, String gatewayCode) {
        FreeSwitchGatewayRuntimeSyncService syncService = runtimeSyncServiceProvider.getIfAvailable();
        if (syncService == null) {
            log.warn("未找到 FreeSWITCH 网关运行态同步服务，跳过新增同步，nodeId={}，gatewayCode={}", nodeId, gatewayCode);
            return;
        }
        syncService.addGateway(nodeId, gatewayCode);
    }

    private void updateRuntimeGateway(Long nodeId, String gatewayCode) {
        FreeSwitchGatewayRuntimeSyncService syncService = runtimeSyncServiceProvider.getIfAvailable();
        if (syncService == null) {
            log.warn("未找到 FreeSWITCH 网关运行态同步服务，跳过修改同步，nodeId={}，gatewayCode={}", nodeId, gatewayCode);
            return;
        }
        syncService.updateGateway(nodeId, gatewayCode);
    }

    private void removeRuntimeGateway(Long nodeId, String gatewayCode) {
        FreeSwitchGatewayRuntimeSyncService syncService = runtimeSyncServiceProvider.getIfAvailable();
        if (syncService == null) {
            log.warn("未找到 FreeSWITCH 网关运行态同步服务，跳过删除同步，nodeId={}，gatewayCode={}", nodeId, gatewayCode);
            return;
        }
        syncService.removeGateway(nodeId, gatewayCode);
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
