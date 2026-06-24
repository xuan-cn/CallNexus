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
            .eq(AiGeneratedMedia::getGenerationStatus, "SUCCESS")
            .orderByDesc(AiGeneratedMedia::getGeneratedAt)
            .last("limit 1"));
        if (binding == null || binding.getMediaId() == null) {
            return null;
        }
        return syncedPath(binding.getMediaId(), nodeId);
    }

    private String syncedPath(Long mediaId, Long nodeId) {
        MediaAsset media = mediaAssetMapper.selectById(mediaId);
        if (media == null || media.getLatestVersionId() == null) {
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
        return sync == null ? null : sync.getTargetPath();
    }
}
