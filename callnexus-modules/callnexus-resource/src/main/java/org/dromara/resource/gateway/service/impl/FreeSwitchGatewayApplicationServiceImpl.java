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
    private static final String DEVICE_REGISTER = "DEVICE_REGISTER";

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
        if (gateway == null) throw new ServiceException("FreeSWITCH 网关不存在");
        return toResponse(gateway);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateFreeSwitchGatewayRequest request) {
        ensureNodeExists(request.getNodeId());
        ensureGatewayCodeUnique(request.getGatewayCode(), null);
        validateAccessMode(request.getAccessMode(), request.getProxy(), request.getUsername(), request.getPassword(),
            request.getRegisteredIdentity());
        ensureRegisteredDeviceUnique(request.getNodeId(), request.getAccessMode(), request.getRegisteredIdentity(),
            request.getUsername(), null);
        FreeSwitchGateway gateway = new FreeSwitchGateway();
        apply(gateway, request.getNodeId(), request.getGatewayCode(), request.getGatewayName(), request.getDirection(), request.getProxy(),
            request.getRealm(), request.getUsername(), request.getRegisterEnabled(), request.getTransport(), request.getCallerIdNumber(), request.getPing(),
            request.getExpireSeconds(), request.getRetrySeconds(), request.getPingMax(), request.getPingMin(), request.getCallerIdInFrom(),
            request.getFromUser(), request.getFromDomain(), request.getContactParams(), request.getDialplanContext(), request.getExtension(), request.getDescription(),
            request.getAccessMode(), request.getRegisteredIdentity(), request.getSipProfile());
        gateway.setPassword(request.getPassword());
        gateway.setEnabled(true);
        mapper.insert(gateway);
        afterCommit(() -> syncAfterCreate(gateway));
        return gateway.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdateFreeSwitchGatewayRequest request) {
        ensureNodeExists(request.getNodeId());
        ensureGatewayCodeUnique(request.getGatewayCode(), id);
        FreeSwitchGateway gateway = mapper.selectById(id);
        if (gateway == null) throw new ServiceException("FreeSWITCH 网关不存在");
        String effectivePassword = request.getPassword() == null || request.getPassword().isBlank() ? gateway.getPassword() : request.getPassword();
        validateAccessMode(request.getAccessMode(), request.getProxy(), request.getUsername(), effectivePassword,
            request.getRegisteredIdentity());
        ensureRegisteredDeviceUnique(request.getNodeId(), request.getAccessMode(), request.getRegisteredIdentity(),
            request.getUsername(), id);
        Long oldNodeId = gateway.getNodeId();
        String oldGatewayCode = gateway.getGatewayCode();
        Boolean oldEnabled = gateway.getEnabled();
        String oldAccessMode = gateway.getAccessMode();
        apply(gateway, request.getNodeId(), request.getGatewayCode(), request.getGatewayName(), request.getDirection(), request.getProxy(),
            request.getRealm(), request.getUsername(), request.getRegisterEnabled(), request.getTransport(), request.getCallerIdNumber(), request.getPing(),
            request.getExpireSeconds(), request.getRetrySeconds(), request.getPingMax(), request.getPingMin(), request.getCallerIdInFrom(),
            request.getFromUser(), request.getFromDomain(), request.getContactParams(), request.getDialplanContext(), request.getExtension(), request.getDescription(),
            request.getAccessMode(), request.getRegisteredIdentity(), request.getSipProfile());
        if (request.getPassword() != null && !request.getPassword().isBlank()) gateway.setPassword(request.getPassword());
        gateway.setEnabled(request.getEnabled());
        gateway.setVersion(request.getVersion());
        if (mapper.updateById(gateway) != 1) throw new ServiceException("FreeSWITCH 网关已被其他用户修改，请刷新后重试");
        afterCommit(() -> syncAfterUpdate(oldNodeId, oldGatewayCode, oldEnabled, oldAccessMode, gateway));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        FreeSwitchGateway gateway = mapper.selectById(id);
        if (gateway == null) throw new ServiceException("FreeSWITCH 网关不存在");
        if (mapper.deleteById(id) != 1) throw new ServiceException("FreeSWITCH 网关不存在");
        afterCommit(() -> syncAfterDelete(gateway));
    }

    @Override
    public List<FreeSwitchGatewayDirectoryResponse> findEnabledDirectoryGateways(String tenantId, String domain, String switchIpv4, String hostname) {
        return TenantHelper.dynamic(tenantId, () -> {
            List<FreeSwitchNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<FreeSwitchNode>()
                .eq(FreeSwitchNode::getEnabled, true));
            List<FreeSwitchNode> matchedNodes = matchRequestNodes(nodes, domain, switchIpv4, hostname);
            if (matchedNodes.isEmpty()) {
                log.warn("FreeSWITCH 网关目录请求未匹配到节点，tenantId={}，domain={}，switchIpv4={}，hostname={}，enabledNodeCount={}",
                    tenantId, domain, switchIpv4, hostname, nodes.size());
                return List.of();
            }
            Map<Long, FreeSwitchNode> nodeById = matchedNodes.stream()
                .collect(Collectors.toMap(FreeSwitchNode::getId, Function.identity()));
            List<FreeSwitchGateway> gateways = mapper.selectList(new LambdaQueryWrapper<FreeSwitchGateway>()
                .in(FreeSwitchGateway::getNodeId, nodeById.keySet())
                .eq(FreeSwitchGateway::getEnabled, true)
                .ne(FreeSwitchGateway::getAccessMode, "DEVICE_REGISTER")
                .orderByAsc(FreeSwitchGateway::getGatewayCode));
            log.info("FreeSWITCH 网关目录已匹配节点，tenantId={}，nodeIds={}，gatewayCount={}",
                tenantId, nodeById.keySet(), gateways.size());
            return gateways.stream().map(gateway -> toDirectoryResponse(gateway, nodeById.get(gateway.getNodeId()))).toList();
        });
    }

    @Override
    public FreeSwitchGatewayDirectoryResponse findEnabledRegisteredDevice(String tenantId, String domain, String requestedUser,
                                                                           String authUsername) {
        if ((requestedUser == null || requestedUser.isBlank()) && (authUsername == null || authUsername.isBlank())) return null;
        return TenantHelper.dynamic(tenantId, () -> {
            FreeSwitchNode node = nodeMapper.selectOne(new LambdaQueryWrapper<FreeSwitchNode>()
                .eq(FreeSwitchNode::getSipDomain, domain)
                .eq(FreeSwitchNode::getEnabled, true)
                .last("limit 1"));
            if (node == null) return null;
            LambdaQueryWrapper<FreeSwitchGateway> wrapper = new LambdaQueryWrapper<FreeSwitchGateway>()
                .eq(FreeSwitchGateway::getNodeId, node.getId())
                .eq(FreeSwitchGateway::getAccessMode, "DEVICE_REGISTER")
                .eq(FreeSwitchGateway::getEnabled, true);
            if (authUsername != null && !authUsername.isBlank()) {
                wrapper.eq(FreeSwitchGateway::getUsername, authUsername);
            } else {
                wrapper.eq(FreeSwitchGateway::getRegisteredIdentity, requestedUser);
            }
            FreeSwitchGateway gateway = mapper.selectOne(wrapper.last("limit 1"));
            if (gateway == null) return null;
            if (requestedUser != null && !requestedUser.isBlank()
                && !requestedUser.equals(gateway.getRegisteredIdentity()) && !requestedUser.equals(gateway.getUsername())) {
                return null;
            }
            return toDirectoryResponse(gateway, node);
        });
    }

    private List<FreeSwitchNode> matchRequestNodes(List<FreeSwitchNode> nodes, String domain, String switchIpv4, String hostname) {
        if (domain != null && !domain.isBlank()) {
            List<FreeSwitchNode> matches = nodes.stream().filter(node -> domain.equalsIgnoreCase(node.getSipDomain())).toList();
            if (!matches.isEmpty()) return matches;
        }
        if (switchIpv4 != null && !switchIpv4.isBlank()) {
            List<FreeSwitchNode> matches = nodes.stream().filter(node -> switchIpv4.equalsIgnoreCase(node.getEslHost())).toList();
            if (!matches.isEmpty()) return matches;
        }
        if (hostname != null && !hostname.isBlank()) {
            List<FreeSwitchNode> matches = nodes.stream()
                .filter(node -> hostname.equalsIgnoreCase(node.getEslHost())
                    || hostname.equalsIgnoreCase(node.getNodeCode())
                    || hostname.equalsIgnoreCase(node.getNodeName()))
                .toList();
            if (!matches.isEmpty()) return matches;
        }
        return nodes.size() == 1 ? nodes : List.of();
    }

    private void ensureNodeExists(Long nodeId) {
        FreeSwitchNode node = nodeMapper.selectById(nodeId);
        if (node == null) throw new ServiceException("FreeSWITCH 节点不存在");
    }

    private void ensureGatewayCodeUnique(String gatewayCode, Long excludedId) {
        boolean exists = mapper.exists(new LambdaQueryWrapper<FreeSwitchGateway>()
            .eq(FreeSwitchGateway::getTenantId, LoginHelper.getTenantId())
            .eq(FreeSwitchGateway::getGatewayCode, gatewayCode)
            .ne(excludedId != null, FreeSwitchGateway::getId, excludedId));
        if (exists) throw new ServiceException("FreeSWITCH 网关编码已存在");
    }

    private void ensureRegisteredDeviceUnique(Long nodeId, String accessMode, String registeredIdentity,
                                              String username, Long excludedId) {
        if (!isDeviceRegistered(accessMode)) return;
        boolean exists = mapper.exists(new LambdaQueryWrapper<FreeSwitchGateway>()
            .eq(FreeSwitchGateway::getNodeId, nodeId)
            .eq(FreeSwitchGateway::getAccessMode, DEVICE_REGISTER)
            .ne(excludedId != null, FreeSwitchGateway::getId, excludedId)
            .and(wrapper -> wrapper.eq(FreeSwitchGateway::getRegisteredIdentity, registeredIdentity)
                .or().eq(FreeSwitchGateway::getUsername, username)));
        if (exists) {
            throw new ServiceException("该节点的设备注册身份或认证用户已被其他线路使用");
        }
    }

    private void apply(FreeSwitchGateway gateway, Long nodeId, String code, String name, String direction, String proxy, String realm, String username,
                       Boolean registerEnabled, String transport, String callerIdNumber, Integer ping, Integer expireSeconds, Integer retrySeconds,
                       Integer pingMax, Integer pingMin, Boolean callerIdInFrom, String fromUser, String fromDomain, String contactParams,
                       String dialplanContext, String extension, String description, String accessMode, String registeredIdentity,
                       String sipProfile) {
        gateway.setNodeId(nodeId);
        gateway.setGatewayCode(code);
        gateway.setGatewayName(name);
        gateway.setDirection(direction);
        gateway.setAccessMode(accessMode);
        gateway.setProxy(proxy);
        gateway.setRealm(realm);
        gateway.setUsername(username);
        gateway.setRegisterEnabled("OUTBOUND_REGISTER".equals(accessMode));
        gateway.setRegisteredIdentity("DEVICE_REGISTER".equals(accessMode) ? registeredIdentity : null);
        gateway.setSipProfile(sipProfile == null || sipProfile.isBlank() ? "internal" : sipProfile);
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
        response.setAccessMode(gateway.getAccessMode());
        response.setProxy(gateway.getProxy());
        response.setRealm(gateway.getRealm());
        response.setUsername(gateway.getUsername());
        response.setRegisteredIdentity(gateway.getRegisteredIdentity());
        response.setSipProfile(gateway.getSipProfile());
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
        response.setAccessMode(gateway.getAccessMode());
        response.setProxy(gateway.getProxy());
        response.setRealm(gateway.getRealm());
        response.setUsername(gateway.getUsername());
        response.setPassword(gateway.getPassword());
        response.setRegisteredIdentity(gateway.getRegisteredIdentity());
        response.setSipProfile(gateway.getSipProfile());
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

    private void validateAccessMode(String accessMode, String proxy, String username, String password, String registeredIdentity) {
        if ("DEVICE_REGISTER".equals(accessMode)) {
            if (registeredIdentity == null || registeredIdentity.isBlank()) {
                throw new ServiceException("FreeSWITCH 接收注册模式必须填写对端注册账号");
            }
            if (username == null || username.isBlank()) {
                throw new ServiceException("FreeSWITCH 接收注册模式必须填写认证用户");
            }
            if (password == null || password.isBlank()) {
                throw new ServiceException("FreeSWITCH 接收注册模式必须填写认证密码");
            }
            return;
        }
        if (proxy == null || proxy.isBlank()) {
            throw new ServiceException("IP 中继或 FreeSWITCH 主动注册模式必须填写 SIP 服务器");
        }
        if ("OUTBOUND_REGISTER".equals(accessMode)
            && (username == null || username.isBlank() || password == null || password.isBlank())) {
            throw new ServiceException("FreeSWITCH 主动注册模式必须填写认证用户和认证密码");
        }
    }

    private void syncAfterCreate(FreeSwitchGateway gateway) {
        if (isDeviceRegistered(gateway.getAccessMode())) {
            refreshRuntimeDirectory(gateway.getNodeId());
        } else {
            addRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
        }
    }

    private void syncAfterDelete(FreeSwitchGateway gateway) {
        if (isDeviceRegistered(gateway.getAccessMode())) {
            refreshRuntimeDirectory(gateway.getNodeId());
        } else {
            removeRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
        }
    }

    private void syncAfterUpdate(Long oldNodeId, String oldGatewayCode, Boolean oldEnabled, String oldAccessMode,
                                 FreeSwitchGateway gateway) {
        boolean codeChanged = !oldGatewayCode.equals(gateway.getGatewayCode());
        boolean nodeChanged = !oldNodeId.equals(gateway.getNodeId());
        boolean oldDeviceRegistered = isDeviceRegistered(oldAccessMode);
        boolean newDeviceRegistered = isDeviceRegistered(gateway.getAccessMode());
        boolean modeChanged = oldDeviceRegistered != newDeviceRegistered;
        if (codeChanged || nodeChanged || modeChanged) {
            if (Boolean.TRUE.equals(oldEnabled)) {
                if (oldDeviceRegistered) refreshRuntimeDirectory(oldNodeId);
                else removeRuntimeGateway(oldNodeId, oldGatewayCode);
            }
            if (Boolean.TRUE.equals(gateway.getEnabled())) {
                if (newDeviceRegistered) refreshRuntimeDirectory(gateway.getNodeId());
                else addRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
            }
            return;
        }
        if (Boolean.TRUE.equals(oldEnabled) && !Boolean.TRUE.equals(gateway.getEnabled())) {
            if (newDeviceRegistered) refreshRuntimeDirectory(gateway.getNodeId());
            else removeRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
        } else if (!Boolean.TRUE.equals(oldEnabled) && Boolean.TRUE.equals(gateway.getEnabled())) {
            if (newDeviceRegistered) refreshRuntimeDirectory(gateway.getNodeId());
            else addRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
        } else if (Boolean.TRUE.equals(gateway.getEnabled())) {
            if (newDeviceRegistered) refreshRuntimeDirectory(gateway.getNodeId());
            else updateRuntimeGateway(gateway.getNodeId(), gateway.getGatewayCode());
        }
    }

    private boolean isDeviceRegistered(String accessMode) {
        return DEVICE_REGISTER.equals(accessMode);
    }

    private void refreshRuntimeDirectory(Long nodeId) {
        FreeSwitchGatewayRuntimeSyncService syncService = runtimeSyncServiceProvider.getIfAvailable();
        if (syncService == null) {
            log.warn("未找到 FreeSWITCH 运行时同步服务，跳过动态目录刷新，nodeId={}", nodeId);
            return;
        }
        syncService.refreshDirectory(nodeId);
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
        try {
            syncService.removeGateway(nodeId, gatewayCode);
        } catch (RuntimeException exception) {
            // Database state is authoritative; FreeSWITCH runtime cleanup is best-effort.
            log.warn("FreeSWITCH 网关运行态删除失败，数据库记录已删除，nodeId={}，gatewayCode={}",
                nodeId, gatewayCode, exception);
        }
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
