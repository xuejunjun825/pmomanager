package com.pmo.supervise.task;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.PageResponse;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.notify.UrgeService;
import com.pmo.supervise.notify.dto.UrgeResult;
import com.pmo.supervise.task.dto.CreateTaskRequest;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TaskEventRow;
import com.pmo.supervise.task.dto.TaskFilter;
import com.pmo.supervise.task.dto.TaskRow;
import com.pmo.supervise.task.dto.TransitionRequest;
import com.pmo.supervise.task.dto.UpdateTaskRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务接口（TECHNICAL §5.4）：流转用显式子路径，让权限与测试逐条对应；
 * owner 的列表查询强制 ownerId = 本人，越界详情 404。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskQueryDao queryDao;
    private final TaskService taskService;
    private final TaskCommandService commandService;
    private final UrgeService urgeService;
    private final ShanghaiClock clock;

    public TaskController(TaskQueryDao queryDao, TaskService taskService,
                          TaskCommandService commandService, UrgeService urgeService, ShanghaiClock clock) {
        this.queryDao = queryDao;
        this.taskService = taskService;
        this.commandService = commandService;
        this.urgeService = urgeService;
        this.clock = clock;
    }

    @GetMapping
    public PageResponse<TaskRow> list(@ModelAttribute TaskFilter filter, Actor actor) {
        if (!actor.isGlobalReader()) {
            filter.setOwnerId(actor.id());
        }
        return queryDao.page(filter, clock.today(), filter.getPage(), filter.getSize());
    }

    @GetMapping("/{id}")
    public TaskDetailRow detail(@PathVariable long id, Actor actor) {
        return taskService.get(id, actor, clock.today());
    }

    @GetMapping("/{id}/events")
    public List<TaskEventRow> events(@PathVariable long id, Actor actor) {
        taskService.get(id, actor, clock.today());
        return queryDao.events(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetailRow create(@Valid @RequestBody CreateTaskRequest request, Actor actor) {
        return taskService.create(actor, request, clock.today());
    }

    @PatchMapping("/{id}")
    public TaskDetailRow update(@PathVariable long id, @Valid @RequestBody UpdateTaskRequest request, Actor actor) {
        return taskService.update(id, actor, request, clock.today());
    }

    @PostMapping("/{id}/start")
    public TaskDetailRow start(@PathVariable long id, Actor actor) {
        return commandService.start(id, actor);
    }

    @PostMapping("/{id}/submit")
    public TaskDetailRow submit(@PathVariable long id, @RequestBody(required = false) TransitionRequest request,
                                Actor actor) {
        return commandService.submit(id, actor, request);
    }

    @PostMapping("/{id}/accept")
    public TaskDetailRow accept(@PathVariable long id, Actor actor) {
        return commandService.accept(id, actor);
    }

    @PostMapping("/{id}/reject")
    public TaskDetailRow reject(@PathVariable long id, @RequestBody TransitionRequest request, Actor actor) {
        return commandService.reject(id, actor, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/cancel")
    public TaskDetailRow cancel(@PathVariable long id, @RequestBody TransitionRequest request, Actor actor) {
        return commandService.cancel(id, actor, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/restore")
    public TaskDetailRow restore(@PathVariable long id, Actor actor) {
        return commandService.restore(id, actor);
    }

    @PostMapping("/{id}/force-complete")
    public TaskDetailRow forceComplete(@PathVariable long id, @RequestBody TransitionRequest request, Actor actor) {
        return commandService.forceComplete(id, actor, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/reassign")
    public TaskDetailRow reassign(@PathVariable long id, @RequestBody TransitionRequest request, Actor actor) {
        if (request == null || request.ownerId() == null) {
            throw com.pmo.supervise.common.BusinessException.validation("ownerId", "请选择新的负责人");
        }
        return commandService.reassign(id, actor, request.ownerId(), request.reason());
    }

    @PostMapping("/{id}/reschedule")
    public TaskDetailRow reschedule(@PathVariable long id, @RequestBody TransitionRequest request, Actor actor) {
        return commandService.reschedule(id, actor, request == null ? null : request.dueDate(),
                request == null ? null : request.reason());
    }

    @PostMapping("/{id}/urges")
    public UrgeResult urge(@PathVariable long id, Actor actor) {
        return urgeService.urge(id, actor);
    }
}
