package com.pmo.supervise.source;

import com.pmo.supervise.auth.LoginAuditService;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.source.SourceTypeDao.SourceTypeConfig;
import com.pmo.supervise.source.dto.SourcePaletteEntry;
import com.pmo.supervise.source.dto.SourceTypeOption;
import com.pmo.supervise.source.dto.SourceTypeRequest;
import com.pmo.supervise.source.dto.SourceTypeView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 来源配置业务（TECHNICAL §4.1）：所有护栏、编码生成、审计的唯一写入口。
 *
 * <p>读侧（建任务 / 筛选 / 矩阵 / 元数据）只调用 {@link #requireKnown} 与
 * {@link #requireEnabled} 取配置，不再各自解析来源枚举——同源一致（AC-5）靠唯一入口保证。
 */
@Service
public class SourceTypeService {

    /** 审计动作：配置的五种变更各自留痕（AC-11）。 */
    public static final String AUDIT_CREATED = "SOURCE_TYPE_CREATED";
    public static final String AUDIT_UPDATED = "SOURCE_TYPE_UPDATED";
    public static final String AUDIT_DISABLED = "SOURCE_TYPE_DISABLED";
    public static final String AUDIT_ENABLED = "SOURCE_TYPE_ENABLED";
    public static final String AUDIT_DELETED = "SOURCE_TYPE_DELETED";

    /** 启用来源上限（AC-4）：到顶后新增/启用都被 409 挡住。 */
    public static final int LIMIT = 20;

    private static final int NAME_MIN = 2;
    private static final int NAME_MAX = 10;
    private static final int SORT_MAX = 99;

    private final SourceTypeDao dao;
    private final LoginAuditService audit;

    public SourceTypeService(SourceTypeDao dao, LoginAuditService audit) {
        this.dao = dao;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SourceTypeView> list() {
        return dao.listWithUsage();
    }

    /** 色板由后端提供，前端不硬编码 8 组色值。 */
    public List<SourcePaletteEntry> palette() {
        return SourcePalette.entries().stream()
                .map(entry -> new SourcePaletteEntry(entry.color(), entry.soft(), entry.border(), entry.name()))
                .toList();
    }

    @Transactional
    public SourceTypeView create(Actor actor, SourceTypeRequest req, HttpServletRequest http) {
        requireAdmin(actor);
        String name = normalizeName(req.name());
        String color = requireColor(req.color());
        SourceForm form = req.form();
        if (dao.nameExists(name, null)) {
            throw nameDuplicate();
        }

        // 上限判断与 INSERT 同事务：锁住全部启用行，两个并发新增不会都读到 19。
        if (dao.lockEnabledCodes().size() >= LIMIT) {
            throw new BusinessException(ErrorCode.SOURCE_LIMIT_REACHED, "来源类型最多 20 个");
        }
        int sort = req.sort() == null ? clampSort(dao.nextSort()) : clampSort(req.sort());

        long id;
        String code;
        try {
            id = dao.insert(name, form.name(), color, sort, placeholderCode());
            code = "S" + String.format(Locale.ROOT, "%05d", id);
            dao.updateCode(id, code);
        } catch (DuplicateKeyException ex) {
            throw nameDuplicate();
        }

        audit.record(actor.id(), AUDIT_CREATED, "SOURCE_TYPE", code,
                detail(name, form, color, true), http);
        SourceTypeConfig created = new SourceTypeConfig(id, code, name, form, color, sort, true);
        return SourceTypeDao.toView(created, 0);
    }

    @Transactional
    public SourceTypeView update(Actor actor, String code, SourceTypeRequest req, HttpServletRequest http) {
        requireAdmin(actor);
        SourceTypeConfig current = requireKnown(code);
        String name = normalizeName(req.name());
        String color = requireColor(req.color());
        SourceForm form = req.form();

        if (SourceCodes.isLocked(current.code()) && form != SourceForm.MEETING) {
            throw new BusinessException(ErrorCode.SOURCE_BUILTIN_PROTECTED, "内置来源不可修改形态");
        }
        if (dao.nameExists(name, current.code())) {
            throw nameDuplicate();
        }
        // 改形为会议型（AC-6）：来源下任何「没有会议」的任务都会撞 ck_tasks_source，
        // 所以前置条件是「原形态不是会议型」，而不是「原形态是说明型」——r4 加入项目型后，
        // PROJECT→MEETING 若沿用旧条件会绕过检查，把无会议的任务写库并撞 CHECK 变成 500。
        if (current.form() != SourceForm.MEETING && form == SourceForm.MEETING) {
            long blocking = dao.countTasksWithoutMeeting(current.code());
            if (blocking > 0) {
                throw new BusinessException(ErrorCode.SOURCE_FORM_CONFLICT,
                        "已有 " + blocking + " 条任务使用该来源且未绑定会议，无法改为会议型",
                        Map.of("taskCount", blocking));
            }
        }
        int sort = req.sort() == null ? current.sort() : clampSort(req.sort());
        dao.update(current.id(), name, form.name(), color, sort);

        audit.record(actor.id(), AUDIT_UPDATED, "SOURCE_TYPE", current.code(),
                detail(name, form, color, current.enabled()), http);
        SourceTypeConfig updated = new SourceTypeConfig(current.id(), current.code(), name, form, color,
                sort, current.enabled());
        return SourceTypeDao.toView(updated, dao.countTasksByCode(current.code()));
    }

    @Transactional
    public SourceTypeView setEnabled(Actor actor, String code, boolean enabled, HttpServletRequest http) {
        requireAdmin(actor);
        SourceTypeConfig current = requireKnown(code);
        if (SourceCodes.isLocked(current.code()) && !enabled) {
            throw new BusinessException(ErrorCode.SOURCE_BUILTIN_PROTECTED, "内置来源不可停用");
        }
        if (enabled && !current.enabled() && dao.lockEnabledCodes().size() >= LIMIT) {
            throw new BusinessException(ErrorCode.SOURCE_LIMIT_REACHED, "来源类型最多 20 个");
        }
        dao.setEnabled(current.code(), enabled);
        audit.record(actor.id(), enabled ? AUDIT_ENABLED : AUDIT_DISABLED, "SOURCE_TYPE", current.code(),
                "enabled=" + enabled, http);
        SourceTypeConfig updated = new SourceTypeConfig(current.id(), current.code(), current.name(),
                current.form(), current.color(), current.sort(), enabled);
        return SourceTypeDao.toView(updated, dao.countTasksByCode(current.code()));
    }

    @Transactional
    public void delete(Actor actor, String code, HttpServletRequest http) {
        requireAdmin(actor);
        SourceTypeConfig current = requireKnown(code);
        if (SourceCodes.isLocked(current.code())) {
            throw new BusinessException(ErrorCode.SOURCE_BUILTIN_PROTECTED, "内置来源不可删除");
        }
        long usage = dao.countTasksByCode(current.code());
        if (usage > 0) {
            throw new BusinessException(ErrorCode.SOURCE_IN_USE,
                    "该来源已被 " + usage + " 条任务使用，只能停用", Map.of("taskCount", usage));
        }
        dao.delete(current.id());
        audit.record(actor.id(), AUDIT_DELETED, "SOURCE_TYPE", current.code(),
                detail(current.name(), current.form(), current.color(), current.enabled()), http);
    }

    /** 编码必须存在（400 而不是 404）：筛选/建任务传错编码是请求参数错误。 */
    @Transactional(readOnly = true)
    public SourceTypeConfig requireKnown(String code) {
        return dao.findByCode(code).orElseThrow(() -> new BusinessException(
                ErrorCode.SOURCE_TYPE_INVALID, "来源类型不合法",
                List.of(new ApiError.FieldError("sourceType", "来源类型不合法", 0)), Map.of()));
    }

    /** 建任务只接受启用来源；历史任务仍可按停用来源筛选。 */
    @Transactional(readOnly = true)
    public SourceTypeConfig requireEnabled(String code) {
        SourceTypeConfig config = requireKnown(code);
        if (!config.enabled()) {
            throw new BusinessException(ErrorCode.SOURCE_TYPE_DISABLED, "该来源已停用，请重新选择",
                    List.of(new ApiError.FieldError("sourceType", "该来源已停用，请重新选择", 0)), Map.of());
        }
        return config;
    }

    /** 下拉与筛选的唯一选项源：返回全部来源（含停用），按 sort_no, id 排序。 */
    @Transactional(readOnly = true)
    public List<SourceTypeOption> options() {
        return list().stream()
                .map(view -> new SourceTypeOption(view.code(), view.name(), view.color(), view.softColor(),
                        view.borderColor(), view.form(), view.enabled(), view.sort()))
                .toList();
    }

    public boolean isLocked(String code) {
        return SourceCodes.isLocked(code);
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
            throw BusinessException.validation("name", "来源名称需 2–10 个字");
        }
        return name;
    }

    private String requireColor(String raw) {
        if (!SourcePalette.isKnown(raw)) {
            throw BusinessException.validation("color", "颜色不在可选色板内");
        }
        return SourcePalette.normalize(raw);
    }

    private int clampSort(long sort) {
        return (int) Math.min(Math.max(sort, 0), SORT_MAX);
    }

    private String placeholderCode() {
        return "TMP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private BusinessException nameDuplicate() {
        return new BusinessException(ErrorCode.SOURCE_NAME_DUPLICATE, "名称已存在，请换一个");
    }

    private String detail(String name, SourceForm form, String color, boolean enabled) {
        return "name=" + name + "; form=" + form.name() + "; color=" + color + "; enabled=" + enabled;
    }
}
