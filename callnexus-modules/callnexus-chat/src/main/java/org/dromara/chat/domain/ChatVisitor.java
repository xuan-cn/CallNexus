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
@TableName("cc_chat_visitor")
public class ChatVisitor extends TenantEntity {
    @TableId
    private Long id;
    private String externalId;
    private String visitorName;
    private String phone;
    private String email;
    private LocalDateTime lastSeenAt;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
