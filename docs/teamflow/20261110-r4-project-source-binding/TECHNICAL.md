# r4 技术方案 · 项目来源绑定与项目配置

> **增量文档**：只写本轮新增/修订；未提及处沿用 r1–r3 任务夹与现有代码。
> **需求**：`docs/teamflow/20261110-r4-project-source-binding/PRD.md`；交互与视觉：同夹 `DESIGN.md`。
> **基线依赖**：r3（`web/src/utils/sourceOptions.ts` 唯一投影、`SourceTag` 唯一渲染点）、r2（来源配置、`SourceForm` 校验单点、错误码）、r1（`TaskSql.OVERDUE`、矩阵五列口径）。
> **取代**：`docs/teamflow/20261109-r2-source-config-progress/PRD.md`#AC-2（两形态 → 三形态）。
> **技术栈复核**：Spring Boot 3.3.5 / Java 17 / MySQL 8 / Flyway（磁盘实际 `V1`–`V5`，本轮新增 `V6`）/ Vue 3 + Vite + TypeScript + Pinia；不引入新依赖、不新增设计令牌。

---

## 0. 一句话方案与五条不变量

**一句话**：新增 `projects` 主数据（仅 ADMIN 维护）与 `tasks.source_project_id`；来源形态扩展为 `MEETING / PROJECT / NOTE`，服务端单点判定三态互斥；项目筛选与项目下拉各只有一个投影和一条 SQL 谓词，任务池 / 矩阵 / 看板共用。

| # | 不变量 | 结构性保证 |
|---|---|---|
| I1 | 「今天」唯一 | 继续只用 `ShanghaiClock.today()`，`TaskSql` 不动 |
| I2 | 逾期谓词唯一 | `TaskSql.OVERDUE` 不动，矩阵/看板/任务池继续引用同一常量 |
| I3 | 权限唯一 | 继续只用 `TaskAccessGuard`（任务）与 `Role` 判定（配置写）；不新增权限维度 |
| I4 | 来源形态校验唯一 | `TaskService.validateSource` 是唯一判定点，库层只兜结构互斥 |
| I5 | 项目过滤唯一 | 新 `ProjectFilterSql.append(...)` 是唯一 SQL 谓词来源，`TaskQueryBuilder` 与 `MatrixService` 都只能调它 |
| I6 | 零迁移 | 改形/升级都不改写任何 `tasks` 行，`source_note` 与 `source_project_id` 各自保留 |

**命名裁定（防「一词三义」翻车）**：

- 形态中文名统一「**需绑定项目**」（`SourceForm.PROJECT`），与内置来源编码 `PROJECT`（名称「项目任务」、形态「自由说明」）**同名不同义**；任何代码不得按编码 `'PROJECT'` 推断形态，只能读 `source_types.form`。
- 实体一律称 `Project`（表 `projects`），JSON 字段 `projectId / projectName / projectEnabled`。
- 筛选参数统一为 `project`（值 `<id>` 或 `none`），前端 URL 短键也是 `project`。

---

## 1. 数据模型与存储

### 1.1 迁移 `V6__projects.sql`（完整内容）

```sql
-- 项目主数据（TECHNICAL §1 / AC-1、AC-4、AC-8）
-- 名称全表唯一（含停用，库层大小写不敏感）；编码由业务生成（P%05d），库层只要求唯一。
CREATE TABLE projects (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code       VARCHAR(16)     NOT NULL,
  name       VARCHAR(40)     NOT NULL COMMENT '业务上限 2–20 字（码点）由服务端判定；列宽留余量避免截断 500',
  sort_no    INT             NOT NULL DEFAULT 0,
  enabled    TINYINT(1)      NOT NULL DEFAULT 1,
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_code (code),
  UNIQUE KEY uk_project_name (name),
  KEY idx_projects_sort (sort_no, id),
  CONSTRAINT ck_project_sort CHECK (sort_no BETWEEN 0 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 任务只增加一个可空列；不触碰任何既有行（AC-3 零迁移）。
ALTER TABLE tasks
  ADD COLUMN source_project_id BIGINT UNSIGNED NULL AFTER source_note,
  ADD KEY idx_tasks_source_project (source_project_id),
  ADD CONSTRAINT fk_tasks_source_project FOREIGN KEY (source_project_id)
    REFERENCES projects (id) ON UPDATE RESTRICT ON DELETE RESTRICT;

-- 三态互斥：恰好一组非空（会议两字段 / 说明 / 项目）。只兜结构，不绑定形态编码。
ALTER TABLE tasks DROP CHECK ck_tasks_source;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_source CHECK (
  (source_meeting_id IS NOT NULL AND source_decision_item_id IS NOT NULL
     AND source_note IS NULL AND source_project_id IS NULL)
  OR (source_meeting_id IS NULL AND source_decision_item_id IS NULL
     AND source_note IS NOT NULL AND source_project_id IS NULL)
  OR (source_meeting_id IS NULL AND source_decision_item_id IS NULL
     AND source_note IS NULL AND source_project_id IS NOT NULL)
);

-- 形态枚举扩三值（AC-2）。
ALTER TABLE source_types DROP CHECK ck_source_form;
ALTER TABLE source_types ADD CONSTRAINT ck_source_form
  CHECK (form IN ('MEETING', 'PROJECT', 'NOTE'));
```

**执行顺序强制**：建表 → 加列/外键 → 换 `ck_tasks_source` → 换 `ck_source_form`。MySQL 的 DDL 不在事务里，任何一步失败会让 Flyway 停在 failed 状态（需 `repair`）；因此脚本里不放任何 `UPDATE/DELETE`，把失败面压到最小。

### 1.2 字段与约束语义

| 对象 | 定义 | 语义 / 为什么 |
|---|---|---|
| `projects.code` | `P%05d`，服务端生成，不可改 | 与 `source_types.code` 同套路：先占位码 `TMP_<uuid8>`，拿到自增 id 后回写 |
| `projects.name` | 2–20 **码点**，全表唯一（含停用） | 库层 `uk_project_name` 用 `utf8mb4_0900_ai_ci`：大小写与全半角等价视为重名（比 PRD 更严，但不会出现「ABC/abc 两条」） |
| `projects.sort_no` | 0–99，越界按边界截断 | 与来源配置 `ck_source_sort` + `clampSort` 完全一致，前端不另立规则 |
| `projects.enabled` | 停用只影响「新建可选」，不影响历史展示/筛选 | 停用**不改** `tasks.source_project_id`（AC-4） |
| `tasks.source_project_id` | 可空 + `ON DELETE RESTRICT` | 库层保证「被引用项目删不掉」；历史任务为 NULL 是正常态，不是坏数据 |
| `ck_tasks_source` | 三组字段**恰好**一组非空 | 注意它**不引用** `source_type`：形态与字段的配对由服务端负责，改形因此不需要迁移数据 |

**为什么库层不校验 form 与字段的配对**：`task.source_type` 是配置编码，形态可被管理员改形（AC-6）。若 CHECK 写成 `source_type IN (...)` 或 join `source_types.form`，改形就必须改写历史行——正好是 PRD 反面目标 2。库层只保证「结构上不出现两类来源字段并存」，这是不可协商的底线。

### 1.3 编码生成、并发与上限

- **编码**：`ProjectService.create` 在 `@Transactional` 内 `insert(name, sort, enabled, "TMP_"+uuid8)` → `code = "P" + String.format(Locale.ROOT, "%05d", id)` → `updateCode(id, code)`；id 超 5 位时自然变长，不截断。
- **上限 200（含停用）**：与来源配置同法——`ProjectDao.lockAllIds()` = `SELECT id FROM projects FOR UPDATE`（同事务内），`size() >= 200` 即 409 `PROJECT_LIMIT_REACHED`。并发新增不会都读到 199。
- **重名**：先 `nameExists(name, excludeId)` 给可读 409；同时捕获 `DuplicateKeyException` 兜住并发（两条同名同时插入时库层只放一条）。
- **删除竞态**：先 `countTasks(id) > 0` → 409（带条数）；`delete` 若撞 FK（并发建任务），捕获 `DataIntegrityViolationException` 后重查条数并抛同一 409，不向用户暴露 500。

### 1.4 升级兼容与回滚

- **升级**：只建表、只加列、只放宽 CHECK（`('MEETING','NOTE')` → 三值），不改写 `tasks`、不新增内置来源、不改三条内置行的编码/名称/颜色/形态。历史 `NOTE` 任务满足新 CHECK 第二臂，`MEETING` 任务满足第一臂——迁移对既有数据是恒等变换。
- **回滚**（Flyway 无 down，按运维脚本记录）：先把 `source_types` 中 `form='PROJECT'` 的行改回 `NOTE`，再删 `ck_tasks_source` 重建两臂版本、删 `fk_tasks_source_project` 与列、最后 `DROP TABLE projects`。**有 `source_project_id` 非空的行时必须先清空该列**，否则重建两臂 CHECK 会失败。该脚本只写在本文档，不落 `db/migration`。

### 1.5 测试库与清理顺序

`IntegrationTestSupport.resetDatabase()` 在 `DELETE FROM tasks` 之后补 `DELETE FROM projects`（否则下一用例因 FK 残留失败）。顺序：`task_progress_mentions → task_progress → urge_records → task_events → tasks → projects → decision_items → …`。

---

## 2. HTTP 契约

### 2.1 端点总表

| 方法 | 路径 | 权限 | 变更 | 说明 |
|---|---|---|---|---|
| GET | `/api/projects` | ADMIN | 新增 | 配置页列表，含 `usageCount`（**唯一**返回引用数的接口） |
| POST | `/api/projects` | ADMIN | 新增 | 201 + `ProjectView` |
| PUT | `/api/projects/{id}` | ADMIN | 新增 | 改名 / 改排序 / 改启用（行内改动也走它） |
| PATCH | `/api/projects/{id}/status` | ADMIN | 新增 | `{enabled}`，行内开关 |
| DELETE | `/api/projects/{id}` | ADMIN | 新增 | 204；被引用 409 |
| GET | `/api/meta/options` | 登录 | 扩展 | 新增 `projects[]`（全员可读，仅 `id/name/enabled/sort`） |
| GET / POST | `/api/tasks` | 登录 / ADMIN | 扩展 | 行返回 `source.project*`；创建接受 `sourceProjectId`；筛选接受 `project` |
| GET | `/api/dashboard` | ADMIN/EXEC | 扩展 | 筛选接受 `project` |
| GET | `/api/matrix` | ADMIN/EXEC | 扩展 | 筛选接受 `project` |

`GET /api/projects` 只对 ADMIN 开放（含引用数、操作信息）；**其他角色读项目名的唯一入口是 `/api/meta/options`**（AC-8「读全员，仅 id/name/enabled/sort」）。

### 2.2 项目配置接口

`ProjectView`（列表/写入返回）：

```json
{ "id": 12, "code": "P00012", "name": "城市更新一期", "sort": 3, "enabled": true, "usageCount": 0 }
```

请求体：

```json
{ "name": "城市更新一期", "sort": 3, "enabled": true }
```

- `name`：必填；trim 后按码点 2–20，越界 `422 VALIDATION_FAILED` + `fieldErrors[{field:"name",message:"名称为 2–20 字"}]`。
- `sort`：可空；空 → 当前最大值 +1（封顶 99）；越界按 0/99 截断（与来源配置一致）。
- `enabled`：可空，默认 `true`（新建）。
- 重名 → `409 PROJECT_NAME_DUPLICATE`，message「名称已存在，请换一个」，`fieldErrors[0].field = "name"`（前端直接落到名称框）。
- 上限 → `409 PROJECT_LIMIT_REACHED`，message「项目最多 200 个」。
- 删除被引用 → `409 PROJECT_IN_USE`，message「该项目已被 N 条任务使用，只能停用」，`details: {taskCount: N}`。

### 2.3 元数据扩展

`GET /api/meta/options` 增加：

```json
"projects": [ { "id": 12, "name": "城市更新一期", "enabled": true, "sort": 3 } ]
```

- 返回**全部**项目（含停用），排序 `sort_no, id`；前端投影决定「启用在前 + 停用后缀」。
- 不含 `code`、不含 `usageCount`（AC-8 读侧最小字段）。
- 所有登录角色都得到同一份（项目不是权限维度，不做收窄）。

### 2.4 任务接口扩展

**创建** `POST /api/tasks` 新增 `sourceProjectId`（仅 `form=PROJECT` 时允许）：

| 情况 | 状态码 | code | fieldErrors |
|---|---|---|---|
| 项目形态缺选 | 422 | `VALIDATION_FAILED` | `sourceProjectId: 项目来源必须选择项目` |
| 选了停用项目 | 400 | `PROJECT_DISABLED` | — |
| 选了不存在/已删项目 | 400 | `PROJECT_INVALID` | — |
| 项目形态却带 `sourceNote`/会议字段 | 400 | `PROJECT_SOURCE_CONFLICT` | `sourceNote` / `sourceMeetingId` 各一条 |
| 会议/说明形态却带 `sourceProjectId` | 422 | `VALIDATION_FAILED` | `sourceProjectId: 非项目来源不能关联项目` |

**返回**（`TaskRow.source` / `TaskDetail.source`）追加四个字段：

```json
"source": {
  "type": "PRJ", "typeLabel": "工程项目", "form": "PROJECT",
  "meetingId": null, "meetingTitle": null, "decisionItemId": null, "note": null,
  "projectId": 12, "projectName": "城市更新一期", "projectEnabled": true
}
```

- `form` 取当前配置（`source_types.form`），前端据此区分「改形历史」（`form=PROJECT` 且 `projectId=null` → 「未指定项目」）。
- 历史 `NOTE` 任务：`projectId=null`、`form=NOTE` → 前端不渲染任何项目元素。
- 反向改形（`PROJECT→NOTE`）：`projectId` 仍有值 → 继续展示项目（AC-6）。

**筛选**：`GET /api/tasks?project=<id|none>`；`none` = 「未指定项目」；缺省 = 不筛。

### 2.5 三处筛选参数（同源）

`GET /api/tasks`、`GET /api/dashboard`、`GET /api/matrix` 都接受同名 `project`：

- `project=<数字>`：`t.source_project_id = :projectId`，且该 id 必须存在（含停用；已删/非法 → 400 `PROJECT_INVALID`，message「项目不合法」）。
- `project=none`：`t.source_project_id IS NULL AND t.source_type IN (SELECT s.code FROM source_types s WHERE s.form='PROJECT')`。
- 缺省/空串：不追加条件（与升级前结果逐行一致）。
- 参数名与前端 URL 短键一致（`project`），便于失败用例直接复现。

### 2.6 错误码（`common/ErrorCode.java` 追加 6 项）

| code | HTTP | 默认文案 | 触发 |
|---|---|---|---|
| `PROJECT_INVALID` | 400 | 项目不合法 | 未知/已删 id、`project` 非数字且非 `none` |
| `PROJECT_DISABLED` | 400 | 该项目已停用，请重新选择 | 建任务选了停用项目 |
| `PROJECT_NAME_DUPLICATE` | 409 | 名称已存在，请换一个 | 重名（含停用） |
| `PROJECT_LIMIT_REACHED` | 409 | 项目最多 200 个 | 第 201 个 |
| `PROJECT_IN_USE` | 409 | 该项目已被 N 条任务使用，只能停用 | 删除被引用项目 |
| `PROJECT_SOURCE_CONFLICT` | 400 | 项目来源不能填写说明或关联会议 | 项目形态携带异构字段 |

`JSON` 形状不变（`ApiError` + 可选 `fieldErrors`），前端 `toApiError` 无需改动。

### 2.7 权限与审计

- **读**：`/api/meta/options` 所有登录角色；`/api/projects` 仅 ADMIN（未登录 401、非 ADMIN 403）。
- **写**：仅 ADMIN，403 由 Controller/Service 双重把关（页面不渲染卡片只是显示层）。
- **审计**：复用 `LoginAuditService.record(actorId, action, "PROJECT", targetId, detail, http)`，`detail` 形如
  `name=旧名→新名; enabled=1→0; sort=3→5`（只写变更项；创建时为全量 `name=…; enabled=1; sort=…`）。
- 动作常量：`PROJECT_CREATED / PROJECT_UPDATED / PROJECT_ENABLED / PROJECT_DISABLED / PROJECT_DELETED`，与来源配置 `SOURCE_TYPE_*` 并存。

---

## 3. 服务端实现

### 3.1 新增包 `com.pmo.supervise.project`

| 文件 | 职责 | 精确公开签名 |
|---|---|---|
| `ProjectDao.java` | `projects` 原生 SQL 读写；计数与加锁同事务 | `record ProjectRow(long id, String code, String name, int sort, boolean enabled)`；`List<ProjectView> listWithUsage()`；`List<ProjectRow> listAll()`；`Optional<ProjectRow> findById(long id)`；`long insert(String name, int sort, boolean enabled, String placeholderCode)`；`void update(long id, String name, int sort, boolean enabled)`；`void updateCode(long id, String code)`；`void updateEnabled(long id, boolean enabled)`；`void delete(long id)`；`boolean nameExists(String name, Long excludeId)`；`long countTasks(long id)`；`List<Long> lockAllIds()`；`int nextSort()` |
| `ProjectService.java` | 护栏、编码、审计的唯一写入口 | `List<ProjectView> list()`；`List<ProjectOption> options()`；`ProjectView create(Actor actor, ProjectRequest req, HttpServletRequest http)`；`ProjectView update(Actor actor, long id, ProjectRequest req, HttpServletRequest http)`；`ProjectView setEnabled(Actor actor, long id, boolean enabled, HttpServletRequest http)`；`void delete(Actor actor, long id, HttpServletRequest http)`；`ProjectDao.ProjectRow requireKnown(long id)`；`ProjectDao.ProjectRow requireEnabled(long id)`；常量 `LIMIT=200`、`NAME_MIN=2`、`NAME_MAX=20`、`AUDIT_*` |
| `ProjectFilterSql.java` | **唯一**项目筛选谓词 | `void append(StringBuilder where, Map<String, Object> params, String raw)`；常量 `UNASSIGNED = "none"` |
| `ProjectController.java` | 五个端点，只做参数与权限入口 | `List<ProjectView> list(Actor)`；`ProjectView create(@Valid @RequestBody ProjectRequest, Actor, HttpServletRequest)`；`ProjectView update(@PathVariable long id, …)`；`ProjectView setEnabled(@PathVariable long id, @RequestBody ProjectStatusRequest, Actor, HttpServletRequest)`；`void delete(@PathVariable long id, Actor, HttpServletRequest)` |
| `dto/ProjectView.java` | 列表/写入返回 | `record ProjectView(long id, String code, String name, int sort, boolean enabled, long usageCount)` |
| `dto/ProjectOption.java` | meta 下发 | `record ProjectOption(long id, String name, boolean enabled, int sort)` |
| `dto/ProjectRequest.java` | 写入体 | `record ProjectRequest(@NotBlank String name, Integer sort, Boolean enabled)`；业务长度在 Service 按码点判定 |
| `dto/ProjectStatusRequest.java` | 启停体 | `record ProjectStatusRequest(boolean enabled)` |

`ProjectController` 照抄 `SourceTypeController` 的形状：`@RequestMapping("/api/projects")`、`GET` 列表在 Controller 内 `requireAdmin(actor)`，写操作 Service 内再判一次。

### 3.2 改动文件与改动点

| 文件 | 改动 |
|---|---|
| `source/SourceForm.java` | 新增 `PROJECT("需绑定项目")`（枚举顺序 `MEETING, PROJECT, NOTE`，`of()` 不变） |
| `source/SourceTypeService.java` | 改形护栏的**前置条件放宽**：`if (current.form() == SourceForm.NOTE && form == SourceForm.MEETING)` → `if (current.form() != SourceForm.MEETING && form == SourceForm.MEETING)`。否则「项目型 → 会议型」会跳过 `countTasksWithoutMeeting` 检查，把无会议无说明的任务写进库并撞 CHECK 变 500 |
| `task/Task.java` | 新增 `@Column(name = "source_project_id") private Long sourceProjectId;` + getter/setter（放在 `sourceNote` 之后） |
| `task/dto/CreateTaskRequest.java` | 末尾追加 `Long sourceProjectId`（不破坏既有 JSON 客户端） |
| `task/dto/UpdateTaskRequest.java` | `hasSourceFields()` 追加 `|| sourceProjectId != null`（来源含项目不可改） |
| `task/TaskService.java` | `validateSource` 扩三态（见 §3.4）；`create` 写入 `task.setSourceProjectId(...)`；注入 `ProjectService` |
| `task/dto/TaskSource.java` | 追加 `SourceForm form, Long projectId, String projectName, Boolean projectEnabled`（4 个组件） |
| `task/TaskRowMapper.java` | `source(rs)` 读取新列；`projectEnabled` 为 NULL 时返回 `null`（不能用 `getBoolean` 变 false） |
| `task/TaskQueryDao.java` | `SELECT_BASE` 增加 `st.form AS source_form, t.source_project_id, p.name AS project_name, p.enabled AS project_enabled` 与 `LEFT JOIN projects p ON p.id = t.source_project_id`（`COUNT_BASE`/`counters` 不加 join） |
| `task/TaskQueryBuilder.java` | 构造器追加 `ProjectFilterSql`；在 `sourceType` 之后调用 `projectFilter.append(where, params, f.getProject())` |
| `task/dto/TaskFilter.java` | 新增 `private String project;` + getter/setter |
| `dashboard/dto/MatrixFilter.java` | 新增 `private String project;` + getter/setter |
| `dashboard/MatrixService.java` | 构造器追加 `ProjectFilterSql`；`load(filter, today)` 在 `sourceType` 之后 `projectFilter.append(sql, params, safeFilter.getProject())` |
| `meta/MetaService.java` | 注入 `ProjectService`，`load(actor)` 里 `List<ProjectOption> projects = projectService.options()` |
| `meta/dto/MetaOptions.java` | record 追加 `List<ProjectOption> projects`（放在 `sourceTypes` 之后） |
| `common/ErrorCode.java` | 追加 §2.6 的 6 个枚举值（沿用 `(HttpStatus, defaultMessage)` 构造） |

**构造器变更的编译影响（必须一起改，否则构建红）**：

- `TaskSource`：`TaskRowMapper.source`、`server/src/test/.../task/TaskJsonTest.java`（两处）。
- `CreateTaskRequest`：`task/TaskServiceIT.java`（8 处）、`task/TaskSourceValidationIT.java`（helper 1 处）。
- `TaskQueryBuilder`：`task/TaskQueryBuilderTest.java`（`new TaskQueryBuilder(sourceTypes)` → 追加 `new ProjectFilterSql(new ProjectService(null, null) { … })` 或 Mockito mock；空白 project 分支不触库）。
- `MetaOptions`：仅 `MetaService` 构造。

### 3.3 关键实现：项目过滤的唯一谓词

```java
// project/ProjectFilterSql.java —— 任务池、看板、矩阵、焦点列表共用这一份 SQL
@Component
public class ProjectFilterSql {

    public static final String UNASSIGNED = "none";

    /** 「改形历史」：来源形态已是项目型，但这条任务没有项目（AC-6）。子查询避免给三个读模型各加 join。 */
    private static final String UNASSIGNED_CONDITION =
            " AND t.source_project_id IS NULL"
            + " AND t.source_type IN (SELECT s.code FROM source_types s WHERE s.form = 'PROJECT')";

    private final ProjectService projects;

    public ProjectFilterSql(ProjectService projects) { this.projects = projects; }

    /**
     * 追加项目筛选条件；raw 为空则完全不动 SQL（保证「不带参数 = 升级前结果」）。
     * 非法/已删 id 抛 PROJECT_INVALID(400)，停用项目允许筛选（AC-4）。
     */
    public void append(StringBuilder where, Map<String, Object> params, String raw) {
        if (raw == null || raw.isBlank()) return;
        String value = raw.trim();
        if (UNASSIGNED.equals(value)) {
            where.append(UNASSIGNED_CONDITION);
            return;
        }
        long id;
        try {
            id = Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.PROJECT_INVALID, "项目不合法");
        }
        if (id <= 0) throw new BusinessException(ErrorCode.PROJECT_INVALID, "项目不合法");
        ProjectDao.ProjectRow project = projects.requireKnown(id);   // 不存在 → PROJECT_INVALID
        where.append(" AND t.source_project_id = :projectId");
        params.put("projectId", project.id());
    }
}
```

调用顺序固定为 `status → ownerId → sourceType → project → meetingId → overdue → keyword`，让 `TaskQueryBuilderTest` 的 WHERE 断言可逐条钉住。

### 3.4 关键实现：`validateSource` 三态

```java
private SourceTypeConfig validateSource(CreateTaskRequest req) {
    SourceTypeConfig config = sourceTypes.requireEnabled(req.sourceType());  // 停用来源 400
    Map<String, String> fields = new LinkedHashMap<>();

    if (config.form() == SourceForm.MEETING) {
        // 与 r2 完全一致：两字段必填、禁说明、禁项目
        if (req.sourceMeetingId() == null) fields.put("sourceMeetingId", "请选择来源会议");
        if (req.sourceDecisionItemId() == null) fields.put("sourceDecisionItemId", "请选择关联决议项");
        if (hasText(req.sourceNote())) fields.put("sourceNote", "会议来源不能填写说明");
        if (req.sourceProjectId() != null) fields.put("sourceProjectId", "非项目来源不能关联项目");
        if (!fields.isEmpty()) throw BusinessException.validation(fields);
        // …会议存在、决议项属于该会议、未重复转任务（原逻辑不动）
    } else if (config.form() == SourceForm.PROJECT) {
        // ① 结构冲突优先（400）：项目形态不允许说明或会议字段
        if (hasText(req.sourceNote()) || req.sourceMeetingId() != null
                || req.sourceDecisionItemId() != null) {
            List<ApiError.FieldError> errors = new ArrayList<>();
            if (hasText(req.sourceNote()))
                errors.add(new ApiError.FieldError("sourceNote", "项目来源不能填写说明"));
            if (req.sourceMeetingId() != null)
                errors.add(new ApiError.FieldError("sourceMeetingId", "项目来源不能关联会议"));
            if (req.sourceDecisionItemId() != null)
                errors.add(new ApiError.FieldError("sourceDecisionItemId", "项目来源不能关联会议"));
            throw new BusinessException(ErrorCode.PROJECT_SOURCE_CONFLICT,
                    "项目来源不能填写说明或关联会议", errors, Map.of());
        }
        // ② 缺选（422）
        if (req.sourceProjectId() == null) {
            throw BusinessException.validation("sourceProjectId", "项目来源必须选择项目");
        }
        // ③ 停用/已删（400）
        projects.requireEnabled(req.sourceProjectId());
    } else { // NOTE
        if (!hasText(req.sourceNote())) fields.put("sourceNote", "请填写来源说明，说明这条任务从哪来");
        if (req.sourceProjectId() != null) fields.put("sourceProjectId", "非项目来源不能关联项目");
        // 会议字段互斥规则保持 r2
        if (!fields.isEmpty()) throw BusinessException.validation(fields);
    }
    return config;
}
```

`create` 里写入字段时按形态归一：

```java
boolean projectForm = config.form() == SourceForm.PROJECT;
task.setSourceMeetingId(projectForm || config.form() == SourceForm.NOTE ? null : req.sourceMeetingId());
task.setSourceDecisionItemId(...同上...);
task.setSourceNote(projectForm ? null : trimToNull(req.sourceNote()));
task.setSourceProjectId(projectForm ? req.sourceProjectId() : null);
```

> 为什么「结构冲突」先于「缺选」：两者同时出现时若先报 422，测试无法稳定复现 PRD 的 400 文案。顺序写死在此，IT 用例按此断言。

### 3.5 关键实现：删除/停用护栏

- `delete`：`requireAdmin` → `requireKnown(id)` → `countTasks` → `>0` 抛 409（message 含条数）→ `dao.delete`。
- 停用：`setEnabled(false)` 只改 `projects.enabled`，**不触碰** `tasks.source_project_id`；历史任务读模型照旧 join 到名称与 `enabled=false`，前端加「（已停用）」。
- 重新启用：无需归位逻辑，前端投影按 `enabled + sort` 重排（`projectOptions` 唯一实现）。
- 建任务只认启用：`requireEnabled` 先 `requireKnown` 再判 `enabled`，两次查询同一行（单实例内网，允许各查一次；不额外加锁，避免和改形互锁）。

### 3.6 关键实现：读模型与 meta

- `SELECT_BASE` 是任务行/详情/焦点列表的**唯一**列集；项目名与启用态只在这里 join 一次，三处页面拿到的是同一形状（与 r3 「来源 join 只写一次」同一原则）。
- `TaskRowMapper.source(rs)` 的兜底：`source_form` 为 NULL（配置行缺失，理论上 FK 不允许）→ `form=null`；`project_id` 为 NULL → `projectName/projectEnabled` 均为 NULL，前端据此不渲染。
- `MetaService.load(actor)` 中 `projects` 不做角色收窄（项目不是权限维度），但也不下发 `code/usageCount`。

### 3.7 本轮明确不做（防回归）

- 不改 `TaskSql`、`TaskStatus`、`TaskAccessGuard`、`TaskCommandService`、进度/@、通知、日报/周报、`ScanJob`。
- 不改会议侧（`MeetingService` / 决议项转任务仍按 `MEETING` 形态走，走的是 `SourceCodes.MEETING` 锁定逻辑）。
- 不改 `SourceTypeTable.vue`（形态列读 `SOURCE_FORM_LABELS[row.form]`，加键即显示）。
- 不给项目加详情页/成员/进度/层级/颜色/图标/导入导出。

---

## 4. 前端实现

### 4.1 类型契约（`web/src/types`）

```ts
// enums.ts
export type SourceForm = 'MEETING' | 'PROJECT' | 'NOTE'
export const SOURCE_FORM_LABELS: Record<SourceForm, string> = {
  MEETING: '需绑定会议',
  PROJECT: '需绑定项目',
  NOTE: '自由说明',
}

// api.ts
/** meta 下发的项目（全员可读，最小字段） */
export interface ProjectOptionRow { id: number; name: string; enabled: boolean; sort: number }
/** 设置页表格行（仅 ADMIN 接口返回） */
export interface ProjectRow { id: number; code: string; name: string; sort: number; enabled: boolean; usageCount: number }
export interface ProjectPayload { name: string; sort?: number | null; enabled?: boolean }

export interface MetaOptions { /* …原字段… */ projects: ProjectOptionRow[] }

export interface TaskSource {
  /* …原字段… */
  form?: SourceForm | null
  projectId?: number | null
  projectName?: string | null
  projectEnabled?: boolean | null
}

export interface TaskFilter { /* …原字段… */ project?: string }
export interface CreateTaskRequest { /* …原字段… */ sourceProjectId?: number | null }
```

`MatrixQuery` / `DashboardQuery` 各加 `project?: string`。

### 4.2 唯一投影 `web/src/utils/projectOptions.ts`

```ts
import type { ProjectOptionRow, TaskSource } from '@/types/api'

export interface ProjectOption { label: string; value: string }

export interface ProjectOptionsConfig {
  /** 首位插入「全部项目」（筛选场景，默认 true） */
  allOption?: boolean
  /** 保留停用项目并加「（已停用）」（筛选场景，默认 true） */
  includeDisabled?: boolean
  /** 末尾追加「未指定项目」（只允许筛选与改形历史，默认 false） */
  includeUnassigned?: boolean
}

export const UNASSIGNED_VALUE = 'none'
export const DISABLED_SUFFIX = '（已停用）'
const UNASSIGNED_LABEL = '未指定项目'

/** 唯一比较器：启用在前 → sort 升序 → id 升序 */
function byEnabledThenSort(a: ProjectOptionRow, b: ProjectOptionRow): number

export function projectOptions(
  projects: ProjectOptionRow[] | null | undefined,
  config: ProjectOptionsConfig = {},
): ProjectOption[]

/** 任务来源的「项目位」展示决策：null = 不渲染（唯一判定点，表格/详情/看板共用） */
export interface ProjectView { name: string; enabled: boolean; unspecified: boolean }
export function projectView(source: TaskSource | null | undefined): ProjectView | null
```

语义钉死：

| 输入 | 输出 |
|---|---|
| `projectId != null` | `{ name: projectName ?? '', enabled: projectEnabled !== false, unspecified: false }` |
| `projectId == null && form === 'PROJECT'` | `{ name: '', enabled: true, unspecified: true }` |
| 其他（NOTE/MEETING/无 source/form 缺失） | `null` |

- `projectOptions` 绝不就地排序：先 `slice()`/`filter()` 复制；停用项加 `DISABLED_SUFFIX`；`allOption` 决策后插 `{label:'全部项目', value:''}`；`includeUnassigned` 时追加 `{label:'未指定项目', value:'none'}`。
- 任何选项都**不带编码**（`P00012` 永不出现在下拉/标签里）。
- 抽屉传 `{ allOption: false, includeDisabled: false }`；三处筛选传 `{ allOption: true, includeDisabled: true, includeUnassigned: true }`。
- 命名别名照 r3 教训：页面内若已有同名 `const`，一律 `import { projectOptions as buildProjectOptions }`（否则 TS7022/TS7024 + 运行期把 computed 当函数调）。

### 4.3 组件

**`web/src/components/ui/ProjectTag.vue`**

- props：`name?: string | null`、`enabled?: boolean`、`unspecified?: boolean`。
- 渲染：`unspecified` → 「未指定项目」+ 虚线边框；否则名称 + `enabled === false ? '（已停用）' : ''`；`!name && !unspecified` → 根节点 `v-if` 不渲染（绝不产生空标签）。
- 样式：`--c-line-soft` 底、`--c-ink-600` 字、`--c-line` 边、`--r-sm` 圆角；**不进来源 8 色板**，不用 `opacity` 表达停用（低对比度不可读）。
- 用法（模板唯一写法）：`<ProjectTag v-if="projectView(row.source)" v-bind="projectView(row.source) ?? {}" />`。

**`web/src/components/ui/AppSearchSelect.vue`**（新，仅项目场景使用）

| 项 | 约定 |
|---|---|
| props | `modelValue?: string | null`、`options: Array<{label:string;value:string}>`、`label`、`placeholder`、`size?:'compact'|'default'|'large'`、`disabled`、`invalid`、`error`、`hint`、`required`、`id`、`emptyText` |
| emits | `update:modelValue`(string)、`change`(string) |
| 结构 | 触发器按钮 `role="combobox"` + `aria-expanded/aria-controls/aria-activedescendant`；浮层 `role="listbox"`，每项 `role="option"` + `aria-selected` + `aria-disabled` |
| 搜索 | 浮层内 `type="search"`，`trim()` 后**名称大小写不敏感子串**匹配；不做拼音、不做首字母 |
| 键盘 | `↑↓` 移动、`Home/End`、`Enter` 选中、`Esc` 关闭并还原、`Tab` 关闭并提交当前高亮 |
| 关闭 | 点击外部 / Esc / 选中；`disabled` 时不展开 |
| 几何 | 浮层 `width: min(320px, max(240px, 100%))`、`z-index: var(--z-dropdown)`、`max-height: 296px` 滚动 |
| 底部 | `共 N 个项目 · 显示 M 个`，`aria-live="polite"`；无匹配显示 `emptyText`（默认「没有匹配的项目」） |
| 焦点 | 复焦触发器；`:focus-visible` 用 `--focus-ring` |

`AppSelect` 保持原样：来源/会议/负责人/状态仍用它，避免为一个场景替换全局控件（回归面最小）。

### 4.4 设置页项目配置

`web/src/pages/SettingsPage.vue`：

- 卡片顺序改为 ① 督办日报收件人 ② 报表开关 ③ 来源配置 ④ **项目配置**（`id="projects"`）⑤ 钉钉配置状态；只插入新卡，不改四张既有卡的标题与结构；顶部注释编号同步；骨架屏 `v-for="n in 4"` → `5`。
- 项目卡仅 `auth.role === 'ADMIN'` 渲染（整卡不渲染，不是 disabled 占位）。
- 新增 `projects / projectsLoading / projectsError` 三个 ref 与 `loadProjects()`；`onMounted` 与来源配置并行加载，失败只影响本卡（`projectsError` + 「重试」按钮），不拖垮整页。
- `<ProjectConfigTable :items="projects" :loading="projectsLoading" :error="projectsError" @refresh="loadProjects" />`。

`web/src/components/settings/ProjectConfigTable.vue`：

- 列宽 240 / 96 / 88 / 64 / 88 / 152（合计 728 ≤ 内容宽 968）；表头 40、行 56。
- 名称列完整展示（`title` 兜底），编码列 `mono` 只读，状态列行内开关，排序列行内数字输入（`blur`/`Enter` 提交，越界按 0–99 截断），被引用数列数字，操作列「编辑 / 删除」。
- 删除按钮只在 `usageCount === 0` 时渲染；被引用行只显示「—」+ 说明「已被 N 条任务使用，只能停用」，**不靠 409 红框兜底**。
- 行内提交走 `projectsApi.setEnabled` / `projectsApi.update`；成功后 `emit('refresh')` 由页面重取（不本地改数组，避免与其它页签不一致）。
- 达到 200（`items.length >= 200`）时「新增项目」禁用并显示「项目最多 200 个，已达上限」。
- 删除用 `ConfirmDialog`：「删除项目「X」？删除后不可恢复。已被任务使用的项目只能停用。」；409 时 toast + 刷新。

`web/src/components/settings/ProjectFormModal.vue`（宽 480）：

- 字段：名称（`maxlength` 不设 20，用码点计数实时提示「N/20」）、编码（只读：新建「保存后自动生成」、编辑 `mono` 显示，旁注「创建后不可修改」）、排序（数字，默认 `nextSort`，hint「0–99，数字越小越靠前；留空自动排到末尾」）、启用开关。
- props：`open`、`row?: ProjectRow | null`、`nextSort?: number`；emits：`saved(row)`、`close`；内部直接调 `projectsApi`。
- 422 → `fieldErrors.name/sort` 落到字段；409 `PROJECT_NAME_DUPLICATE` → 名称字段；409 `PROJECT_LIMIT_REACHED` → 顶部摘要；网络错误 → 顶部摘要 + 可重试。

`web/src/api/projects.ts`：

```ts
export const projectsApi = {
  list(): Promise<ProjectRow[]>,                              // GET /projects
  create(payload: ProjectPayload): Promise<ProjectRow>,       // POST /projects
  update(id: number, payload: ProjectPayload): Promise<ProjectRow>, // PUT /projects/{id}
  setEnabled(id: number, enabled: boolean): Promise<ProjectRow>,    // PATCH /projects/{id}/status
  remove(id: number): Promise<void>,                         // DELETE /projects/{id}
}
```

### 4.5 来源形态三选一与改形

`web/src/components/settings/SourceTypeFormModal.vue`：

- `FORM_OPTIONS` 变三项，顺序：自由说明 → 需绑定会议 → 需绑定项目（`PROJECT` 提示「建任务时必须选项目」）。
- 弹窗宽 480 → **560**（三张卡并排：`(560-64-2*12)/3 ≈ 157` ≥ 卡内最长文案 144）。
- 改形且 `row.usageCount > 0` 时，在形态组下方插入提示块（`--c-warn-soft` 底）：
  「该来源已有 N 条历史任务，改形后不迁移历史数据；项目形态下它们将显示为「未指定项目」，说明照旧保留。」
  并出现必勾复选框「我已知悉，历史任务不迁移」；未勾选时保存按钮 disabled，主按钮文案变「确认改形并保存」。
- 形态未变时不出现提示块、按钮文案保持「保存」。
- `formLocked`（内置会议决议）逻辑不动；`MAX_NAME = 10` 不动（来源名仍是 2–10，项目名才是 2–20）。

### 4.6 建任务绑项目

`web/src/components/task/TaskFormDrawer.vue`：

- props 增加 `projects?: ProjectOptionRow[]`（由 `TaskPoolPage` 传 `meta.projects`）。
- `FormState` 增加 `projectId: number | ''`；`blankForm()` 置 `''`。
- `activeForm === 'PROJECT'` 时：隐藏会议/决议项与说明，渲染
  `<AppSearchSelect label="来源项目" required placeholder="选择项目" :options="projectOptions(props.projects, { allOption: false, includeDisabled: false })" :model-value="form.projectId === '' ? '' : String(form.projectId)" hint="项目由管理员在「系统设置 → 项目配置」维护" />`。
- `onSourceChange` 切到项目形态时清 `meetingId/decisionItemId/note`；切走时清 `projectId`。
- 校验（`validate()` 内，仅新建态）：`PROJECT` 且 `projectId === ''` → `errors.projectId = '请选择来源项目'`，滚动聚焦到该项目字段。
- 提交 payload：`sourceProjectId: activeForm === 'PROJECT' && form.projectId !== '' ? form.projectId : undefined`；会议/说明字段仅各自形态带。
- 编辑态：来源类型只读 + 新增一行「来源项目」只读（名称 + 停用后缀），来源不可改提示追加「如需更换项目请新建任务」。
- 下拉空态：`emptyText` 用「还没有可用项目。请联系管理员在「系统设置 → 项目配置」添加项目。」

### 4.7 三处筛选与展示跳转

**`TaskFilterBar.vue`**

- `PoolFilters` 增加 `project: string`；props 增加 `projects?: ProjectOptionRow[]`。
- computed `projectOptions = buildProjectOptions(props.projects, { allOption: true, includeDisabled: true, includeUnassigned: true })`。
- 模板位置：**来源之后、会议之前**，`AppSearchSelect` `size="compact"` `class="filter__project"`（宽 148），`aria-label="项目筛选"`。
- chips：`project` 非空时显示 `项目：<名称>` / `项目：<名称>（已停用）` / `项目：未指定项目`；`removeChip('project')` → `patch({ project: '' })`；`clearAll()` 一并清空。
- 筛选行总宽 966 ≤ 1008；≤1180 时允许换行（`row-gap: 8px`），不做横向滚动。

**`TaskPoolPage.vue`**

- `PoolQuery` 增加 `project: string`，`DEFAULTS.project = ''`（string kind 用默认即可），`hasFilters` 计入 `state.project !== ''`。
- `filters` 映射 `project: state.value.project`；`buildFilter()` 组装 `project: state.value.project || undefined`。
- 400 `PROJECT_INVALID` 分支照 `SOURCE_TYPE_INVALID`：清空 `state.project` + toast「项目不合法，已按全部项目展示」+ 重取（防旧链接白屏）。
- 抽屉传 `:projects="projects"`（`computed(() => meta.value?.projects ?? [])`）。

**`TaskTable.vue`**

- 来源列保持 150 宽两行：第一行 `SourceTag`（不动），第二行：
  `<ProjectTag v-if="projectView(row.source)" v-bind="projectView(row.source) ?? {}" />`；
  否则沿用现有 `sourceText(row)`（会议名 / 说明 / 「—」）。
- 项目标签 `max-width: 128px` + `text-overflow: ellipsis` + `title` 全名。

**`TaskDetailPage.vue`**

- 来源区在 `SourceTag` 之后新增项目位（8px 间距）：
  - `projectId != null`：`<RouterLink :to="{ path: '/tasks', query: { project: String(source.projectId) } }" class="source__project">{{ source.projectName }}<span v-if="source.projectEnabled === false">（已停用）</span></RouterLink>`；
  - `form === 'PROJECT' && projectId == null`：`<ProjectTag unspecified />`（不可点）；
  - 其他：不渲染（不产生空行、不改「来源是创建时的快照」提示）。
- 详情项目名完整展示（20 字不截断），样式 `--c-primary` + 下划线 hover。

**`MatrixPage.vue` / `DashboardPage.vue`**

- `MatrixQueryState` / `DashboardQueryState` 各加 `project: ''`；工具栏在来源下拉之后加 `AppSearchSelect`（同一 `projectOptions` 参数），`class="toolbar__select"`（矩阵保持 160 宽）。
- 请求带 `project: state.project || undefined`；400 `PROJECT_INVALID` 同样清空 + toast。
- 矩阵下钻：`matrixDrilldown(row, bucket, { sourceType: state.source || undefined, project: state.project || undefined })`，`DrillFilter` 增加 `project?: string`，命中时 `query.project = filter.project`（否则下钻后格数字对不上）。
- 看板焦点列表第二行同样渲染 `ProjectTag`（与任务池同形，复用 `projectView`）。

**`api/tasks.ts` / `api/dashboard.ts` / `api/matrix.ts`**

- `toParams` 增加 `if (filter.project) params.project = filter.project`；
- `dashboardApi.load` / `matrixApi.load` 增加 `project` 透传；
- `matrixDrilldown` 的 `DrillFilter` 增加 `project`，并写入 `query.project`。

### 4.8 状态管理

- **不新增 Pinia store**：项目清单属于「元数据」，随 `metaApi.options()` 一次性取，页面各自持有 `meta`（与 `sourceTypes` 同生命周期）。理由：项目不是会话态、不需要跨页写回，多一份 store 就多一处失效点。
- 筛选状态仍以 **URL 为唯一真源**（`useQuerySync`），`project` 是 string 短键，`'none'` 与数字 id 都能原样往返。
- 管理端项目列表（含 `usageCount/code`）只存在于设置页局部状态，**不写入 meta、不缓存第二份**。
- 写入后一律 `emit('refresh')` 重取，不做本地乐观更新（排序/启停/删除都影响别的页面）。

### 4.9 固定文案

| 位置 | 文案 |
|---|---|
| 项目卡描述 | 项目是任务来源的可选绑定对象；行内改动即时生效。 |
| 上限 | 项目最多 200 个，已达上限 |
| 名称校验 | 名称为 2–20 字 |
| 重名 | 名称已存在，请换一个 |
| 删除确认 | 删除项目「X」？删除后不可恢复。 |
| 被引用 | 该项目已被 N 条任务使用，只能停用 |
| 抽屉必选 | 项目来源必须选择项目 |
| 抽屉空态 | 还没有可用项目。请联系管理员在「系统设置 → 项目配置」添加项目。 |
| 改形提示 | 该来源已有 N 条历史任务，改形后不迁移历史数据；项目形态下它们将显示为「未指定项目」，说明照旧保留。 |
| 未指定档 | 未指定项目 |
| 停用后缀 | （已停用） |
| 非法项目 | 项目不合法 |

### 4.10 边界与降级

- `meta.projects` 缺失/为空：抽屉显示空态文案，筛选只剩「全部项目 / 未指定项目」，页面不报错。
- 已停用项目：历史任务标签带后缀、可筛、可点（详情链接不受影响）。
- 已删项目：不可能有任务引用（FK），只在「手改 URL」时出现 → 400 → 清筛选提示。
- 超长名称：列表 128px 截断 + `title`，详情完整展示；标签不换行撑破行高。
- 非管理员：设置页项目卡不渲染；直接访问 `/api/projects` 得到 403，前端 toast 不白屏。
- 键盘/读屏：`AppSearchSelect` 全键盘可达，combobox/listbox ARIA 完整；`ProjectTag` 是纯文本，不引入图标语义。

---

## 5. 一致性地基（对照 PRD 五条反面目标）

| 反面目标 | 结构性保证 | 验证 |
|---|---|---|
| ① 「项目」一词三义 | 形态只由 `source_types.form` 决定；`formOf()` 不按编码猜；文档与文案统一「需绑定项目」；测试钉住「内置 PROJECT 来源仍是自由说明」 | `SourceFormProjectIT`、`task-form-project.spec` |
| ② 改形产生空白坏数据 | 改形零迁移；`source_note` 与 `source_project_id` 不互相覆盖；详情对 `form=PROJECT && projectId=null` 显示「未指定项目」 | `SourceFormProjectIT`、`task-detail-project.spec` |
| ③ 停用项目变「未知项目」 | 读模型 join 全量项目（不按 enabled 过滤）；标签带「（已停用）」；筛选允许停用 id | `MatrixProjectIT`、`task-detail-project.spec` |
| ④ 三处数字不一致 | 唯一 SQL 谓词 `ProjectFilterSql.append`；任务池/看板共用 `TaskQueryBuilder`；矩阵也调同一方法 | `MatrixProjectIT` |
| ⑤ 项目多到选不到 / 第 201 个静默失败 | `AppSearchSelect` 子串搜索 + 计数；上限 409 + 按钮置灰 + 可见原因 | `ProjectApiIT`、`project-config.spec` |

**合计对齐的精确口径**（沿用 r1，不新造口径）：同一 `project` 条件下，`任务池 total === 看板 total`（同一个 `COUNT(*)`）；`Σ矩阵五列 === 看板 total − 取消任务数`，且矩阵仍只列「有非取消任务」的人。验收数据集不含取消任务时三者严格相等；含取消任务时按上式核对，禁止为了对齐去改矩阵口径。

**AC 覆盖**：AC-1 → §1/§2.2/§4.4；AC-2 → §1.1/§3.4/§4.5/§4.6；AC-3 → §1.4；AC-4 → §3.5/§4.4；AC-5 → §3.3/§4.7；AC-6 → §1.2/§3.2/§4.5；AC-7 → §4.7；AC-8 → §2.7；AC-9 → §3.7 + §6。

---

## 6. 测试策略

### 6.1 后端（真 MySQL `supervise_test`，Flyway 建表）

| 文件 | 覆盖 |
|---|---|
| `project/ProjectApiIT.java`（新） | ADMIN 五端点 200/201/204；OWNER/EXEC 403；未登录 401；重名（含停用同名）409；名称 1/21 字 422；第 201 个 409；`usageCount` 正确；删除被引用 409 + `details.taskCount`；删除未引用 204 |
| `project/ProjectServiceIT.java`（新） | 编码 `P00001` 递增；审计 5 个 action 落 `audit_logs`；`detail` 含前后名称/启停/排序；`requireKnown`/`requireEnabled` 错误码；空表时排序默认 0 |
| `project/ProjectFilterSqlTest.java`（新，纯单测） | 空/空白不追加；`none` 生成「未指定项目」条件；非数字/0/负数 → 400；合法 id 绑定 `:projectId` |
| `task/TaskSourceProjectIT.java`（新） | 项目形态建任务成功（行返回 `form/projectId/projectName/projectEnabled`）；缺选 422 字段；停用项目 400；不存在项目 400；携带 note/会议 400；NOTE/MEETING 带 `sourceProjectId` 422；编辑任务带 `sourceProjectId` → `TASK_SOURCE_IMMUTABLE`；库层 CHECK 拒绝「说明+项目并存」 |
| `dashboard/MatrixProjectIT.java`（新） | 同一 `project` 下任务池 total = 看板 total；`Σ矩阵五列 = total − 取消数`；`project=none` 只命中「PROJECT 形态且无项目」的历史任务；未知 id 400；停用 id 可筛 |
| `meta/MetaApiIT.java`（扩展） | `projects` 对 OWNER 也下发；只含 `id/name/enabled/sort`（断言 JSON 无 `code/usageCount`） |
| `source/SourceFormProjectIT.java`（新） | 新建 PROJECT 形态来源；`NOTE→PROJECT` 允许且 `usageCount` 正确、任务行零变化（`source_note` 保留、`source_project_id` 仍 NULL）；`PROJECT→NOTE` 允许、项目仍可读；`PROJECT→MEETING` 有未绑会议任务时 409；内置 `MEETING` 形态仍锁定 |
| 既有测试修改 | `TaskServiceIT`/`TaskSourceValidationIT`（`CreateTaskRequest` 构造）、`TaskJsonTest`（`TaskSource` 构造 + JSON 键）、`TaskQueryBuilderTest`（构造器 + `WHERE 1 = 1` 断言不变） |
| `IntegrationTestSupport.java` | 清表顺序补 `DELETE FROM projects` |

### 6.2 前端（Vitest，`web/tests`）

| 文件 | 覆盖 |
|---|---|
| `project-options.spec.ts`（新） | 排序（启用→sort→id）、停用后缀、`none` 仅在 includeUnassigned 时出现、不就地排序、`projectView` 三分支 |
| `project-config.spec.ts`（新） | 六列渲染、`usageCount>0` 不出删除、上限 200 置灰、行内启停/排序调用参数、409 落名称字段 |
| `task-form-project.spec.ts`（新） | 选 PROJECT 只显示项目字段、缺选校验、payload 只带 `sourceProjectId`、切形态清字段、编辑态只读 |
| `task-pool-project.spec.ts`（新） | URL `project` 往返、chip 文案与移除、请求参数、`PROJECT_INVALID` 清筛选 + toast |
| `task-detail-project.spec.ts`（新） | 链接 href=`/tasks?project=12`、停用后缀、`unspecified` 不可点、NOTE 不渲染 |
| `matrix-dashboard-project.spec.ts`（新） | 下钻带 `project`、看板请求带 `project`、三处选项同源 |
| 既有 spec 更新 | `t12-source-form-display`（三形态标签）、`task-table`（第二行项目标签）、`permissions`（非管理员不渲染项目卡） |

### 6.3 升级验证（QA 阶段执行，本文档给步骤）

1. 用 `git stash` 前的 `V1–V5` 建库并跑旧版本，造数据：MEETING 任务、NOTE 任务、内置 PROJECT 来源任务、自定义来源。
2. 应用 `V6`（`./mvnw flyway:migrate` 或启动应用）。
3. 断言：`tasks` 行数/`source_note`/`status`/`task_events` 与升级前逐行一致；`projects` 为空；三条内置来源编码/名称/颜色/形态不变；页面历史显示与筛选与升级前一致。

### 6.4 冒烟脚本扩展（`scripts/smoke-api.sh`，原地扩展不重建）

新增段：创建项目 → 建 PROJECT 来源 → 建绑项目任务 → 详情含 `projectName` → `?project=<id>` 命中 → `?project=none` 为 0 → 删除被引用项目 409 → 停用项目后建任务 400 → 非管理员 `GET /api/projects` 403。全部沿用脚本现有 `request/pass/fail/skip` 与临时库约定（可丢弃库）。

### 6.5 手工验收（只留必要项）

- 找一个**没读过文档**的同事，从零建一条「项目来源」任务：能自己找到项目下拉即为通过（反面目标 1 的唯一有效验证）。
- 把一条有历史任务的说明型来源改成项目型：提示块出现且必须勾选，保存后历史任务显示「未指定项目」、说明仍在。
- 200 个项目时新建按钮置灰、搜索仍可用。

---

## 7. 任务拆分（按文件边界，可并行）

### 7.1 任务表

| # | 任务 | 修改文件（ownership） | 只读 | 依赖 |
|---|---|---|---|---|
| T01 | 迁移 + 项目主数据（server） | `V6__projects.sql`、`project/ProjectDao.java`、`ProjectService.java`、`ProjectController.java`、`ProjectFilterSql.java`、`project/dto/*.java`（4）、`common/ErrorCode.java`、`IntegrationTestSupport.java`、`project/ProjectApiIT.java`、`ProjectServiceIT.java`、`ProjectFilterSqlTest.java` | `source/SourceTypeDao.java`、`source/SourceTypeService.java`、`auth/LoginAuditService.java`、`common/BusinessException.java` | — |
| T02 | 来源形态三态（server） | `source/SourceForm.java`、`source/SourceTypeService.java`、`source/SourceFormProjectIT.java` | `source/SourceTypeDao.java`、`source/SourceTypeServiceIT.java` | T01（CHECK 已放宽） |
| T03 | 任务域三态与项目读写（server） | `task/Task.java`、`task/TaskService.java`、`task/TaskRowMapper.java`、`task/TaskQueryDao.java`、`task/TaskQueryBuilder.java`、`task/dto/TaskFilter.java`、`task/dto/TaskSource.java`、`task/dto/CreateTaskRequest.java`、`task/dto/UpdateTaskRequest.java`、`task/TaskSourceProjectIT.java`、`task/TaskServiceIT.java`、`task/TaskSourceValidationIT.java`、`task/TaskJsonTest.java`、`task/TaskQueryBuilderTest.java` | `project/ProjectService.java`、`project/ProjectFilterSql.java` | T01 |
| T04 | 看板/矩阵/元数据接入（server） | `dashboard/MatrixFilter.java`、`dashboard/MatrixService.java`、`meta/MetaService.java`、`meta/dto/MetaOptions.java`、`dashboard/MatrixProjectIT.java`、`meta/MetaApiIT.java` | `project/ProjectFilterSql.java`、`project/dto/ProjectOption.java`、`task/TaskQueryDao.java` | T01、T03 |
| T05 | 前端契约与公共件（web） | `types/enums.ts`、`types/api.ts`、`utils/projectOptions.ts`、`api/projects.ts`、`components/ui/ProjectTag.vue`、`components/ui/AppSearchSelect.vue`、`tests/project-options.spec.ts` | `utils/sourceOptions.ts`、`components/ui/AppSelect.vue` | — |
| T06 | 设置页项目配置（web） | `pages/SettingsPage.vue`、`components/settings/ProjectConfigTable.vue`、`components/settings/ProjectFormModal.vue`、`tests/project-config.spec.ts` | `components/settings/SourceTypeTable.vue`、`api/projects.ts` | T01、T05 |
| T07 | 来源形态三选一弹窗（web） | `components/settings/SourceTypeFormModal.vue`、`tests/t12-source-form-display.spec.ts`、`tests/source-form-three.spec.ts` | `types/enums.ts`、`types/api.ts` | T02、T05 |
| T08 | 建任务绑项目 + 任务池筛选/列表（web） | `components/task/TaskFormDrawer.vue`、`components/task/TaskFilterBar.vue`、`components/task/TaskTable.vue`、`pages/TaskPoolPage.vue`、`tests/task-form-project.spec.ts`、`tests/task-pool-project.spec.ts`、`tests/task-table.spec.ts` | `types/api.ts`、`utils/projectOptions.ts`、`api/tasks.ts` | T03、T05 |
| T09 | 详情/矩阵/看板接入（web） | `pages/TaskDetailPage.vue`、`pages/MatrixPage.vue`、`pages/DashboardPage.vue`、`api/tasks.ts`、`api/dashboard.ts`、`api/matrix.ts`、`tests/task-detail-project.spec.ts`、`tests/matrix-dashboard-project.spec.ts` | `utils/projectOptions.ts`、`components/ui/ProjectTag.vue` | T04、T05、T08（筛选参数形状） |
| T10 | 交付自检与全量回归（无新代码） | 无（发现小问题当场修，只记结论） | 全部 | T01–T09 |
| T11 | 记忆收口与提交（git） | `docs/teamflow/memory.md`（替换同主题行）、`.gitignore`（仅核对） | `PRD.md`、`TECHNICAL.md` | T10 全绿 |

### 7.2 逐任务 spec 与验收

**T01**：按 §1.1 建表/加列/换 CHECK；实现 §3.1 的 8 个文件与 6 个错误码；`ProjectService` 护栏（2–20 码点、含停用查重、200 上限、0–99 截断、审计 5 动作、删除护栏 + FK 竞态兜底）；`ProjectFilterSql` 按 §3.3 逐字实现。验收：`ProjectApiIT`/`ProjectServiceIT`/`ProjectFilterSqlTest` 全绿；`./mvnw -q -DskipTests package` 通过。

**T02**：`SourceForm` 加 `PROJECT("需绑定项目")`；`SourceTypeService.update` 的改形前置条件改为 `!= MEETING`（§3.2 第一条，防 500）。验收：`SourceFormProjectIT` 全绿；「内置 PROJECT 来源形态仍是 NOTE」有断言。

**T03**：§3.2 全部 service/读模型改动 + §3.4 校验顺序；4 个既有测试随 record 构造同步。验收：`TaskSourceProjectIT` 全绿，`TaskJsonTest` 断言 JSON 含 `form/projectId/projectName/projectEnabled`，`TaskQueryBuilderTest` 空筛选仍为 ` WHERE 1 = 1`。

**T04**：`MatrixFilter.project`、`MatrixService` 调同一 `ProjectFilterSql`、`MetaOptions` 加 `projects`（全员、最小字段）。验收：`MatrixProjectIT` 的「任务池 total = 看板 total = Σ五列 + 取消数」断言成立；`MetaApiIT` 断言无 `code/usageCount`。

**T05**：§4.1 类型、§4.2 投影、`api/projects.ts`、`ProjectTag`、`AppSearchSelect`（§4.3 全表）。验收：`project-options.spec.ts` 全绿、`pnpm build` 通过（新组件无未用变量/类型错误）。

**T06**：§4.4 全部行为（含 200 置灰、删除仅未引用可点、行内启停/排序、409 落字段）。验收：`project-config.spec.ts` 全绿；非管理员渲染不渲染卡片。

**T07**：§4.5 全部行为（三选一、560 宽、改形提示 + 必勾、按钮文案）。验收：`source-form-three.spec.ts` 全绿；`t12` 三形态标签通过。

**T08**：§4.6 + §4.7 中任务池/筛选条/表格部分。验收：`task-form-project.spec.ts`、`task-pool-project.spec.ts`、`task-table.spec.ts` 全绿；URL 手改 `project=none` 能复现。

**T09**：§4.7 中详情/矩阵/看板 + 三个 api client。验收：`task-detail-project.spec.ts`、`matrix-dashboard-project.spec.ts` 全绿；矩阵下钻链接含 `project`。

**T10**：跑 §8 全部命令；修只属于「本次实现遗漏」的小问题（不改契约、不扩范围）；记录结论到任务夹（只写结论，不写 QA-REPORT/ACCEPTANCE，不预跑跨浏览器/活环境矩阵）。

**T11**：见 §7.4。

### 7.3 并行波次

```
W1: T01, T05            （server 地基 / 前端契约，文件不相交）
W2: T02, T03, T06, T07   （T03 需 T01；T06/T07 需 T05）
W3: T04, T08             （T04 需 T01+T03；T08 需 T03+T05）
W4: T09                  （需 T04 的矩阵参数与 T08 的筛选形状）
W5: T10 → T11
```

同一波次内文件集合互不相交，可并发；`reads` 只读不阻塞。

### 7.4 工程约束与 git 动作（PRD「工程约束」原文必须落地，不得丢失）

- **分支**：用户本轮未给工程指令，**不新建分支、不切分支**，在当前 `main` 开发（团队约定）。
- **提交时机**：T10 全绿之后，由 T11 提交到 `main`；提交信息需同时描述两项能力（项目主数据 + 来源「需绑定项目」形态与三处筛选），不得夹带构建产物/依赖缓存/临时日志。
- **提交前核对 `.gitignore`**：现有已覆盖 `logs/teamflow/`、`server/target/`、`server/.mvn/repo|home/`、`node_modules/`、`dist/`、`.pnpm-store/`、`*.tsbuildinfo`、`.env*`、`*.log`、`.idea/`、`.vscode/`、`.DS_Store`。本轮**不新增忽略规则**：`db/migration/*.sql` 必须入库（切勿因「临时 SQL」误加 `*.sql`）；若实现中确实产生了 `*.dump` 等本地数据文件，则补 `*.dump` 一行，且不得把迁移脚本排除。
- **提交范围**：代码 + `docs/teamflow/20261110-r4-project-source-binding/`（PRD/DESIGN/TECHNICAL + 后续 QA/ACCEPTANCE）+ `docs/teamflow/memory.md` 的同主题替换行。禁止提交 `logs/teamflow/**`（已忽略）。
- **文档边界**：只写任务夹与 `docs/teamflow/memory.md`；不改 `AGENTS.md`（除 managed zone，本轮也不改）；不写宿主 `docs/<role>/`；不在项目根散落日志。
- **记忆更新**：`memory.md` 用「替换同主题行」而非追加流水账（项目主数据三态、`ProjectFilterSql` 唯一谓词、`projectOptions` 唯一投影、`ProjectTag` 唯一展示决策）。

---

## 8. 验证脚本

```bash
# 后端
cd server && ./mvnw -q test
cd server && ./mvnw -q -DskipTests package
cd server && ./mvnw -q -Dtest='ProjectApiIT,ProjectServiceIT,ProjectFilterSqlTest,TaskSourceProjectIT,MatrixProjectIT,MetaApiIT,SourceFormProjectIT' test

# 前端
cd web && pnpm install && pnpm test
cd web && pnpm build
cd web && pnpm exec vitest run tests/project-options.spec.ts tests/project-config.spec.ts \
  tests/task-form-project.spec.ts tests/task-pool-project.spec.ts \
  tests/task-detail-project.spec.ts tests/matrix-dashboard-project.spec.ts \
  tests/source-form-three.spec.ts tests/t12-source-form-display.spec.ts

# 冒烟（可丢弃库；账号按环境提供）
BASE_URL=http://127.0.0.1:18090 SMOKE_USERNAME=admin SMOKE_PASSWORD='Qa@2026pass' \
  SMOKE_OWNER_USERNAME=qaowner SMOKE_OWNER_PASSWORD='Admin@12345' bash scripts/smoke-api.sh

# 提交前
git status --short
```

---

## 9. 风险与遗留

| 风险 | 影响 | 处置 |
|---|---|---|
| `ck_tasks_source` 重建在既有脏数据上失败 | 迁移中断 | 迁移前先 `SELECT` 校验三类组合；新 CHECK 对历史行是恒等变换（§1.4），已在升级验证步骤中逐项断言 |
| 「未指定项目」谓词含子查询 | 任务量大时慢 | `source_types` 表最多 20 行、有主键，子查询可忽略；如后续变慢，再物化 `form` 到 `tasks`（本轮不做） |
| 名称唯一大小写不敏感 | 用户以为能建「ABC/abc」 | 比 PRD 更严且更安全，文案已提示「名称已存在」；不为此放宽 collation |
| 行内排序与编辑并发 | 后写覆盖先写 | 单实例内网、单管理员操作，可接受；写入返回完整行并 `refresh`，页面不会停留在旧值 |
| 改形 PROJECT→MEETING | 若无护栏会写坏数据 | T02 放宽前置条件为 `!= MEETING`，由 `countTasksWithoutMeeting` 阻断并 409 |
| 遗留（不在本轮） | `ownerOptions/meetingOptions` 仍在 `TaskFilterBar` 与 `DashboardPage` 两处同形复制，可下一轮抽 util | 已记入 `memory.md` 待办候选 |
