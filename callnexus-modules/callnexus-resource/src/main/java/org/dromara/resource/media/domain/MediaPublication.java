package org.dromara.resource.media.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_media_publication")
public class MediaPublication extends TenantEntity {
    @TableId private Long id;
    private Long mediaId;
    private Long versionId;
    private Long nodeGroupId;
    private String status;
    private Integer successCount;
    private Integer failedCount;
    private Integer targetCount;
    private LocalDateTime publishedAt;
    private LocalDateTime unpublishedAt;
    @TableLogic private Boolean deleted;
}
