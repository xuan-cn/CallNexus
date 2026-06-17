package org.dromara.system.callcenterconfig.domain.response;

import lombok.Data;

import java.util.List;

@Data
public class CallCenterConfigGroupResponse {
    private String groupCode;
    private String groupName;
    private List<CallCenterConfigItemResponse> items;
}
