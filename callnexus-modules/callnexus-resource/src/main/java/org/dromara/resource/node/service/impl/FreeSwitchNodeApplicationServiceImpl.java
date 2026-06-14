package org.dromara.resource.node.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.domain.request.CreateFreeSwitchNodeRequest;
import org.dromara.resource.node.domain.request.FreeSwitchNodePageQuery;
import org.dromara.resource.node.domain.request.UpdateFreeSwitchNodeRequest;
import org.dromara.resource.node.domain.response.FreeSwitchNodeResponse;
import org.dromara.resource.node.domain.response.FreeSwitchNodeConnectionResponse;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.dromara.resource.node.service.FreeSwitchNodeApplicationService;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.gateway.domain.FreeSwitchGateway;
import org.dromara.resource.gateway.mapper.FreeSwitchGatewayMapper;
import org.dromara.resource.sip.domain.SipAccount;
import org.dromara.resource.sip.mapper.SipAccountMapper;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FreeSwitchNodeApplicationServiceImpl implements FreeSwitchNodeApplicationService, FreeSwitchNodeQueryService {
    private final FreeSwitchNodeMapper mapper;
    private final SipAccountMapper sipAccountMapper;
    private final FreeSwitchGatewayMapper gatewayMapper;
    private final FreeSwitchNodeGroupMemberMapper groupMemberMapper;

    @Override
    public TableDataInfo<FreeSwitchNodeResponse> page(FreeSwitchNodePageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<FreeSwitchNode> wrapper = new LambdaQueryWrapper<FreeSwitchNode>()
            .like(query.getNodeCode() != null && !query.getNodeCode().isBlank(), FreeSwitchNode::getNodeCode, query.getNodeCode())
            .like(query.getNodeName() != null && !query.getNodeName().isBlank(), FreeSwitchNode::getNodeName, query.getNodeName())
            .eq(query.getEnabled() != null, FreeSwitchNode::getEnabled, query.getEnabled())
            .orderByAsc(FreeSwitchNode::getNodeCode);
        Page<FreeSwitchNode> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public FreeSwitchNodeResponse get(Long id) {
        FreeSwitchNode node = mapper.selectById(id);
        if (node == null) throw new ServiceException("FreeSWITCH 节点不存在");
        return toResponse(node);
    }

    @Override
    public FreeSwitchNodeConnectionResponse getEnabledConnection(Long nodeId) {
        FreeSwitchNode node = mapper.selectById(nodeId);
        if (node == null || !Boolean.TRUE.equals(node.getEnabled())) {
            throw new ServiceException("FreeSWITCH 节点不存在或已停用");
        }
        if (node.getEslHost() == null || node.getEslHost().isBlank()
            || node.getEslPort() == null || node.getEslPassword() == null || node.getEslPassword().isBlank()) {
            throw new ServiceException("FreeSWITCH 节点 ESL 未配置");
        }
        return toConnectionResponse(node);
    }

    @Override
    public List<FreeSwitchNodeConnectionResponse> listEnabledConnections() {
        return TenantHelper.ignore(() -> mapper.selectList(new LambdaQueryWrapper<FreeSwitchNode>()
                .eq(FreeSwitchNode::getEnabled, true))
            .stream()
            .filter(node -> node.getEslHost() != null && !node.getEslHost().isBlank()
                && node.getEslPort() != null && node.getEslPassword() != null && !node.getEslPassword().isBlank())
            .map(this::toConnectionResponse)
              .toList());
    }

    @Override
    public String findTenantId(Long nodeId) {
        if (nodeId == null) return null;
        return TenantHelper.ignore(() -> {
            FreeSwitchNode node = mapper.selectById(nodeId);
            return node == null ? null : node.getTenantId();
        });
    }

    @Override
    public Long resolveEnabledNodeIdByAgentToken(String tenantId, String nodeCode, String nodeToken) {
        if (nodeCode == null || nodeCode.isBlank() || nodeToken == null || nodeToken.isBlank()) {
            return null;
        }
        String tokenHash = sha256(nodeToken);
        return TenantHelper.dynamic(tenantId, () -> {
            FreeSwitchNode node = mapper.selectOne(new LambdaQueryWrapper<FreeSwitchNode>()
                .eq(FreeSwitchNode::getTenantId, tenantId)
                .eq(FreeSwitchNode::getNodeCode, nodeCode)
                .eq(FreeSwitchNode::getAgentTokenHash, tokenHash)
                .eq(FreeSwitchNode::getAgentEnabled, true)
                .eq(FreeSwitchNode::getEnabled, true)
                .last("limit 1"));
            return node == null ? null : node.getId();
        });
    }

    @Override
    public Long resolveEnabledNodeId(String tenantId, String remoteAddress, String switchIpv4, String hostname, String fallbackNodeId) {
        return TenantHelper.dynamic(tenantId, () -> {
            List<FreeSwitchNode> automaticMatches = mapper.selectList(new LambdaQueryWrapper<FreeSwitchNode>()
                    .eq(FreeSwitchNode::getTenantId, tenantId)
                    .eq(FreeSwitchNode::getEnabled, true))
                .stream()
                .filter(node -> matchesNode(node, remoteAddress, switchIpv4, hostname))
                .toList();
            if (automaticMatches.size() > 1) {
                throw new ServiceException("FreeSWITCH 主机信息匹配到多个启用节点，请检查节点 ESL 主机和节点编码");
            }
            if (automaticMatches.size() == 1) {
                return automaticMatches.get(0).getId();
            }
            if (fallbackNodeId == null || !fallbackNodeId.matches("^\\d+$")) {
                return null;
            }
            FreeSwitchNode fallback = mapper.selectById(Long.valueOf(fallbackNodeId));
            return fallback != null && Objects.equals(tenantId, fallback.getTenantId())
                && Boolean.TRUE.equals(fallback.getEnabled()) ? fallback.getId() : null;
        });
    }

    private boolean matchesNode(FreeSwitchNode node, String remoteAddress, String switchIpv4, String hostname) {
        return equalsIgnoreCase(node.getEslHost(), remoteAddress)
            || equalsIgnoreCase(node.getEslHost(), switchIpv4)
            || equalsIgnoreCase(node.getEslHost(), hostname)
            || equalsIgnoreCase(node.getNodeCode(), hostname);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && !left.isBlank() && !right.isBlank()
            && left.trim().equalsIgnoreCase(right.trim());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ServiceException("生成节点 Token 摘要失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateFreeSwitchNodeRequest request) {
        ensureNodeCodeUnique(request.getNodeCode(), null);
        FreeSwitchNode node = new FreeSwitchNode();
        apply(node, request.getNodeCode(), request.getNodeName(), request.getSipDomain(), request.getWssUrl(), request.getEslHost(), request.getEslPort());
        node.setEslPassword(request.getEslPassword());
        node.setEnabled(true);
        node.setAgentEnabled(false);
        node.setMediaRootPath("/var/lib/freeswitch/sounds/callnexus");
        mapper.insert(node);
        return node.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdateFreeSwitchNodeRequest request) {
        ensureNodeCodeUnique(request.getNodeCode(), id);
        FreeSwitchNode node = mapper.selectById(id);
        if (node == null) throw new ServiceException("FreeSWITCH 节点不存在");
        apply(node, request.getNodeCode(), request.getNodeName(), request.getSipDomain(), request.getWssUrl(), request.getEslHost(), request.getEslPort());
        if (request.getEslPassword() != null && !request.getEslPassword().isBlank()) node.setEslPassword(request.getEslPassword());
        node.setEnabled(request.getEnabled());
        node.setAgentEnabled(request.getAgentEnabled());
        node.setMediaRootPath(request.getMediaRootPath());
        node.setVersion(request.getVersion());
        if (mapper.updateById(node) != 1) throw new ServiceException("FreeSWITCH 节点已被其他用户修改，请刷新后重试");
    }

    @Override
    public void delete(Long id) {
        if (sipAccountMapper.exists(new LambdaQueryWrapper<SipAccount>().eq(SipAccount::getNodeId, id))) {
            throw new ServiceException("FreeSWITCH 节点正在被使用，无法删除");
        }
        if (gatewayMapper.exists(new LambdaQueryWrapper<FreeSwitchGateway>().eq(FreeSwitchGateway::getNodeId, id))) {
            throw new ServiceException("FreeSWITCH 节点正在被使用，无法删除");
        }
        if (groupMemberMapper.exists(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>().eq(FreeSwitchNodeGroupMember::getNodeId, id))) {
            throw new ServiceException("FreeSWITCH 节点已加入节点分组，无法删除");
        }
        if (mapper.deleteById(id) != 1) throw new ServiceException("FreeSWITCH 节点不存在");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resetAgentToken(Long id) {
        FreeSwitchNode node = mapper.selectById(id);
        if (node == null) throw new ServiceException("FreeSWITCH 节点不存在");
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        try {
            node.setAgentTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception exception) {
            throw new ServiceException("生成媒体 Agent Token 失败");
        }
        node.setAgentEnabled(true);
        mapper.updateById(node);
        return token;
    }

    private void ensureNodeCodeUnique(String nodeCode, Long excludedId) {
        boolean exists = mapper.exists(new LambdaQueryWrapper<FreeSwitchNode>()
            .eq(FreeSwitchNode::getTenantId, LoginHelper.getTenantId())
            .eq(FreeSwitchNode::getNodeCode, nodeCode)
            .ne(excludedId != null, FreeSwitchNode::getId, excludedId));
        if (exists) throw new ServiceException("FreeSWITCH 节点编码已存在");
    }

    private void apply(FreeSwitchNode node, String code, String name, String sipDomain, String wssUrl, String eslHost, Integer eslPort) {
        node.setNodeCode(code);
        node.setNodeName(name);
        node.setSipDomain(sipDomain);
        node.setWssUrl(wssUrl);
        node.setEslHost(eslHost);
        node.setEslPort(eslPort == null ? 8021 : eslPort);
    }

    private FreeSwitchNodeResponse toResponse(FreeSwitchNode node) {
        FreeSwitchNodeResponse response = new FreeSwitchNodeResponse();
        response.setId(node.getId());
        response.setNodeCode(node.getNodeCode());
        response.setNodeName(node.getNodeName());
        response.setSipDomain(node.getSipDomain());
        response.setWssUrl(node.getWssUrl());
        response.setEslHost(node.getEslHost());
        response.setEslPort(node.getEslPort());
        response.setEnabled(node.getEnabled());
        response.setAgentEnabled(node.getAgentEnabled());
        response.setAgentLastHeartbeat(node.getAgentLastHeartbeat());
        response.setAgentVersion(node.getAgentVersion());
        response.setMediaRootPath(node.getMediaRootPath());
        response.setVersion(node.getVersion());
        response.setCreateTime(node.getCreateTime());
        return response;
    }

    private FreeSwitchNodeConnectionResponse toConnectionResponse(FreeSwitchNode node) {
        FreeSwitchNodeConnectionResponse response = new FreeSwitchNodeConnectionResponse();
        response.setNodeId(node.getId());
        response.setSipDomain(node.getSipDomain());
        response.setEslHost(node.getEslHost());
        response.setEslPort(node.getEslPort());
        response.setEslPassword(node.getEslPassword());
        return response;
    }
}
