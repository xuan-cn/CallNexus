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
@TableName("cc_media_node_sync")
public class MediaNodeSync extends TenantEntity {
    @TableId private Long id;
    private Long publicationId;
    private Long mediaId;
    private Long versionId;
    private Long nodeId;
    private String status;
    private String targetPath;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private String failureReason;
    private LocalDateTime syncedAt;
    @TableLogic private Boolean deleted;
}
