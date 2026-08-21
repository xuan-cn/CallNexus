package org.dromara.resource.inbound.service.impl;

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
import org.dromara.resource.freeswitch.xmlcurl.FreeSwitchXmlCurlRequest;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.gateway.mapper.FreeSwitchGatewayMapper;
import org.dromara.resource.inbound.domain.InboundDidEntry;
import org.dromara.resource.inbound.domain.request.CreateInboundDidEntryRequest;
import org.dromara.resource.inbound.domain.request.InboundDidEntryPageQuery;
import org.dromara.resource.inbound.domain.request.InboundRouteTestRequest;
import org.dromara.resource.inbound.domain.request.UpdateInboundDidEntryRequest;
import org.dromara.resource.inbound.domain.response.InboundDidEntryResponse;
import org.dromara.resource.inbound.domain.response.InboundRouteMatchResponse;
import org.dromara.resource.inbound.mapper.InboundDidEntryMapper;
import org.dromara.resource.inbound.service.InboundDidEntryApplicationService;
import org.dromara.resource.inbound.service.InboundDidEntryQueryService;
import org.dromara.resource.ivr.service.IvrDialplanQueryService;
import org.dromara.resource.businesshours.service.PhoneBusinessHoursRouteService;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.dromara.resource.phone.domain.PhoneNumber;
import org.dromara.resource.phone.mapper.PhoneNumberMapper;
import org.dromara.resource.queue.domain.response.CallQueueDialplanResponse;
import org.dromara.resource.queue.service.CallQueueQueryService;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.dromara.resource.voicemail.service.VoiceMailBoxQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InboundDidEntryApplicationServiceImpl implements InboundDidEntryApplicationService, InboundDidEntryQueryService {
    private static final List<String> ENTRY_TYPES = List.of("DID", "PORT", "ACCOUNT", "HEADER");
    private static final List<String> ROUTE_TYPES = List.of("IVR", "QUEUE", "EXTENSION", "VOICEMAIL", "BUSINESS_HOURS");

    private final InboundDidEntryMapper mapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final FreeSwitchGatewayMapper gatewayMapper;
    private final PhoneNumberMapper phoneNumberMapper;
    private final IvrDialplanQueryService ivrDialplanQueryService;
    private final CallQueueQueryService callQueueQueryService;
    private final SipAccountQueryService sipAccountQueryService;
    private final VoiceMailBoxQueryService voiceMailBoxQueryService;
    private final PhoneBusinessHoursRouteService phoneBusinessHoursRouteService;

    @Override
    public TableDataInfo<InboundDidEntryResponse> page(InboundDidEntryPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<InboundDidEntry> wrapper = new LambdaQueryWrapper<InboundDidEntry>()
            .eq(query.getNodeId() != null, InboundDidEntry::getNodeId, query.getNodeId())
            .eq(query.getGatewayId() != null, InboundDidEntry::getGatewayId, query.getGatewayId())
            .eq(query.getPhoneNumberId() != null, InboundDidEntry::getPhoneNumberId, query.getPhoneNumberId())
            .like(StringUtils.isNotBlank(query.getEntryName()), InboundDidEntry::getEntryName, query.getEntryName())
            .eq(StringUtils.isNotBlank(query.getEntryType()), InboundDidEntry::getEntryType, query.getEntryType())
            .like(StringUtils.isNotBlank(query.getDidNumber()), InboundDidEntry::getDidNumber, query.getDidNumber())
            .eq(StringUtils.isNotBlank(query.getRouteTargetType()), InboundDidEntry::getRouteTargetType, query.getRouteTargetType())
            .eq(query.getEnabled() != null, InboundDidEntry::getEnabled, query.getEnabled())
            .orderByAsc(InboundDidEntry::getGatewayId)
            .orderByAsc(InboundDidEntry::getPriority)
            .orderByDesc(InboundDidEntry::getCreateTime);
        Page<InboundDidEntry> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public InboundDidEntryResponse get(Long id) {
        InboundDidEntry entry = mapper.selectById(id);
        if (entry == null) throw new ServiceException("DID/端口入口不存在");
        return toResponse(entry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateInboundDidEntryRequest request) {
        validateReference(request.getNodeId(), request.getGatewayId(), request.getPhoneNumberId(), request.getEntryType(), request.getRouteTargetType(), request.getRouteTargetId());
        ensureMatchKey(request.getEntryType(), request.getDidNumber(), request.getPortCode(), request.getAccountCode(), request.getHeaderName(), request.getHeaderValue());
        ensureUnique(request.getNodeId(), request.getGatewayId(), request.getEntryType(), request.getDidNumber(), request.getPortCode(),
            request.getAccountCode(), request.getHeaderName(), request.getHeaderValue(), null);
        InboundDidEntry entry = new InboundDidEntry();
        apply(entry, request.getNodeId(), request.getGatewayId(), request.getPhoneNumberId(), request.getEntryName(), request.getEntryType(), request.getDidNumber(),
            request.getPortCode(), request.getAccountCode(), request.getHeaderName(), request.getHeaderValue(), request.getRouteTargetType(),
            request.getRouteTargetId(), request.getPriority(), request.getRemark());
        entry.setEnabled(true);
        mapper.insert(entry);
        return entry.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdateInboundDidEntryRequest request) {
        validateReference(request.getNodeId(), request.getGatewayId(), request.getPhoneNumberId(), request.getEntryType(), request.getRouteTargetType(), request.getRouteTargetId());
        ensureMatchKey(request.getEntryType(), request.getDidNumber(), request.getPortCode(), request.getAccountCode(), request.getHeaderName(), request.getHeaderValue());
        ensureUnique(request.getNodeId(), request.getGatewayId(), request.getEntryType(), request.getDidNumber(), request.getPortCode(),
            request.getAccountCode(), request.getHeaderName(), request.getHeaderValue(), id);
        InboundDidEntry entry = mapper.selectById(id);
        if (entry == null) throw new ServiceException("DID/端口入口不存在");
        apply(entry, request.getNodeId(), request.getGatewayId(), request.getPhoneNumberId(), request.getEntryName(), request.getEntryType(), request.getDidNumber(),
            request.getPortCode(), request.getAccountCode(), request.getHeaderName(), request.getHeaderValue(), request.getRouteTargetType(),
            request.getRouteTargetId(), request.getPriority(), request.getRemark());
        entry.setEnabled(request.getEnabled());
        entry.setVersion(request.getVersion());
        if (mapper.updateById(entry) != 1) throw new ServiceException("DID/端口入口已被其他用户修改，请刷新后重试");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (mapper.deleteById(id) != 1) throw new ServiceException("DID/端口入口不存在");
    }

    @Override
    public InboundRouteMatchResponse test(InboundRouteTestRequest request) {
        return match(LoginHelper.getTenantId(), request.getNodeId(), request.getGatewayId(), request.getCalledNumber(),
            request.getPortCode(), request.getAccountCode(), request.getHeaderName(), request.getHeaderValue());
    }

    @Override
    public InboundRouteMatchResponse match(String tenantId, Long nodeId, Long gatewayId, String calledNumber,
                                           String portCode, String accountCode, String headerName, String headerValue) {
        return TenantHelper.dynamic(tenantId, () -> {
            List<InboundDidEntry> entries = mapper.selectList(new LambdaQueryWrapper<InboundDidEntry>()
                .eq(InboundDidEntry::getEnabled, true)
                .eq(nodeId != null, InboundDidEntry::getNodeId, nodeId)
                .eq(gatewayId != null, InboundDidEntry::getGatewayId, gatewayId)
                .orderByAsc(InboundDidEntry::getPriority)
                .orderByDesc(InboundDidEntry::getCreateTime));
            InboundDidEntry matched = entries.stream()
                .filter(entry -> matches(entry, calledNumber, portCode, accountCode, headerName, headerValue))
                .min(Comparator.comparing(entry -> entry.getPriority() == null ? 100 : entry.getPriority()))
                .orElse(null);
            if (matched == null) return unmatched();
            return toMatchResponse(matched, "ENTRY", "命中 DID/端口入口规则");
        });
    }

    @Override
    public PhoneNumberDialplanRouteResponse findDialplanRoute(FreeSwitchXmlCurlRequest request, Long nodeId,
                                                              Long gatewayId, String calledNumber) {
        InboundRouteMatchResponse match = match(request.tenantId(), nodeId, gatewayId, calledNumber,
            firstValue(request, "variable_callnexus_port_code", "callnexus_port_code", "sip_h_X-CallNexus-Port", "variable_sip_h_X-CallNexus-Port"),
            firstValue(request, "variable_gateway", "gateway", "sip_gateway", "variable_sip_gateway", "sip_auth_username", "variable_sip_auth_username"),
            firstValue(request, "variable_callnexus_header_name", "callnexus_header_name"),
            firstValue(request, "variable_callnexus_header_value", "callnexus_header_value"));
        if (!Boolean.TRUE.equals(match.getMatched())) return null;
        PhoneNumberDialplanRouteResponse route = new PhoneNumberDialplanRouteResponse();
        route.setId(match.getEntryId());
        route.setNumber(StringUtils.isNotBlank(match.getDidNumber()) ? match.getDidNumber() : calledNumber);
        route.setRouteType(match.getRouteTargetType());
        route.setRouteTarget(match.getRouteTargetId());
        route.setNodeId(match.getNodeId());
        FreeSwitchNode node = nodeMapper.selectById(match.getNodeId());
        route.setSipDomain(node == null ? request.domain() : node.getSipDomain());
        return route;
    }

    private void validateReference(Long nodeId, Long gatewayId, Long phoneNumberId, String entryType, String routeType, String routeTargetId) {
        FreeSwitchNode node = nodeMapper.selectById(nodeId);
        if (node == null) throw new ServiceException("FreeSWITCH 节点不存在");
        FreeSwitchGateway gateway = gatewayMapper.selectById(gatewayId);
        if (gateway == null || !nodeId.equals(gateway.getNodeId())) throw new ServiceException("网关不存在或不属于当前节点");
        if (phoneNumberId != null) {
            PhoneNumber phoneNumber = phoneNumberMapper.selectById(phoneNumberId);
            if (phoneNumber == null) throw new ServiceException("关联号码不存在");
            if (!nodeId.equals(phoneNumber.getNodeId())) throw new ServiceException("关联号码不属于当前节点");
            if (phoneNumber.getGatewayId() != null && !gatewayId.equals(phoneNumber.getGatewayId())) {
                throw new ServiceException("关联号码不属于当前网关");
            }
        }
        if (!ENTRY_TYPES.contains(entryType)) throw new ServiceException("不支持的入口类型");
        if (!ROUTE_TYPES.contains(routeType)) throw new ServiceException("不支持的呼入路由目标类型");
        validateRouteTarget(node, routeType, routeTargetId);
    }

    private void validateRouteTarget(FreeSwitchNode node, String routeType, String routeTargetId) {
        try {
            if ("IVR".equals(routeType)) {
                Long flowId = Long.valueOf(routeTargetId);
                if (!ivrDialplanQueryService.isPublishedFlowAvailable(LoginHelper.getTenantId(), flowId, node.getId())) {
                    throw new ServiceException("IVR 流程不存在、未发布或不属于当前节点");
                }
            } else if ("QUEUE".equals(routeType)) {
                Long queueId = Long.valueOf(routeTargetId);
                CallQueueDialplanResponse queue = callQueueQueryService.findAvailableQueue(LoginHelper.getTenantId(), queueId, node.getId());
                if (queue == null) throw new ServiceException("队列不存在、停用或不属于当前节点");
            } else if ("EXTENSION".equals(routeType)) {
                SipDirectoryAccountResponse account = sipAccountQueryService.findDirectoryAccountByExtension(
                    LoginHelper.getTenantId(), node.getSipDomain(), routeTargetId);
                if (account == null) throw new ServiceException("分机不存在或未启用");
            } else if ("VOICEMAIL".equals(routeType)) {
                Long boxId = Long.valueOf(routeTargetId);
                if (!voiceMailBoxQueryService.isAvailable(LoginHelper.getTenantId(), boxId, node.getId())) {
                    throw new ServiceException("语音留言箱未启用，或提示音未同步到当前节点");
                }
            } else if ("BUSINESS_HOURS".equals(routeType)) {
                phoneBusinessHoursRouteService.require(Long.valueOf(routeTargetId));
            }
        } catch (NumberFormatException exception) {
            throw new ServiceException("路由目标格式不正确");
        }
    }

    private void ensureMatchKey(String entryType, String didNumber, String portCode, String accountCode,
                                String headerName, String headerValue) {
        if ("DID".equals(entryType) && StringUtils.isBlank(didNumber)) throw new ServiceException("DID入口必须填写 DID 号码");
        if ("PORT".equals(entryType) && StringUtils.isBlank(portCode)) throw new ServiceException("端口入口必须填写端口标识");
        if ("ACCOUNT".equals(entryType) && StringUtils.isBlank(accountCode)) throw new ServiceException("账号入口必须填写账号标识");
        if ("HEADER".equals(entryType) && (StringUtils.isBlank(headerName) || StringUtils.isBlank(headerValue))) {
            throw new ServiceException("Header入口必须填写 Header 名称和值");
        }
    }

    private void ensureUnique(Long nodeId, Long gatewayId, String entryType, String didNumber, String portCode,
                              String accountCode, String headerName, String headerValue, Long excludedId) {
        boolean exists = mapper.exists(new LambdaQueryWrapper<InboundDidEntry>()
            .eq(InboundDidEntry::getNodeId, nodeId)
            .eq(InboundDidEntry::getGatewayId, gatewayId)
            .eq(InboundDidEntry::getEntryType, entryType)
            .eq("DID".equals(entryType), InboundDidEntry::getDidNumber, blankToNull(didNumber))
            .eq("PORT".equals(entryType), InboundDidEntry::getPortCode, blankToNull(portCode))
            .eq("ACCOUNT".equals(entryType), InboundDidEntry::getAccountCode, blankToNull(accountCode))
            .eq("HEADER".equals(entryType), InboundDidEntry::getHeaderName, blankToNull(headerName))
            .eq("HEADER".equals(entryType), InboundDidEntry::getHeaderValue, blankToNull(headerValue))
            .ne(excludedId != null, InboundDidEntry::getId, excludedId));
        if (exists) throw new ServiceException("相同网关下已存在相同入口规则");
    }

    private void apply(InboundDidEntry entry, Long nodeId, Long gatewayId, Long phoneNumberId, String entryName, String entryType, String didNumber,
                       String portCode, String accountCode, String headerName, String headerValue, String routeTargetType,
                       String routeTargetId, Integer priority, String remark) {
        entry.setNodeId(nodeId);
        entry.setGatewayId(gatewayId);
        entry.setPhoneNumberId(phoneNumberId);
        entry.setEntryName(entryName);
        entry.setEntryType(entryType);
        entry.setDidNumber("DID".equals(entryType) ? blankToNull(didNumber) : null);
        entry.setPortCode("PORT".equals(entryType) ? blankToNull(portCode) : null);
        entry.setAccountCode("ACCOUNT".equals(entryType) ? blankToNull(accountCode) : null);
        entry.setHeaderName("HEADER".equals(entryType) ? blankToNull(headerName) : null);
        entry.setHeaderValue("HEADER".equals(entryType) ? blankToNull(headerValue) : null);
        entry.setRouteTargetType(routeTargetType);
        entry.setRouteTargetId(routeTargetId);
        entry.setPriority(priority == null ? 100 : priority);
        entry.setRemark(remark);
    }

    private boolean matches(InboundDidEntry entry, String calledNumber, String portCode, String accountCode,
                            String headerName, String headerValue) {
        return switch (entry.getEntryType()) {
            case "DID" -> equalsIgnoreBlank(entry.getDidNumber(), calledNumber);
            case "PORT" -> equalsIgnoreBlank(entry.getPortCode(), portCode);
            case "ACCOUNT" -> equalsIgnoreBlank(entry.getAccountCode(), accountCode);
            case "HEADER" -> equalsIgnoreBlank(entry.getHeaderName(), headerName) && equalsIgnoreBlank(entry.getHeaderValue(), headerValue);
            default -> false;
        };
    }

    private InboundRouteMatchResponse unmatched() {
        InboundRouteMatchResponse response = new InboundRouteMatchResponse();
        response.setMatched(false);
        response.setMatchedType("NONE");
        response.setMatchedMessage("未命中 DID/端口入口规则，将继续走号码资源或网关默认路由");
        return response;
    }

    private InboundRouteMatchResponse toMatchResponse(InboundDidEntry entry, String type, String message) {
        InboundRouteMatchResponse response = new InboundRouteMatchResponse();
        response.setMatched(true);
        response.setMatchedType(type);
        response.setMatchedMessage(message);
        response.setEntryId(entry.getId());
        response.setEntryName(entry.getEntryName());
        response.setEntryType(entry.getEntryType());
        response.setDidNumber(entry.getDidNumber());
        response.setPortCode(entry.getPortCode());
        response.setAccountCode(entry.getAccountCode());
        response.setMatchValue(resolveMatchValue(entry));
        response.setGatewayId(entry.getGatewayId());
        FreeSwitchGateway gateway = gatewayMapper.selectById(entry.getGatewayId());
        if (gateway != null) {
            response.setGatewayName(gateway.getGatewayName());
            response.setGatewayCode(gateway.getGatewayCode());
        }
        response.setNodeId(entry.getNodeId());
        response.setRouteTargetType(entry.getRouteTargetType());
        response.setRouteTargetId(entry.getRouteTargetId());
        response.setRouteTargetName(resolveTargetName(entry));
        response.setPriority(entry.getPriority());
        return response;
    }

    private InboundDidEntryResponse toResponse(InboundDidEntry entry) {
        InboundDidEntryResponse response = new InboundDidEntryResponse();
        response.setId(entry.getId());
        response.setNodeId(entry.getNodeId());
        FreeSwitchNode node = nodeMapper.selectById(entry.getNodeId());
        if (node != null) response.setNodeName(node.getNodeName());
        response.setGatewayId(entry.getGatewayId());
        FreeSwitchGateway gateway = gatewayMapper.selectById(entry.getGatewayId());
        if (gateway != null) {
            response.setGatewayName(gateway.getGatewayName());
            response.setGatewayCode(gateway.getGatewayCode());
        }
        response.setPhoneNumberId(entry.getPhoneNumberId());
        if (entry.getPhoneNumberId() != null) {
            PhoneNumber phoneNumber = phoneNumberMapper.selectById(entry.getPhoneNumberId());
            if (phoneNumber != null) {
                response.setPhoneNumber(phoneNumber.getNumber());
                response.setPhoneNumberName(phoneNumber.getNumberName());
            }
        }
        response.setEntryName(entry.getEntryName());
        response.setEntryType(entry.getEntryType());
        response.setDidNumber(entry.getDidNumber());
        response.setPortCode(entry.getPortCode());
        response.setAccountCode(entry.getAccountCode());
        response.setHeaderName(entry.getHeaderName());
        response.setHeaderValue(entry.getHeaderValue());
        response.setRouteTargetType(entry.getRouteTargetType());
        response.setRouteTargetId(entry.getRouteTargetId());
        response.setRouteTargetName(resolveTargetName(entry));
        response.setPriority(entry.getPriority());
        response.setEnabled(entry.getEnabled());
        response.setRemark(entry.getRemark());
        response.setVersion(entry.getVersion());
        response.setCreateTime(entry.getCreateTime());
        return response;
    }

    private String resolveTargetName(InboundDidEntry entry) {
        return switch (entry.getRouteTargetType()) {
            case "QUEUE", "IVR", "EXTENSION" -> entry.getRouteTargetId();
            default -> entry.getRouteTargetId();
        };
    }

    private String resolveMatchValue(InboundDidEntry entry) {
        return switch (entry.getEntryType()) {
            case "DID" -> entry.getDidNumber();
            case "PORT" -> entry.getPortCode();
            case "ACCOUNT" -> entry.getAccountCode();
            case "HEADER" -> entry.getHeaderName() + ": " + entry.getHeaderValue();
            default -> null;
        };
    }

    private String firstValue(FreeSwitchXmlCurlRequest request, String... names) {
        for (String name : names) {
            String value = request.firstValue(name);
            if (StringUtils.isNotBlank(value)) return value;
        }
        return null;
    }

    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private boolean equalsIgnoreBlank(String expected, String actual) {
        return StringUtils.isNotBlank(expected) && StringUtils.isNotBlank(actual) && expected.trim().equalsIgnoreCase(actual.trim());
    }
}
