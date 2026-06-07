package org.dromara.resource.sip.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.dromara.resource.sip.domain.SipAccount;
import org.dromara.resource.sip.domain.request.CreateSipAccountRequest;
import org.dromara.resource.sip.domain.request.SipAccountPageQuery;
import org.dromara.resource.sip.domain.request.UpdateSipAccountRequest;
import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.domain.response.SipRegistrationConfigResponse;
import org.dromara.resource.sip.domain.response.SipAccountRealtimeResponse;
import org.dromara.resource.sip.mapper.SipAccountMapper;
import org.dromara.resource.sip.service.SipAccountApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SipAccountApplicationServiceImpl implements SipAccountApplicationService {
    private final SipAccountMapper mapper;
    private final FreeSwitchNodeMapper nodeMapper;

    @Override
    public TableDataInfo<SipAccountResponse> page(SipAccountPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<SipAccount> wrapper = new LambdaQueryWrapper<SipAccount>()
            .like(query.getExtension() != null && !query.getExtension().isBlank(), SipAccount::getExtension, query.getExtension())
            .like(query.getDisplayName() != null && !query.getDisplayName().isBlank(), SipAccount::getDisplayName, query.getDisplayName())
            .eq(query.getEnabled() != null, SipAccount::getEnabled, query.getEnabled())
            .orderByAsc(SipAccount::getExtension);
        Page<SipAccount> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public SipAccountResponse get(Long id) {
        SipAccount account = mapper.selectById(id);
        if (account == null) throw new ServiceException("SIP_ACCOUNT_NOT_FOUND");
        return toResponse(account);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateSipAccountRequest request) {
        ensureExtensionUnique(request.getExtension(), null);
        FreeSwitchNode node = requireEnabledNode(request.getNodeId());
        SipAccount account = new SipAccount();
        account.setNodeId(node.getId());
        account.setExtension(request.getExtension());
        account.setDisplayName(request.getDisplayName());
        account.setDomain(node.getSipDomain());
        account.setAuthPassword(request.getPassword());
        account.setEnabled(true);
        mapper.insert(account);
        return account.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdateSipAccountRequest request) {
        ensureExtensionUnique(request.getExtension(), id);
        SipAccount account = mapper.selectById(id);
        if (account == null) throw new ServiceException("SIP_ACCOUNT_NOT_FOUND");
        FreeSwitchNode node = requireEnabledNode(request.getNodeId());
        account.setNodeId(node.getId());
        account.setExtension(request.getExtension());
        account.setDisplayName(request.getDisplayName());
        account.setDomain(node.getSipDomain());
        account.setEnabled(request.getEnabled());
        account.setVersion(request.getVersion());
        if (request.getPassword() != null && !request.getPassword().isBlank()) account.setAuthPassword(request.getPassword());
        if (mapper.updateById(account) != 1) throw new ServiceException("SIP_ACCOUNT_UPDATE_CONFLICT");
    }

    @Override
    public void delete(Long id) {
        if (mapper.deleteById(id) != 1) throw new ServiceException("SIP_ACCOUNT_NOT_FOUND");
    }

    @Override
    public boolean existsEnabled(Long sipAccountId) {
        return mapper.exists(new LambdaQueryWrapper<SipAccount>().eq(SipAccount::getId, sipAccountId).eq(SipAccount::getEnabled, true));
    }

    @Override
    public SipRegistrationConfigResponse getRegistrationConfig(Long id) {
        SipAccount account = requireEnabledAccount(id);
        FreeSwitchNode node = requireEnabledNode(account.getNodeId());
        SipRegistrationConfigResponse response = new SipRegistrationConfigResponse();
        response.setSipAccountId(account.getId());
        response.setNodeId(node.getId());
        response.setExtension(account.getExtension());
        response.setSipDomain(node.getSipDomain());
        response.setWssUrl(node.getWssUrl());
        return response;
    }

    @Override
    public SipAccountRealtimeResponse findEnabledByNodeAndExtension(Long nodeId, String extension) {
        return TenantHelper.ignore(() -> {
            SipAccount account = mapper.selectOne(new LambdaQueryWrapper<SipAccount>()
                .eq(SipAccount::getNodeId, nodeId)
                .eq(SipAccount::getExtension, extension)
                .eq(SipAccount::getEnabled, true));
            if (account == null) return null;
            SipAccountRealtimeResponse response = new SipAccountRealtimeResponse();
            response.setSipAccountId(account.getId());
            response.setTenantId(account.getTenantId());
            response.setExtension(account.getExtension());
            return response;
        });
    }

    private void ensureExtensionUnique(String extension, Long excludedId) {
        boolean exists = mapper.exists(new LambdaQueryWrapper<SipAccount>()
            .eq(SipAccount::getTenantId, LoginHelper.getTenantId())
            .eq(SipAccount::getExtension, extension)
            .ne(excludedId != null, SipAccount::getId, excludedId));
        if (exists) throw new ServiceException("SIP_ACCOUNT_EXTENSION_ALREADY_EXISTS");
    }

    private SipAccountResponse toResponse(SipAccount account) {
        SipAccountResponse response = new SipAccountResponse();
        response.setId(account.getId());
        response.setNodeId(account.getNodeId());
        if (account.getNodeId() != null) {
            FreeSwitchNode node = nodeMapper.selectById(account.getNodeId());
            if (node != null) response.setNodeName(node.getNodeName());
        }
        response.setExtension(account.getExtension());
        response.setDisplayName(account.getDisplayName());
        response.setDomain(account.getDomain());
        response.setEnabled(account.getEnabled());
        response.setVersion(account.getVersion());
        response.setCreateTime(account.getCreateTime());
        return response;
    }

    private FreeSwitchNode requireEnabledNode(Long nodeId) {
        if (nodeId == null) throw new ServiceException("SIP_ACCOUNT_NODE_NOT_BOUND");
        FreeSwitchNode node = nodeMapper.selectById(nodeId);
        if (node == null || !Boolean.TRUE.equals(node.getEnabled())) throw new ServiceException("FREESWITCH_NODE_NOT_FOUND_OR_DISABLED");
        return node;
    }

    private SipAccount requireEnabledAccount(Long id) {
        SipAccount account = mapper.selectById(id);
        if (account == null || !Boolean.TRUE.equals(account.getEnabled())) throw new ServiceException("SIP_ACCOUNT_NOT_FOUND_OR_DISABLED");
        return account;
    }
}
