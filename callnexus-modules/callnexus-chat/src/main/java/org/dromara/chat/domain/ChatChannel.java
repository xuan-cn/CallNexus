package org.dromara.chat.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_chat_channel")
public class ChatChannel extends TenantEntity {
    @TableId
    private Long id;
    private String channelKey;
    private String channelName;
    private Long skillGroupId;
    private String welcomeMessage;
    private String offlineMessage;
    private String allowedOrigins;
    private Boolean enabled;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
