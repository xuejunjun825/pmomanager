package com.pmo.supervise.task.progress;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.task.progress.dto.AddProgressRequest;
import com.pmo.supervise.task.progress.dto.ProgressPage;
import com.pmo.supervise.task.progress.dto.ProgressRow;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务进度子资源（TECHNICAL §5.3）。独立控制器，避免与任务状态动作混在一个类里。
 */
@RestController
@RequestMapping("/api/tasks/{taskId}/progress")
public class TaskProgressController {

    private final TaskProgressService progressService;

    public TaskProgressController(TaskProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping
    public ProgressPage page(@PathVariable long taskId, Actor actor,
                             @RequestParam(defaultValue = "50") int limit,
                             @RequestParam(required = false) Long beforeId) {
        return progressService.page(taskId, actor, limit, beforeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgressRow add(@PathVariable long taskId, Actor actor,
                           @Valid @RequestBody AddProgressRequest request) {
        return progressService.add(taskId, actor, request);
    }
}
