package org.dromara.resource.media.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_media_asset")
public class MediaAsset extends TenantEntity {
    @TableId
    private Long id;
    private String assetName;
    private String category;
    private String sourceType;
    private Long ossId;
    private String originalFileName;
    private String contentType;
    private String fileSuffix;
    private Long fileSize;
    private Long durationMs;
    private Integer sampleRate;
    private Integer channels;
    private String codec;
    private String languageCode;
    private Boolean enabled;
    private Integer referenceCount;
    private Long latestVersionId;
    private Long currentPublicationId;
    private String publishStatus;
    private String transcriptStatus;
    private String transcriptText;
    private Long transcriptOssId;
    private String summaryText;
    private String keywordsJson;
    private String sentimentJson;
    private String aiMetadataJson;
    private String voiceProvider;
    private String voiceModel;
    private String voiceName;
    private String sourceText;
    private String remark;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
