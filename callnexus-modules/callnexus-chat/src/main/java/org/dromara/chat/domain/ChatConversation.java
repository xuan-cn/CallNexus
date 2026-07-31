package org.dromara.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_chat_conversation")
public class ChatConversation extends TenantEntity {
    @TableId
    private Long id;
    private String conversationNo;
    private Long channelId;
    private Long visitorId;
    private String accessTokenHash;
    private String status;
    private Integer priority;
    private Long assignedUserId;
    private String assignedUserName;
    private Long customerId;
    private Long ticketId;
    private LocalDateTime queuedAt;
    private LocalDateTime assignedAt;
    private LocalDateTime closedAt;
    private LocalDateTime lastMessageAt;
    private Integer unreadAgentCount;
    private Integer unreadVisitorCount;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
