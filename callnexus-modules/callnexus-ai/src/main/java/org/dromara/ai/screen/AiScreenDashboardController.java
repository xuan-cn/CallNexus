package org.dromara.ai.screen;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 话务大屏只读接口。独立 package，不侵入实时通话业务服务。
 */
@RestController
@RequestMapping("/api/v1/ai/screen")
@RequiredArgsConstructor
public class AiScreenDashboardController {

    private final AiScreenDashboardService dashboardService;

    @GetMapping("/dashboard")
    @SaCheckPermission("callcenter:ai-agent:query")
    public R<AiScreenDashboardResponse> dashboard() {
        return R.ok(dashboardService.overview());
    }
}
