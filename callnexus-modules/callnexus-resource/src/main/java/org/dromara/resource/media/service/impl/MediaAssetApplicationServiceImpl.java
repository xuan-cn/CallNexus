package org.dromara.resource.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.OssService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.media.config.MediaStorageProperties;
import org.dromara.resource.media.domain.MediaAsset;
import org.dromara.resource.media.domain.MediaAssetCategory;
import org.dromara.resource.media.domain.MediaAssetVersion;
import org.dromara.resource.media.domain.MediaNodeSync;
import org.dromara.resource.media.domain.request.MediaAssetPageQuery;
import org.dromara.resource.media.domain.request.UpdateMediaAssetRequest;
import org.dromara.resource.media.domain.response.MediaAssetResponse;
import org.dromara.resource.media.mapper.MediaAssetMapper;
import org.dromara.resource.media.mapper.MediaAssetVersionMapper;
import org.dromara.resource.media.mapper.MediaNodeSyncMapper;
import org.dromara.resource.media.service.MediaAssetApplicationService;
import org.dromara.system.domain.vo.SysOssVo;
import org.dromara.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaAssetApplicationServiceImpl implements MediaAssetApplicationService {
    private static final Duration PLAYBACK_URL_TTL = Duration.ofHours(2);

    private final MediaAssetMapper mapper;
    private final MediaAssetVersionMapper versionMapper;
    private final MediaNodeSyncMapper syncMapper;
    private final ISysOssService sysOssService;
    private final OssService ossService;
    private final MediaStorageProperties storageProperties;

    @Override
    public TableDataInfo<MediaAssetResponse> page(MediaAssetPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<MediaAsset>()
            .notIn(MediaAsset::getCategory, MediaAssetCategory.CALL_RECORDING.name(), MediaAssetCategory.VOICEMAIL_RECORDING.name())
            .like(StringUtils.isNotBlank(query.getAssetName()), MediaAsset::getAssetName, query.getAssetName())
            .eq(StringUtils.isNotBlank(query.getCategory()), MediaAsset::getCategory, query.getCategory())
            .eq(StringUtils.isNotBlank(query.getSourceType()), MediaAsset::getSourceType, query.getSourceType())
            .eq(query.getEnabled() != null, MediaAsset::getEnabled, query.getEnabled())
            .orderByDesc(MediaAsset::getCreateTime);
        Page<MediaAsset> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(asset -> toResponse(asset, false)).toList(), page.getTotal());
    }

    @Override
    public MediaAssetResponse get(Long id) {
        return toResponse(requireAsset(id), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long upload(String assetName, MediaAssetCategory category, String languageCode, String remark, Long durationMs, MultipartFile file) {
        if (category == MediaAssetCategory.CALL_RECORDING || category == MediaAssetCategory.VOICEMAIL_RECORDING) {
            throw new ServiceException("录音类媒体不允许手动上传");
        }
        validateAudio(file);
        MediaAsset asset = store(assetName, category, "UPLOAD", languageCode, remark, durationMs, 0, file);
        log.info("上传声音媒体，mediaId={}，category={}，ossId={}，fileName={}",
            asset.getId(), asset.getCategory(), asset.getOssId(), asset.getOriginalFileName());
        return asset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long storeGenerated(String assetName, MediaAssetCategory category, String languageCode, String remark,
                               Long durationMs, String sourceText, String voiceProvider, String voiceName, MultipartFile file) {
        if (category == MediaAssetCategory.CALL_RECORDING || category == MediaAssetCategory.VOICEMAIL_RECORDING) {
            throw new ServiceException("录音类媒体不允许作为系统生成媒体保存");
        }
        validateVoiceMetadata(voiceProvider, voiceName);
        validateAudio(file);
        MediaAsset asset = store(assetName, category, "TTS", languageCode, remark, durationMs, 0, file);
        asset.setSourceText(sourceText);
        asset.setVoiceProvider(voiceProvider);
        asset.setVoiceName(voiceName);
        mapper.updateById(asset);
        log.info("保存 TTS 生成声音媒体，mediaId={}，category={}，provider={}，voice={}，fileName={}",
            asset.getId(), asset.getCategory(), voiceProvider, voiceName, asset.getOriginalFileName());
        return asset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long storeGeneratedVersion(Long mediaId, String assetName, String languageCode, String remark,
                                      Long durationMs, String sourceText, String voiceProvider, String voiceName, MultipartFile file) {
        MediaAsset asset = requireAsset(mediaId);
        MediaAssetCategory category = MediaAssetCategory.valueOf(asset.getCategory());
        if (category == MediaAssetCategory.CALL_RECORDING || category == MediaAssetCategory.VOICEMAIL_RECORDING) {
            throw new ServiceException("录音类媒体不允许作为系统生成媒体保存");
        }
        validateVoiceMetadata(voiceProvider, voiceName);
        validateAudio(file);
        String checksum = checksum(file);
        SysOssVo oss = sysOssService.upload(file, storageProperties.getConfigKey(category));
        populateAudioMetadata(asset, durationMs, file);
        if (asset.getDurationMs() == null) {
            populateWavDuration(asset, file);
        }

        Integer next = versionMapper.selectList(new LambdaQueryWrapper<MediaAssetVersion>()
                .eq(MediaAssetVersion::getMediaId, mediaId)
                .orderByDesc(MediaAssetVersion::getVersionNo)
                .last("limit 1"))
            .stream()
            .findFirst()
            .map(version -> version.getVersionNo() + 1)
            .orElse(1);

        MediaAssetVersion mediaVersion = new MediaAssetVersion();
        mediaVersion.setMediaId(asset.getId());
        mediaVersion.setVersionNo(next);
        mediaVersion.setOssId(oss.getOssId());
        mediaVersion.setOriginalFileName(file.getOriginalFilename());
        mediaVersion.setContentType(file.getContentType());
        mediaVersion.setFileSuffix(oss.getFileSuffix());
        mediaVersion.setFileSize(file.getSize());
        mediaVersion.setDurationMs(asset.getDurationMs());
        mediaVersion.setSampleRate(asset.getSampleRate());
        mediaVersion.setChannels(asset.getChannels());
        mediaVersion.setCodec(asset.getCodec());
        mediaVersion.setChecksum(checksum);
        mediaVersion.setStatus("DRAFT");
        versionMapper.insert(mediaVersion);

        if (StringUtils.isNotBlank(assetName)) {
            asset.setAssetName(assetName);
        }
        asset.setSourceType("TTS");
        asset.setOssId(oss.getOssId());
        asset.setOriginalFileName(file.getOriginalFilename());
        asset.setContentType(file.getContentType());
        asset.setFileSuffix(oss.getFileSuffix());
        asset.setFileSize(file.getSize());
        asset.setLanguageCode(languageCode);
        asset.setRemark(remark);
        asset.setSourceText(sourceText);
        asset.setVoiceProvider(voiceProvider);
        asset.setVoiceName(voiceName);
        asset.setLatestVersionId(mediaVersion.getId());
        asset.setPublishStatus("DRAFT");
        mapper.updateById(asset);
        log.info("追加 TTS 生成声音媒体版本，mediaId={}，versionNo={}，category={}，provider={}，voice={}，fileName={}",
            asset.getId(), next, asset.getCategory(), voiceProvider, voiceName, asset.getOriginalFileName());
        return asset.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAssetResponse storeRecording(String businessCallId, Long durationMs, MultipartFile file) {
        validateAudio(file);
        MediaAsset asset = store("通话录音-" + businessCallId, MediaAssetCategory.CALL_RECORDING, "RECORDING",
            null, businessCallId, durationMs, 1, file);
        return toResponse(asset, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAssetResponse storeVoicemail(String businessCallId, Long durationMs, MultipartFile file) {
        validateAudio(file);
        MediaAsset asset = store("语音留言-" + businessCallId, MediaAssetCategory.VOICEMAIL_RECORDING, "VOICEMAIL",
            null, businessCallId, durationMs, 1, file);
        return toResponse(asset, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UpdateMediaAssetRequest request) {
        MediaAsset asset = requireAsset(id);
        if (!asset.getCategory().equals(request.getCategory())) {
            throw new ServiceException("媒体分类不可修改");
        }
        asset.setAssetName(request.getAssetName());
        asset.setLanguageCode(request.getLanguageCode());
        asset.setRemark(request.getRemark());
        asset.setEnabled(request.getEnabled());
        asset.setVersion(request.getVersion());
        if (mapper.updateById(asset) != 1) throw new ServiceException("声音媒体已被其他用户修改，请刷新后重试");
        log.info("更新声音媒体，mediaId={}，category={}，enabled={}", id, asset.getCategory(), asset.getEnabled());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MediaAsset asset = requireAsset(id);
        if (asset.getReferenceCount() != null && asset.getReferenceCount() > 0) {
            throw new ServiceException("声音媒体正在被使用，无法删除");
        }
        if (asset.getPublishStatus() != null && !List.of("DRAFT", "UNPUBLISHED", "FAILED").contains(asset.getPublishStatus())) {
            throw new ServiceException("声音媒体已发布，无法删除");
        }
        if (mapper.deleteById(id) != 1) throw new ServiceException("声音媒体不存在");
        sysOssService.deleteWithValidByIds(List.of(asset.getOssId()), false);
        log.info("删除声音媒体，mediaId={}，ossId={}", id, asset.getOssId());
    }

    private MediaAsset store(String assetName, MediaAssetCategory category, String sourceType, String languageCode,
                             String remark, Long durationMs, int referenceCount, MultipartFile file) {
        String checksum = checksum(file);
        SysOssVo oss = sysOssService.upload(file, storageProperties.getConfigKey(category));
        MediaAsset asset = new MediaAsset();
        asset.setAssetName(StringUtils.isBlank(assetName) ? file.getOriginalFilename() : assetName);
        asset.setCategory(category.name());
        asset.setSourceType(sourceType);
        asset.setOssId(oss.getOssId());
        asset.setOriginalFileName(file.getOriginalFilename());
        asset.setContentType(file.getContentType());
        asset.setFileSuffix(oss.getFileSuffix());
        asset.setFileSize(file.getSize());
        populateAudioMetadata(asset, durationMs, file);
        if (asset.getDurationMs() == null) {
            populateWavDuration(asset, file);
        }
        asset.setLanguageCode(languageCode);
        asset.setEnabled(true);
        asset.setReferenceCount(referenceCount);
        asset.setTranscriptStatus("NONE");
        asset.setRemark(remark);
        mapper.insert(asset);
        if (category != MediaAssetCategory.CALL_RECORDING && category != MediaAssetCategory.VOICEMAIL_RECORDING) {
            MediaAssetVersion mediaVersion = new MediaAssetVersion();
            mediaVersion.setMediaId(asset.getId());
            mediaVersion.setVersionNo(1);
            mediaVersion.setOssId(asset.getOssId());
            mediaVersion.setOriginalFileName(asset.getOriginalFileName());
            mediaVersion.setContentType(asset.getContentType());
            mediaVersion.setFileSuffix(asset.getFileSuffix());
            mediaVersion.setFileSize(asset.getFileSize());
            mediaVersion.setDurationMs(asset.getDurationMs());
            mediaVersion.setSampleRate(asset.getSampleRate());
            mediaVersion.setChannels(asset.getChannels());
            mediaVersion.setCodec(asset.getCodec());
            mediaVersion.setChecksum(checksum);
            mediaVersion.setStatus("DRAFT");
            versionMapper.insert(mediaVersion);
            asset.setLatestVersionId(mediaVersion.getId());
            asset.setPublishStatus("DRAFT");
            mapper.updateById(asset);
        }
        return asset;
    }

    private void validateVoiceMetadata(String voiceProvider, String voiceName) {
        if (voiceProvider != null && voiceProvider.length() > 64) {
            throw new ServiceException("TTS 语音服务商编码不能超过 64 个字符");
        }
        if (voiceName != null && voiceName.length() > 64) {
            throw new ServiceException("TTS 音色名称不能超过 64 个字符");
        }
    }

    private void validateAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ServiceException("请上传声音文件");
        String contentType = file.getContentType();
        if (StringUtils.isBlank(contentType) || !contentType.startsWith("audio/")) {
            throw new ServiceException("声音文件格式不支持");
        }
    }

    private void populateAudioMetadata(MediaAsset asset, Long durationMs, MultipartFile file) {
        asset.setDurationMs(durationMs);
        try (InputStream inputStream = file.getInputStream();
             AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(inputStream)) {
            AudioFormat format = audioInputStream.getFormat();
            asset.setSampleRate(Math.round(format.getSampleRate()));
            asset.setChannels(format.getChannels());
            asset.setCodec(format.getEncoding().toString());
            if (asset.getDurationMs() == null && audioInputStream.getFrameLength() > 0 && format.getFrameRate() > 0) {
                asset.setDurationMs(Math.round(audioInputStream.getFrameLength() * 1000D / format.getFrameRate()));
            }
        } catch (Exception ex) {
            log.debug("无法从音频文件提取元数据，使用上传参数，fileName={}，error={}",
                file.getOriginalFilename(), ex.getMessage());
        }
    }

    private void populateWavDuration(MediaAsset asset, MultipartFile file) {
        try (InputStream inputStream = file.getInputStream();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            byte[] bytes = outputStream.toByteArray();
            if (bytes.length < 44 || !"RIFF".equals(ascii(bytes, 0, 4)) || !"WAVE".equals(ascii(bytes, 8, 4))) {
                return;
            }
            int offset = 12;
            Integer byteRate = null;
            Integer dataSize = null;
            while (offset + 8 <= bytes.length) {
                String chunkId = ascii(bytes, offset, 4);
                int chunkSize = littleEndianInt(bytes, offset + 4);
                int chunkDataStart = offset + 8;
                if ("fmt ".equals(chunkId) && chunkDataStart + 16 <= bytes.length) {
                    asset.setChannels(littleEndianShort(bytes, chunkDataStart + 2));
                    asset.setSampleRate(littleEndianInt(bytes, chunkDataStart + 4));
                    byteRate = littleEndianInt(bytes, chunkDataStart + 8);
                    asset.setCodec("PCM");
                } else if ("data".equals(chunkId)) {
                    dataSize = chunkSize;
                    break;
                }
                offset = chunkDataStart + chunkSize + (chunkSize % 2);
            }
            if (byteRate != null && byteRate > 0 && dataSize != null && dataSize > 0) {
                asset.setDurationMs(Math.round(dataSize * 1000D / byteRate));
            }
        } catch (Exception ex) {
            log.debug("无法从 WAV 文件头解析时长，fileName={}，error={}", file.getOriginalFilename(), ex.getMessage());
        }
    }

    private String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
            | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16)
            | ((bytes[offset + 3] & 0xff) << 24);
    }

    private String checksum(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) >= 0) digest.update(buffer, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            log.warn("计算声音媒体校验值失败，fileName={}，error={}", file.getOriginalFilename(), exception.getMessage());
            return null;
        }
    }

    private MediaAsset requireAsset(Long id) {
        MediaAsset asset = mapper.selectById(id);
        if (asset == null) throw new ServiceException("声音媒体不存在");
        return asset;
    }

    private MediaAssetResponse toResponse(MediaAsset asset, boolean includePlaybackUrl) {
        MediaAssetResponse response = new MediaAssetResponse();
        response.setId(asset.getId());
        response.setAssetName(asset.getAssetName());
        response.setCategory(asset.getCategory());
        response.setSourceType(asset.getSourceType());
        response.setOssId(asset.getOssId());
        response.setOriginalFileName(asset.getOriginalFileName());
        response.setContentType(asset.getContentType());
        response.setFileSuffix(asset.getFileSuffix());
        response.setFileSize(asset.getFileSize());
        response.setDurationMs(asset.getDurationMs());
        response.setSampleRate(asset.getSampleRate());
        response.setChannels(asset.getChannels());
        response.setCodec(asset.getCodec());
        response.setLanguageCode(asset.getLanguageCode());
        response.setEnabled(asset.getEnabled());
        response.setReferenceCount(asset.getReferenceCount());
        response.setLatestVersionId(asset.getLatestVersionId());
        response.setCurrentPublicationId(asset.getCurrentPublicationId());
        response.setPublishStatus(asset.getPublishStatus());
        if (asset.getLatestVersionId() != null) {
            MediaAssetVersion version = versionMapper.selectById(asset.getLatestVersionId());
            response.setLatestVersionNo(version == null ? null : version.getVersionNo());
        }
        response.setSyncSuccessCount(Math.toIntExact(syncMapper.selectCount(new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getMediaId, asset.getId()).eq(MediaNodeSync::getStatus, "SUCCESS"))));
        response.setSyncFailedCount(Math.toIntExact(syncMapper.selectCount(new LambdaQueryWrapper<MediaNodeSync>()
            .eq(MediaNodeSync::getMediaId, asset.getId()).eq(MediaNodeSync::getStatus, "FAILED"))));
        response.setTranscriptStatus(asset.getTranscriptStatus());
        response.setTranscriptText(asset.getTranscriptText());
        response.setSummaryText(asset.getSummaryText());
        response.setRemark(asset.getRemark());
        if (includePlaybackUrl) {
            response.setPlaybackUrl(ossService.selectUrlById(asset.getOssId(), PLAYBACK_URL_TTL));
        }
        response.setVersion(asset.getVersion());
        response.setCreateTime(asset.getCreateTime());
        return response;
    }
}
