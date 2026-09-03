package org.dromara.outbound.screen;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页运营大屏只读接口。独立 package，组合既有监控服务，不侵入业务链路。
 */
@RestController
@RequestMapping("/api/v1/callcenter/screen/home")
@RequiredArgsConstructor
public class HomeScreenDashboardController {

    private final HomeScreenDashboardService dashboardService;

    @GetMapping("/dashboard")
    @SaCheckPermission("callcenter:queue-monitor:query")
    public R<HomeScreenDashboardResponse> dashboard() {
        return R.ok(dashboardService.overview());
    }
}
