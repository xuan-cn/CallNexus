package org.dromara.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_chat_message")
public class ChatMessage extends TenantEntity {
    @TableId
    private Long id;
    private Long conversationId;
    private String senderType;
    private Long senderId;
    private String senderName;
    private String messageType;
    private String content;
    private String clientMessageId;
    private LocalDateTime sentAt;
    @TableLogic
    private Boolean deleted;
}
