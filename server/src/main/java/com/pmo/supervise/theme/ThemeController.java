package com.pmo.supervise.theme;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.source.SourcePalette;
import com.pmo.supervise.theme.dto.ThemeRequest;
import com.pmo.supervise.theme.dto.ThemeView;
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
 * 主题接口（TECHNICAL §3.1 / AC-8）：Controller 只取 Actor 与 @Valid。
 * 读接口全员可用（任务表单与标签需要），写接口的 ADMIN 判定在 Service 内——
 * 与 ProjectController「列表在 Controller 判」不同，主题列表不是管理页专属。
 */
@RestController
@RequestMapping("/api/themes")
public class ThemeController {

    private final ThemeService service;

    public ThemeController(ThemeService service) {
        this.service = service;
    }

    @GetMapping
    public List<ThemeView> list() {
        return service.list();
    }

    @GetMapping("/palette")
    public List<SourcePalette.Entry> palette() {
        return service.palette();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ThemeView create(@Valid @RequestBody ThemeRequest request, Actor actor,
                            HttpServletRequest http) {
        return service.create(actor, request, http);
    }

    @PutMapping("/{id}")
    public ThemeView update(@PathVariable long id, @Valid @RequestBody ThemeRequest request,
                            Actor actor, HttpServletRequest http) {
        return service.update(id, actor, request, http);
    }

    @PatchMapping("/{id}/status")
    public ThemeView setEnabled(@PathVariable long id, @RequestBody ThemeStatusRequest request,
                                Actor actor, HttpServletRequest http) {
        return service.setEnabled(id, actor, request.enabled(), http);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, Actor actor, HttpServletRequest http) {
        service.delete(id, actor, http);
    }

    public record ThemeStatusRequest(boolean enabled) {
    }
}
