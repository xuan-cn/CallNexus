package org.dromara.resource.media.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_media_asset_version")
public class MediaAssetVersion extends TenantEntity {
    @TableId private Long id;
    private Long mediaId;
    private Integer versionNo;
    private Long ossId;
    private String originalFileName;
    private String contentType;
    private String fileSuffix;
    private Long fileSize;
    private Long durationMs;
    private Integer sampleRate;
    private Integer channels;
    private String codec;
    private String checksum;
    private String status;
    @TableLogic private Boolean deleted;
}
