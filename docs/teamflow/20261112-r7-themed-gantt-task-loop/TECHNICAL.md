# r7 技术方案：会议登记 · 任务闭环 · 主题化甘特

> 基线：`docs/teamflow/20261112-r7-themed-gantt-task-loop/PRD.md`（14 AC）、`DESIGN.md`（497 行）。
> 既有工程：Spring Boot 3.3.5 / Java 17 / MySQL 8 / Flyway V1–V6；Vue 3.5 + TS + Vite + Pinia。基线用例 server 248 / web 188。
> 本文只描述**增量与改动点**，未提及的文件与行为一律不动（状态机、验收权限、来源三态、项目模块语义均保持不变）。

---

## 0. 交付边界

| 项 | 结论 |
|---|---|
| 新增表 | `themes`（V7），`tasks.theme_id`、`tasks.planned_start_date` 两列 |
| 新增接口 | 主题 6 个、甘特 1 个、会议任务清单 1 个；其余为既有接口加字段 |
| 新增前端页面 | `/gantt`（仅 ADMIN/EXEC） |
| 新增依赖 | 无（甘特自研 SVG/CSS；不引图表库、不引日期库） |
| 状态机 | 不变：`PENDING → IN_PROGRESS → PENDING_ACCEPT → DONE`，`CANCELLED` 终态；`CANCELLED → PENDING` 仅恢复 |
| 回填 | 零回填。历史任务 `theme_id = NULL`、`planned_start_date = NULL`，甘特按「未归主题 + 按创建日推算」呈现 |
| 分支/提交 | main 直接开发，不切分支；收口用 `git commit -- <pathspec>` |

---

## 1. 架构总览

### 1.1 分层落位

后端沿用「Controller 薄 / Service 判权限与事务 / DAO 拼 SQL」：

```
theme/     ThemeController → ThemeService → ThemeDao            （主数据，照 Project 模块形状）
task/      TaskController  → TaskService   → TaskQueryDao       （增量：theme_id / planned_start_date / atRisk）
meeting/   MeetingController → MeetingService ─┬→ DecisionItemRepository
                                                └→ TaskQueryDao.byMeeting()   （新增只读复用）
gantt/     GanttController → GanttService → GanttDao            （复用 task.TaskQueryDao.selectBase() + TaskRowMapper）
```

`GanttDao` **不新建行映射器**：直接复用 `task.TaskQueryDao.selectBase()` 的列与 `task.TaskRowMapper`，只在末尾追加甘特自己的 `WHERE / ORDER BY / LIMIT`。甘特的分组（主题→项目）在 `GanttService` 里用 `LinkedHashMap` 完成，不写第二套 DTO 映射。代价是 gantt 包依赖 task 包（单向，可接受）；收益是「主题三色、逾期、风险」只有一条映射路径。

前端：

```
pages/GanttPage.vue
  ├── components/gantt/GanttChart.vue → GanttRow.vue → GanttBar.vue
  ├── components/gantt/GanttSummary.vue
  └── composables/useGanttWindow.ts（纯日期→像素） + useGanttView.ts（模块级会话状态）
components/ui/ThemeTag.vue           ← 主题唯一渲染点（任务表单、任务池、详情、甘特左列、设置页共用）
components/ui/UserMultiSelect.vue    ← 参会人多选（会议新建 + 会议编辑共用）
```

### 1.2 三条口径的单一真源

| 口径 | 唯一实现 | 禁止 |
|---|---|---|
| 逾期 | `TaskSql.OVERDUE`（SQL）+ `OverdueSpec.isOverdue/overdueDays`（Java） | 页面出现 `dueDate < today` 之类比较；甘特组件内零日期比较 |
| 风险 | `TaskSql.AT_RISK`（= 逾期 ∪ 待验收且 `submitted_at` ≤ 今天−3 天）+ `TaskRiskSpec` | 前端自算「待验收满 3 天」 |
| 主题筛选 | `task.ThemeFilterSql`（`id` / `none`） | 各 Service 内联手写 `t.theme_id = ?` |
| 主题渲染 | `ThemeTag.vue`（三色来自接口，不新增 CSS 令牌） | 页面里 `:style="{background: ...}"` 拼主题色 |
| 主题色板 | `source.SourcePalette.of(hex)`（8 组 hex→(color,soft,border)） | 新建主题色板；`ThemeTag` 再定义一套色值 |

`TaskRiskSpec.PENDING_ACCEPT_DAYS = 3`，`TaskSql.AT_RISK` 用字符串拼接引用该常量，保证 SQL 与 Java 不会各写一个 3。

### 1.3 关键请求流

**建会（AC-2，单事务）**

```
POST /api/meetings  {title, meetingDate, host, attendeeIds[], note?, items:[{description,ownerId,dueDate,deliveryNote?}]}
  MeetingService.create(@Transactional)
    1. requireAdmin
    2. validateAttendees(ACTIVE)                → 422 attendeeIds
    3. validateBatchItems()                      → 422 items[K].<field>（K 为 1 基行号）
    4. saveAndFlush(meeting) → saveAll(items)
    5. return MeetingDetail（含 items 与计数）
```

任何一条决议非法都在第 3 步抛错，会议与决议都不落库（"要么一次成功，要么什么都不留"）。

**甘特（AC-10/11/12，单请求）**

```
GET /api/gantt?from=2026-10-26&to=2027-01-18&includeDone=true&onlyRisk=false&ownerId=&theme=
  GanttService.load(actor, today, query)
    requireGlobalRead（ADMIN/EXEC；OWNER → 403）
    windowRows = GanttDao.query(..., limit=301)     ← 逾期/风险无视窗口
    windowTotal, inProgress, pendingAccept, done = GanttDao.count(...)
    overdue, risk = TaskQueryDao.counters(filter, today)   ← 全局口径（与看板同一个 counters）
    groups = 按 theme.sort → project.sort → 风险桶 分组
```

---

## 2. 数据模型与迁移

### 2.1 `server/src/main/resources/db/migration/V7__themes.sql`

```sql
CREATE TABLE themes (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name       VARCHAR(40)     NOT NULL,
    color      VARCHAR(16)     NOT NULL,
    sort_no    INT             NOT NULL DEFAULT 0,
    enabled    TINYINT(1)      NOT NULL DEFAULT 1,
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_theme_name (name),
    KEY idx_themes_sort (sort_no, id),
    CONSTRAINT ck_theme_sort CHECK (sort_no BETWEEN 0 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE tasks
    ADD COLUMN theme_id BIGINT UNSIGNED NULL AFTER source_project_id,
    ADD COLUMN planned_start_date DATE NULL AFTER due_date,
    ADD KEY idx_tasks_theme (theme_id),
    ADD KEY idx_tasks_planned_due (planned_start_date, due_date),
    ADD CONSTRAINT fk_tasks_theme FOREIGN KEY (theme_id) REFERENCES themes (id) ON DELETE RESTRICT;
```

### 2.2 字段与索引理由

| 字段/索引 | 语义 | 理由 |
|---|---|---|
| `themes.name` UNIQUE | 2–20 字，全表唯一 | 并发唯一性靠 `uk_theme_name`，业务校验只负责给出可读文案 |
| `themes.color` | 存 **hex**（8 个之一） | 与 `source_types.color` 同构，`SourcePalette.of()` 直接派生浅底/边框，前端零新令牌 |
| `themes.sort_no` | 0–99，甘特主行排序 | 与 `projects.sort_no` 同构，`ck_theme_sort` 兜底 |
| `themes.enabled` | 停用不等于删除 | 被引用时只能停用；停用后不可新选，历史展示保留 |
| `tasks.theme_id` | 可空，`ON DELETE RESTRICT` | 归档/删除语义由 Service 控制；DB 层不允许出现孤儿引用 |
| `tasks.planned_start_date` | 甘特条起点，可空 | 为空时用 `DATE(t.created_at)` 推算并标 `estimatedStart` |
| `idx_tasks_planned_due` | 窗口谓词 `planned_start <= to AND due >= from` | 覆盖窗口扫描；`due_date` 单独走既有索引 |
| `idx_tasks_theme` | 主题筛选与分组 | 供 `ThemeFilterSql` 与 `ORDER BY th.sort_no` 前置过滤 |

不新增 `ARCHIVED` 状态；不新增计划结束日期（条终点只认 `due_date`），避免与逾期口径分裂。

### 2.3 兼容

- `tasks` 新增列全部可空，V1–V6 不动，不回填、不写种子数据。
- `themes` 为空时：设置页显示空态；甘特全部任务落在「未归主题」行；任务表单主题下拉只有「不归属主题」。
- 唯一键冲突在 Service 转 `THEME_NAME_DUPLICATE`（409），不把 SQL 异常抛给前端。

---

## 3. 后端设计

### 3.1 主题主数据 `com.pmo.supervise.theme`

**文件**：`ThemeController.java`、`ThemeService.java`、`ThemeDao.java`、`dto/{ThemeRequest,ThemeView,ThemeOption}.java`。

`ThemeService` 常量与行为照 `ProjectService`：`LIMIT = 100`、`NAME_MIN = 2`、`NAME_MAX = 20`、`SORT_MAX = 99`；`lockAllIds()` 与 INSERT 同事务判上限；`DuplicateKeyException → nameDuplicate`；每次写操作 `audit.record(actor.id(), ACTION, "THEME", code, detail, http)`。

```java
public List<ThemeView> list();                       // 全员，含 usageCount
public List<ThemeOption> options();                  // 供 meta / 下拉，含停用
public List<SourcePalette.Entry> palette();          // 8 色，复用来源色板
public ThemeView create(Actor actor, ThemeRequest req);
public ThemeView update(long id, Actor actor, ThemeRequest req);
public ThemeView setEnabled(long id, Actor actor, boolean enabled);
public void delete(long id, Actor actor);
public ThemeRow requireKnown(long id);               // 404 NOT_FOUND「主题不存在」
public ThemeRow requireSelectable(Long id);          // null→null；不存在/停用→422 fieldErrors.themeId
```

接口：

| 方法 | 路径 | 权限 | 入参 | 返回 |
|---|---|---|---|---|
| GET | `/api/themes` | 全员 | — | `ThemeView[]`（含停用，按 sort,id） |
| GET | `/api/themes/palette` | 全员 | — | `{color,soft,border,name}[]` |
| POST | `/api/themes` | ADMIN | `ThemeRequest{name,color,sort?,enabled?}` | 201 `ThemeView` |
| PUT | `/api/themes/{id}` | ADMIN | 同上 | `ThemeView` |
| PATCH | `/api/themes/{id}/status` | ADMIN | `{enabled:boolean}` | `ThemeView` |
| DELETE | `/api/themes/{id}` | ADMIN | — | 204；`countTasks>0` → 409 `THEME_IN_USE` |

`ThemeView(id,name,color,softColor,borderColor,sort,enabled,usageCount)`；`ThemeOption(id,name,color,softColor,borderColor,sort,enabled)`。三色由 `SourcePalette.of(color)` 派生，接口一次给全，前端不再解析 hex。

### 3.2 任务域增量

**实体/DTO**

```java
// Task.java 追加
private Long themeId;
private LocalDate plannedStartDate;

// task/dto/ThemeRef.java（新增）
public record ThemeRef(long id, String name, String color, String softColor,
                       String borderColor, boolean enabled) {}

// TaskRow.java 末尾追加三个组件（record 组件顺序即构造顺序，仅两处构造点需要改）
..., LocalDateTime completedAt, ThemeRef theme, LocalDate plannedStartDate, boolean atRisk
```

`TaskRowMapper.mapRow`（唯一构造点）从 `th.id/th.name/th.color/th.enabled` 组装 `ThemeRef`，并用 `SourcePalette.of(color)` 补 `softColor/borderColor`；`at_risk` 由 SQL 计算，不在 Java 里二次判断。`TaskDetailRow.toRow()` 透传三个新字段。

`TaskCounters` 末尾追加 `long risk`（看板 DTO 不变，仍取前 6 项）。

**SQL 单点**

```java
// TaskSql.java
public static final String AT_RISK = "(" + OVERDUE
    + " OR (t.status = 'PENDING_ACCEPT' AND t.submitted_at IS NOT NULL"
    + " AND DATE(t.submitted_at) <= DATE_SUB(:today, INTERVAL "
    + TaskRiskSpec.PENDING_ACCEPT_DAYS + " DAY)))";
```

`TaskRiskSpec`（新增）：`static final int PENDING_ACCEPT_DAYS = 3`；`static LocalDate cutoff(LocalDate today)`；`static boolean isAtRisk(TaskStatus, LocalDate dueDate, LocalDateTime submittedAt, LocalDate today)`；`static boolean isAtRisk(TaskRow, LocalDate today)`。仅为单测与「会议任务区」复用，生产查询以 SQL 为准。

`TaskQueryDao.SELECT_BASE` 追加 `t.planned_start_date`、`th.id AS theme_id ... th.enabled AS theme_enabled`、`CASE WHEN <AT_RISK> THEN 1 ELSE 0 END AS at_risk`，并 `LEFT JOIN themes th ON th.id = t.theme_id`；`counters()` 追加 `SUM(CASE WHEN <AT_RISK> THEN 1 ELSE 0 END) AS risk`。`AT_RISK` 只引用既有 `:today` 参数，**不引入新命名参数**，所有走 `SELECT_BASE` 的查询无需改参数注入。

**新增 DAO 方法**（会议任务区复用）

```java
List<TaskRow> byMeeting(long meetingId, LocalDate today);   // WHERE t.source_meeting_id = :id ORDER BY t.due_date, t.id
```

**筛选**

```java
// task/ThemeFilterSql.java（新增，@Component，形状照 ProjectFilterSql）
public static final String UNASSIGNED = "none";
public void append(StringBuilder where, Map<String, Object> params, String raw);
// 空 → 不动 SQL；"none" → " AND t.theme_id IS NULL"；数字 → " AND t.theme_id = :themeId"；
// 其他 → BusinessException(ErrorCode.THEME_INVALID, "主题不合法")
```

`TaskFilter` 追加 `String theme`；`TaskQueryBuilder.build()` 在项目谓词后调用 `themeFilterSql.append()`。`counters()` 自然跟随，任务池默认计数不回归（theme 为空时 SQL 与今天逐字一致）。

**写路径**

- `CreateTaskRequest` 追加 `Long themeId`、`LocalDate plannedStartDate`。
- `UpdateTaskRequest` 追加 `Long themeId`（`null`=不改，`0`=清空）、`LocalDate plannedStartDate`（`null`=不改）、`boolean clearPlannedStart`（清空日期，日期控件没有 0 语义）。`hasSourceFields()` 不含新字段。
- `TaskService.create`：`themeService.requireSelectable(themeId)`；`plannedStartDate != null && plannedStartDate.isAfter(dueDate)` → 422 `plannedStartDate`「计划开始不能晚于截止日期」。
- `TaskService.update`：`themeId != null` → `0` 置空，否则 `requireSelectable`；`clearPlannedStart` → 置空；否则非空则校验 ≤ 当前 `dueDate`。
- `DecisionConversionService` 转任务时同样写入两列（见 3.3）。

### 3.3 会议域增量

**批量建会（AC-2）**

```java
// MeetingRequest.java 追加
List<@Valid DecisionItemRequest> items   // 可空/空数组，最多 50 条
```

`MeetingService.create` 增加第 3 步 `validateBatchItems(items)`：

- 逐条复用 `validateItem`，把 `ApiError.FieldError` 的 `field` 拼成 `items[{K}].{field}`（K 为 1 基行号），`index` 同时填 K；
- 全部合法后才 `save` 会议与决议；返回 `MeetingDetail`。
- 参会人仍是「只接受 ACTIVE」，错误字段 `attendeeIds`。

**会议任务清单（AC-4）**

| 方法 | 路径 | 权限 | 返回 |
|---|---|---|---|
| GET | `/api/meetings/{id}/tasks` | ADMIN | `TaskRow[]`（按 due_date 升序，最多 500） |

`MeetingService.tasks(id, actor, today)`：`requireAdmin` → `require(id)` → `taskQueryDao.byMeeting(id, today)`。不塞进 `MeetingDetail`：列表页不需要任务集合，避免每行都带 N 条任务。

会议详情页的 4 个计数由前端 `MeetingTaskProgress` 从该数组一次 `computed` 得出（已转=`tasks.length`，未完成/逾期/已完成按 `status`/`isOverdue`/`atRisk` 过滤），不再读 `taskCount/unfinishedCount`（这两个字段保留给列表页）。

**转任务挂主题（AC-9）**

`ConvertRequest` 扩为 `{List<Long> itemIds, Long themeId, LocalDate plannedStartDate}`：

- `themeService.requireSelectable(themeId)`；
- `plannedStartDate` 非空时须 ≤ 每个被转决议的 `dueDate`，否则 422 `plannedStartDate`；
- INSERT 增加 `theme_id, planned_start_date` 两列；
- 逐行覆盖不做（一次批量统一），前端在弹窗里说明「应用于本次全部 N 条」。

### 3.4 甘特聚合 `com.pmo.supervise.gantt`

**接口**

| 方法 | 路径 | 权限 | 参数 |
|---|---|---|---|
| GET | `/api/gantt` | ADMIN/EXEC | `from`(必填 ISO)、`to`(必填)、`ownerId?`、`includeDone?=true`、`onlyRisk?=false`、`theme?`(`id`/`none`) |

校验：`from <= to`、窗口 ≤ 400 天，否则 400 `VALIDATION_FAILED`。OWNER 直接 403（`FORBIDDEN`），前端路由守卫同时拦。

**响应**（`GanttView` 内嵌 record，一个文件装下整棵结构）

```java
public record GanttView(LocalDate today, LocalDate from, LocalDate to, boolean truncated,
                        int windowTotal, Summary summary, List<ThemeGroup> groups) {
    public record Summary(long overdue, long risk, long inProgress,
                          long pendingAccept, long done, long unassigned) {}
    public record ThemeGroup(Long themeId, String themeName, String color, String softColor,
                             String borderColor, boolean enabled, boolean unassigned,
                             long taskCount, long overdueCount, long riskCount,
                             List<ProjectGroup> projects) {}
    public record ProjectGroup(Long projectId, String projectName, boolean unspecified,
                               long taskCount, List<Task> tasks) {}
    public record Task(long id, String title, UserRef owner, String status, String statusLabel,
                       LocalDate startDate, LocalDate plannedStartDate, LocalDate dueDate,
                       boolean estimatedStart, boolean isOverdue, int overdueDays, boolean isAtRisk,
                       boolean pinnedStart, boolean pinnedEnd,
                       Long projectId, String projectName, String sourceLabel, String sourceColor) {}
}
```

**查询（`GanttDao`，复用 `TaskQueryDao.selectBase()`）**

```sql
SELECT <TaskQueryDao.selectBase() 的列 + th.sort_no/p.sort_no 所需 JOIN 已在其中>
FROM tasks t ... LEFT JOIN themes th ON th.id = t.theme_id LEFT JOIN projects p ...
WHERE 1 = 1
  <ownerId / theme 过滤（ThemeFilterSql）>
  AND (:includeDone = 1 OR t.status <> 'DONE')
  AND (:onlyRisk = 0 OR <AT_RISK>)
  AND ( <AT_RISK> OR ( COALESCE(t.planned_start_date, DATE(t.created_at)) <= :to
                       AND t.due_date >= :from ) )
ORDER BY (t.theme_id IS NULL) ASC, th.sort_no ASC, th.id ASC,
         (t.source_project_id IS NULL) ASC, p.sort_no ASC, p.id ASC,
         CASE WHEN <OVERDUE> THEN 0 WHEN <AT_RISK> THEN 1
              WHEN t.status = 'IN_PROGRESS' THEN 2 WHEN t.status = 'PENDING_ACCEPT' THEN 3
              WHEN t.status = 'PENDING' THEN 4 ELSE 5 END ASC,
         t.due_date ASC, t.id DESC
LIMIT :limit
```

- `LIMIT = 301`（多取 1 条探测截断），`size == 301` → `truncated = true` 并丢弃第 301 条；`windowTotal` 由同 `WHERE` 的 `COUNT(*)` 得到。
- `pinnedStart = startDate < from`，`pinnedEnd = dueDate > to`，在 Service 里按 ISO 日期比较一次；`startDate = plannedStartDate != null ? plannedStartDate : createdAt.toLocalDate()`。
- 排序把逾期放最前，截断时不会先丢逾期任务。
- `summary.overdue/risk` 来自 `TaskQueryDao.counters(owner+theme)`（与看板同一函数）；`inProgress/pendingAccept/done/unassigned` 来自窗口 `COUNT`，`unassigned` 只数 `theme_id IS NULL`。
- 分组：按 SQL 首次出现顺序建 `LinkedHashMap`，未归主题组放最后（SQL 已保证）；组内 `taskCount/overdueCount/riskCount` 累加。

**性能**：单请求 301 行 + 3 条聚合；窗口谓词走 `idx_tasks_planned_due`；`ORDER BY` 含 `th.sort_no/p.sort_no`（主题/项目表极小，filesort 可接受）。实测目标 1.5s 内（AC-12），在 T9 用演示库 `EXPLAIN` 留证。

### 3.5 元数据、权限、错误码

- `MetaOptions` 追加 `List<ThemeOption> themes`；`MetaService.load` 调 `themeService.options()`（全员可读，含停用，停用项前端置灰）。
- `ErrorCode` 追加：`THEME_INVALID(400)`、`THEME_DISABLED(400)`、`THEME_NAME_DUPLICATE(409)`、`THEME_LIMIT_REACHED(409)`、`THEME_IN_USE(409)`。
- `TaskAccessGuard` 不动；甘特沿用 `DashboardService` 的 `requireGlobalRead` 判定（ADMIN/EXEC）。

---

## 4. 前端设计

### 4.1 路由、导航、权限

- `router/index.ts` 新增 `{ path: '/gantt', name: 'gantt', component: page('GanttPage'), meta: { title: '甘特视图', section: '工作台', roles: ['ADMIN', 'EXEC'] } }`，放在 `/matrix` 之后。
- `utils/permissions.ts`：`NavIcon` 加 `'gantt'`；`NAV_GROUPS` 的「工作台」组加「甘特视图」（roles ADMIN/EXEC）；`PAGE_ACCESS` 加 `/gantt` 两条角色。
- `layout/SideNav.vue` 的 `ICONS` 加 `gantt`（16px、stroke 1.5，三条横线 + 两条竖线的时间轴图形）。

### 4.2 类型与 API

```ts
// types/api.ts 追加
export interface ThemeRow { id:number; name:string; color:string; softColor:string;
  borderColor:string; sort:number; enabled:boolean; usageCount:number }
export interface ThemeOptionRow { id:number; name:string; color:string; softColor:string;
  borderColor:string; sort:number; enabled:boolean }
export interface ThemeRef { id:number; name:string; color:string; softColor:string;
  borderColor:string; enabled:boolean }
export type GanttZoom = 'day' | 'week' | 'month'
export type GanttQuickFilter = 'overdue'|'risk'|'inProgress'|'pendingAccept'|'done'|''
export interface GanttTask { id:number; title:string; owner:UserRef; status:TaskStatus;
  statusLabel:string; startDate:string; plannedStartDate:string|null; dueDate:string;
  estimatedStart:boolean; isOverdue:boolean; overdueDays:number; isAtRisk:boolean;
  pinnedStart:boolean; pinnedEnd:boolean; projectId:number|null; projectName:string|null;
  sourceLabel:string; sourceColor:string }
export interface GanttProjectGroup { projectId:number|null; projectName:string|null;
  unspecified:boolean; taskCount:number; tasks:GanttTask[] }
export interface GanttThemeGroup { themeId:number|null; themeName:string; color:string;
  softColor:string; borderColor:string; enabled:boolean; unassigned:boolean;
  taskCount:number; overdueCount:number; riskCount:number; projects:GanttProjectGroup[] }
export interface GanttSummary { overdue:number; risk:number; inProgress:number;
  pendingAccept:number; done:number; unassigned:number }
export interface GanttView { today:string; from:string; to:string; truncated:boolean;
  windowTotal:number; summary:GanttSummary; groups:GanttThemeGroup[] }

// TaskRow 追加（可选，避免旧 spec 构造失败）
theme?: ThemeRef | null; plannedStartDate?: string | null; atRisk?: boolean
```

```ts
// api/themes.ts
export const themesApi = {
  list(): Promise<ThemeRow[]>,
  palette(): Promise<SourcePaletteEntry[]>,
  create(payload: { name:string; color:string; sort?:number; enabled?:boolean }): Promise<ThemeRow>,
  update(id:number, payload: { name:string; color:string; sort?:number; enabled?:boolean }): Promise<ThemeRow>,
  setEnabled(id:number, enabled:boolean): Promise<ThemeRow>,
  remove(id:number): Promise<void>,
}

// api/gantt.ts
export interface GanttQuery { from:string; to:string; ownerId?:number|''; includeDone:boolean;
  onlyRisk:boolean; theme?:string }
export const ganttApi = { load(q:GanttQuery): Promise<GanttView> }

// api/meetings.ts 追加
tasks(id:number): Promise<TaskRow[]>
convert(meetingId:number, payload:{ itemIds:number[]; themeId?:number|null; plannedStartDate?:string|null })
```

`api/tasks.ts` 的 `PoolFilters`/`TaskFilter` 透传 `theme`（与 `project` 同：`'<id>' | 'none' | ''`）。

### 4.3 组件契约（下游必须逐字实现）

| 组件 | props | emits / 暴露 |
|---|---|---|
| `ThemeTag.vue` | `{ name?:string\|null; color?:string\|null; softColor?:string\|null; borderColor?:string\|null; enabled?:boolean }` | 无；`name` 空则渲染 `—` |
| `UserMultiSelect.vue` | `{ modelValue:number[]; options:UserOption[]; placeholder?:string; invalid?:boolean; error?:string; disabled?:boolean }` | `update:modelValue[number[]]` |
| `ThemeConfigTable.vue` | `{ items:ThemeRow[]; loading?:boolean; error?:ApiError\|null }` | `refresh`；内部 `ThemeFormModal` |
| `ThemeFormModal.vue` | `{ open:boolean; row?:ThemeRow\|null; nextSort?:number }` | `saved[ThemeRow]`、`close` |
| `TaskStatusFlow.vue` | `{ status:TaskStatus; ownerName?:string; dispatcherName?:string }` | 无 |
| `MeetingTaskProgress.vue` | `{ items:TaskRow[]; decisionTotal:number; loading?:boolean; error?:ApiError\|null }` | `retry`、`open[id:number]` |
| `ConvertTasksModal.vue` | `{ open:boolean; selected:DecisionItemRow[]; themes:ThemeOptionRow[] }` | `close`、`converted[ConvertResult]` |
| `GanttSummary.vue` | `{ summary:GanttSummary; active:GanttQuickFilter; onlyRisk:boolean; showDone:boolean }` | `pick[GanttQuickFilter]`、`update:onlyRisk[boolean]` |
| `GanttChart.vue` | `{ groups:GanttThemeGroup[]; window:GanttWindow; collapsed:Set<string>; dayW:number; loading:boolean; error:ApiError\|null; truncated:boolean; windowTotal:number }` | `toggle[key:string]`、`open[id:number]` |
| `GanttRow.vue` | `{ kind:'theme'\|'project'\|'task'; item:GanttThemeGroup\|GanttProjectGroup\|GanttTask; depth:number; collapsed:boolean; hasChildren:boolean; dayW:number; window:GanttWindow }` | `toggle`、`open` |
| `GanttBar.vue` | `{ task:GanttTask; x:number; width:number; pinnedStart:boolean; pinnedEnd:boolean }` | `open` |

`AppSearchSelect.vue` 新增 `allowCustom?:boolean`（默认 false，既有调用零影响）：

- `allowCustom && query.trim() !== ''` 且无精确 label 匹配时，列表尾部追加一条 `{ label: 使用「${query}」作为主持人（非系统用户）, value: query, custom: true }`；
- 选中后 `update:modelValue` 发原始文本；
- 已选值不在 `options` 里时，触发按钮显示 `值 + （非系统用户）`，不清空、不报错。

`themeOptions.ts`（新增，形状照 `projectOptions.ts`）：

```ts
export function themeOptions(list: ThemeOptionRow[] | undefined | null,
  opts?: { allOption?: boolean; includeUnassigned?: boolean; includeDisabled?: boolean }): { label:string; value:string; disabled?:boolean }[]
export function themeView(theme: ThemeRef | null | undefined): { name:string; color:string; softColor:string; borderColor:string; enabled:boolean; unspecified:boolean }
```

下拉值统一为字符串：`'<id>' | 'none'`（`none` 复用 `UNASSIGNED_VALUE`），提交时转数字或 `0`。

### 4.4 甘特窗口与像素（纯函数，可单测）

`composables/useGanttWindow.ts`：

```ts
export interface GanttWindow { from:string; to:string; zoom:GanttZoom }
export function defaultWindow(zoom:GanttZoom, today:string): GanttWindow   // 日:今天-1..+12；周:本周一-2周..+10周；月:本月1日-1月..+5月
export function shiftWindow(win:GanttWindow, direction:-1|1): GanttWindow
export function windowDays(win:GanttWindow): number                        // day 14 / week 84 / month 182
export function dayWidth(win:GanttWindow, timelineWidth:number): number    // clamp(40, floor(w/days), 96)
export function barGeometry(task:GanttTask, win:GanttWindow, dayW:number): { x:number; width:number }
export function buildTicks(win:GanttWindow, dayW:number): { key:string; label:string; width:number; weekend:boolean; monthStart:boolean }[]
export function monthBands(win:GanttWindow, dayW:number): { label:string; width:number }[]
```

- 日期一律 `yyyy-MM-dd` 字符串，内部用 `Date.UTC(y,m,d)` 做天数运算，**不做时区换算**；`barGeometry` 内 `Math.max(10, ...)` 保证 1 天任务在月档可见。
- 该文件允许用 `today`（仅 `defaultWindow` 定位窗口），**不得**判断逾期；`components/gantt/*` 内不得出现 `dueDate` 与今天的比较（AC-7 可用 grep 断言）。

`composables/useGanttView.ts`（模块级单例，会话内保持）：

```ts
export function useGanttView(): {
  zoom: Ref<GanttZoom>; onlyRisk: Ref<boolean>; showDone: Ref<boolean>;
  filter: Ref<GanttQuickFilter>; collapsed: Set<string>;
  isCollapsed(key:string): boolean; toggle(key:string): void;
  toggleTheme(id:number): void; reset(): void
}
```

折叠键：`theme:{id}` / `project:{themeId}:{projectId}`。首次加载时 `> 12 个主题` 自动折叠「无风险且无逾期」的主题（写入 `collapsed`，用户操作后不再自动改动）。

### 4.5 页面改造点

| 文件 | 改动 |
|---|---|
| `web/src/pages/MeetingDetailPage.vue` | ①`:396-398` 摘要条删「已转任务 / 未完成」两项，保留决议数与归档标记；②决议表（`:403`）下插入 `<MeetingTaskProgress>`；③转任务按钮（`:594`）改为打开 `ConvertTasksModal`；④`taskLink()`（`:354`）保留（详情页仍按角色渲染）；⑤`onMounted` 读 `route.query.item`，滚动到 `[data-item]` 并高亮 1600ms；⑥编辑弹窗主持人换 `AppSearchSelect allowCustom`、参会人换 `UserMultiSelect` |
| `web/src/pages/TaskDetailPage.vue` | ①`<ol class="steps">`（`:313`）换成 `<TaskStatusFlow>`，删除 `stepIndex/stepState`（`:139`、`:152`）；②会议链接（`:355`）按 `auth.role === 'ADMIN'` 渲染 `<RouterLink>`，否则纯文本 + 「仅管理员可查看会议」；③来源区（`:330`）追加 `<ThemeTag>` 与计划开始一行 |
| `web/src/components/task/TaskFormDrawer.vue` | ①`FormState` 加 `themeId:string`、`plannedStartDate:string`；②props 加 `themes?:ThemeOptionRow[]`；③「计划」区新增主题下拉（`AppSelect`）+ 计划开始（`AppDatePicker`），截止日之前；④payload（`:496-505`）带 `themeId`（`none→0`）与 `plannedStartDate`/`clearPlannedStart`；⑤422 已由 `applyFieldErrors` 覆盖，新字段只需加进聚焦映射 |
| `web/src/pages/TaskPoolPage.vue` | ①`PoolQuery` 加 `theme`，`DEFAULTS` 加 `theme:''`；②把 `meta.themes` 传给筛选栏与表格；③行内主题保存：`taskApi.update(id,{themeId: value==='none'?0:Number(value)})`，失败回滚并按行显示错误 |
| `web/src/components/task/TaskFilterBar.vue` | `PoolFilters` 加 `theme`；props 加 `themes?:ThemeOptionRow[]`；项目筛选后加主题筛选（全宽 200，`includeUnassigned`） |
| `web/src/components/task/TaskTable.vue` | 列加 `theme`（宽 96，排在 `source` 后）；`#cell-theme` 插槽透传给页面（ADMIN 渲染 `AppSelect`，其他人 `ThemeTag`）；总宽 964+96=1060，容器 `overflow-x:auto` 不变 |
| `web/src/pages/MeetingsPage.vue` | ①新建弹窗主持人 `AppSearchSelect allowCustom`；②参会人 `UserMultiSelect`；③选中主持人若是系统用户则自动加入参会人（仅一次，手动移除后不再自动加回）；④弹窗内新增「决议事项（可留空）」子卡，0..N 行，行首 `#K`、`description/ownerId/dueDate`，行内删除；⑤提交 payload 带 `items`；⑥422 映射 `items[K].field` 到行内单元格并聚焦（K 从 1 起） |
| `web/src/pages/SettingsPage.vue` | 项目配置卡（`:466`）后新增「主题配置」卡 `<section v-if="isAdmin" id="themes">` + `ThemeConfigTable`，数据 `themesApi.list()`，保存后刷新 |
| `web/src/pages/GanttPage.vue` | 新页面：头部（标题/负责人筛选/仅看逾期与风险/显示已完成/今翻/缩放档/翻屏）+ `GanttSummary` + `GanttChart` + 图例 + 截断提示 + 空态 |

### 4.6 状态与竞态

- `GanttPage` 用 `seq` 序号丢弃过期响应（切档/翻屏/勾选都可能连点）；任何交互只发 **1** 个请求，不做前后端双算。
- 摘要 chip 点击：`pick(filter)` 先纠正互斥状态（`onlyRisk` 为真且点的是非逾期/风险 → 自动关 `onlyRisk`；`showDone=false` 点已完成 → 自动开 `showDone`），随后**本地**按 `status/isOverdue/isAtRisk` 过滤已返回的 300 条，**不额外请求**。若在当前窗口内 0 命中，按 direction 翻一屏（最多 3 次）后仍无 → 提示「当前时间窗内没有符合条件的任务」。
- 会议详情：`tasks` 与 `meeting` 并行加载，`Promise.allSettled`；任务区失败只让任务区显示重试，不阻塞决议编辑。
- 主题行内保存：乐观更新 + 失败回滚 + 行级错误（3s 自动消失），不做整表刷新。

---

## 5. 关键实现点与边界

1. **今天/昨天边界**：`today` 由服务端 `ShanghaiClock` 给出（既有注入），前端只展示；昨天到期任务 `isOverdue=true, overdueDays=1`；今天到期 `false, 0`；`DONE/CANCELLED` 恒 `false,0`。用 `TaskRiskSpecTest` + `OverdueSqlParityTest` 双向钉住 SQL 与 Java 一致。
2. **风险恒含逾期**：`AT_RISK` 的定义里已 `OR OVERDUE`，因此「仅看逾期与风险」只需一个谓词；风险数 ≥ 逾期数恒成立（可用测试断言）。
3. **逾期不被窗口裁剪**：窗口谓词写成 `<AT_RISK> OR (start<=to AND due>=from)`，逾期任务无论多老都会返回并 `pinnedStart=true`；左帽虚线 + 「逾期 N 天」Pill。右界外的风险条 `pinnedEnd=true`，右边界画红色三角夹标。
4. **300 截断**：`LIMIT 301` 探测；截断提示「窗口内共 N 条，已显示前 300 条，请缩窄时间范围或按负责人筛选」。排序把逾期置顶，截断不会先丢风险。
5. **计划开始晚于截止**：服务端 422 `plannedStartDate`；前端在日期控件旁直接提示，不提交。
6. **主题停用**：历史任务仍显示 `ThemeTag`（带「已停用」后缀）；新建/编辑/筛选下拉里停用项 `disabled`；甘特主行标题带后缀。
7. **主题删除**：`usageCount>0` → 409；设置页删除按钮禁用并给 tooltip「被 N 个任务引用，请改为停用」。
8. **未归主题**：甘特恒有最后一行「未归主题」（即使 0 条也显示在摘要覆盖率里）；筛选 `none` 与之对应。
9. **批量建会原子性**：任一决议非法 → 422 且会议不落库；`items` 上限 50，超过 422 `items`。
10. **会议归档**：转任务仍 409 `MEETING_ARCHIVED`（既有）；任务进度区只读。
11. **角色**：`/gantt` 路由守卫 + 接口双拦；OWNER 直接输入 URL 落到 403 页。任务详情的会议链接对 EXEC/OWNER 是纯文本。
12. **时区/日期格式**：接口一律 `yyyy-MM-dd`；前端 `buildTicks` 用 UTC 构造避免夏令时漂移；不使用 `dayjs`。
13. **空态**：无主题、无任务、窗口为空分别有空态文案与「扩大时间范围 / 去建任务」动作；加载失败给重试，不用空态冒充失败。
14. **性能**：甘特行 `v-memo`；300 行 × 3 层为上限；首屏只渲染可视区（`content-visibility:auto`），不引入虚拟滚动库。

---

## 6. 测试策略

### 6.1 服务端（248 → ≥256）

| 用例 | 覆盖 |
|---|---|
| `ThemeServiceTest` | 名称 2–20、唯一（大小写/空格归一）、上限 100（并发 INSERT 同事务）、被引用只能停用、删除 409、停用后 `requireSelectable` 422 |
| `ThemeApiTest` | GET 全员 200；POST/PUT/PATCH/DELETE 对 EXEC/OWNER 403；palette 8 条且与 `SourcePalette` 字面一致 |
| `ThemeFilterSqlTest` | 空/`none`/`id`/非法值四种分支；`id` 时 SQL 只多一个谓词 |
| `TaskRiskSpecTest` | 今天/昨天/已完成/已取消/待验收第 3 天/第 4 天；`TaskSql.AT_RISK` 含 `INTERVAL 3 DAY` |
| `TaskThemeTest` | 建任务/改任务挂主题；`themeId=0` 清空；停用主题 422；`plannedStartDate>dueDate` 422；任务池 `theme=none` 只回未归任务；counters 带 theme 不回归 |
| `MeetingBatchCreateTest` | 3 条决议中第 2 条缺负责人 → 422 `items[2].ownerId` 且会议表 0 行；全合法 → 会议+3 条同事务 |
| `MeetingTasksApiTest` | 会议任务清单只回本会议；已转 N 条 == 清单长度；EXEC 403 |
| `GanttServiceTest` | 窗口裁剪、逾期 pin、右界风险夹标、300 截断、主题/项目分组顺序、未归主题置底、`summary` 与 `counters` 相等、OWNER 403 |
| `GanttWindowBoundaryTest` | `from=to`、400 天上限、非法日期 400、空窗口 200 + 空数组 |
| `ConvertThemeTest` | 转任务写主题与计划开始；计划开始晚于任一截止 → 422 且 0 条落库 |

### 6.2 前端（188 → ≥202，新增 ≥14 条）

| spec | 断言 |
|---|---|
| `tests/gantt-window.spec.ts` | 三档默认窗口、`shiftWindow` 步长、`dayWidth` clamp 40/96、1 天条 min 10px、ticks 抽稀 |
| `tests/gantt-groups.spec.ts` | 未归主题置底、主题/项目折叠键、>12 主题默认折叠（无风险） |
| `tests/gantt-page.spec.ts` | 切档/翻屏/勾选各 1 请求；截断提示；空窗口空态；chip 点击本地筛选；`onlyRisk` 互斥纠正 |
| `tests/theme-tag.spec.ts` | 名称/停用后缀/空值、三色内联样式来自 props |
| `tests/theme-options.spec.ts` | `none` 选项、停用 disabled、`themeView` 兜底 |
| `tests/theme-config.spec.ts` | 行内排序 Enter/失焦/Esc、删除禁用、409 文案 |
| `tests/user-multi-select.spec.ts` | chips 增删、搜索、键盘、重复不叠加 |
| `tests/meeting-create-items.spec.ts` | 0 条可提交、`items[K].field` 回填到行、失败不清空、主持人自动进参会人一次 |
| `tests/meeting-task-progress.spec.ts` | 四个计数与 `items` 同源、空态、失败态重试、点击进任务 |
| `tests/task-status-flow.spec.ts` | 五步映射、CANCELLED 灰化、下一步提示只读 |
| `tests/task-detail-role-source.spec.ts` | ADMIN 有链接、EXEC/OWNER 纯文本、`?item` 高亮类 |
| `tests/task-form-theme.spec.ts` | 主题/计划开始提交、`none→0`、422 回填并聚焦、编辑回显 |
| `tests/task-pool-theme.spec.ts` | URL `theme` 同步、行内保存乐观更新与回滚、计数不回归 |
| `tests/app-search-select.spec.ts`（扩） | `allowCustom` 自定义项、历史值原样显示、默认模式零变化 |

### 6.3 验证命令

```bash
cd server && ./mvnw -q test
cd server && ./mvnw -q -DskipTests package
cd web && pnpm test && pnpm typecheck && pnpm build
bash scripts/smoke-api.sh
# 真机闭环（18081，AC-14）：ADMIN 建会→录决议→转任务→挂主题→甘特定位→改期→再验；证据存 .demo-shots/r7/
```

`grep -rn "dueDate" web/src/components/gantt web/src/pages/GanttPage.vue` 中不得出现与今天比较的表达式（AC-7 的机器化断言）。

---

## 7. 任务拆分

> 并行规则：`files` 不相交即可并行；`reads` 只读不占所有权。T1→T2→(T3∥T4)；T5→(T6∥T7∥T8)；T9 最后串行。

| ID | 任务 | files（own） | reads | 验收 |
|---|---|---|---|---|
| **T1** | 后端·V7 与主题主数据 | `server/src/main/resources/db/migration/V7__themes.sql`、`server/src/main/java/com/pmo/supervise/theme/**`、`common/ErrorCode.java`、`meta/MetaService.java`、`meta/dto/MetaOptions.java`、`theme` 3 个测试 | `project/ProjectService.java`、`source/SourcePalette.java`、`common/BusinessException.java` | 6 个接口通；单测全绿；meta 返回 themes |
| **T2** | 后端·任务域增量 | `task/Task.java`、`task/TaskSql.java`、`task/TaskRiskSpec.java`、`task/ThemeFilterSql.java`、`task/TaskQueryBuilder.java`、`task/TaskQueryDao.java`、`task/TaskRowMapper.java`、`task/TaskService.java`、`task/dto/{TaskRow,TaskDetailRow,TaskFilter,CreateTaskRequest,UpdateTaskRequest,TaskCounters,ThemeRef}.java`、3 个测试 | `task/TaskController.java`（不改签名）、`source/SourcePalette.java` | `at_risk/theme/planned_start_date` 出现在列表与详情；`theme=none` 可用；风险=逾期∪待验收≥3 天 |
| **T3** | 后端·会议批量建会与任务区 | `meeting/MeetingService.java`、`meeting/MeetingController.java`、`meeting/dto/MeetingRequest.java`、`meeting/dto/ConvertRequest.java`、`meeting/DecisionConversionService.java`、2 个测试 | `meeting/dto/DecisionItemRequest.java`、`task/TaskQueryDao.java`（byMeeting）、`theme/ThemeService.java` | 422 定位 `items[K].field` 且不落库；`GET /meetings/{id}/tasks` 只回本会议；转任务写主题 |
| **T4** | 后端·甘特聚合 | `gantt/GanttController.java`、`gantt/GanttService.java`、`gantt/GanttDao.java`、`gantt/dto/GanttQuery.java`、`gantt/dto/GanttView.java`、2 个测试 | `task/TaskQueryDao.java`（selectBase/counters）、`task/TaskRowMapper.java`、`theme/ThemeDao.java`、`project/ProjectDao.java` | 逾期 pin、风险夹标、300 截断、OWNER 403、summary 与 counters 相等 |
| **T5** | 前端·类型/主题设置/基础组件 | `web/src/types/api.ts`、`web/src/api/themes.ts`、`web/src/api/gantt.ts`、`web/src/utils/themeOptions.ts`、`web/src/components/ui/{ThemeTag,UserMultiSelect}.vue`、`web/src/components/settings/{ThemeConfigTable,ThemeFormModal}.vue`、`web/src/pages/SettingsPage.vue`、4 个 spec | `web/src/components/settings/ProjectConfigTable.vue`、`web/src/components/ui/{AppSearchSelect,SourceColorSwatches}.vue`、`web/src/api/projects.ts` | 设置页可增改停用删；409 文案；`ThemeTag` 三色来自 props |
| **T6** | 前端·任务三入口与来源展示 | `web/src/api/tasks.ts`、`web/src/components/task/{TaskFormDrawer,TaskFilterBar,TaskTable,TaskStatusFlow}.vue`、`web/src/pages/{TaskPoolPage,TaskDetailPage}.vue`、`web/src/components/ui/AppSearchSelect.vue`、4 个 spec | `web/src/types/api.ts`、`themeTag`、`themeOptions.ts` | 三入口可挂主题；行内改主题；五步条；角色化会议链接；`allowCustom` 不破坏既有 |
| **T7** | 前端·会议登记与任务进度 | `web/src/pages/{MeetingsPage,MeetingDetailPage}.vue`、`web/src/components/meeting/{MeetingTaskProgress,ConvertTasksModal}.vue`、`web/src/api/meetings.ts`、2 个 spec | `web/src/components/ui/{AppSearchSelect,UserMultiSelect}.vue`、`web/src/types/api.ts` | 批量决议 0..N、422 行内回填、进度区单一口径、转任务挂主题 |
| **T8** | 前端·甘特页 | `web/src/pages/GanttPage.vue`、`web/src/components/gantt/**`、`web/src/composables/{useGanttWindow,useGanttView}.ts`、`web/src/utils/permissions.ts`、`web/src/router/index.ts`、`web/src/layout/SideNav.vue`、3 个 spec | `web/src/types/api.ts`、`web/src/api/gantt.ts`、`web/src/stores/auth.ts` | 三档/翻屏/缩放；逾期贴左界；折叠会话保持；单请求；1.5s 内首屏 |
| **T9** | 收口·回归、真机、提交 | 无固定归属（只修回归） | 全部 | server ≥256、web ≥202、typecheck/build 通过；18081 真机闭环；`git commit -- server web docs/teamflow/20261112-r7-themed-gantt-task-loop docs/teamflow/memory.md` |

**T9 必须携带的工程约束（PRD §工程约束，不得丢失）**：

1. 全程 main，不切分支、不 rebase；
2. 提交用 pathspec：`git commit -m "feat: 主题化甘特与会议/任务闭环（runId=...）" -- server web docs/teamflow/20261112-r7-themed-gantt-task-loop docs/teamflow/memory.md`；
3. 不提交 `logs/`、`.demo-shots/`、`server/target`、`node_modules`、`dist`；
4. DDL 只走 `V7__themes.sql`，不改 V1–V6、不做数据回填；
5. 不新增依赖（package.json / pom.xml 零改动）；
6. 状态机与验收权限零改动（`TaskStatus`、`TaskAccessGuard`、`TaskCommandService` 不动）。

---

## 8. 与 DESIGN 的偏差与取舍

| 项 | DESIGN | 本方案 | 原因 |
|---|---|---|---|
| 甘特行映射 | 未指定 | 复用 `task.TaskRowMapper` + `TaskQueryDao.selectBase()` | 避免第二个「主题三色/逾期」映射器；gantt→task 单向依赖 |
| 主题色存储 | 未指定 | 存 hex，复用 `SourcePalette` | 与 `source_types.color` 同构，前端零新令牌；色板接口直接回 `name` |
| 转任务主题 | 挂主题 | 一次批量统一主题与计划开始，不做逐行覆盖 | `items` 是勾选集合，逐行覆盖需要二次弹窗；AC-9 只要求「可挂」 |
| 摘要计数与筛选 | 逾期/风险全局，其余窗口内 | 同上，且 chip 点击时自动纠正 `onlyRisk/showDone` 互斥 | 否则「数字在、图上无」，点击即筛失效 |
| 会议任务数据 | 未指定 | 新增 `GET /api/meetings/{id}/tasks` | 列表页不该背 N 条任务；详情页一次拿全 |
| 看板 | 6 项 KPI | 不新增风险 KPI | AC-16 冻结六项；AC-7 的「与看板相等」由同一 `counters` 保证逾期一致，风险另在甘特摘要 |

---

## 9. 交付与回写

- 产物：本文件 + `PRD.md` + `DESIGN.md`（同任务夹，不归档不升版）。
- 记忆：`docs/teamflow/memory.md` 已登记「主题单点 `ThemeFilterSql`/`ThemeTag`、甘特时间与风险口径」；本轮若发现新口径（如 `AT_RISK` 参数约定）在实现后补一条，不做 changelog。
- 证据：真机截图/EXPLAIN 结果落 `.demo-shots/r7/`（gitignored），结论写进 QA-REPORT，不往任务夹塞日志。
