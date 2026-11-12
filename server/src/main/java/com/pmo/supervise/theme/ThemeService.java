package com.pmo.supervise.theme;

import com.pmo.supervise.auth.LoginAuditService;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.source.SourcePalette;
import com.pmo.supervise.theme.ThemeDao.ThemeRow;
import com.pmo.supervise.theme.dto.ThemeOption;
import com.pmo.supervise.theme.dto.ThemeRequest;
import com.pmo.supervise.theme.dto.ThemeView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主题主数据业务（TECHNICAL §3.1 / AC-8）：权限、名称、上限、引用的唯一写入口。
 *
 * <p>读侧（任务表单、标签、甘特、任务池筛选）只调用 {@link #requireSelectable} / {@link #requireKnown}，
 * 不各自判断「主题是否存在/停用」——停用语义靠唯一入口保持一致。
 */
@Service
public class ThemeService {

    /** 审计动作：五种变更各自留痕（AC-8）。 */
    public static final String AUDIT_CREATED = "THEME_CREATED";
    public static final String AUDIT_UPDATED = "THEME_UPDATED";
    public static final String AUDIT_ENABLED = "THEME_ENABLED";
    public static final String AUDIT_DISABLED = "THEME_DISABLED";
    public static final String AUDIT_DELETED = "THEME_DELETED";

    /** 主题上限（含停用）：到顶后新增被 409 挡住。 */
    public static final int LIMIT = 100;

    public static final int NAME_MIN = 2;
    public static final int NAME_MAX = 20;

    private static final int SORT_MAX = 99;

    private final ThemeDao dao;
    private final LoginAuditService audit;

    public ThemeService(ThemeDao dao, LoginAuditService audit) {
        this.dao = dao;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ThemeView> list() {
        return dao.listWithUsage();
    }

    /** meta / 下拉的唯一选项源：含停用项（前端置灰），按 sort_no, id 排序。 */
    @Transactional(readOnly = true)
    public List<ThemeOption> options() {
        return dao.listAll().stream().map(ThemeDao::toOption).toList();
    }

    /** 8 色色板复用来源色板，前端不维护第二套主题色（AC-8）。 */
    @Transactional(readOnly = true)
    public List<SourcePalette.Entry> palette() {
        return SourcePalette.entries();
    }

    @Transactional
    public ThemeView create(Actor actor, ThemeRequest req, HttpServletRequest http) {
        requireAdmin(actor);
        String name = normalizeName(req.name());
        if (dao.nameExists(name, null)) {
            throw nameDuplicate();
        }

        // 上限判断与 INSERT 同事务：锁住全部主题行，两个并发新增不会都读到 99。
        List<Long> existing = dao.lockAllIds();
        if (existing.size() >= LIMIT) {
            throw new BusinessException(ErrorCode.THEME_LIMIT_REACHED, "主题最多 " + LIMIT + " 个");
        }
        String color = resolveColor(req.color(), existing.size());
        int sort = req.sort() == null ? clampSort(dao.nextSort()) : clampSort(req.sort());
        boolean enabled = req.enabled() == null || req.enabled();

        long id;
        try {
            id = dao.insert(name, color, sort, enabled);
        } catch (DuplicateKeyException ex) {
            throw nameDuplicate();
        }

        audit.record(actor.id(), AUDIT_CREATED, "THEME", String.valueOf(id),
                "name=" + name + "; color=" + color + "; sort=" + sort + "; enabled=" + (enabled ? 1 : 0),
                http);
        return ThemeDao.toView(new ThemeRow(id, name, color, sort, enabled), 0);
    }

    @Transactional
    public ThemeView update(long id, Actor actor, ThemeRequest req, HttpServletRequest http) {
        requireAdmin(actor);
        ThemeRow current = requireKnown(id);
        String name = normalizeName(req.name());
        if (dao.nameExists(name, id)) {
            throw nameDuplicate();
        }
        String color = req.color() == null || req.color().isBlank()
                ? current.color()
                : requireColor(req.color());
        int sort = req.sort() == null ? current.sort() : clampSort(req.sort());
        boolean enabled = req.enabled() == null ? current.enabled() : req.enabled();

        try {
            dao.update(id, name, color, sort, enabled);
        } catch (DuplicateKeyException ex) {
            throw nameDuplicate();
        }

        ThemeRow updated = new ThemeRow(id, name, color, sort, enabled);
        audit.record(actor.id(), AUDIT_UPDATED, "THEME", String.valueOf(id), changes(current, updated), http);
        return ThemeDao.toView(updated, dao.countTasks(id));
    }

    @Transactional
    public ThemeView setEnabled(long id, Actor actor, boolean enabled, HttpServletRequest http) {
        requireAdmin(actor);
        ThemeRow current = requireKnown(id);
        dao.updateEnabled(id, enabled);
        audit.record(actor.id(), enabled ? AUDIT_ENABLED : AUDIT_DISABLED, "THEME", String.valueOf(id),
                "name=" + current.name() + "; enabled=" + (current.enabled() ? 1 : 0) + "→"
                        + (enabled ? 1 : 0), http);
        return ThemeDao.toView(new ThemeRow(id, current.name(), current.color(), current.sort(), enabled),
                dao.countTasks(id));
    }

    @Transactional
    public void delete(long id, Actor actor, HttpServletRequest http) {
        requireAdmin(actor);
        ThemeRow current = requireKnown(id);
        long usage = dao.countTasks(id);
        if (usage > 0) {
            throw inUse(usage);
        }
        try {
            dao.delete(id);
        } catch (DataIntegrityViolationException ex) {
            // 竞态：检查与删除之间有人把任务挂到了该主题。重查后给同一 409，不向用户暴露 500。
            throw inUse(dao.countTasks(id));
        }
        audit.record(actor.id(), AUDIT_DELETED, "THEME", String.valueOf(id),
                "name=" + current.name() + "; color=" + current.color() + "; sort=" + current.sort(), http);
    }

    /** 写路径与配置页按 id 取行：不存在是 404（主题不是任务字段，不冒充 422）。 */
    @Transactional(readOnly = true)
    public ThemeRow requireKnown(long id) {
        return dao.findById(id).orElseThrow(() -> BusinessException.notFound("主题不存在"));
    }

    /**
     * 建任务/改任务只接受启用主题；null 表示「未归主题」合法（AC-9）。
     * 统一 422 + fieldErrors.themeId，前端回填并聚焦。
     */
    @Transactional(readOnly = true)
    public ThemeRow requireSelectable(Long id) {
        if (id == null) {
            return null;
        }
        ThemeRow row = dao.findById(id).orElseThrow(
                () -> BusinessException.validation("themeId", "主题不存在，请重新选择"));
        if (!row.enabled()) {
            throw BusinessException.validation("themeId", "该主题已停用，请重新选择");
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

    /** 未指定颜色时按创建顺序取下一个预设色（AC-8「默认按序分配」）。 */
    private String resolveColor(String raw, int existingCount) {
        if (raw == null || raw.isBlank()) {
            List<SourcePalette.Entry> palette = SourcePalette.entries();
            return palette.get(existingCount % palette.size()).color();
        }
        return requireColor(raw);
    }

    private String requireColor(String raw) {
        String color = SourcePalette.normalize(raw);
        if (!SourcePalette.isKnown(color)) {
            throw BusinessException.validation("color", "颜色不在可选色板内");
        }
        return color;
    }

    private BusinessException nameDuplicate() {
        return new BusinessException(ErrorCode.THEME_NAME_DUPLICATE, "名称已存在，请换一个",
                List.of(new ApiError.FieldError("name", "名称已存在，请换一个", 0)), Map.of());
    }

    private BusinessException inUse(long usage) {
        return new BusinessException(ErrorCode.THEME_IN_USE,
                "该主题已被 " + usage + " 条任务使用，只能停用", Map.of("taskCount", usage));
    }

    /** 更新审计只写变更项；没有变化时留一条可读记录（AC-8 要求每条写操作可追溯）。 */
    private String changes(ThemeRow current, ThemeRow updated) {
        List<String> parts = new ArrayList<>();
        if (!current.name().equals(updated.name())) {
            parts.add("name=" + current.name() + "→" + updated.name());
        }
        if (!current.color().equals(updated.color())) {
            parts.add("color=" + current.color() + "→" + updated.color());
        }
        if (current.sort() != updated.sort()) {
            parts.add("sort=" + current.sort() + "→" + updated.sort());
        }
        if (current.enabled() != updated.enabled()) {
            parts.add("enabled=" + (current.enabled() ? 1 : 0) + "→" + (updated.enabled() ? 1 : 0));
        }
        return parts.isEmpty() ? "unchanged" : String.join("; ", parts);
    }
}
