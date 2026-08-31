package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_ticket_prompt_version")
public class AiTicketPromptVersion extends TenantEntity {
    @TableId private Long id;
    private Long policyId;
    private Integer versionNo;
    private String versionName;
    private String promptContent;
    private String protocolVersion;
    private String promptHash;
    private String status;
    private Long publishedBy;
    private Date publishedAt;
}
