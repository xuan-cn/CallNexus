package org.dromara.call.domain;

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
@TableName("cc_voicemail_message")
public class VoiceMailMessage extends TenantEntity {
    @TableId
    private Long id;
    private Long voicemailBoxId;
    private String businessCallId;
    private Long callSessionId;
    private Long nodeId;
    private String callerNumber;
    private String calledNumber;
    private Long customerId;
    private Long ticketId;
    private Long recordingOssId;
    private Long recordingMediaId;
    private String recordingFileName;
    private Long durationMs;
    private String status;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleRemark;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
