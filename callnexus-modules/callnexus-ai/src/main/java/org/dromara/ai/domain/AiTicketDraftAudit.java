package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_ticket_draft_audit")
public class AiTicketDraftAudit extends TenantEntity {
    @TableId private Long id;
    private Long draftId;
    private String actionType;
    private String beforeDataJson;
    private String afterDataJson;
    private String remark;
}
