package com.pmo.supervise.project;

import com.pmo.supervise.auth.LoginAuditService;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.project.ProjectDao.ProjectRow;
import com.pmo.supervise.project.dto.ProjectOption;
import com.pmo.supervise.project.dto.ProjectRequest;
import com.pmo.supervise.project.dto.ProjectView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 项目主数据业务（TECHNICAL §3.1）：所有护栏、编码生成、审计的唯一写入口。
 *
 * <p>读侧（建任务 / 筛选 / 矩阵 / 元数据）只调用 {@link #requireKnown} 与
 * {@link #requireEnabled}，不各自解析项目状态——同源一致（AC-5）靠唯一入口保证。
 */
@Service
public class ProjectService {

    /** 审计动作（AC-8）：五种变更各自留痕。 */
    public static final String AUDIT_CREATED = "PROJECT_CREATED";
    public static final String AUDIT_UPDATED = "PROJECT_UPDATED";
    public static final String AUDIT_ENABLED = "PROJECT_ENABLED";
    public static final String AUDIT_DISABLED = "PROJECT_DISABLED";
    public static final String AUDIT_DELETED = "PROJECT_DELETED";

    /** 项目上限（含停用）：到顶后新增被 409 挡住。 */
    public static final int LIMIT = 200;

    public static final int NAME_MIN = 2;
    public static final int NAME_MAX = 20;

    private static final int SORT_MAX = 99;

    private final ProjectDao dao;
    private final LoginAuditService audit;

    public ProjectService(ProjectDao dao, LoginAuditService audit) {
        this.dao = dao;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ProjectView> list() {
        return dao.listWithUsage();
    }

    /** 下拉与筛选的唯一选项源：返回全部项目（含停用），按 sort_no, id 排序。 */
    @Transactional(readOnly = true)
    public List<ProjectOption> options() {
        return dao.listAll().stream()
                .map(row -> new ProjectOption(row.id(), row.name(), row.enabled(), row.sort()))
                .toList();
    }

    @Transactional
    public ProjectView create(Actor actor, ProjectRequest req, HttpServletRequest http) {
        requireAdmin(actor);
        String name = normalizeName(req.name());
        if (dao.nameExists(name, null)) {
            throw nameDuplicate();
        }

        // 上限判断与 INSERT 同事务：锁住全部项目行，两个并发新增不会都读到 199。
        if (dao.lockAllIds().size() >= LIMIT) {
            throw new BusinessException(ErrorCode.PROJECT_LIMIT_REACHED, "项目最多 " + LIMIT + " 个");
        }
        int sort = req.sort() == null ? clampSort(dao.nextSort()) : clampSort(req.sort());
        boolean enabled = req.enabled() == null || req.enabled();

        long id;
        String code;
        try {
            id = dao.insert(name, sort, enabled, placeholderCode());
            code = "P" + String.format(Locale.ROOT, "%05d", id);
            dao.updateCode(id, code);
        } catch (DuplicateKeyException ex) {
            throw nameDuplicate();
        }

        audit.record(actor.id(), AUDIT_CREATED, "PROJECT", code,
                "name=" + name + "; enabled=" + (enabled ? 1 : 0) + "; sort=" + sort, http);
        return new ProjectView(id, code, name, sort, enabled, 0);
    }

    @Transactional
    public ProjectView update(Actor actor, long id, ProjectRequest req, HttpServletRequest http) {
        requireAdmin(actor);
        ProjectRow current = requireKnown(id);
        String name = normalizeName(req.name());
        if (dao.nameExists(name, current.id())) {
            throw nameDuplicate();
        }
        int sort = req.sort() == null ? current.sort() : clampSort(req.sort());
        boolean enabled = req.enabled() == null ? current.enabled() : req.enabled();
        dao.update(current.id(), name, sort, enabled);

        audit.record(actor.id(), AUDIT_UPDATED, "PROJECT", current.code(),
                changes(current, name, sort, enabled), http);
        return new ProjectView(current.id(), current.code(), name, sort, enabled, dao.countTasks(current.id()));
    }

    @Transactional
    public ProjectView setEnabled(Actor actor, long id, boolean enabled, HttpServletRequest http) {
        requireAdmin(actor);
        ProjectRow current = requireKnown(id);
        dao.updateEnabled(current.id(), enabled);
        audit.record(actor.id(), enabled ? AUDIT_ENABLED : AUDIT_DISABLED, "PROJECT", current.code(),
                "enabled=" + (current.enabled() ? 1 : 0) + "→" + (enabled ? 1 : 0), http);
        return new ProjectView(current.id(), current.code(), current.name(), current.sort(),
                enabled, dao.countTasks(current.id()));
    }

    @Transactional
    public void delete(Actor actor, long id, HttpServletRequest http) {
        requireAdmin(actor);
        ProjectRow current = requireKnown(id);
        long usage = dao.countTasks(current.id());
        if (usage > 0) {
            throw inUse(usage);
        }
        try {
            dao.delete(current.id());
        } catch (DataIntegrityViolationException ex) {
            // 竞态：检查与删除之间有人建了引用该项目的任务。重查后给同一 409，不向用户暴露 500。
            throw inUse(dao.countTasks(current.id()));
        }
        audit.record(actor.id(), AUDIT_DELETED, "PROJECT", current.code(),
                "name=" + current.name() + "; enabled=" + (current.enabled() ? 1 : 0)
                        + "; sort=" + current.sort(), http);
    }

    /** 项目必须存在（400 而不是 404）：筛选/建任务传错 id 是请求参数错误。 */
    @Transactional(readOnly = true)
    public ProjectRow requireKnown(long id) {
        return dao.findById(id).orElseThrow(() -> new BusinessException(
                ErrorCode.PROJECT_INVALID, "项目不合法",
                List.of(new ApiError.FieldError("sourceProjectId", "项目不合法", 0)), Map.of()));
    }

    /** 建任务只接受启用项目；历史任务仍可按停用项目筛选与展示。 */
    @Transactional(readOnly = true)
    public ProjectRow requireEnabled(long id) {
        ProjectRow row = requireKnown(id);
        if (!row.enabled()) {
            throw new BusinessException(ErrorCode.PROJECT_DISABLED, "该项目已停用，请重新选择");
        }
        return row;
    }

    private void requireAdmin(Actor actor) {
        if (!actor.isAdmin()) {
            throw BusinessException.forbidden("需要管理员权限");
        }
    }

    private String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int length = name.codePointCount(0, name.length());
        if (length < NAME_MIN || length > NAME_MAX) {
            throw BusinessException.validation("name", "名称为 " + NAME_MIN + "–" + NAME_MAX + " 字");
        }
        return name;
    }

    private int clampSort(long sort) {
        return (int) Math.min(Math.max(sort, 0), SORT_MAX);
    }

    private String placeholderCode() {
        return "TMP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private BusinessException nameDuplicate() {
        return new BusinessException(ErrorCode.PROJECT_NAME_DUPLICATE, "名称已存在，请换一个",
                List.of(new ApiError.FieldError("name", "名称已存在，请换一个", 0)), Map.of());
    }

    private BusinessException inUse(long usage) {
        return new BusinessException(ErrorCode.PROJECT_IN_USE,
                "该项目已被 " + usage + " 条任务使用，只能停用", Map.of("taskCount", usage));
    }

    /** 更新审计只写变更项；没有变化时留一条可读记录（AC-8 要求每条写操作可追溯）。 */
    private String changes(ProjectRow current, String name, int sort, boolean enabled) {
        List<String> parts = new ArrayList<>();
        if (!current.name().equals(name)) {
            parts.add("name=" + current.name() + "→" + name);
        }
        if (current.sort() != sort) {
            parts.add("sort=" + current.sort() + "→" + sort);
        }
        if (current.enabled() != enabled) {
            parts.add("enabled=" + (current.enabled() ? 1 : 0) + "→" + (enabled ? 1 : 0));
        }
        return parts.isEmpty() ? "unchanged" : String.join("; ", parts);
    }
}
