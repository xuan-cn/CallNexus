package org.dromara.resource.media.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class MediaAssetResponse {
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
    private Integer latestVersionNo;
    private Long currentPublicationId;
    private String publishStatus;
    private Integer syncSuccessCount;
    private Integer syncFailedCount;
    private String transcriptStatus;
    private String transcriptText;
    private String summaryText;
    private String remark;
    private String playbackUrl;
    private Integer version;
    private Date createTime;
}
