package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

/**
 * AI 模块读取通话事件的轻量映射。
 *
 * <p>只用于 ASR 前处理定位真实通话开始点，避免 AI 模块依赖 call 模块实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_call_event")
public class AiCallEvent extends TenantEntity {
    @TableId
    private Long id;
    private Long sessionId;
    private String eventType;
    private LocalDateTime occurredAt;
}
