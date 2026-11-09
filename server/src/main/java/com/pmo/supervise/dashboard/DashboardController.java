package com.pmo.supervise.dashboard;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.dashboard.dto.DashboardView;
import com.pmo.supervise.task.dto.TaskFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 看板接口（TECHNICAL §5.5 / AC-16）：GET /api/dashboard，权限 admin / exec。
 * 只读：管理层在本接口下不会得到任何验收类动作入口（验收动作由 /api/tasks/{id}/accept 拒绝）。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final ShanghaiClock clock;

    public DashboardController(DashboardService dashboardService, ShanghaiClock clock) {
        this.dashboardService = dashboardService;
        this.clock = clock;
    }

    @GetMapping
    public DashboardView load(@ModelAttribute TaskFilter filter,
                              @RequestParam(defaultValue = "10") int focusLimit,
                              Actor actor) {
        return dashboardService.load(filter, clock.today(), focusLimit, actor);
    }
}
