package org.dromara.resource.node.group.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.resource.node.group.domain.NodeGroupRequest;
import org.dromara.resource.node.group.domain.NodeGroupResponse;
import org.dromara.resource.node.group.service.NodeGroupService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/freeswitch-node-groups")
@RequiredArgsConstructor
public class NodeGroupController {
    private final NodeGroupService service;
    @GetMapping @SaCheckPermission("callcenter:freeswitch-node-group:list")
    public R<List<NodeGroupResponse>> list() { return R.ok(service.list()); }
    @GetMapping("/{id}") @SaCheckPermission("callcenter:freeswitch-node-group:query")
    public R<NodeGroupResponse> get(@PathVariable Long id) { return R.ok(service.get(id)); }
    @PostMapping @SaCheckPermission("callcenter:freeswitch-node-group:create")
    public R<Long> create(@Valid @RequestBody NodeGroupRequest request) { return R.ok(service.create(request)); }
    @PutMapping("/{id}") @SaCheckPermission("callcenter:freeswitch-node-group:update")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody NodeGroupRequest request) { service.update(id, request); return R.ok(); }
    @DeleteMapping("/{id}") @SaCheckPermission("callcenter:freeswitch-node-group:delete")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
