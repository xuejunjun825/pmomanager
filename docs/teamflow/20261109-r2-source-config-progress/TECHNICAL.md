# TECHNICAL · r2 来源自定义配置与任务进度 @ 提醒

- 任务夹：`docs/teamflow/20261109-r2-source-config-progress/`
- 基线：`docs/teamflow/20261109-r1-meeting-task-supervision/`
- 取代：r1#AC-7（来源写死三类）、r1#AC-2（任务可见范围）
- 技术栈不变：Spring Boot 3.3.5 / Java 17 / MySQL 8 / Flyway / JdbcTemplate + JPA 写模型；Vue 3.5 + Vite 6 + TS 5.7 + Pinia 2 + Vitest 3
- 工程约束（PRD 原文）：本轮**直接在 main 分支开发，不新建特性分支**；任务夹随代码一并提交；`.gitignore` 按 Maven + Vite 栈复核；提交信息覆盖两项能力

---

## 1. 关键决策

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| D1 | 来源配置落在哪 | 新表 `source_types`，`code` 为业务主键 | 配置要能被任务引用、被 FK 保护；编码稳定才能让旧链接 `sourceType=MEETING` 继续可用（AC-5） |
| D2 | 三类内置来源 | V4 种子写入 `MEETING/PROJECT/DAILY`，编码与颜色逐字与 r1 一致 | 升级后历史任务不用改写一行数据，显示与筛选天然不变（AC-3） |
| D3 | 硬编码边界 | 删除 `common/SourceType` 枚举；只保留 `SourceCodes.MEETING` 一个常量（锁定形态 + 一键转任务） | 禁止 `SourceType.values()` 喂下拉是 AC-5 的字面要求；MEETING 的业务特殊性必须有且只有一个落点 |
| D4 | 形态校验 | 库层只做「结构」约束（会议两列 vs 说明列互斥），「形态 ↔ 字段」由 `TaskService.validateSource` 单点校验 | MySQL CHECK 不能跨表子查询，无法在库层表达「该 code 的 form 是 MEETING」 |
| D5 | 删除护栏 | `tasks.source_type` 加 FK → `source_types(code)`，删除前再查引用数返 409 | FK 是结构性兜底（并发下也不会删掉在用的来源），409 是可读的业务提示 |
| D6 | 任务来源可编辑性 | **保持不可编辑**（r1 `TASK_SOURCE_IMMUTABLE` 不变） | 改来源会与「决议项唯一」和详情页既有「来源是创建时的快照」语义冲突；DESIGN §3.3 已预留该退化方案 |
| D7 | 进度存储 | 新表 `task_progress` + `task_progress_mentions`，append-only，无 update/delete 接口 | 时间线语义、审计语义都要求不可变；@ 关系单独成表才能支撑「被 @ 可见」查询 |
| D8 | 被 @ 可见性 | `TaskAccessGuard.requireView` 增加 `EXISTS(mentions …)` 分支；写进度用独立 `requireProgressWrite`（不可见者 **403**） | AC-11 要求详情 404→200；AC-8 要求他人提交进度 403 而详情仍 404，两者不能共用一个方法 |
| D9 | 进度上限并发 | 写进度前 `SELECT … FOR UPDATE` 锁 `tasks` 行，再计数 | 两条并发写在第 199 条时不能都通过；复用 r1 状态机的行锁范式 |
| D10 | @ 通知幂等 | `dedup_key = mention:{progressId}:{userId}`，沿用 INSERT-捕唯一键 | 同一进度对同一人只有一条；钉钉重试只更新同一行（AC-10） |
| D11 | 深链 | `notifications` 加 `progress_id`，`urlFor` 拼 `#progress-{id}` | 通知点击要落到具体那条进度，且刷新后仍能定位（AC-10） |
| D12 | 颜色真源 | 后端 `SourcePalette` 8 组三元组（主色/浅底/边框），API 直接下发三值 | 任务池、矩阵、看板、标签共用同一 DTO 就等于同源；避免前端按 code 分支写死颜色（AC-5） |

---

## 2. 目录结构与模块边界

```
server/src/main/java/com/pmo/supervise/
├─ source/                        # 新增：来源配置域（唯一写入口）
│  ├─ SourceForm.java             # MEETING | NOTE + label
│  ├─ SourcePalette.java          # 8 组颜色三元组 + hex→三元组映射
│  ├─ SourceCodes.java            # 仅 MEETING 常量
│  ├─ SourceTypeService.java      # CRUD / 上限 / 锁定 / 审计 / requireKnown / requireEnabled
│  ├─ SourceTypeDao.java          # 列表（含 usageCount）、按 code 查、计数
│  ├─ SourceTypeController.java   # /api/source-types
│  └─ dto/ SourceTypeView, SourceTypeRequest, SourceTypeStatusRequest, SourcePaletteEntry
├─ task/
│  ├─ progress/                   # 新增：进度域
│  │  ├─ TaskProgressService.java
│  │  ├─ TaskProgressDao.java
│  │  ├─ TaskProgressController.java   # /api/tasks/{taskId}/progress
│  │  └─ dto/ ProgressRow, ProgressPage, AddProgressRequest
│  ├─ Task.java                   # sourceType: SourceType → String
│  ├─ TaskService.java            # validateSource 改为 form 驱动；permissions 增加 @ 视角
│  ├─ TaskQueryBuilder.java       # 来源编码校验改调 SourceTypeService
│  ├─ TaskQueryDao.java           # SELECT_BASE 增加 LEFT JOIN source_types
│  ├─ TaskRowMapper.java          # 读 source_name / source_color → 三元组
│  ├─ TaskAccessGuard.java        # requireView 增加 mention 分支 + requireProgressWrite
│  └─ dto/ TaskSource, TaskPermissions
├─ meta/ MetaService + dto/MetaOptions   # sourceTypes 改为配置驱动
├─ common/ ErrorCode.java, NotifyType.java
├─ notify/ NotificationService, NotificationKeys, MessageTemplates, dto/*
└─ resources/db/migration/ V4__source_types.sql, V5__task_progress.sql

web/src/
├─ types/enums.ts, types/api.ts
├─ api/sourceTypes.ts（新增）, api/tasks.ts（加进度）
├─ components/ui/SourceTag.vue（改）
├─ components/settings/SourceTypeTable.vue, SourceTypeFormModal.vue, SourceColorSwatches.vue（新增）
├─ components/task/ProgressComposer.vue, MentionPicker.vue, MentionChips.vue, ProgressTimeline.vue（新增）
├─ components/task/TaskFormDrawer.vue, TaskFilterBar.vue（改）
└─ pages/SettingsPage.vue, MatrixPage.vue, TaskDetailPage.vue, NotificationsPage.vue（改）
```

分层规则沿用 r1：Controller 只做参数绑定与 Actor 注入；业务规则在 Service；读模型走 `TaskQueryDao` 原生 SQL；跨表计数只允许出现在 DAO 层。

> **命名债**：审计写入复用 `auth/LoginAuditService.record(...)`（REQUIRES_NEW，失败不抛）。类名带 Login，但方法本身是通用审计；本轮不重构，避免牵动 r1 所有调用点。

---

## 3. 数据模型与迁移

### 3.1 `source_types`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT UNSIGNED | PK, AUTO_INCREMENT | |
| code | VARCHAR(16) | NOT NULL, UNIQUE | 业务主键；内置 `MEETING/PROJECT/DAILY`，自定义 `S000004` |
| name | VARCHAR(10) | NOT NULL, UNIQUE, 2–10 字 | 全表唯一（utf8mb4_0900_ai_ci，大小写不敏感） |
| form | VARCHAR(16) | `MEETING` / `NOTE` | 形态：需绑定会议 / 自由说明 |
| color | VARCHAR(7) | `^#[0-9A-Fa-f]{6}$` | 只存主色；浅底/边框由 `SourcePalette` 派生 |
| sort_no | INT | 0–99 | 升序展示，重复值按 id |
| enabled | TINYINT(1) | 默认 1 | 停用后新建不可选，历史仍显示 |
| created_at / updated_at | DATETIME | | |

内置三行（V4 种子，颜色逐字对齐 r1）：

| code | name | form | color |
|---|---|---|---|
| MEETING | 会议决议 | MEETING | `#1B4D8F` |
| PROJECT | 项目任务 | NOTE | `#6941C6` |
| DAILY | 日常待办 | NOTE | `#475467` |

8 色板（`SourcePalette`，key 即主色 hex）：

| 名称 | 主色 | 浅底 | 边框 |
|---|---|---|---|
| 墨蓝 | `#1B4D8F` | `#EAF1FA` | `#C7D9EF` |
| 靛蓝 | `#3538CD` | `#EEF4FF` | `#C7D7FE` |
| 青色 | `#0B7A6E` | `#F0FDFA` | `#99F6E4` |
| 绿色 | `#067647` | `#ECFDF3` | `#ABEFC6` |
| 琥珀 | `#B54708` | `#FFFAEB` | `#FEDF89` |
| 紫色 | `#6941C6` | `#F4F3FF` | `#D9D6FE` |
| 石板灰 | `#475467` | `#F2F4F7` | `#E4E7EC` |
| 橄榄 | `#3F6212` | `#F3F6E8` | `#C9D6A4` |

### 3.2 `tasks` 约束改造（V4）

```sql
ALTER TABLE tasks DROP CONSTRAINT ck_tasks_source;
ALTER TABLE tasks
  ADD CONSTRAINT ck_tasks_source CHECK (
    (source_meeting_id IS NOT NULL AND source_decision_item_id IS NOT NULL AND source_note IS NULL)
    OR (source_meeting_id IS NULL AND source_decision_item_id IS NULL AND source_note IS NOT NULL)
  ),
  ADD CONSTRAINT fk_tasks_source_type FOREIGN KEY (source_type)
    REFERENCES source_types (code) ON UPDATE RESTRICT ON DELETE RESTRICT;
```

- 旧 CHECK 里的 `source_type IN ('MEETING','PROJECT','DAILY')` 必须去掉，否则自定义编码写不进去。
- 新 CHECK 只表达「会议型字段 vs 说明型字段」的结构互斥（AC-2 第三层），**形态匹配**在 `TaskService` 单点校验。
- `uk_tasks_decision`、`source_type NOT NULL` 原样保留。
- FK 让「删除被引用来源」在数据库层直接失败（D5），业务层仍先查引用数给出 409 文案。

### 3.3 `task_progress`（V5）

```sql
CREATE TABLE task_progress (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id    BIGINT UNSIGNED NOT NULL,
  author_id  BIGINT UNSIGNED NOT NULL,
  content    VARCHAR(500)    NOT NULL,
  created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_progress_task (task_id, id),
  CONSTRAINT fk_progress_task FOREIGN KEY (task_id) REFERENCES tasks (id),
  CONSTRAINT fk_progress_author FOREIGN KEY (author_id) REFERENCES users (id),
  CONSTRAINT ck_progress_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_progress_mentions (
  progress_id BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (progress_id, user_id),
  KEY idx_mention_user (user_id, progress_id),
  CONSTRAINT fk_pm_progress FOREIGN KEY (progress_id) REFERENCES task_progress (id),
  CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE notifications
  ADD COLUMN progress_id BIGINT UNSIGNED NULL AFTER task_id,
  ADD KEY idx_notify_progress (progress_id),
  ADD CONSTRAINT fk_notify_progress FOREIGN KEY (progress_id) REFERENCES task_progress (id);
```

`task_progress_mentions` 同时是「谁被 @ 过」与「谁获得只读可见权」的唯一真源，不再冗余一张 `task_mentions`。

### 3.4 迁移顺序与兼容

1. V4 建表 + 种子 → 2. 替换 CHECK → 3. 加 FK。升级库（已有 r1 数据）三步都通过：种子编码与历史 `source_type` 完全一致，FK 校验不会失败。
2. V5 只加表与一列，不改任何既有行。`notifications.progress_id` 可空，历史通知 `NULL`，`urlFor` 回落到 `/tasks/{id}`。
3. 回滚策略：Flyway 社区版不做 down；如需回退按「删 FK → 删列 → 删表」逆序手工执行（写进 §11 风险表）。

---

## 4. 后端核心机制

### 4.1 编码生成与上限（`SourceTypeService`）

- 创建：事务内先以占位码 `TMP_` + 10 位随机插入，再 `UPDATE source_types SET code = CONCAT('S', LPAD(id, 5, '0'))`，返回前回读。占位码仅事务内存在，外部永远看不到（`code` 唯一键保证不会撞）。
- 名称：`trim()` 后长度 2–10；先 `SELECT id WHERE name = ?` 预判重名（409 `SOURCE_NAME_DUPLICATE`），并发由唯一键兜底（捕获 `DuplicateKeyException` 同样转 409）。
- 上限 20：`SELECT COUNT(*) FROM source_types WHERE enabled = 1 FOR UPDATE`，≥20 时新增/启用返回 409 `SOURCE_LIMIT_REACHED`（文案「来源类型最多 20 个」）。count + 后续写在同一事务，锁住索引区间，避免并发把 19 变 21。
- 排序：创建未传 `sort` 时取 `MAX(sort_no)+1`，截断到 99。
- 校验：`form ∈ {MEETING,NOTE}`；`color ∈ SourcePalette`（不在色板直接 422 `color`）。
- 锁定：`locked = "MEETING".equals(code)`。锁定来源不可停用、不可删除、不可改形态；名称/颜色/排序可改。
- 形态切换护栏：`NOTE → MEETING` 前统计 `SELECT COUNT(*) FROM tasks WHERE source_type = ? AND source_meeting_id IS NULL`，>0 抛 409 `SOURCE_FORM_CONFLICT`，message 带 N。
- 审计：每次成功变更调用 `LoginAuditService.record(actorId, action, "SOURCE_TYPE", code, detail, request)`；detail 形如 `name=客户投诉; form=NOTE; color=#1B4D8F; enabled=true`，更新时写 `name: 旧 → 新`。action 五值：`SOURCE_TYPE_CREATED / UPDATED / DISABLED / ENABLED / DELETED`。
- 读取不做进程内缓存：表 ≤20 行，一次索引查询即可，天然满足「改完刷新即生效」。

### 4.2 建任务校验单点（`TaskService.validateSource`）

```
cfg = sourceTypeService.requireEnabled(req.sourceType)
if cfg.form == MEETING:
    必须有 sourceMeetingId + sourceDecisionItemId，且 sourceNote 必须为空
    会议存在 / 决议项存在 / 决议项属于该会议 / 决议项未被转任务（沿用 r1 四条）
else:
    sourceNote trim 后 1–200 字；sourceMeetingId / sourceDecisionItemId 必须为空
```

- `requireEnabled`：编码不存在 → 400 `SOURCE_TYPE_INVALID`（`fieldErrors.sourceType`「来源类型不合法」）；存在但停用 → 400 `SOURCE_TYPE_DISABLED`（「该来源已停用，请重新选择」）。
- 「未知编码 400」与 r1 的 422 校验分开：AC-5 明确要求 400，所以用显式 `ErrorCode` 携带 `BAD_REQUEST`，但仍带 `fieldErrors` 以复用前端字段定位。
- 一键转任务固定 `SourceCodes.MEETING`，不经配置读取（会议型是它的业务前提）。

### 4.3 同源一致（AC-5 的结构保证）

| 消费点 | 改造 |
|---|---|
| `GET /api/meta/options` | `MetaService` 注入 `SourceTypeService.options()`，返回**全部**来源（含停用），按 `sort_no, id` 排序 |
| 建任务抽屉 | 前端只过滤 `enabled === true`；停用项不出现在下拉 |
| 任务池 / 矩阵 / 看板筛选 | 读同一份 meta，停用项仍可筛（历史数据要能查） |
| 列表标签 | `TaskQueryDao.SELECT_BASE` 增加 `LEFT JOIN source_types st ON st.code = t.source_type`，多取 `st.name AS source_name, st.color AS source_color` |
| 矩阵 / 看板 | `MatrixService` 删除自己的 `parseSourceType`，改调 `sourceTypeService.requireKnown`；`DashboardService` 复用 `TaskRowMapper` 自动拿到同一份名称与颜色 |
| 详情 / 通知标题 | 通知正文用任务标题，不依赖来源；详情来源块直接读 `TaskSource` |

`TaskRowMapper.source()`：

```java
String code = rs.getString("source_type");
String name = rs.getString("source_name");   // LEFT JOIN 可能为 null
SourcePalette.Triple triple = SourcePalette.of(rs.getString("source_color"));
return new TaskSource(code,
        name == null || name.isBlank() ? "未知来源（" + code + "）" : name,
        triple.color(), triple.soft(), triple.border(),
        rs.getObject("source_meeting_id", Long.class), rs.getString("meeting_title"),
        rs.getObject("source_decision_item_id", Long.class), rs.getString("source_note"));
```

名称永不空白（DESIGN §4）；颜色缺失回落石板灰。

### 4.4 进度写入（`TaskProgressService.add`）

1. `Task task = taskRepository.findByIdForUpdate(taskId)`（不存在 → 404）。
2. `guard.requireProgressWrite(actor, task)`：可查看者通过；否则 403 `PROGRESS_FORBIDDEN`「你没有补充进度的权限」。**不授予任何状态动作**。
3. 内容：`trim()`；空或 >500 → 422 `content`（「请输入进度内容」/「进度内容不能超过 500 字」）。纯文本存储，前端不做 Markdown。
4. @ 校验：`mentionedUserIds` 去重后 ≤10（超出 422「最多 @ 10 人」）；剔除自己；逐个查 `users` 必须 `ACTIVE`，否则 422「只能 @ 启用成员」。
5. 上限：`SELECT COUNT(*) FROM task_progress WHERE task_id = ?`，≥200 → 409 `PROGRESS_LIMIT_REACHED`「该任务进度已达 200 条上限」。行锁保证 199→201 不会同时通过。
6. INSERT 进度（拿自增 id）+ 批量 INSERT mentions。
7. 对每个被 @ 人调 `notificationService.createIfAbsent(CreateNotificationCommand.async(...))`：类型 `MENTION`，标题 `{作者} 在任务《{任务标题}》中@了你`，正文取进度内容前 100 字（按 code point 截断）+「…」，dedup `mention:{progressId}:{userId}`，url `/tasks/{taskId}#progress-{progressId}`，`progressId` 一并落库。
8. 返回 `ProgressRow`（含 `mentions`、`createdAt`、`mentionedMe`）。通知在事务提交后由既有 `@TransactionalEventListener(AFTER_COMMIT)` + `dingtalkExecutor` 异步推送，**不阻塞响应**；未配置/未绑定分别落 `NOT_CONFIGURED` / `UNBOUND`，仍是一条站内通知。

读取：`page(taskId, limit, beforeId)` 按 `id DESC`（同秒也稳定），`total` 由 count 得到，`hasMore = 已取条数 < total`。

### 4.5 可见性与权限（`TaskAccessGuard`）

```java
public boolean canView(Actor actor, Task task) {
    return actor.isAdmin() || actor.isExec() || task.getOwnerId() == actor.id()
        || mentionDao.isMentioned(task.getId(), actor.id());
}
public void requireView(...)        // 不可见 → 404（保持 r1）
public void requireProgressWrite(...) // 不可见 → 403（AC-8）
```

- `isMentioned` 查询：`SELECT 1 FROM task_progress_mentions pm JOIN task_progress p ON p.id = pm.progress_id WHERE p.task_id = ? AND pm.user_id = ? LIMIT 1`，命中 `idx_mention_user`。
- 每次详情/事件/进度读会多一次索引查询，单任务量级可忽略；不做用户级缓存，避免撤权延迟。
- `TaskPermissions` 增加 `canAddProgress`（= 可查看）与 `mentionOnly`（= 非 admin/exec/owner 且被 @）。前端据此整组隐藏状态动作并展示「你是被 @ 成员，仅可查看与补充进度」。

### 4.6 口径隔离（AC-12）

进度写入**只**写 `task_progress` / `task_progress_mentions` / `notifications` 三张表：

- 不 `UPDATE tasks`，不碰 `status/due_date/owner_id/dispatcher_id/updated_at` → 任务池排序与逾期数字不变；
- 不写 `task_events` → 流转时间线与状态步进不变；
- 不参与 `TaskQueryDao.counters` / `MatrixService` / 日报查询（这些 SQL 未引用新表）；
- 被 @ 人不在 `tasks.owner_id` 上，故不进入非管理员的个人任务池、矩阵行与 KPI。

### 4.7 MENTION 通知

- `NotifyType` 增加 `MENTION("被@")`（放在 `URGE` 之后）。
- `NotificationKeys.mention(long progressId, long userId)` → `mention:{progressId}:{userId}`。
- `MessageTemplates.mentionTitle(actorName, taskTitle)` / `mentionBody(content)`（100 字 + `…`，`codePointCount` 安全截断）。
- `CreateNotificationCommand` 增加 `Long progressId`（record 末尾，提供兼容的 8 参静态工厂 + 新 9 参工厂）。
- `NotificationService.createIfAbsent` INSERT 增加 `progress_id`；`urlFor` 优先 `#progress-`。
- `NotificationService.page` 增加可选 `type` 过滤；`NotificationRow` 增加 `progressId`。
- 未读角标与「全部已读」不需要改：它们本来就是按 `user_id` 全类型统计。

---

## 5. API 设计

统一前缀 `/api`；Cookie-Session + CSRF 沿用现有配置；错误体为 `{code, message, fieldErrors[], details, traceId}`。

### 5.1 来源配置

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/source-types` | admin | 全量列表（含停用），带 `usageCount` |
| GET | `/source-types/palette` | 登录 | 8 色板（`color/soft/border/name`） |
| POST | `/source-types` | admin | 新增，201 |
| PUT | `/source-types/{code}` | admin | 改名/形态/颜色/排序 |
| PATCH | `/source-types/{code}/status` | admin | `{enabled:boolean}` |
| DELETE | `/source-types/{code}` | admin | 204；失败 409 |

`SourceTypeView`（列表与写操作统一返回）：

```json
{"code":"S000004","name":"客户投诉","form":"NOTE","color":"#1B4D8F",
 "softColor":"#EAF1FA","borderColor":"#C7D9EF",
 "sort":3,"enabled":true,"builtin":false,"locked":false,"usageCount":12}
```

`SourceTypeRequest`：`{"name":"客户投诉","form":"NOTE","color":"#1B4D8F","sort":3}`（`sort` 可省）。
`SourceTypeStatusRequest`：`{"enabled":false}`。

失败语义：

| 场景 | 状态 | code | 文案 |
|---|---|---|---|
| 名称重复 | 409 | `SOURCE_NAME_DUPLICATE` | 名称已存在，请换一个 |
| 名称/形态/颜色/排序非法 | 422 | `VALIDATION_FAILED` | 字段级 `name/form/color/sort` |
| 启用数已达 20 | 409 | `SOURCE_LIMIT_REACHED` | 来源类型最多 20 个 |
| 停用/删除 MEETING | 409 | `SOURCE_BUILTIN_PROTECTED` | 内置来源不可停用 / 内置来源不可删除 |
| 被任务引用仍删除 | 409 | `SOURCE_IN_USE` | 该来源已被 N 条任务使用，只能停用 |
| NOTE→MEETING 有未绑会议任务 | 409 | `SOURCE_FORM_CONFLICT` | 已有 N 条任务使用该来源且未绑定会议，无法改为会议型 |
| 非 admin | 403 | `FORBIDDEN` | 无权执行该操作 |
| 编码不存在 | 404 | `NOT_FOUND` | 来源类型不存在 |

### 5.2 元数据（改形状，路径不变）

`GET /api/meta/options` → `sourceTypes` 由 `Option<SourceType>` 改为：

```json
[{"value":"MEETING","label":"会议决议","color":"#1B4D8F",
  "softColor":"#EAF1FA","borderColor":"#C7D9EF",
  "form":"MEETING","enabled":true,"sort":0}]
```

其余 `owners/meetings/statuses/priorities` 不动。

### 5.3 进度

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/tasks/{taskId}/progress?limit=50&beforeId=` | 可查看者 | 新的在前，`{items,total,hasMore}` |
| POST | `/tasks/{taskId}/progress` | 可查看者 | 201，返回 `ProgressRow` |

请求：`{"content":"已联系客户，等待回访","mentionedUserIds":[12,15]}`

`ProgressRow`：

```json
{"id":318,"content":"已联系客户，等待回访",
 "author":{"id":7,"name":"张伟","role":"OWNER"},
 "mentions":[{"id":12,"name":"李娜"}],
 "mentionedMe":false,
 "createdAt":"2026-11-09T20:31:12.418"}
```

失败语义：403 `PROGRESS_FORBIDDEN`；409 `PROGRESS_LIMIT_REACHED`；422 字段级 `content` / `mentionedUserIds`；任务不存在 404。

### 5.4 通知（形状扩展）

`GET /api/notifications?type=MENTION&unreadOnly=false&page=1&size=20`

- `type` 可选；非法值 422 `type`「通知类型不合法」。
- `NotificationRow` 增加 `progressId`（可为 null）。前端据 `taskId + progressId` 拼深链。

---

## 6. 前端设计

### 6.1 类型与请求层

- `types/enums.ts`：删 `SourceType` 联合与 `SOURCE_TYPES/SOURCE_TYPE_LABELS`；`export type SourceType = string`（保留名字，减少改动面）；新增 `SourceForm = 'MEETING' | 'NOTE'` 与 `SOURCE_FORM_LABELS = { MEETING: '需绑定会议', NOTE: '自由说明' }`；`NotifyType` 增加 `MENTION`，`NOTIFY_TYPE_LABELS` 增加「被@」。
- `types/api.ts`：`MetaOptions.sourceTypes: SourceTypeOption[]`；`TaskSource` 增加 `color/softColor/borderColor`；新增 `SourceTypeRow/SourceTypePayload/SourcePaletteEntry/ProgressRow/ProgressPage/AddProgressPayload`；`NotificationRow.progressId`。
- `api/sourceTypes.ts`：`list / palette / create / update / setEnabled / remove`。
- `api/tasks.ts`：`progress(taskId, params)`、`addProgress(taskId, payload)`。
- **不新增 Pinia store**：来源配置是页面级数据（设置页/抽屉/筛选各自从 meta 或接口取），进度是详情页局部状态；全局未读仍由现有 `stores/notifications.ts` 负责（服务端计数已含 MENTION）。

### 6.2 组件切分

| 组件 | 职责 | 关键约束 |
|---|---|---|
| `ui/SourceTag.vue` | 来源标签 | props `{ code, name, color, softColor, borderColor, meetingTitle? }`；名称空 → `未知来源（CODE）`；颜色回退石板灰；20 高/r4/11px/max-w128 |
| `settings/SourceTypeTable.vue` | 配置表 | 列：名称/编码/形态/颜色/排序/状态/引用数/操作；行 56、头 40；删除按钮**仅 usageCount===0 时渲染**；locked 行不渲染停用与删除；`新增来源` 在启用数≥20 时禁用并给出可见原因 |
| `settings/SourceTypeFormModal.vue` | 新增/编辑 | 480 宽、r12；名称 2–10、编码只读（新增时显示「由系统生成」）、形态单选、排序 0–99；Esc 直接丢弃 |
| `settings/SourceColorSwatches.vue` | 色板 | 8×32、gap12、`role=radiogroup`，选中态有边框与对勾，`aria-checked` |
| `task/ProgressComposer.vue` | 进度输入 | textarea 88–160 自适应、500 计数右对齐、`@` 按钮 28、提交 32；提交中禁用防双击；200 条时输入区**保留可见**但提交禁用并显示原因；提交成功 emit `created(row)` |
| `task/MentionPicker.vue` | @ 选择 | 320 宽、最大高 320、本地搜索、排除自己、最多 10 人、去重；`combobox/listbox/aria-activedescendant`；仅选择器选中才产生 id（手打 `@` 不算） |
| `task/MentionChips.vue` | 已选成员 | 24 高 pill，可单个移除，`已选 N/10` |
| `task/ProgressTimeline.vue` | 合并时间线 | 输入 `progress[] + events[]`，按时间倒序合并；进度项带「进度」chip 与 @ 名单；`<ol>` 语义；进度项 `id="progress-{id}" tabindex="-1"` |
| `task/TaskDetailPage.vue` | 详情 | 面板 ④ 改名「进度与流转」；挂载后读 `route.hash` 定位并高亮 1600ms；`permissions.mentionOnly` 时整组隐藏 `TaskActions` 并显示只读说明；404→/404 保持 |
| `pages/NotificationsPage.vue` | 通知中心 | 三 tab（全部 / @我的 / 未读）+「被@」类型标签；点击先 markRead 再 `router.push('/tasks/{id}#progress-{pid}')`；空态「还没有人 @ 你」 |
| `pages/SettingsPage.vue` | 设置 | 第四张卡「来源配置」（报表开关之后、钉钉状态之前）；仅 admin 渲染；增删改后就地刷新列表，不触发页面未保存拦截 |
| `task/TaskFormDrawer.vue` | 建任务 | 来源下拉来自 `meta.sourceTypes.filter(enabled)`；切形态清空另一组字段；**编辑态来源只读展示**（D6） |
| `task/TaskFilterBar.vue` / `pages/MatrixPage.vue` | 筛选 | 下拉读 meta 全量（含停用），标签加「（已停用）」；不再 import `SOURCE_TYPES` |

### 6.3 交互与边界

- 来源标签颜色统一走 `style` 三元组，**不写 `tag--meeting` 之类按 code 分支的类名**。
- 详情页首次加载并行拉 `GET /tasks/{id}` 与 `GET /tasks/{id}/progress?limit=50`；进度失败不阻塞详情。
- 提交进度：`POST` 返回行 → 直接 `unshift` 到列表顶部（同秒按 id 倒序，服务端已保证），并 `toast('进度已记录')`；@ 了人时追加「已通知 N 位成员」。
- 200 条上限：由 `ProgressPage.total` 驱动禁用，不在前端自行计数。
- 深链：`TaskDetailPage.onMounted` 读 `location.hash`，`getElementById` 后 `scrollIntoView({block:'center'})`，加 `.is-anchor` 1600ms；元素未渲染时等首批数据后重试一次。
- 停用来源在历史任务标签上仍显示原名原色；只有新建下拉过滤。
- 设置页 1280 下表格 844px ≤ 1008 内容宽，不横向滚动。

---

## 7. 错误码新增

| code | HTTP | 文案 |
|---|---|---|
| `SOURCE_TYPE_INVALID` | 400 | 来源类型不合法 |
| `SOURCE_TYPE_DISABLED` | 400 | 该来源已停用，请重新选择 |
| `SOURCE_NAME_DUPLICATE` | 409 | 名称已存在，请换一个 |
| `SOURCE_LIMIT_REACHED` | 409 | 来源类型最多 20 个 |
| `SOURCE_BUILTIN_PROTECTED` | 409 | 内置来源不可停用 / 不可删除 / 不可改形态 |
| `SOURCE_IN_USE` | 409 | 该来源已被 N 条任务使用，只能停用 |
| `SOURCE_FORM_CONFLICT` | 409 | 已有 N 条任务使用该来源且未绑定会议，无法改为会议型 |
| `PROGRESS_FORBIDDEN` | 403 | 你没有补充进度的权限 |
| `PROGRESS_LIMIT_REACHED` | 409 | 该任务进度已达 200 条上限 |

既有码不变；`VALIDATION_FAILED` 仍为 422，用于字段级校验（包括 @ 人数、内容长度）。

---

## 8. 测试策略

### 8.1 后端（JUnit 5 + 真实 MySQL `supervise_test`，Flyway 开启）

| 测试 | 覆盖 |
|---|---|
| `SourceTypeServiceIT` | 创建/改名/排序/停用/启用/删除；重名 409；第 21 个 409；MEETING 锁定；NOTE→MEETING 护栏（N 条未绑会议）；编码生成形如 `S\d{5}`；审计 5 条 action |
| `SourceTypeApiIT` | admin 200 / 非 admin 403；未登录 401；palette 8 条；删除被引用 409；`usageCount` 正确 |
| `SourceUpgradeIT` | 空库跑 V4 后三条内置存在且颜色/排序正确；插入 r1 形态的历史任务后详情仍显示「会议决议/项目任务/日常待办」 |
| `TaskSourceValidationIT` | 未知编码 400 + fieldErrors；停用编码 400；NOTE 缺说明/带会议 422；MEETING 缺会议/决议项 422；决议项重复仍被拒 |
| `TaskSourceQueryParityIT` | 同一 `sourceType=S000004` 在 `/tasks`、`/dashboard/matrix`、`/dashboard/kpis` 返回同一集合与计数；旧 `sourceType=MEETING` 仍可用；非法编码 400 |
| `TaskProgressServiceIT` | 1–500 字；trim；200 条上限 409；append-only（无 update 接口）；同秒排序按 id；任何状态可补 |
| `TaskProgressAccessIT` | 负责人/管理员/管理层/@ 人均可写；无关用户 403；无关用户详情 404；被 @ 人详情 200、事件 200、状态动作 403 |
| `TaskProgressRegressionIT` | 补进度前后 `status/due_date/owner_id/dispatcher_id/updated_at` 不变；`task_events` 行数不变；矩阵五列与 6 项 KPI 不变 |
| `MentionNotificationIT` | 每(进度,用户)一条；type=MENTION、标题/正文截断；`progress_id` 落库；未配置 `NOT_CONFIGURED`、未绑定 `UNBOUND`；@10 人产生 10 条；不阻塞响应 |
| `NotificationPageTypeIT` | `type=MENTION` 过滤；未读计数含 MENTION；全部已读清零 |
| 既有 `MetaApiIT / TaskQueryBuilderTest / TaskJsonTest / TaskQueryDaoConsistencyIT` | 按新 DTO 更新断言，不得放宽 |

### 8.2 前端（Vitest + Vue Test Utils）

`SourceTag` 未知编码兜底、`ProgressComposer` 字数/空值/上限禁用、`MentionPicker` 去重/10 人上限/排除自己、`ProgressTimeline` 合并排序与锚点 id、`NotificationsPage` 三 tab 参数映射与深链、`SourceTypeTable` 引用>0 不渲染删除。

### 8.3 端到端

`scripts/smoke-api.sh` 扩展为：登录取 CSRF → 建自定义来源 → 用它建 NOTE 任务 → 追加两条进度并 @ 一人 → 通知列表出现 MENTION → 被 @ 人详情 200、状态动作 403 → 删除来源 409 → 停用后建任务 400。脚本必须在 main 上可重复跑（每次生成唯一名称，结束后停用该来源）。

### 8.4 门禁

`cd server && ./mvnw -q test`、`./mvnw -q -DskipTests package`、`cd web && pnpm test && pnpm build` 全绿；smoke 通过；`git status` 干净（任务夹与代码同一提交）。

---

## 9. 任务拆分

> 波次内文件互斥可并行；依赖只影响开始时间，不改变文件归属。**所有任务都在 main 上工作，不建分支**。

| ID | 任务 | 写文件（所有权） | 读文件 | 依赖 | 验收 |
|---|---|---|---|---|---|
| T01 | 来源配置后端：V4、source 域、palette、审计、错误码（来源部分） | `resources/db/migration/V4__source_types.sql`；`source/SourceForm.java`、`SourcePalette.java`、`SourceCodes.java`、`SourceTypeService.java`、`SourceTypeDao.java`、`SourceTypeController.java`、`source/dto/*`；`common/ErrorCode.java`；`test/.../source/SourceTypeServiceIT.java`、`SourceTypeApiIT.java`、`SourceUpgradeIT.java` | `V1__schema.sql`、`LoginAuditService.java`、`BusinessException.java` | — | 三张 IT 全绿；V4 在空库与 r1 升级库均通过；MEETING 锁行为逐条符合 AC-4 |
| T05 | 前端基础：令牌、类型、请求层、SourceTag | `web/src/styles/tokens.css`；`types/enums.ts`；`types/api.ts`；`api/sourceTypes.ts`；`api/tasks.ts`；`components/ui/SourceTag.vue` | DESIGN §1.2/§4、`api/http.ts` | — | `pnpm build` 通过；SourceTag 单测覆盖兜底与三元组 |
| T02 | 任务读模型与元数据接入配置（删枚举） | `task/Task.java`、`TaskService.java`、`TaskQueryBuilder.java`、`TaskQueryDao.java`、`TaskRowMapper.java`、`task/dto/TaskSource.java`、`meta/MetaService.java`、`meta/dto/MetaOptions.java`、`dashboard/MatrixService.java`、`dashboard/DashboardService.java`、`meeting/DecisionConversionService.java`（或同类转任务入口）、`common/SourceType.java`（删除）、相关测试 | T01 的 `SourceTypeService`、`SourcePalette` | T01 | 全仓无 `SourceType` 枚举引用；未知编码 400；旧 `MEETING` 链接可用；`TaskSourceQueryParityIT` 绿 |
| T06 | 设置页来源配置卡 | `components/settings/SourceTypeTable.vue`、`SourceTypeFormModal.vue`、`SourceColorSwatches.vue`；`pages/SettingsPage.vue` | T05 类型与 api、DESIGN §4/§8 | T01、T05 | 增删改启停排序全通；409 文案按 §5.1；20 上限与锁定态可见 |
| T07 | 建任务抽屉 / 筛选 / 矩阵配置化 | `components/task/TaskFormDrawer.vue`、`TaskFilterBar.vue`；`pages/MatrixPage.vue`、`pages/TaskPoolPage.vue` | T05 类型、meta 形状 §5.2 | T05（T02 合并后联调） | 下拉仅启用项；停用项仍可筛；编辑态来源只读；无 `SOURCE_TYPE_LABELS` 残留 |
| T03 | 进度 + @ 通知后端 | `resources/db/migration/V5__task_progress.sql`；`task/progress/**`；`task/TaskAccessGuard.java`；`task/dto/TaskPermissions.java`；`task/TaskService.java`（仅 permissions/mentionOnly）；`common/NotifyType.java`；`common/ErrorCode.java`（进度部分）；`notify/NotificationKeys.java`、`MessageTemplates.java`、`NotificationService.java`、`NotificationController.java`、`notify/dto/CreateNotificationCommand.java`、`NotificationRow.java`；`test/.../progress/*`、`notify/MentionNotificationIT.java`、`NotificationPageTypeIT.java` | T01 的 ErrorCode、r1 `NotificationServiceIT` | T01（错误码文件串行） | §8.1 九个 IT 全绿；AC-7/8/9/10/11/12 逐条可验 |
| T08 | 详情进度 + 通知中心前端 | `components/task/ProgressComposer.vue`、`MentionPicker.vue`、`MentionChips.vue`、`ProgressTimeline.vue`；`pages/TaskDetailPage.vue`、`pages/NotificationsPage.vue` | T05 类型/api、`useTaskActions`、`TaskActions.vue` | T05（T03 合并后联调） | 1 秒内入时间线；@ 选择器边界；200 上限；深链高亮；被 @ 视角无动作组 |
| T09 | 交付自检 + 冒烟 + Git | `scripts/smoke-api.sh`；`.gitignore`（如需）；任务夹文档 | 全部 | T01–T08 | 全量构建/测试/smoke 通过；main 上一次提交包含代码 + 任务夹；提交信息覆盖两项能力 |

**波次**

1. W1：T01 ∥ T05
2. W2：T02 ∥ T06 ∥ T07
3. W3：T03 ∥ T08
4. W4：T09

**Git 约束（落入 T09，T01–T08 亦须遵守）**

- 不执行 `git checkout -b` / `git switch -c`，全程在 main；
- 不 `git add -A`：只加任务夹、`server/`、`web/`、`scripts/`、必要的 `.gitignore`；
- 提交信息：`feat: 来源类型改为管理员可配置，任务支持补充进度并 @ 提醒`；正文列 AC 组与迁移编号；
- 任务夹 `docs/teamflow/20261109-r2-source-config-progress/` 与代码同一提交；
- `.gitignore` 复核：`target/`、`node_modules/`、`dist/`、`.pnpm-store/`、`.env`、`logs/` 已覆盖，勿新增宽泛规则。

---

## 10. AC 覆盖

| AC | 落点 | 验证 |
|---|---|---|
| AC-1 配置维护 | T01 + T06；V4 表与唯一键 | `SourceTypeServiceIT`、`SourceTypeApiIT`、设置页手工 |
| AC-2 按配置建任务 | T02 `validateSource` + T07 抽屉 | `TaskSourceValidationIT` |
| AC-3 升级兼容 | V4 种子；不改 `tasks` | `SourceUpgradeIT` |
| AC-4 删除/停用护栏 | T01 服务 + FK；T06 不渲染删除 | `SourceTypeServiceIT`、API IT |
| AC-5 同源一致 | T02 meta/query/mapper/matrix | `TaskSourceQueryParityIT` |
| AC-6 仅 admin + 审计 | T01 controller + `LoginAuditService` | `SourceTypeApiIT` 断言 403 与 audit 行 |
| AC-7 进度追加 | T03 service；T08 composer | `TaskProgressServiceIT`、前端单测 |
| AC-8 写权限 | `requireProgressWrite` 403 / `requireView` 404 | `TaskProgressAccessIT` |
| AC-9 @ 选择 | T03 校验 + T08 picker | `TaskProgressServiceIT`、picker 单测 |
| AC-10 @ 通知 | T03 MENTION + 深链 | `MentionNotificationIT` |
| AC-11 被 @ 视角 | guard + permissions + detail | `TaskProgressAccessIT`、详情手工 |
| AC-12 口径回归 | 只写新表；不写 `task_events` | `TaskProgressRegressionIT` |
| AC-13 通知中心 | T03 `type` 过滤 + T08 三 tab | `NotificationPageTypeIT`、页面单测 |

---

## 11. 风险与取舍

| 风险 | 处置 |
|---|---|
| `SELECT_BASE` 的 LEFT JOIN 与 `COUNT_BASE` 不一致导致计数/列表不匹配 | 计数 SQL 不 join（只按 `t.source_type` 过滤），列表 join 只取展示字段；`TaskQueryDaoConsistencyIT` 继续兜底 |
| 颜色在 `tokens.css` 与 `SourcePalette` 两处出现 | `SourcePaletteTest` 钉住 8 组 hex 字面值；`tokens.css` 仅作设计参考与静态场景，标签一律用 API 三元组 |
| 形态校验在应用层，绕过接口直写库仍可能写入形态不匹配的行 | 结构 CHECK + FK 已挡住崩溃型脏数据；形态错配只影响展示，后续可在 V6 加触发器（本轮不做） |
| 被 @ 可见性依赖 join 查询，热点任务详情多一次查询 | 覆盖索引 `(user_id, progress_id)`；单任务量级 ≤200，可接受 |
| 单条 @10 人 → 一次提交 10 条通知 + 10 次钉钉 | 站内同步入库（轻），钉钉异步 + 既有 1/5/15 退避；限速在 sender 层既有 |
| 真实钉钉凭证仍缺失 | 全链路按 `NOT_CONFIGURED` 降级，QA 只验状态与不阻塞；不伪造发送成功 |
| 迁移不可自动回滚 | 发布前备份；回退按 §3.4 逆序手工脚本 |
| DESIGN §3.3 曾允许编辑态改来源 | 本轮取「退化方案」保持只读，理由见 D6；若后续要放开，需单开需求处理决议项唯一与快照语义 |

---

## 12. 交付自检记录

T09 执行并只记录结论：

1. `cd server && ./mvnw -q test` —— 全绿（含 9 个新 IT）。
2. `cd server && ./mvnw -q -DskipTests package` —— 通过。
3. `cd web && pnpm test` / `pnpm build` —— 通过。
4. `bash scripts/smoke-api.sh` —— 通过（按 §8.3 扩展后）。
5. 空库 + r1 升级库各跑一次 `Flyway migrate` —— V4/V5 均成功，历史任务来源显示不变。
6. grep 确认：`SourceType` 枚举零引用、`SOURCE_TYPE_LABELS` 零引用、`task_events` 无进度写入。
7. `git status` 干净；main 上一个提交包含代码与任务夹；未新建分支。
