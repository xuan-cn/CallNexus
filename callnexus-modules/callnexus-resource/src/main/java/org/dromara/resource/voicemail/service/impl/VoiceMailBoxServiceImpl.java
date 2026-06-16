package org.dromara.resource.voicemail.service.impl;

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
import org.dromara.resource.media.domain.MediaAsset;
import org.dromara.resource.media.domain.MediaNodeSync;
import org.dromara.resource.media.mapper.MediaAssetMapper;
import org.dromara.resource.media.mapper.MediaNodeSyncMapper;
import org.dromara.resource.voicemail.domain.VoiceMailBox;
import org.dromara.resource.voicemail.domain.request.VoiceMailBoxPageQuery;
import org.dromara.resource.voicemail.domain.request.VoiceMailBoxRequest;
import org.dromara.resource.voicemail.domain.response.VoiceMailBoxResponse;
import org.dromara.resource.voicemail.domain.response.VoiceMailDialplanResponse;
import org.dromara.resource.voicemail.mapper.VoiceMailBoxMapper;
import org.dromara.resource.voicemail.service.VoiceMailBoxApplicationService;
import org.dromara.resource.voicemail.service.VoiceMailBoxQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceMailBoxServiceImpl implements VoiceMailBoxApplicationService, VoiceMailBoxQueryService {

    private final VoiceMailBoxMapper mapper;
    private final MediaAssetMapper mediaMapper;
    private final MediaNodeSyncMapper syncMapper;

    @Override
    public TableDataInfo<VoiceMailBoxResponse> page(VoiceMailBoxPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<VoiceMailBox> wrapper = new LambdaQueryWrapper<VoiceMailBox>()
            .like(StringUtils.isNotBlank(query.getBoxCode()), VoiceMailBox::getBoxCode, query.getBoxCode())
            .like(StringUtils.isNotBlank(query.getBoxName()), VoiceMailBox::getBoxName, query.getBoxName())
            .eq(query.getEnabled() != null, VoiceMailBox::getEnabled, query.getEnabled())
            .orderByAsc(VoiceMailBox::getBoxCode);
        Page<VoiceMailBox> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public VoiceMailBoxResponse get(Long id) {
        return toResponse(requireBox(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(VoiceMailBoxRequest request) {
        validate(request, null);
        VoiceMailBox box = new VoiceMailBox();
        apply(box, request);
        mapper.insert(box);
        log.info("新增语音留言箱，boxId={}，boxCode={}，promptMediaId={}", box.getId(), box.getBoxCode(), box.getPromptMediaId());
        return box.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, VoiceMailBoxRequest request) {
        validate(request, id);
        VoiceMailBox box = requireBox(id);
        apply(box, request);
        box.setVersion(request.getVersion());
        if (mapper.updateById(box) != 1) {
            throw new ServiceException("语音留言箱已被其他用户修改，请刷新后重试");
        }
        log.info("更新语音留言箱，boxId={}，boxCode={}，enabled={}", box.getId(), box.getBoxCode(), box.getEnabled());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        VoiceMailBox box = requireBox(id);
        if (mapper.deleteById(id) != 1) {
            throw new ServiceException("语音留言箱不存在");
        }
        log.info("删除语音留言箱，boxId={}，boxCode={}", id, box.getBoxCode());
    }

    @Override
    public boolean isAvailable(String tenantId, Long boxId, Long nodeId) {
        return findAvailableBox(tenantId, boxId, nodeId) != null;
    }

    @Override
    public VoiceMailDialplanResponse findAvailableBox(String tenantId, Long boxId, Long nodeId) {
        if (boxId == null || nodeId == null) {
            return null;
        }
        return TenantHelper.dynamic(tenantId, () -> {
            VoiceMailBox box = mapper.selectById(boxId);
            if (box == null || !Boolean.TRUE.equals(box.getEnabled())) {
                return null;
            }
            MediaAsset prompt = mediaMapper.selectById(box.getPromptMediaId());
            if (!isPublishedPrompt(prompt)) {
                return null;
            }
            MediaNodeSync sync = syncMapper.selectOne(new LambdaQueryWrapper<MediaNodeSync>()
                .eq(MediaNodeSync::getMediaId, prompt.getId())
                .eq(MediaNodeSync::getVersionId, prompt.getLatestVersionId())
                .eq(MediaNodeSync::getNodeId, nodeId)
                .eq(MediaNodeSync::getStatus, "SUCCESS")
                .orderByDesc(MediaNodeSync::getSyncedAt)
                .last("limit 1"));
            if (sync == null || StringUtils.isBlank(sync.getTargetPath())) {
                return null;
            }
            VoiceMailDialplanResponse response = new VoiceMailDialplanResponse();
            response.setId(box.getId());
            response.setBoxCode(box.getBoxCode());
            response.setBoxName(box.getBoxName());
            response.setPromptMediaId(box.getPromptMediaId());
            response.setPromptPath(sync.getTargetPath());
            response.setMaxSeconds(box.getMaxSeconds());
            response.setSilenceThreshold(box.getSilenceThreshold());
            response.setSilenceHits(box.getSilenceHits());
            return response;
        });
    }

    private void validate(VoiceMailBoxRequest request, Long excludedId) {
        boolean duplicate = mapper.exists(new LambdaQueryWrapper<VoiceMailBox>()
            .eq(VoiceMailBox::getTenantId, LoginHelper.getTenantId())
            .eq(VoiceMailBox::getBoxCode, request.getBoxCode())
            .ne(excludedId != null, VoiceMailBox::getId, excludedId));
        if (duplicate) {
            throw new ServiceException("语音留言箱编码已存在");
        }
        MediaAsset prompt = mediaMapper.selectById(request.getPromptMediaId());
        if (!isPublishedPrompt(prompt)) {
            throw new ServiceException("留言提示音必须是已发布的 IVR 提示音");
        }
    }

    private boolean isPublishedPrompt(MediaAsset prompt) {
        return prompt != null
            && "IVR_PROMPT".equals(prompt.getCategory())
            && Boolean.TRUE.equals(prompt.getEnabled())
            && prompt.getLatestVersionId() != null
            && "PUBLISHED".equals(prompt.getPublishStatus());
    }

    private VoiceMailBox requireBox(Long id) {
        VoiceMailBox box = mapper.selectById(id);
        if (box == null) {
            throw new ServiceException("语音留言箱不存在");
        }
        return box;
    }

    private void apply(VoiceMailBox box, VoiceMailBoxRequest request) {
        box.setBoxCode(request.getBoxCode());
        box.setBoxName(request.getBoxName());
        box.setPromptMediaId(request.getPromptMediaId());
        box.setMaxSeconds(request.getMaxSeconds());
        box.setSilenceThreshold(request.getSilenceThreshold());
        box.setSilenceHits(request.getSilenceHits());
        box.setEnabled(request.getEnabled());
        box.setRemark(request.getRemark());
    }

    private VoiceMailBoxResponse toResponse(VoiceMailBox box) {
        VoiceMailBoxResponse response = new VoiceMailBoxResponse();
        response.setId(box.getId());
        response.setBoxCode(box.getBoxCode());
        response.setBoxName(box.getBoxName());
        response.setPromptMediaId(box.getPromptMediaId());
        MediaAsset prompt = mediaMapper.selectById(box.getPromptMediaId());
        if (prompt != null) {
            response.setPromptMediaName(prompt.getAssetName());
        }
        response.setMaxSeconds(box.getMaxSeconds());
        response.setSilenceThreshold(box.getSilenceThreshold());
        response.setSilenceHits(box.getSilenceHits());
        response.setEnabled(box.getEnabled());
        response.setRemark(box.getRemark());
        response.setVersion(box.getVersion());
        response.setCreateTime(box.getCreateTime());
        return response;
    }
}
