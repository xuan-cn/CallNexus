package org.dromara.call.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_dispatch_call_task")
public class DispatchCallTask extends TenantEntity {
    @TableId
    private Long id;
    private String businessCallId;
    private Long nodeId;
    private Long operatorUserId;
    private Long operatorSipAccountId;
    private String operatorExtension;
    private String operatorLegUuid;
    private String conferenceName;
    private Long mediaAssetId;
    private String mediaName;
    private String mediaPath;
    private Boolean intercomTalking;
    private String taskType;
    private String taskState;
    private Integer totalCount;
    private Integer answeredCount;
    private Integer failedCount;
    private Integer cancelledCount;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    @Version
    private Integer version;
}
