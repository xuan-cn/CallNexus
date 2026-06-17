package org.dromara.system.controller.callcenter;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.system.callcenterconfig.domain.request.CallCenterConfigGroupSaveRequest;
import org.dromara.system.callcenterconfig.domain.response.CallCenterConfigGroupResponse;
import org.dromara.system.callcenterconfig.service.CallCenterConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/callcenter-config")
@RequiredArgsConstructor
public class CallCenterConfigController {
    private final CallCenterConfigService service;

    @GetMapping("/groups")
    @SaCheckPermission("callcenter:config:list")
    public R<List<CallCenterConfigGroupResponse>> groups() {
        return R.ok(service.listGroups());
    }

    @GetMapping("/groups/{groupCode}")
    @SaCheckPermission("callcenter:config:query")
    public R<CallCenterConfigGroupResponse> group(@PathVariable String groupCode) {
        return R.ok(service.getGroup(groupCode));
    }

    @PutMapping("/groups/{groupCode}")
    @SaCheckPermission("callcenter:config:update")
    public R<Void> saveGroup(@PathVariable String groupCode, @Valid @RequestBody CallCenterConfigGroupSaveRequest request) {
        service.saveGroup(groupCode, request);
        return R.ok();
    }

    @DeleteMapping("/items/{configKey}")
    @SaCheckPermission("callcenter:config:update")
    public R<Void> reset(@PathVariable String configKey) {
        service.reset(configKey);
        return R.ok();
    }
}
