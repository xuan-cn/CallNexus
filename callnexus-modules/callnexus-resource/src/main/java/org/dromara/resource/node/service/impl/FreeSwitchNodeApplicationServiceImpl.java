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
        if (node == null) throw new ServiceException("FREESWITCH_NODE_NOT_FOUND");
        return toResponse(node);
    }

    @Override
    public FreeSwitchNodeConnectionResponse getEnabledConnection(Long nodeId) {
        FreeSwitchNode node = mapper.selectById(nodeId);
        if (node == null || !Boolean.TRUE.equals(node.getEnabled())) {
            throw new ServiceException("FREESWITCH_NODE_NOT_FOUND_OR_DISABLED");
        }
        if (node.getEslHost() == null || node.getEslHost().isBlank()
            || node.getEslPort() == null || node.getEslPassword() == null || node.getEslPassword().isBlank()) {
            throw new ServiceException("FREESWITCH_NODE_ESL_NOT_CONFIGURED");
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
        if (node == null) throw new ServiceException("FREESWITCH_NODE_NOT_FOUND");
        apply(node, request.getNodeCode(), request.getNodeName(), request.getSipDomain(), request.getWssUrl(), request.getEslHost(), request.getEslPort());
        if (request.getEslPassword() != null && !request.getEslPassword().isBlank()) node.setEslPassword(request.getEslPassword());
        node.setEnabled(request.getEnabled());
        node.setAgentEnabled(request.getAgentEnabled());
        node.setMediaRootPath(request.getMediaRootPath());
        node.setVersion(request.getVersion());
        if (mapper.updateById(node) != 1) throw new ServiceException("FREESWITCH_NODE_UPDATE_CONFLICT");
    }

    @Override
    public void delete(Long id) {
        if (sipAccountMapper.exists(new LambdaQueryWrapper<SipAccount>().eq(SipAccount::getNodeId, id))) {
            throw new ServiceException("FREESWITCH_NODE_IN_USE");
        }
        if (gatewayMapper.exists(new LambdaQueryWrapper<FreeSwitchGateway>().eq(FreeSwitchGateway::getNodeId, id))) {
            throw new ServiceException("FREESWITCH_NODE_IN_USE");
        }
        if (groupMemberMapper.exists(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>().eq(FreeSwitchNodeGroupMember::getNodeId, id))) {
            throw new ServiceException("FREESWITCH_NODE_IN_GROUP");
        }
        if (mapper.deleteById(id) != 1) throw new ServiceException("FREESWITCH_NODE_NOT_FOUND");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resetAgentToken(Long id) {
        FreeSwitchNode node = mapper.selectById(id);
        if (node == null) throw new ServiceException("FREESWITCH_NODE_NOT_FOUND");
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        try {
            node.setAgentTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception exception) {
            throw new ServiceException("MEDIA_AGENT_TOKEN_HASH_FAILED");
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
        if (exists) throw new ServiceException("FREESWITCH_NODE_CODE_ALREADY_EXISTS");
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
