package com.pmo.supervise.gantt;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.gantt.dto.GanttQuery;
import com.pmo.supervise.gantt.dto.GanttView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 甘特视图接口（TECHNICAL §3.4 / §5.3）：{@code GET /api/gantt} 一个只读入口。
 *
 * <p>薄入口：只负责把 query string 变成 {@link GanttQuery} 并取服务端「今天」。
 * 权限（ADMIN/EXEC）与日期范围校验在 {@link GanttService}，与看板同一份判定；
 * 日期格式非法或参数缺失由 Spring 的绑定失败路径给 400，服务端不再手写第二套解析。
 */
@RestController
@RequestMapping("/api")
public class GanttController {

    private final GanttService ganttService;
    private final ShanghaiClock clock;

    public GanttController(GanttService ganttService, ShanghaiClock clock) {
        this.ganttService = ganttService;
        this.clock = clock;
    }

    @GetMapping("/gantt")
    public GanttView load(@RequestParam LocalDate from,
                          @RequestParam LocalDate to,
                          @RequestParam(required = false) Long ownerId,
                          @RequestParam(defaultValue = "true") boolean includeDone,
                          @RequestParam(defaultValue = "false") boolean onlyRisk,
                          @RequestParam(required = false) String theme,
                          Actor actor) {
        return ganttService.load(actor, clock.today(),
                new GanttQuery(from, to, ownerId, includeDone, onlyRisk, theme));
    }
}
