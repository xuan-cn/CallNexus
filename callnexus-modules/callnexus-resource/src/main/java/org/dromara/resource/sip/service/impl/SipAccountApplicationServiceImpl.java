package org.dromara.resource.sip.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.sip.domain.SipAccount;
import org.dromara.resource.sip.domain.request.CreateSipAccountRequest;
import org.dromara.resource.sip.domain.request.SipAccountPageQuery;
import org.dromara.resource.sip.domain.request.UpdateSipAccountRequest;
import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.mapper.SipAccountMapper;
import org.dromara.resource.sip.service.SipAccountApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SipAccountApplicationServiceImpl implements SipAccountApplicationService {
    private final SipAccountMapper mapper;

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
        SipAccount account = new SipAccount();
        account.setExtension(request.getExtension());
        account.setDisplayName(request.getDisplayName());
        account.setDomain(request.getDomain());
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
        account.setExtension(request.getExtension());
        account.setDisplayName(request.getDisplayName());
        account.setDomain(request.getDomain());
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
        response.setExtension(account.getExtension());
        response.setDisplayName(account.getDisplayName());
        response.setDomain(account.getDomain());
        response.setEnabled(account.getEnabled());
        response.setVersion(account.getVersion());
        response.setCreateTime(account.getCreateTime());
        return response;
    }
}
