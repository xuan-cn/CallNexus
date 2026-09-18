package org.dromara.outbound.domain.request;

import lombok.Data;

@Data
public class AutoOutboundTaskQuery {
    private String keyword;
    private String dialMode;
    private String status;
}
