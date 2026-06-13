package org.dromara.resource.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.media.domain.MediaAsset;
import org.dromara.resource.media.domain.MediaAssetVersion;
import org.dromara.resource.media.domain.MediaNodeSync;
import org.dromara.resource.media.domain.MediaPublication;
import org.dromara.resource.media.domain.request.AgentResultRequest;
import org.dromara.resource.media.domain.response.AgentTaskResponse;
import org.dromara.resource.media.domain.response.MediaSyncResponse;
import org.dromara.resource.media.domain.response.MediaVersionResponse;
import org.dromara.resource.media.mapper.MediaAssetMapper;
import org.dromara.resource.media.mapper.MediaAssetVersionMapper;
import org.dromara.resource.media.mapper.MediaNodeSyncMapper;
import org.dromara.resource.media.mapper.MediaPublicationMapper;
import org.dromara.resource.media.service.MediaPublicationService;
import org.dromara.resource.media.config.MediaStorageProperties;
import org.dromara.resource.media.domain.MediaAssetCategory;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroup;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMapper;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.dromara.system.domain.vo.SysOssVo;
import org.dromara.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaPublicationServiceImpl implements MediaPublicationService {
    private final MediaAssetMapper assetMapper;
    private final MediaAssetVersionMapper versionMapper;
    private final MediaPublicationMapper publicationMapper;
    private final MediaNodeSyncMapper syncMapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final FreeSwitchNodeGroupMapper groupMapper;
    private final FreeSwitchNodeGroupMemberMapper memberMapper;
    private final ISysOssService ossService;
    private final MediaStorageProperties storageProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long uploadVersion(Long mediaId, Long durationMs, MultipartFile file) {
        MediaAsset asset = requirePublishableAsset(mediaId);
        if (file == null || file.isEmpty() || file.getContentType() == null || !file.getContentType().startsWith("audio/")) {
            throw new ServiceException("MEDIA_FILE_TYPE_INVALID");
        }
        String checksum = checksum(file);
        SysOssVo oss = ossService.upload(file, storageProperties.getConfigKey(MediaAssetCategory.valueOf(asset.getCategory())));
        Integer next = versionMapper.selectList(new LambdaQueryWrapper<MediaAssetVersion>().eq(MediaAssetVersion::getMediaId, mediaId)
            .orderByDesc(MediaAssetVersion::getVersionNo).last("limit 1")).stream().findFirst().map(v -> v.getVersionNo() + 1).orElse(1);
        MediaAssetVersion version = new MediaAssetVersion();
        version.setMediaId(mediaId);
        version.setVersionNo(next);
        version.setOssId(oss.getOssId());
        version.setOriginalFileName(file.getOriginalFilename());
        version.setContentType(file.getContentType());
        version.setFileSuffix(oss.getFileSuffix());
        version.setFileSize(file.getSize());
        version.setDurationMs(durationMs);
        version.setChecksum(checksum);
        version.setStatus("DRAFT");
        versionMapper.insert(version);
        asset.setLatestVersionId(version.getId());
        asset.setOssId(oss.getOssId());
        asset.setOriginalFileName(file.getOriginalFilename());
        asset.setContentType(file.getContentType());
        asset.setFileSuffix(oss.getFileSuffix());
        asset.setFileSize(file.getSize());
        asset.setDurationMs(durationMs);
        asset.setPublishStatus("DRAFT");
        assetMapper.updateById(asset);
        return version.getId();
    }

    @Override
    public List<MediaVersionResponse> versions(Long mediaId) {
        requirePublishableAsset(mediaId);
        return versionMapper.selectList(new LambdaQueryWrapper<MediaAssetVersion>().eq(MediaAssetVersion::getMediaId, mediaId)
            .orderByDesc(MediaAssetVersion::getVersionNo)).stream().map(this::versionResponse).toList();
    }

    @Override
    public List<Long> publishedGroupIds(Long mediaId) {
        requirePublishableAsset(mediaId);
        return publicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>().eq(MediaPublication::getMediaId, mediaId)
            .ne(MediaPublication::getStatus, "UNPUBLISHED").orderByDesc(MediaPublication::getCreateTime))
            .stream().map(MediaPublication::getNodeGroupId).distinct().toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long mediaId, List<Long> groupIds) {
        MediaAsset asset = requirePublishableAsset(mediaId);
        MediaAssetVersion version = versionMapper.selectById(asset.getLatestVersionId());
        if (version == null) throw new ServiceException("MEDIA_VERSION_NOT_FOUND");
        List<MediaPublication> previous = publicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>()
            .eq(MediaPublication::getMediaId, mediaId).ne(MediaPublication::getStatus, "UNPUBLISHED"));
        for (MediaPublication old : previous) {
            old.setStatus("UNPUBLISHED");
            old.setUnpublishedAt(LocalDateTime.now());
            publicationMapper.updateById(old);
        }
        for (Long groupId : groupIds.stream().distinct().toList()) {
            FreeSwitchNodeGroup group = groupMapper.selectById(groupId);
            if (group == null || !Boolean.TRUE.equals(group.getEnabled())) throw new ServiceException("NODE_GROUP_NOT_FOUND_OR_DISABLED");
            MediaPublication publication = new MediaPublication();
            publication.setMediaId(mediaId);
            publication.setVersionId(version.getId());
            publication.setNodeGroupId(groupId);
            publication.setStatus("PUBLISHING");
            publication.setSuccessCount(0);
            publication.setFailedCount(0);
            publication.setTargetCount(0);
            publicationMapper.insert(publication);
            List<Long> nodeIds = memberMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                .eq(FreeSwitchNodeGroupMember::getGroupId, groupId)).stream().map(FreeSwitchNodeGroupMember::getNodeId).toList();
            createTasks(publication, asset, version, nodeIds);
            asset.setCurrentPublicationId(publication.getId());
        }
        version.setStatus("PUBLISHED");
        versionMapper.updateById(version);
        asset.setPublishStatus("PUBLISHING");
        assetMapper.updateById(asset);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unpublish(Long mediaId) {
        MediaAsset asset = requirePublishableAsset(mediaId);
        List<MediaPublication> publications = publicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>()
            .eq(MediaPublication::getMediaId, mediaId).ne(MediaPublication::getStatus, "UNPUBLISHED"));
        for (MediaPublication publication : publications) {
            publication.setStatus("UNPUBLISHED");
            publication.setUnpublishedAt(LocalDateTime.now());
            publicationMapper.updateById(publication);
        }
        asset.setPublishStatus("UNPUBLISHED");
        asset.setCurrentPublicationId(null);
        assetMapper.updateById(asset);
    }

    @Override
    public List<MediaSyncResponse> syncs(Long mediaId) {
        requirePublishableAsset(mediaId);
        return syncMapper.selectList(new LambdaQueryWrapper<MediaNodeSync>().eq(MediaNodeSync::getMediaId, mediaId)
            .orderByDesc(MediaNodeSync::getCreateTime)).stream().map(this::syncResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retryFailed(Long mediaId) {
        requirePublishableAsset(mediaId);
        List<MediaNodeSync> tasks = syncMapper.selectList(new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getMediaId, mediaId).eq(MediaNodeSync::getStatus, "FAILED"));
        for (MediaNodeSync task : tasks) {
            task.setStatus("PENDING");
            task.setRetryCount(0);
            task.setNextRetryAt(null);
            task.setFailureReason(null);
            task.setLeaseToken(null);
            task.setLeaseExpiresAt(null);
            syncMapper.updateById(task);
        }
        tasks.stream().map(MediaNodeSync::getPublicationId).distinct().forEach(this::refreshPublication);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncNewGroupMembers(Long groupId, List<Long> nodeIds) {
        List<MediaPublication> publications = publicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>()
            .eq(MediaPublication::getNodeGroupId, groupId).in(MediaPublication::getStatus, List.of("PUBLISHING", "PARTIAL", "PUBLISHED")));
        for (MediaPublication publication : publications) {
            MediaAsset asset = assetMapper.selectById(publication.getMediaId());
            MediaAssetVersion version = versionMapper.selectById(publication.getVersionId());
            createTasks(publication, asset, version, nodeIds);
        }
    }

    @Override
    public void heartbeat(String nodeCode, String token, String agentVersion) {
        FreeSwitchNode node = authenticate(nodeCode, token);
        TenantHelper.dynamic(node.getTenantId(), () -> {
            node.setAgentLastHeartbeat(LocalDateTime.now());
            node.setAgentVersion(agentVersion);
            nodeMapper.updateById(node);
        });
    }

    @Override
    public AgentTaskResponse claim(String nodeCode, String token) {
        FreeSwitchNode node = authenticate(nodeCode, token);
        return TenantHelper.dynamic(node.getTenantId(), () -> claimForNode(node));
    }

    @Override
    public void downloadSource(Long taskId, String nodeCode, String token, String leaseToken, HttpServletResponse response) throws IOException {
        FreeSwitchNode node = authenticate(nodeCode, token);
        TenantHelper.dynamic(node.getTenantId(), () -> {
            MediaNodeSync task = requireLeasedTask(taskId, node.getId(), leaseToken);
            MediaAssetVersion version = versionMapper.selectById(task.getVersionId());
            SysOssVo oss = ossService.getById(version.getOssId());
            if (oss == null) throw new ServiceException("MEDIA_SOURCE_NOT_FOUND");
            response.setContentType(version.getContentType() == null ? "application/octet-stream" : version.getContentType());
            response.setHeader("Content-Disposition", "attachment; filename=\"source" + safeSuffix(version.getFileSuffix()) + "\"");
            try {
                OssFactory.instance(oss.getService()).download(oss.getFileName(), response.getOutputStream(), response::setContentLengthLong);
            } catch (IOException exception) {
                throw new ServiceException("MEDIA_SOURCE_DOWNLOAD_FAILED");
            }
        });
    }

    @Override
    public void report(Long taskId, String nodeCode, String token, AgentResultRequest request) {
        FreeSwitchNode node = authenticate(nodeCode, token);
        TenantHelper.dynamic(node.getTenantId(), () -> reportForNode(taskId, node.getId(), request));
    }

    private AgentTaskResponse claimForNode(FreeSwitchNode node) {
        LocalDateTime now = LocalDateTime.now();
        MediaNodeSync task = syncMapper.selectOne(new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getNodeId, node.getId())
            .and(w -> w.eq(MediaNodeSync::getStatus, "PENDING")
                .or(x -> x.eq(MediaNodeSync::getStatus, "FAILED").lt(MediaNodeSync::getRetryCount, 3)
                    .le(MediaNodeSync::getNextRetryAt, now))
                .or(x -> x.eq(MediaNodeSync::getStatus, "PROCESSING").lt(MediaNodeSync::getLeaseExpiresAt, now)))
            .orderByAsc(MediaNodeSync::getCreateTime).last("limit 1"));
        if (task == null) return null;
        task.setStatus("PROCESSING");
        task.setLeaseToken(UUID.randomUUID().toString().replace("-", ""));
        task.setLeaseExpiresAt(now.plusMinutes(10));
        syncMapper.updateById(task);
        MediaAsset asset = assetMapper.selectById(task.getMediaId());
        MediaAssetVersion version = versionMapper.selectById(task.getVersionId());
        AgentTaskResponse response = new AgentTaskResponse();
        response.setTaskId(task.getId());
        response.setLeaseToken(task.getLeaseToken());
        response.setMediaId(task.getMediaId());
        response.setVersionId(task.getVersionId());
        response.setVersionNo(version.getVersionNo());
        response.setCategory(asset.getCategory().toLowerCase().replace('_', '-'));
        response.setTargetPath(task.getTargetPath());
        response.setContentType(version.getContentType());
        response.setOriginalFileName(version.getOriginalFileName());
        return response;
    }

    private void reportForNode(Long taskId, Long nodeId, AgentResultRequest request) {
        MediaNodeSync task = requireLeasedTask(taskId, nodeId, request.getLeaseToken());
        task.setLeaseToken(null);
        task.setLeaseExpiresAt(null);
        if (Boolean.TRUE.equals(request.getSuccess())) {
            task.setStatus("SUCCESS");
            task.setFailureReason(null);
            task.setSyncedAt(LocalDateTime.now());
        } else {
            int retry = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
            task.setStatus("FAILED");
            task.setRetryCount(retry);
            task.setFailureReason(truncate(request.getFailureReason(), 1000));
            task.setNextRetryAt(retry >= 3 ? null : LocalDateTime.now().plusSeconds(retry == 1 ? 30 : retry == 2 ? 120 : 600));
        }
        syncMapper.updateById(task);
        refreshPublication(task.getPublicationId());
    }

    private void createTasks(MediaPublication publication, MediaAsset asset, MediaAssetVersion version, List<Long> nodeIds) {
        for (Long nodeId : nodeIds.stream().distinct().toList()) {
            if (syncMapper.exists(new LambdaQueryWrapper<MediaNodeSync>().eq(MediaNodeSync::getPublicationId, publication.getId())
                .eq(MediaNodeSync::getNodeId, nodeId))) continue;
            FreeSwitchNode node = nodeMapper.selectById(nodeId);
            if (node == null || !Boolean.TRUE.equals(node.getEnabled()) || !Boolean.TRUE.equals(node.getAgentEnabled())) continue;
            MediaNodeSync task = new MediaNodeSync();
            task.setPublicationId(publication.getId());
            task.setMediaId(asset.getId());
            task.setVersionId(version.getId());
            task.setNodeId(nodeId);
            task.setStatus("PENDING");
            task.setTargetPath(mediaPath(node, asset, version));
            task.setRetryCount(0);
            syncMapper.insert(task);
        }
        refreshPublication(publication.getId());
    }

    private void refreshPublication(Long publicationId) {
        MediaPublication publication = publicationMapper.selectById(publicationId);
        if (publication == null || "UNPUBLISHED".equals(publication.getStatus())) return;
        List<MediaNodeSync> tasks = syncMapper.selectList(new LambdaQueryWrapper<MediaNodeSync>().eq(MediaNodeSync::getPublicationId, publicationId));
        int success = (int) tasks.stream().filter(t -> "SUCCESS".equals(t.getStatus())).count();
        int failed = (int) tasks.stream().filter(t -> "FAILED".equals(t.getStatus()) && t.getRetryCount() != null && t.getRetryCount() >= 3).count();
        publication.setTargetCount(tasks.size());
        publication.setSuccessCount(success);
        publication.setFailedCount(failed);
        publication.setStatus(tasks.isEmpty() || failed == tasks.size() ? "FAILED" : success == tasks.size() ? "PUBLISHED" : success > 0 ? "PARTIAL" : "PUBLISHING");
        if ("PUBLISHED".equals(publication.getStatus()) || "PARTIAL".equals(publication.getStatus())) publication.setPublishedAt(LocalDateTime.now());
        publicationMapper.updateById(publication);
        refreshAssetStatus(publication.getMediaId());
    }

    private void refreshAssetStatus(Long mediaId) {
        MediaAsset asset = assetMapper.selectById(mediaId);
        List<MediaPublication> publications = publicationMapper.selectList(new LambdaQueryWrapper<MediaPublication>()
            .eq(MediaPublication::getMediaId, mediaId).eq(MediaPublication::getVersionId, asset.getLatestVersionId())
            .ne(MediaPublication::getStatus, "UNPUBLISHED"));
        if (publications.stream().anyMatch(p -> "PARTIAL".equals(p.getStatus()))) asset.setPublishStatus("PARTIAL");
        else if (!publications.isEmpty() && publications.stream().allMatch(p -> "PUBLISHED".equals(p.getStatus()))) asset.setPublishStatus("PUBLISHED");
        else if (!publications.isEmpty() && publications.stream().allMatch(p -> "FAILED".equals(p.getStatus()))) asset.setPublishStatus("FAILED");
        else asset.setPublishStatus("PUBLISHING");
        assetMapper.updateById(asset);
    }

    private FreeSwitchNode authenticate(String nodeCode, String token) {
        if (nodeCode == null || token == null) throw new ServiceException("MEDIA_AGENT_AUTH_FAILED");
        String hash = sha256(token);
        FreeSwitchNode node = TenantHelper.ignore(() -> nodeMapper.selectOne(new LambdaQueryWrapper<FreeSwitchNode>()
            .eq(FreeSwitchNode::getNodeCode, nodeCode).eq(FreeSwitchNode::getAgentTokenHash, hash)
            .eq(FreeSwitchNode::getAgentEnabled, true).eq(FreeSwitchNode::getEnabled, true).last("limit 1")));
        if (node == null) throw new ServiceException("MEDIA_AGENT_AUTH_FAILED");
        return node;
    }

    private MediaNodeSync requireLeasedTask(Long taskId, Long nodeId, String leaseToken) {
        MediaNodeSync task = syncMapper.selectById(taskId);
        if (task == null || !nodeId.equals(task.getNodeId()) || leaseToken == null || !leaseToken.equals(task.getLeaseToken())
            || task.getLeaseExpiresAt() == null || task.getLeaseExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ServiceException("MEDIA_SYNC_TASK_LEASE_INVALID");
        }
        return task;
    }

    private MediaAsset requirePublishableAsset(Long id) {
        MediaAsset asset = assetMapper.selectById(id);
        if (asset == null) throw new ServiceException("MEDIA_ASSET_NOT_FOUND");
        if ("CALL_RECORDING".equals(asset.getCategory())) throw new ServiceException("CALL_RECORDING_PUBLISH_NOT_ALLOWED");
        return asset;
    }

    private String mediaPath(FreeSwitchNode node, MediaAsset asset, MediaAssetVersion version) {
        String root = node.getMediaRootPath() == null || node.getMediaRootPath().isBlank()
            ? "/var/lib/freeswitch/sounds/callnexus" : node.getMediaRootPath();
        return root + "/" + asset.getTenantId() + "/" + asset.getCategory().toLowerCase().replace('_', '-')
            + "/" + asset.getId() + "/" + version.getVersionNo() + "/audio.wav";
    }

    private MediaVersionResponse versionResponse(MediaAssetVersion version) {
        MediaVersionResponse response = new MediaVersionResponse();
        response.setId(version.getId());
        response.setVersionNo(version.getVersionNo());
        response.setOriginalFileName(version.getOriginalFileName());
        response.setFileSize(version.getFileSize());
        response.setDurationMs(version.getDurationMs());
        response.setStatus(version.getStatus());
        response.setCreateTime(version.getCreateTime());
        return response;
    }

    private MediaSyncResponse syncResponse(MediaNodeSync task) {
        MediaSyncResponse response = new MediaSyncResponse();
        response.setId(task.getId());
        response.setPublicationId(task.getPublicationId());
        response.setNodeId(task.getNodeId());
        FreeSwitchNode node = nodeMapper.selectById(task.getNodeId());
        response.setNodeName(node == null ? "-" : node.getNodeName());
        MediaAssetVersion version = versionMapper.selectById(task.getVersionId());
        response.setVersionNo(version == null ? null : version.getVersionNo());
        response.setStatus(task.getStatus());
        response.setTargetPath(task.getTargetPath());
        response.setRetryCount(task.getRetryCount());
        response.setFailureReason(task.getFailureReason());
        response.setSyncedAt(task.getSyncedAt());
        return response;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ServiceException("MEDIA_AGENT_TOKEN_HASH_FAILED");
        }
    }

    private String checksum(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) >= 0) digest.update(buffer, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            log.warn("计算媒体版本校验值失败，fileName={}，error={}", file.getOriginalFilename(), exception.getMessage());
            return null;
        }
    }

    private String safeSuffix(String suffix) {
        return suffix == null || !suffix.matches("^\\.[A-Za-z0-9]{1,10}$") ? "" : suffix;
    }

    private String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }
}
