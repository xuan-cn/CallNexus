package org.dromara.ivr.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.media.domain.MediaAsset;
import org.dromara.resource.media.domain.MediaNodeSync;
import org.dromara.resource.media.mapper.MediaAssetMapper;
import org.dromara.resource.media.mapper.MediaNodeSyncMapper;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class IvrMediaPathResolver {

    private final MediaAssetMapper mediaMapper;
    private final MediaNodeSyncMapper syncMapper;
    private final FreeSwitchNodeGroupMemberMapper memberMapper;

    public void validatePublishedForGroup(Long mediaId, Long nodeGroupId) {
        MediaAsset media = requirePublishedMedia(mediaId);
        List<Long> nodeIds = memberMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
            .eq(FreeSwitchNodeGroupMember::getGroupId, nodeGroupId)).stream()
            .map(FreeSwitchNodeGroupMember::getNodeId)
            .toList();
        for (Long nodeId : nodeIds) {
            if (!syncMapper.exists(successSync(mediaId, media.getLatestVersionId(), nodeId))) {
                throw new ServiceException("IVR_MEDIA_NOT_SYNCED_TO_ALL_NODES");
            }
        }
    }

    public String resolveTargetPath(Long mediaId, Long nodeId) {
        MediaAsset media = requirePublishedMedia(mediaId);
        MediaNodeSync sync = syncMapper.selectOne(successSync(mediaId, media.getLatestVersionId(), nodeId)
            .orderByDesc(MediaNodeSync::getSyncedAt)
            .last("limit 1"));
        if (sync == null || sync.getTargetPath() == null || sync.getTargetPath().isBlank()) {
            throw new IllegalStateException("IVR媒体尚未同步到目标节点");
        }
        return sync.getTargetPath();
    }

    private MediaAsset requirePublishedMedia(Long mediaId) {
        MediaAsset media = mediaId == null ? null : mediaMapper.selectById(mediaId);
        if (media == null || !"IVR_PROMPT".equals(media.getCategory()) || media.getLatestVersionId() == null
            || !"PUBLISHED".equals(media.getPublishStatus())) {
            throw new ServiceException("IVR_MEDIA_NOT_PUBLISHED");
        }
        return media;
    }

    private LambdaQueryWrapper<MediaNodeSync> successSync(Long mediaId, Long versionId, Long nodeId) {
        return new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getMediaId, mediaId)
            .eq(MediaNodeSync::getVersionId, versionId)
            .eq(MediaNodeSync::getNodeId, nodeId)
            .eq(MediaNodeSync::getStatus, "SUCCESS");
    }
}
