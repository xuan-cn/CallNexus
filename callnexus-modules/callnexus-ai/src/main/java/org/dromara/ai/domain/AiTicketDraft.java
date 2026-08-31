package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_ticket_draft")
public class AiTicketDraft extends TenantEntity {
    @TableId private Long id;
    private Long policyId;
    private Long aiAgentId;
    private String sourceCallId;
    private Long customerId;
    private String callerNumber;
    private Long ticketTemplateId;
    private Long promptVersionId;
    private String status;
    private BigDecimal confidence;
    private String title;
    private String summary;
    private String formDataJson;
    private String missingFieldsJson;
    private String evidenceJson;
    private String failureReason;
    private Long formalTicketId;
    private Long reviewedBy;
    private Date reviewedAt;
    @Version private Integer version;
    @TableLogic private Boolean deleted;
}
