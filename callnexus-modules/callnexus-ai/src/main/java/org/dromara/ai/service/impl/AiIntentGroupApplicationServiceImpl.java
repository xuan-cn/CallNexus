package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiIntent;
import org.dromara.ai.domain.AiIntentGroup;
import org.dromara.ai.domain.request.AiIntentGroupRequest;
import org.dromara.ai.domain.response.AiIntentGroupResponse;
import org.dromara.ai.mapper.AiIntentGroupMapper;
import org.dromara.ai.mapper.AiIntentMapper;
import org.dromara.ai.service.AiIntentGroupApplicationService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AiIntentGroupApplicationServiceImpl implements AiIntentGroupApplicationService {
    private final AiIntentGroupMapper groupMapper;
    private final AiIntentMapper intentMapper;

    @Override
    public List<AiIntentGroupResponse> groups() {
        return groupMapper.selectList(new LambdaQueryWrapper<AiIntentGroup>()
                .orderByAsc(AiIntentGroup::getSortOrder).orderByDesc(AiIntentGroup::getCreateTime))
            .stream().map(this::response).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AiIntentGroupRequest request) {
        ensureCode(request.getGroupCode(), null);
        AiIntentGroup value = new AiIntentGroup();
        fill(value, request);
        groupMapper.insert(value);
        return value.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AiIntentGroupRequest request) {
        AiIntentGroup value = require(id);
        ensureCode(request.getGroupCode(), id);
        fill(value, request);
        groupMapper.updateById(value);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id);
        if (intentMapper.selectCount(new LambdaQueryWrapper<AiIntent>().eq(AiIntent::getGroupId, id)) > 0) {
            throw new ServiceException("该分类下仍有意图，请先移动意图后再删除");
        }
        groupMapper.deleteById(id);
    }

    private void fill(AiIntentGroup value, AiIntentGroupRequest request) {
        value.setGroupCode(request.getGroupCode().trim().toUpperCase(Locale.ROOT));
        value.setGroupName(request.getGroupName().trim());
        value.setDescription(request.getDescription());
        value.setSortOrder(request.getSortOrder() == null ? 100 : request.getSortOrder());
        value.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private AiIntentGroup require(Long id) {
        AiIntentGroup value = groupMapper.selectById(id);
        if (value == null) throw new ServiceException("意图分类不存在");
        return value;
    }

    private void ensureCode(String code, Long excludeId) {
        long count = groupMapper.selectCount(new LambdaQueryWrapper<AiIntentGroup>()
            .eq(AiIntentGroup::getGroupCode, code.trim().toUpperCase(Locale.ROOT))
            .ne(excludeId != null, AiIntentGroup::getId, excludeId));
        if (count > 0) throw new ServiceException("意图分类编码已存在");
    }

    private AiIntentGroupResponse response(AiIntentGroup value) {
        AiIntentGroupResponse response = new AiIntentGroupResponse();
        response.setId(value.getId());
        response.setGroupCode(value.getGroupCode());
        response.setGroupName(value.getGroupName());
        response.setDescription(value.getDescription());
        response.setSortOrder(value.getSortOrder());
        response.setEnabled(value.getEnabled());
        response.setVersion(value.getVersion());
        response.setIntentCount(intentMapper.selectCount(new LambdaQueryWrapper<AiIntent>().eq(AiIntent::getGroupId, value.getId())));
        return response;
    }
}
