package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiGeneratedMedia;
import org.dromara.ai.mapper.AiGeneratedMediaMapper;
import org.dromara.ai.service.AiGeneratedMediaQueryService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.media.domain.MediaAsset;
import org.dromara.resource.media.domain.MediaNodeSync;
import org.dromara.resource.media.domain.MediaPublication;
import org.dromara.resource.media.mapper.MediaAssetMapper;
import org.dromara.resource.media.mapper.MediaNodeSyncMapper;
import org.dromara.resource.media.mapper.MediaPublicationMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiGeneratedMediaQueryServiceImpl implements AiGeneratedMediaQueryService {
    private final AiGeneratedMediaMapper generatedMediaMapper;
    private final MediaAssetMapper mediaAssetMapper;
    private final MediaPublicationMapper publicationMapper;
    private final MediaNodeSyncMapper syncMapper;

    @Override
    public String findSyncedPath(String businessType, Long businessId, Long nodeId) {
        if (StringUtils.isBlank(businessType) || businessId == null || nodeId == null) {
            return null;
        }
        AiGeneratedMedia binding = generatedMediaMapper.selectOne(new LambdaQueryWrapper<AiGeneratedMedia>()
            .eq(AiGeneratedMedia::getBusinessType, businessType)
            .eq(AiGeneratedMedia::getBusinessId, businessId)
            .orderByDesc(AiGeneratedMedia::getGeneratedAt)
            .last("limit 1"));
        if (binding == null || binding.getMediaId() == null) {
            return null;
        }
        SyncedMedia media = findSyncedMedia(binding.getMediaId(), nodeId);
        return media == null ? null : media.path();
    }

    @Override
    public SyncedMedia findSyncedMedia(Long mediaId, Long nodeId) {
        if (mediaId == null || nodeId == null) {
            return null;
        }
        MediaAsset media = mediaAssetMapper.selectById(mediaId);
        if (media == null || !Boolean.TRUE.equals(media.getEnabled()) || media.getLatestVersionId() == null) {
            return null;
        }
        List<Long> publicationIds = publicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>()
                .eq(MediaPublication::getMediaId, mediaId)
                .eq(MediaPublication::getVersionId, media.getLatestVersionId())
                .in(MediaPublication::getStatus, List.of("PUBLISHING", "PARTIAL", "PUBLISHED")))
            .stream().map(MediaPublication::getId).toList();
        if (publicationIds.isEmpty()) {
            return null;
        }
        MediaNodeSync sync = syncMapper.selectOne(new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getMediaId, mediaId)
            .in(MediaNodeSync::getPublicationId, publicationIds)
            .eq(MediaNodeSync::getNodeId, nodeId)
            .eq(MediaNodeSync::getStatus, "SUCCESS")
            .orderByDesc(MediaNodeSync::getSyncedAt)
            .last("limit 1"));
        return sync == null ? null : new SyncedMedia(sync.getTargetPath(), media.getDurationMs(), media.getAssetName());
    }
}
