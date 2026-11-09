package com.pmo.supervise.user;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.PageResponse;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.UserStatus;
import com.pmo.supervise.user.dto.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * /api/users（TECHNICAL §5.2）。管理动作全部 admin——权限判定在 UserService，
 * Controller 只做参数绑定，避免两处各写一遍角色判断。
 *
 * <p>/options 是表单与筛选的下拉数据源（任务详情改派、收件人名单），任何登录用户可用，
 * 只暴露 id/name/role/status，不含口令与审计字段。</p>
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public PageResponse<UserRow> page(@RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) Role role,
                                      @RequestParam(required = false) UserStatus status,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      Actor actor) {
        return userService.page(actor, keyword, role, status, page, size);
    }

    @GetMapping("/options")
    public List<UserRow> options(@RequestParam(defaultValue = "false") boolean includeDisabled, Actor actor) {
        return userService.options(includeDisabled);
    }

    @PatchMapping("/{id}/role")
    public UserRow updateRole(@PathVariable long id, @Valid @RequestBody RoleRequest request, Actor actor,
                              HttpServletRequest httpRequest) {
        return userService.updateRole(id, request.role(), actor, httpRequest);
    }

    @PatchMapping("/{id}/status")
    public UserRow updateStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request, Actor actor,
                                HttpServletRequest httpRequest) {
        return userService.updateStatus(id, request.status(), actor, httpRequest);
    }

    /** 一次性临时口令只在此响应里出现，前端展示后即丢弃。 */
    @PostMapping("/{id}/password-reset")
    public Map<String, String> resetPassword(@PathVariable long id, Actor actor, HttpServletRequest httpRequest) {
        return Map.of("temporaryPassword", userService.resetPassword(id, actor, httpRequest));
    }

    public record RoleRequest(@jakarta.validation.constraints.NotNull Role role) {
    }

    public record StatusRequest(@jakarta.validation.constraints.NotNull UserStatus status) {
    }
}
