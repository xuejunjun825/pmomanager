package com.pmo.supervise.project;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.project.dto.ProjectRequest;
import com.pmo.supervise.project.dto.ProjectStatusRequest;
import com.pmo.supervise.project.dto.ProjectView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目配置接口（TECHNICAL §2.1）。
 *
 * <p>照 {@code SourceTypeController} 的形状：列表在 Controller 内 requireAdmin，
 * 写操作 Service 内再判一次。其它角色读项目名的唯一入口是 /api/meta/options。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectView> list(Actor actor) {
        requireAdmin(actor);
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(@Valid @RequestBody ProjectRequest request, Actor actor,
                              HttpServletRequest http) {
        return service.create(actor, request, http);
    }

    @PutMapping("/{id}")
    public ProjectView update(@PathVariable long id, @Valid @RequestBody ProjectRequest request,
                              Actor actor, HttpServletRequest http) {
        return service.update(actor, id, request, http);
    }

    @PatchMapping("/{id}/status")
    public ProjectView setEnabled(@PathVariable long id, @RequestBody ProjectStatusRequest request,
                                  Actor actor, HttpServletRequest http) {
        return service.setEnabled(actor, id, request.enabled(), http);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, Actor actor, HttpServletRequest http) {
        service.delete(actor, id, http);
    }

    private void requireAdmin(Actor actor) {
        if (!actor.isAdmin()) {
            throw BusinessException.forbidden("需要管理员权限");
        }
    }
}
