package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.call.domain.DispatchOperatorExtension;
import org.dromara.call.domain.response.DispatchOperatorExtensionResponse;
import org.dromara.call.mapper.DispatchOperatorExtensionMapper;
import org.dromara.call.service.DispatchOperatorExtensionService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.resource.sip.domain.response.SipAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DispatchOperatorExtensionServiceImpl implements DispatchOperatorExtensionService {
    private final DispatchOperatorExtensionMapper mapper;
    private final SipAccountQueryService sipAccountQueryService;

    @Override
    public DispatchOperatorExtensionResponse current() {
        Long userId = LoginHelper.getUserId();
        DispatchOperatorExtension binding = mapper.selectOne(new LambdaQueryWrapper<DispatchOperatorExtension>()
            .eq(DispatchOperatorExtension::getUserId, userId)
            .last("limit 1"));
        if (binding == null) {
            DispatchOperatorExtensionResponse response = new DispatchOperatorExtensionResponse();
            response.setConfigured(false);
            response.setUserId(userId);
            return response;
        }
        return toResponse(binding, sipAccountQueryService.get(binding.getSipAccountId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DispatchOperatorExtensionResponse bindCurrent(Long sipAccountId) {
        SipAccountResponse account = sipAccountQueryService.get(sipAccountId);
        if (account == null || !Boolean.TRUE.equals(account.getEnabled())) {
            throw new ServiceException("调度分机不存在或已停用");
        }
        Long userId = LoginHelper.getUserId();
        DispatchOperatorExtension occupied = mapper.selectOne(new LambdaQueryWrapper<DispatchOperatorExtension>()
            .eq(DispatchOperatorExtension::getSipAccountId, sipAccountId)
            .ne(DispatchOperatorExtension::getUserId, userId)
            .last("limit 1"));
        if (occupied != null) {
            throw new ServiceException("该分机已绑定其他调度员");
        }
        DispatchOperatorExtension binding = mapper.selectOne(new LambdaQueryWrapper<DispatchOperatorExtension>()
            .eq(DispatchOperatorExtension::getUserId, userId)
            .last("limit 1"));
        if (binding == null) {
            binding = new DispatchOperatorExtension();
            binding.setUserId(userId);
            binding.setSipAccountId(sipAccountId);
            try {
                mapper.insert(binding);
            } catch (DuplicateKeyException exception) {
                throw new ServiceException("调度分机绑定冲突，请刷新后重试");
            }
        } else {
            binding.setSipAccountId(sipAccountId);
            mapper.updateById(binding);
        }
        return toResponse(binding, account);
    }

    @Override
    public DispatchOperatorExtensionResponse requireCurrent() {
        DispatchOperatorExtensionResponse response = current();
        if (!Boolean.TRUE.equals(response.getConfigured()) || response.getNodeId() == null
            || response.getExtension() == null || response.getExtension().isBlank()) {
            throw new ServiceException("当前用户未绑定调度分机，请先在调度台选择本机分机");
        }
        return response;
    }

    private DispatchOperatorExtensionResponse toResponse(DispatchOperatorExtension binding, SipAccountResponse account) {
        DispatchOperatorExtensionResponse response = new DispatchOperatorExtensionResponse();
        response.setUserId(binding.getUserId());
        response.setSipAccountId(binding.getSipAccountId());
        response.setConfigured(account != null && Boolean.TRUE.equals(account.getEnabled()));
        if (account != null) {
            response.setNodeId(account.getNodeId());
            response.setNodeName(account.getNodeName());
            response.setExtension(account.getExtension());
            response.setDisplayName(account.getDisplayName());
            response.setDomain(account.getDomain());
        }
        return response;
    }
}
