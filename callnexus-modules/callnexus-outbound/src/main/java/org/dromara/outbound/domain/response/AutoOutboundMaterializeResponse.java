package org.dromara.outbound.domain.response;

import lombok.Data;

@Data
public class AutoOutboundMaterializeResponse {
    private int sourceCount;
    private int candidateCount;
    private int addedCount;
    private int duplicateCount;
    private int invalidPhoneCount;
    private int blacklistedCount;
}
