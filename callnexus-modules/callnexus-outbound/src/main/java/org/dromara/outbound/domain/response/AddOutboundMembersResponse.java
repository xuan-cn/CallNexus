package org.dromara.outbound.domain.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AddOutboundMembersResponse {
    private int addedCount;
    private int duplicateCount;
    private List<BlockedMemberDetail> blocked = new ArrayList<>();

    @Data
    public static class BlockedMemberDetail {
        private Long customerId;
        private String customerName;
        private String phoneNumber;
        private String reason;
        private Long blacklistId;
    }
}
