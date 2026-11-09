package com.pmo.supervise.dashboard;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.dashboard.dto.MatrixFilter;
import com.pmo.supervise.dashboard.dto.MatrixView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 矩阵接口（TECHNICAL §5.5 / AC-15）：GET /api/matrix，权限 admin / exec。
 * today 每请求取一次后下传，矩阵与任务池在同一次刷新内共享同一个「今天」。
 */
@RestController
@RequestMapping("/api/matrix")
public class MatrixController {

    private final MatrixService matrixService;
    private final ShanghaiClock clock;

    public MatrixController(MatrixService matrixService, ShanghaiClock clock) {
        this.matrixService = matrixService;
        this.clock = clock;
    }

    @GetMapping
    public MatrixView load(@ModelAttribute MatrixFilter filter, Actor actor) {
        return matrixService.load(filter, clock.today(), actor);
    }
}
