package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_agent_assist_suggestion")
public class AiAgentAssistSuggestion extends TenantEntity {
    @TableId
    private Long id;
    private Long sessionId;
    private Long transcriptSegmentId;
    private String customerText;
    private String suggestedReply;
    private String sourceType;
    private String status;
    private String failureReason;
    private Long processingMs;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
