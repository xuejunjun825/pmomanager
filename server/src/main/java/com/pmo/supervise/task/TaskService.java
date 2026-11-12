package com.pmo.supervise.task;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Priority;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.common.TaskStatus;
import com.pmo.supervise.project.ProjectService;
import com.pmo.supervise.source.SourceForm;
import com.pmo.supervise.source.SourceTypeDao.SourceTypeConfig;
import com.pmo.supervise.source.SourceTypeService;
import com.pmo.supervise.task.dto.CreateTaskRequest;
import com.pmo.supervise.task.dto.TaskDetailRow;
import com.pmo.supervise.task.dto.TaskEventRow;
import com.pmo.supervise.task.dto.TaskPermissions;
import com.pmo.supervise.task.dto.UpdateTaskRequest;
import com.pmo.supervise.task.dto.UserRef;
import com.pmo.supervise.theme.ThemeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务创建 / 编辑 / 详情（TECHNICAL §4.5）。创建时派发人取当前登录人，
 * 来源字段不可修改；改人/改期走 TaskCommandService 的 reassign/reschedule 留痕。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskQueryDao queryDao;
    private final TaskAccessGuard guard;
    private final JdbcTemplate jdbc;
    private final SourceTypeService sourceTypes;
    private final ProjectService projects;
    private final ThemeService themes;

    public TaskService(TaskRepository taskRepository, TaskQueryDao queryDao,
                       TaskAccessGuard guard, JdbcTemplate jdbc,
                       SourceTypeService sourceTypes, ProjectService projects, ThemeService themes) {
        this.taskRepository = taskRepository;
        this.queryDao = queryDao;
        this.guard = guard;
        this.jdbc = jdbc;
        this.sourceTypes = sourceTypes;
        this.projects = projects;
        this.themes = themes;
    }

    @Transactional
    public TaskDetailRow create(Actor actor, CreateTaskRequest req, LocalDate today) {
        guard.requireAdmin(actor);
        validateOwner(req.ownerId());
        SourceTypeConfig source = validateSource(req);
        validateThemeSelectable(req.themeId());
        validatePlannedStart(req.plannedStartDate(), req.dueDate());

        // 按形态归一：每条任务只落一组来源字段，形态之外的一律写 null（库层 CHECK 兜底）。
        boolean meetingForm = source.form() == SourceForm.MEETING;
        boolean projectForm = source.form() == SourceForm.PROJECT;
        Task task = new Task();
        task.setTitle(req.title().trim());
        task.setOwnerId(req.ownerId());
        task.setDueDate(req.dueDate());
        task.setSourceType(source.code());
        task.setSourceMeetingId(meetingForm ? req.sourceMeetingId() : null);
        task.setSourceDecisionItemId(meetingForm ? req.sourceDecisionItemId() : null);
        task.setSourceNote(meetingForm || projectForm ? null : trimToNull(req.sourceNote()));
        task.setSourceProjectId(projectForm ? req.sourceProjectId() : null);
        task.setThemeId(req.themeId());
        task.setPlannedStartDate(req.plannedStartDate());
        task.setStatus(TaskStatus.PENDING);
        task.setDispatcherId(actor.id());
        task.setPriority(req.priority() == null ? Priority.MEDIUM : req.priority());
        task = taskRepository.saveAndFlush(task);

        return load(task.getId(), actor, today);
    }

    @Transactional
    public TaskDetailRow update(long id, Actor actor, UpdateTaskRequest req, LocalDate today) {
        Task task = require(id);
        guard.requireAdmin(actor);
        if (req.hasSourceFields()) {
            throw new BusinessException(ErrorCode.TASK_SOURCE_IMMUTABLE, "任务来源不可修改");
        }
        if (req.ownerId() != null || req.dueDate() != null) {
            Map<String, String> fields = new LinkedHashMap<>();
            if (req.ownerId() != null) {
                fields.put("ownerId", "请使用改派操作变更负责人");
            }
            if (req.dueDate() != null) {
                fields.put("dueDate", "请使用改期操作变更截止日期");
            }
            throw BusinessException.validation(fields);
        }
        if (req.title() != null) {
            if (req.title().isBlank()) {
                throw BusinessException.validation("title", "任务标题不能为空");
            }
            task.setTitle(req.title().trim());
        }
        if (req.priority() != null) {
            task.setPriority(req.priority());
        }
        // themeId：null = 不改；0 = 清空（未归主题）；其它 = 改选（必须是启用中的主题）。
        if (req.themeId() != null) {
            if (req.themeId() == 0L) {
                task.setThemeId(null);
            } else {
                validateThemeSelectable(req.themeId());
                task.setThemeId(req.themeId());
            }
        }
        // 计划开始：clearPlannedStart 显式清空（日期控件没有 0 语义），否则非空即改。
        if (req.clearPlannedStart()) {
            task.setPlannedStartDate(null);
        } else if (req.plannedStartDate() != null) {
            validatePlannedStart(req.plannedStartDate(), task.getDueDate());
            task.setPlannedStartDate(req.plannedStartDate());
        }
        taskRepository.saveAndFlush(task);
        return load(id, actor, today);
    }

    @Transactional(readOnly = true)
    public TaskDetailRow get(long id, Actor actor, LocalDate today) {
        Task task = require(id);
        guard.requireView(actor, task);
        return load(id, actor, today);
    }

    public Task require(long id) {
        return taskRepository.findById(id).orElseThrow(() -> BusinessException.notFound("任务不存在"));
    }

    public TaskDetailRow load(long id, Actor actor, LocalDate today) {
        Task task = require(id);
        TaskDetailRow row = queryDao.findDetail(id, today)
                .orElseThrow(() -> BusinessException.notFound("任务不存在"));
        List<TaskEventRow> events = queryDao.events(id);
        return row.with(events, permissions(task, actor));
    }

    /** 详情 permissions 由服务端计算，前端只按此渲染动作（AC-9 / §4.3）。 */
    public TaskPermissions permissions(Task task, Actor actor) {
        boolean unfinished = task.getStatus().isUnfinished();
        boolean owner = task.getOwnerId() == actor.id();
        boolean dispatcher = task.getDispatcherId() == actor.id();
        Map<String, String> reasons = new LinkedHashMap<>();

        boolean canStart = unfinished && task.getStatus() == TaskStatus.PENDING && (owner || actor.isAdmin());
        boolean canSubmit = unfinished
                && (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.IN_PROGRESS)
                && (owner || actor.isAdmin());
        boolean canAccept = task.getStatus() == TaskStatus.PENDING_ACCEPT && (actor.isAdmin() || dispatcher);
        boolean canReject = task.getStatus() == TaskStatus.PENDING_ACCEPT && (actor.isAdmin() || dispatcher);
        boolean canCancel = unfinished && actor.isAdmin();
        boolean canRestore = task.getStatus() == TaskStatus.CANCELLED && actor.isAdmin();
        boolean canForceComplete = unfinished && actor.isAdmin();
        boolean canReassign = unfinished && (actor.isAdmin() || actor.isExec());
        boolean canReschedule = unfinished && (actor.isAdmin() || actor.isExec());
        boolean canUrge = unfinished && (actor.isAdmin() || actor.isExec());

        if (!canStart && task.getStatus() != TaskStatus.PENDING && (owner || actor.isAdmin())) {
            reasons.put("start", "任务已开始或已结束");
        }
        if (!canSubmit && (owner || actor.isAdmin())) {
            reasons.put("submit", task.getStatus().isFinished() ? "任务已结束" : "当前状态不可提交");
        }
        if (!canAccept && (actor.isAdmin() || dispatcher)) {
            reasons.put("accept", task.getStatus().isFinished() ? "任务已结束" : "负责人尚未提交验收");
        }
        if (!canCancel && actor.isAdmin()) {
            reasons.put("cancel", "任务已结束");
        }
        if (!canReassign && (actor.isAdmin() || actor.isExec())) {
            reasons.put("reassign", "任务已结束");
        }
        if (!canReschedule && (actor.isAdmin() || actor.isExec())) {
            reasons.put("reschedule", "任务已结束");
        }
        if (!canUrge && (actor.isAdmin() || actor.isExec())) {
            reasons.put("urge", "任务已结束");
        }

        // 被 @ 的人拿到「只读可见 + 续写进度」视角：状态动作整组隐藏，接口侧仍 403 兜底（AC-8）。
        // 可查看谓词只在 TaskAccessGuard.canView 一处（D-03）：此处复用，不再复制一份「被 @」判断。
        boolean globalReader = actor.isAdmin() || actor.isExec();
        boolean canView = guard.canView(actor, task);
        boolean canAddProgress = canView;
        boolean mentionOnly = canView && !globalReader && !owner;

        return new TaskPermissions(canStart, canSubmit, canAccept, canReject, canCancel, canRestore,
                canForceComplete, canReassign, canReschedule, canUrge, canAddProgress, mentionOnly, reasons);
    }

    private void validateOwner(long ownerId) {
        List<String> rows = jdbc.query(
                "SELECT status FROM users WHERE id = ?", (rs, i) -> rs.getString(1), ownerId);
        if (rows.isEmpty()) {
            throw BusinessException.validation("ownerId", "负责人不存在");
        }
        if (!"ACTIVE".equals(rows.get(0))) {
            throw BusinessException.validation("ownerId", "负责人已停用");
        }
    }

    /**
     * 来源校验（TECHNICAL §3.4）：形态由配置驱动——MEETING 绑会议与决议项、PROJECT 绑项目、
     * NOTE 填自由说明。编码必须存在且启用；三组的互斥只在这一个方法判定，库层 CHECK 兜底。
     *
     * <p>项目形态的错误顺序写死为「① 结构冲突 400 → ② 缺选 422 → ③ 停用/已删 400」，
     * 两者同时出现时先报结构冲突，IT 用例才可稳定复现 PRD 的 400 文案。
     */
    private SourceTypeConfig validateSource(CreateTaskRequest req) {
        SourceTypeConfig config = sourceTypes.requireEnabled(req.sourceType());
        Map<String, String> fields = new LinkedHashMap<>();

        if (config.form() == SourceForm.MEETING) {
            if (req.sourceMeetingId() == null) {
                fields.put("sourceMeetingId", "请选择来源会议");
            }
            if (req.sourceDecisionItemId() == null) {
                fields.put("sourceDecisionItemId", "请选择关联决议项");
            }
            if (hasText(req.sourceNote())) {
                fields.put("sourceNote", "会议来源不能填写说明");
            }
            if (req.sourceProjectId() != null) {
                fields.put("sourceProjectId", "非项目来源不能关联项目");
            }
            if (!fields.isEmpty()) {
                throw BusinessException.validation(fields);
            }
        } else if (config.form() == SourceForm.PROJECT) {
            // ① 结构冲突优先（400）：项目形态不允许说明或会议字段
            if (hasText(req.sourceNote()) || req.sourceMeetingId() != null
                    || req.sourceDecisionItemId() != null) {
                List<ApiError.FieldError> errors = new ArrayList<>();
                if (hasText(req.sourceNote())) {
                    errors.add(new ApiError.FieldError("sourceNote", "项目来源不能填写说明", 0));
                }
                if (req.sourceMeetingId() != null) {
                    errors.add(new ApiError.FieldError("sourceMeetingId", "项目来源不能关联会议", 0));
                }
                if (req.sourceDecisionItemId() != null) {
                    errors.add(new ApiError.FieldError("sourceDecisionItemId", "项目来源不能关联会议", 0));
                }
                throw new BusinessException(ErrorCode.PROJECT_SOURCE_CONFLICT,
                        "项目来源不能填写说明或关联会议", errors, Map.of());
            }
            // ② 缺选（422）
            if (req.sourceProjectId() == null) {
                throw BusinessException.validation("sourceProjectId", "项目来源必须选择项目");
            }
            // ③ 停用/已删（400）：requireKnown → PROJECT_INVALID，禁用 → PROJECT_DISABLED
            projects.requireEnabled(req.sourceProjectId());
        } else { // NOTE
            if (!hasText(req.sourceNote())) {
                fields.put("sourceNote", "请填写来源说明，说明这条任务从哪来");
            }
            if (req.sourceProjectId() != null) {
                fields.put("sourceProjectId", "非项目来源不能关联项目");
            }
            if (req.sourceMeetingId() != null) {
                fields.put("sourceMeetingId", "非会议来源不能关联会议");
            }
            if (req.sourceDecisionItemId() != null) {
                fields.put("sourceDecisionItemId", "非会议来源不能关联决议项");
            }
            if (!fields.isEmpty()) {
                throw BusinessException.validation(fields);
            }
        }
        if (config.form() == SourceForm.MEETING) {
            List<Long> meetingIds = jdbc.query(
                    "SELECT id FROM meetings WHERE id = ?", (rs, i) -> rs.getLong(1), req.sourceMeetingId());
            if (meetingIds.isEmpty()) {
                throw BusinessException.validation("sourceMeetingId", "会议不存在");
            }
            List<Long> itemMeetings = jdbc.query(
                    "SELECT meeting_id FROM decision_items WHERE id = ?",
                    (rs, i) -> rs.getLong(1), req.sourceDecisionItemId());
            if (itemMeetings.isEmpty()) {
                throw BusinessException.validation("sourceDecisionItemId", "决议项不存在");
            }
            if (!itemMeetings.get(0).equals(req.sourceMeetingId())) {
                throw BusinessException.validation("sourceDecisionItemId", "决议项不属于所选会议");
            }
            if (taskRepository.existsBySourceDecisionItemId(req.sourceDecisionItemId())) {
                throw BusinessException.validation("sourceDecisionItemId", "该决议项已转任务");
            }
        }
        return config;
    }

    /**
     * 主题可选性（r7 / AC-9、AC-10）：只接受启用中的主题，判定与文案归 ThemeService
     * （422 + fieldErrors.themeId，前端回填并聚焦）。null = 未归主题，合法。
     */
    private void validateThemeSelectable(Long themeId) {
        themes.requireSelectable(themeId);
    }

    /** 计划开始不得晚于截止日期（r7 / AC-10）：同日合法，空值不校验。 */
    private static void validatePlannedStart(LocalDate plannedStartDate, LocalDate dueDate) {
        if (plannedStartDate != null && dueDate != null && plannedStartDate.isAfter(dueDate)) {
            throw BusinessException.validation("plannedStartDate", "计划开始不能晚于截止日期");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    public static UserRef ref(long id, String name) {
        return new UserRef(id, name, (Role) null);
    }
}
