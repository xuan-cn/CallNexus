package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_ticket_policy")
public class AiTicketPolicy extends TenantEntity {
    @TableId private Long id;
    private Long aiAgentId;
    private Boolean enabled;
    private String creationMode;
    private Long ticketTemplateId;
    private String triggerTypesJson;
    private String includeIntentsJson;
    private String excludeIntentsJson;
    private BigDecimal confidenceThreshold;
    private String missingRequiredAction;
    private String duplicatePolicy;
    private Integer duplicateWindowHours;
    private String afterCreateAction;
    private Long customerTemplateId;
    private Long defaultSkillGroupId;
    private String defaultValuesJson;
    private Long activePromptVersionId;
    @Version private Integer version;
}
