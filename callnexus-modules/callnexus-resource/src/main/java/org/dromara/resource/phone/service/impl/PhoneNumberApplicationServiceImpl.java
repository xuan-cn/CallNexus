package org.dromara.resource.phone.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.gateway.mapper.FreeSwitchGatewayMapper;
import org.dromara.resource.businesshours.service.PhoneBusinessHoursRouteService;
import org.dromara.resource.inbound.domain.InboundDidEntry;
import org.dromara.resource.inbound.mapper.InboundDidEntryMapper;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.dromara.resource.phone.domain.PhoneNumber;
import org.dromara.resource.phone.domain.request.CreatePhoneNumberRequest;
import org.dromara.resource.phone.domain.request.PhoneNumberPageQuery;
import org.dromara.resource.phone.domain.request.UpdatePhoneNumberRequest;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.phone.domain.response.PhoneNumberResponse;
import org.dromara.resource.phone.mapper.PhoneNumberMapper;
import org.dromara.resource.phone.service.PhoneNumberApplicationService;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class PhoneNumberApplicationServiceImpl implements PhoneNumberApplicationService, PhoneNumberQueryService {
    private final PhoneNumberMapper mapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final FreeSwitchGatewayMapper gatewayMapper;
    private final InboundDidEntryMapper inboundDidEntryMapper;
    private final PhoneBusinessHoursRouteService businessHoursRouteService;

    @Override
    public TableDataInfo<PhoneNumberResponse> page(PhoneNumberPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<PhoneNumber> wrapper = new LambdaQueryWrapper<PhoneNumber>()
            .eq(query.getNodeId() != null, PhoneNumber::getNodeId, query.getNodeId())
            .eq(query.getGatewayId() != null, PhoneNumber::getGatewayId, query.getGatewayId())
            .like(StringUtils.isNotBlank(query.getNumber()), PhoneNumber::getNumber, query.getNumber())
            .like(StringUtils.isNotBlank(query.getNumberName()), PhoneNumber::getNumberName, query.getNumberName())
            .eq(StringUtils.isNotBlank(query.getNumberType()), PhoneNumber::getNumberType, query.getNumberType())
            .eq(query.getEnabled() != null, PhoneNumber::getEnabled, query.getEnabled())
            .orderByAsc(PhoneNumber::getNumber);
        Page<PhoneNumber> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public PhoneNumberResponse get(Long id) {
        PhoneNumber number = mapper.selectById(id);
        if (number == null) throw new ServiceException("号码不存在");
        return toResponse(number);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreatePhoneNumberRequest request) {
        ensureNodeExists(request.getNodeId());
        ensureGatewayAvailable(request.getNodeId(), request.getGatewayId());
        ensureNumberUnique(request.getNumber(), null);
        PhoneNumber number = new PhoneNumber();
        apply(number, request.getNumber(), request.getNumberName(), request.getNumberType(), request.getNodeId(), request.getGatewayId(),
            request.getOutboundDefault());
        number.setRouteType("NONE");
        number.setRouteTarget(null);
        number.setEnabled(true);
        mapper.insert(number);
        log.info("新增号码管理配置，number={}，numberType={}，nodeId={}，gatewayId={}",
            number.getNumber(), number.getNumberType(), number.getNodeId(), number.getGatewayId());
        return number.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdatePhoneNumberRequest request) {
        ensureNodeExists(request.getNodeId());
        ensureGatewayAvailable(request.getNodeId(), request.getGatewayId());
        ensureNumberUnique(request.getNumber(), id);
        PhoneNumber number = mapper.selectById(id);
        if (number == null) throw new ServiceException("号码不存在");
        if (hasInboundRoutes(id)
            && (!Objects.equals(number.getNumber(), request.getNumber()) || !Objects.equals(number.getNodeId(), request.getNodeId())
                || !Objects.equals(number.getGatewayId(), request.getGatewayId()))) {
            throw new ServiceException("该号码已配置呼入规则，请先删除规则后再修改号码、节点或网关");
        }
        apply(number, request.getNumber(), request.getNumberName(), request.getNumberType(), request.getNodeId(), request.getGatewayId(),
            request.getOutboundDefault());
        if (!"BUSINESS_HOURS".equals(number.getRouteType())) {
            number.setRouteType("NONE");
            number.setRouteTarget(null);
        }
        number.setEnabled(request.getEnabled());
        number.setVersion(request.getVersion());
        if (mapper.updateById(number) != 1) throw new ServiceException("号码已被其他用户修改，请刷新后重试");
        log.info("更新号码管理配置，id={}，number={}，enabled={}",
            number.getId(), number.getNumber(), number.getEnabled());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        PhoneNumber number = mapper.selectById(id);
        if (number == null) throw new ServiceException("号码不存在");
        if (hasInboundRoutes(id)) throw new ServiceException("该号码已配置呼入规则，请先删除规则后再删除号码");
        businessHoursRouteService.removeByPhoneNumberId(id);
        if (mapper.deleteById(id) != 1) throw new ServiceException("号码不存在");
        log.info("删除号码管理配置，id={}，number={}", id, number.getNumber());
    }

    private FreeSwitchNode findEnabledNodeByDomain(String domain) {
        if (StringUtils.isBlank(domain)) return null;
        return nodeMapper.selectOne(new LambdaQueryWrapper<FreeSwitchNode>()
            .eq(FreeSwitchNode::getSipDomain, domain)
            .eq(FreeSwitchNode::getEnabled, true)
            .last("limit 1"));
    }

    @Override
    public PhoneNumberOutboundRouteResponse findDefaultOutboundRoute(String tenantId, Long nodeId) {
        if (nodeId == null) return null;
        return TenantHelper.dynamic(tenantId, () -> {
            PhoneNumber number = findDefaultOutboundNumber(nodeId);
            if (number == null && (number = findFirstOutboundNumber(nodeId)) == null) {
                log.warn("未找到默认外呼号码路由，tenantId={}，nodeId={}", tenantId, nodeId);
                return null;
            }
            FreeSwitchGateway gateway = gatewayMapper.selectById(number.getGatewayId());
            if (gateway == null || !Boolean.TRUE.equals(gateway.getEnabled()) || !nodeId.equals(gateway.getNodeId())
                || !("OUTBOUND".equals(gateway.getDirection()) || "BOTH".equals(gateway.getDirection()))) {
                log.warn("默认外呼号码绑定的网关不可用，tenantId={}，nodeId={}，number={}，gatewayId={}",
                    tenantId, nodeId, number.getNumber(), number.getGatewayId());
                return null;
            }
            PhoneNumberOutboundRouteResponse response = new PhoneNumberOutboundRouteResponse();
            response.setNumberId(number.getId());
            response.setNumber(number.getNumber());
            response.setGatewayId(gateway.getId());
            response.setGatewayCode(gateway.getGatewayCode());
            response.setGatewayName(gateway.getGatewayName());
            applyGatewayRoute(response, gateway, nodeId);
            return response;
        });
    }

    private PhoneNumber findDefaultOutboundNumber(Long nodeId) {
        return mapper.selectOne(new LambdaQueryWrapper<PhoneNumber>()
            .eq(PhoneNumber::getNodeId, nodeId)
            .eq(PhoneNumber::getEnabled, true)
            .eq(PhoneNumber::getOutboundDefault, true)
            .isNotNull(PhoneNumber::getGatewayId)
            .in(PhoneNumber::getNumberType, "CALLER_ID", "BOTH")
            .orderByAsc(PhoneNumber::getId)
            .last("limit 1"));
    }

    private PhoneNumber findFirstOutboundNumber(Long nodeId) {
        return mapper.selectOne(new LambdaQueryWrapper<PhoneNumber>()
            .eq(PhoneNumber::getNodeId, nodeId)
            .eq(PhoneNumber::getEnabled, true)
            .isNotNull(PhoneNumber::getGatewayId)
            .in(PhoneNumber::getNumberType, "CALLER_ID", "BOTH")
            .orderByDesc(PhoneNumber::getOutboundDefault)
            .orderByAsc(PhoneNumber::getId)
            .last("limit 1"));
    }

    @Override
    public PhoneNumberOutboundRouteResponse findOutboundRouteByNumberId(String tenantId, Long nodeId, Long numberId) {
        if (nodeId == null || numberId == null) return null;
        return TenantHelper.dynamic(tenantId, () -> {
            PhoneNumber number = mapper.selectOne(new LambdaQueryWrapper<PhoneNumber>()
                .eq(PhoneNumber::getId, numberId)
                .eq(PhoneNumber::getNodeId, nodeId)
                .eq(PhoneNumber::getEnabled, true)
                .isNotNull(PhoneNumber::getGatewayId)
                .in(PhoneNumber::getNumberType, "CALLER_ID", "BOTH")
                .last("limit 1"));
            return number == null ? null : toOutboundRoute(tenantId, nodeId, number);
        });
    }

    @Override
    public PhoneNumberOutboundRouteResponse findDefaultOutboundRoute(String tenantId, String domain, String switchIpv4) {
        return TenantHelper.dynamic(tenantId, () -> {
            FreeSwitchNode node = findEnabledNodeByDomain(domain);
            if (node == null && StringUtils.isNotBlank(switchIpv4)) {
                node = nodeMapper.selectOne(new LambdaQueryWrapper<FreeSwitchNode>()
                    .eq(FreeSwitchNode::getEslHost, switchIpv4)
                    .eq(FreeSwitchNode::getEnabled, true)
                    .last("limit 1"));
            }
            if (node == null) {
                log.warn("动态外呼路由未匹配到 FreeSWITCH 节点，tenantId={}，domain={}，switchIpv4={}",
                    tenantId, domain, switchIpv4);
                return null;
            }
            return findDefaultOutboundRoute(tenantId, node.getId());
        });
    }

    private PhoneNumberOutboundRouteResponse toOutboundRoute(String tenantId, Long nodeId, PhoneNumber number) {
        FreeSwitchGateway gateway = gatewayMapper.selectById(number.getGatewayId());
        if (gateway == null || !Boolean.TRUE.equals(gateway.getEnabled()) || !nodeId.equals(gateway.getNodeId())
            || !("OUTBOUND".equals(gateway.getDirection()) || "BOTH".equals(gateway.getDirection()))) {
            log.warn("外呼主叫号码绑定的网关不可用，tenantId={}，nodeId={}，number={}，gatewayId={}",
                tenantId, nodeId, number.getNumber(), number.getGatewayId());
            return null;
        }
        PhoneNumberOutboundRouteResponse response = new PhoneNumberOutboundRouteResponse();
        response.setNumberId(number.getId());
        response.setNumber(number.getNumber());
        response.setGatewayId(gateway.getId());
        response.setGatewayCode(gateway.getGatewayCode());
        response.setGatewayName(gateway.getGatewayName());
        applyGatewayRoute(response, gateway, nodeId);
        return response;
    }

    private void applyGatewayRoute(PhoneNumberOutboundRouteResponse response, FreeSwitchGateway gateway, Long nodeId) {
        response.setGatewayAccessMode(gateway.getAccessMode());
        response.setRegisteredIdentity(gateway.getRegisteredIdentity());
        response.setGatewaySipProfile(gateway.getSipProfile());
        FreeSwitchNode node = nodeMapper.selectById(nodeId);
        if (node != null) response.setSipDomain(node.getSipDomain());
    }

    private void ensureNodeExists(Long nodeId) {
        FreeSwitchNode node = nodeMapper.selectById(nodeId);
        if (node == null) throw new ServiceException("FreeSWITCH 节点不存在");
    }

    private void ensureGatewayAvailable(Long nodeId, Long gatewayId) {
        if (gatewayId == null) return;
        FreeSwitchGateway gateway = gatewayMapper.selectById(gatewayId);
        if (gateway == null || !nodeId.equals(gateway.getNodeId())) {
            throw new ServiceException("FreeSWITCH 网关不存在");
        }
    }

    private void ensureNumberUnique(String number, Long excludedId) {
        boolean exists = mapper.exists(new LambdaQueryWrapper<PhoneNumber>()
            .eq(PhoneNumber::getTenantId, LoginHelper.getTenantId())
            .eq(PhoneNumber::getNumber, number)
            .ne(excludedId != null, PhoneNumber::getId, excludedId));
        if (exists) throw new ServiceException("该号码已存在");
    }

    private boolean hasInboundRoutes(Long phoneNumberId) {
        return inboundDidEntryMapper.exists(new LambdaQueryWrapper<InboundDidEntry>()
            .eq(InboundDidEntry::getPhoneNumberId, phoneNumberId));
    }

    private void apply(PhoneNumber number, String value, String name, String type, Long nodeId, Long gatewayId,
                       Boolean outboundDefault) {
        number.setNumber(value);
        number.setNumberName(name);
        number.setNumberType(type);
        number.setNodeId(nodeId);
        number.setGatewayId(gatewayId);
        number.setOutboundDefault(outboundDefault);
    }

    private PhoneNumberResponse toResponse(PhoneNumber number) {
        PhoneNumberResponse response = new PhoneNumberResponse();
        response.setId(number.getId());
        response.setNumber(number.getNumber());
        response.setNumberName(number.getNumberName());
        response.setNumberType(number.getNumberType());
        response.setNodeId(number.getNodeId());
        FreeSwitchNode node = nodeMapper.selectById(number.getNodeId());
        if (node != null) response.setNodeName(node.getNodeName());
        response.setGatewayId(number.getGatewayId());
        if (number.getGatewayId() != null) {
            FreeSwitchGateway gateway = gatewayMapper.selectById(number.getGatewayId());
            if (gateway != null) response.setGatewayName(gateway.getGatewayName());
        }
        response.setRouteType(number.getRouteType());
        response.setRouteTarget(number.getRouteTarget());
        response.setOutboundDefault(number.getOutboundDefault());
        response.setEnabled(number.getEnabled());
        response.setVersion(number.getVersion());
        response.setCreateTime(number.getCreateTime());
        if ("BUSINESS_HOURS".equals(number.getRouteType())) {
            response.setBusinessHoursRoute(businessHoursRouteService.findByPhoneNumberId(number.getId()));
        }
        return response;
    }
}
