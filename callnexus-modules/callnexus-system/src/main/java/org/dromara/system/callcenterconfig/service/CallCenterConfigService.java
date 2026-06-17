package org.dromara.system.callcenterconfig.service;

import org.dromara.system.callcenterconfig.domain.request.CallCenterConfigGroupSaveRequest;
import org.dromara.system.callcenterconfig.domain.response.CallCenterConfigGroupResponse;

import java.util.List;

public interface CallCenterConfigService {
    List<CallCenterConfigGroupResponse> listGroups();

    CallCenterConfigGroupResponse getGroup(String groupCode);

    void saveGroup(String groupCode, CallCenterConfigGroupSaveRequest request);

    void reset(String configKey);

    String getString(String configKey);

    Integer getInt(String configKey);

    Boolean getBoolean(String configKey);
}
