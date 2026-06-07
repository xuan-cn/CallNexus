package org.dromara.customer.ticket.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ticket")
public class Ticket extends TenantEntity {
    @TableId
    private Long id;
    private String ticketNo;
    private TicketStatus ticketStatus;
    private Long customerId;
    private String callerNumber;
    private String sourceCallId;
    private Long templateId;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
