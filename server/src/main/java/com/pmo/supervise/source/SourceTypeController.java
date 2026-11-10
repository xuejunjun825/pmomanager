package com.pmo.supervise.source;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.source.dto.SourcePaletteEntry;
import com.pmo.supervise.source.dto.SourceTypeRequest;
import com.pmo.supervise.source.dto.SourceTypeStatusRequest;
import com.pmo.supervise.source.dto.SourceTypeView;
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
 * 来源配置接口（TECHNICAL §5.1）。
 *
 * <p>非管理员在入口即 403：设置页不出入口只是显示层，接口本身必须自己把关。
 * Controller 不写业务分支，护栏与文案全部留在 {@link SourceTypeService}。
 */
@RestController
@RequestMapping("/api/source-types")
public class SourceTypeController {

    private final SourceTypeService service;

    public SourceTypeController(SourceTypeService service) {
        this.service = service;
    }

    @GetMapping
    public List<SourceTypeView> list(Actor actor) {
        requireAdmin(actor);
        return service.list();
    }

    /** 色板是登录即可读的展示数据（建任务表单也要用它渲染历史任务标签）。 */
    @GetMapping("/palette")
    public List<SourcePaletteEntry> palette() {
        return service.palette();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceTypeView create(@Valid @RequestBody SourceTypeRequest request, Actor actor,
                                 HttpServletRequest http) {
        return service.create(actor, request, http);
    }

    @PutMapping("/{code}")
    public SourceTypeView update(@PathVariable String code, @Valid @RequestBody SourceTypeRequest request,
                                 Actor actor, HttpServletRequest http) {
        return service.update(actor, code, request, http);
    }

    @PatchMapping("/{code}/status")
    public SourceTypeView setEnabled(@PathVariable String code, @RequestBody SourceTypeStatusRequest request,
                                     Actor actor, HttpServletRequest http) {
        return service.setEnabled(actor, code, request.enabled(), http);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code, Actor actor, HttpServletRequest http) {
        service.delete(actor, code, http);
    }

    private void requireAdmin(Actor actor) {
        if (!actor.isAdmin()) {
            throw BusinessException.forbidden("需要管理员权限");
        }
    }
}
