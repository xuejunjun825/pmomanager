<!-- meta: summary="会议任务督办系统技术设计：Spring Boot 3.3 + Vue 3 + MySQL 8 单实例；统一任务池四字段三层强制、读时逾期单一真源、状态机行锁串行化、@Scheduled 扫描 + job_runs 幂等、通知 dedup_key 与钉钉 outbox 退避、按人矩阵与看板同查询口径；含完整 API 契约、前端工程结构、测试矩阵与 12 个可并行任务的波次拆分。" -->
基线依赖：无

# 会议任务督办系统 · 技术设计

> 读者：开发 / QA / 验收。PRD 是契约，DESIGN 是视觉地板，本文件只回答"怎么建、建在哪、怎么验"。
> 现有工作区为**空仓**（仅 `docs/`、`logs/` 与 `.gitignore`，`git log` 只有一个 baseline 提交，无任何源码、无 AGENTS.md）。因此本文同时确立工程骨架；路径一旦定下即为后续所有任务的写入边界。

---

## 1. 技术选型与关键决策

| 决策 | 选择 | 理由（为什么不选另一个） |
| --- | --- | --- |
| 后端 | Java 17 + Spring Boot 3.3.5 + Maven Wrapper | PRD 已定；`@Scheduled`、Flyway、Security、Validation 全在 Boot 生态内，不引额外框架 |
| 持久层 | Spring Data JPA（写）+ 原生 SQL 查询构建器（读） | 写路径需要实体、乐观锁、级联与唯一约束；读路径（任务池/矩阵/看板/焦点）要求**同一段逾期谓词与同一套聚合口径**，JPA Criteria 反而把 SQL 藏起来、易漂移。故读写分离，读侧集中到 `TaskQueryDao` |
| 前端 | Vue 3.5 + TypeScript 5 + Vite 5 + Pinia 2 + Vue Router 4 + Axios | PRD 已定 Vue；**不引 Element Plus/Ant Design**：DESIGN 规定 48px 行高、1px 细边框、6 种语义色、自绘 Badge/Pill/矩阵格，组件库主题覆盖成本高于自绘 14 个基础组件，且容易破坏"红色是配额资源" |
| 鉴权 | 服务端 Session（`HttpSession`）+ Cookie（HttpOnly/SameSite=Lax）+ CSRF Token | AC-1 要求"退出后原会话立即失效"、7 天有效期、钉钉登录后建立会话——服务端会话是最短路径；JWT 无法即时吊销。单实例部署，内存会话可接受（重启后需重登，见 §11 风险） |
| 时区 | JVM `-Duser.timezone=Asia/Shanghai` + `serverTimezone`/`connectionTimeZone=Asia/Shanghai` + `@Scheduled(zone="Asia/Shanghai")`，业务日期只认 `ShanghaiClock` | AC-11/AC-17 要求"同一个今天"；把 `today` 作为**参数**贯穿一切查询，而不是在 SQL 里调 `CURDATE()` |
| 调度 | `@Scheduled`（扫描固定 09:00）+ 1 分钟轮询器（日报/周报，时间可配） | 日报时间要能在系统设置里改，`@Scheduled(cron)` 编译期固定；轮询 + `job_runs` 唯一键既支持可配时间，又天然幂等与补跑 |
| 钉钉 | 服务端直调 `oapi.dingtalk.com` / `api.dingtalk.com`，自建 `RestClient` 封装 | 只用到取 token、免登换身份、工作通知三个接口，不引 SDK 可避免版本与内部 HTTP 客户端绑定 |
| 构建 | 后端 `server/`（Maven，`target/`）+ 前端 `web/`（pnpm，`dist/`），Nginx 同源托管 | 单机内网、同源 Cookie 免跨域；Vite dev 用 `/api` 代理 |

**为什么代码分两个顶层目录**：前后端构建工具链与依赖树完全不同，同仓分目录是单实例内网交付最省心的形态；`docker-compose.yml` 仅本地起 MySQL，不参与生产部署（生产是 jar + Nginx + 现成 MySQL 8）。

---

## 2. 目录结构与模块边界

```
.
├── server/
│   ├── pom.xml  mvnw  mvnw.cmd
│   └── src/main/
│       ├── java/com/pmo/supervise/
│       │   ├── SuperviseApplication.java
│       │   ├── common/          ApiError, ErrorCode, GlobalExceptionHandler, PageResponse,
│       │   │                    Actor, ShanghaiClock, Role, TaskStatus, SourceType,
│       │   │                    NotifyType, DingTalkStatus, BusinessException
│       │   ├── config/          SecurityConfig, WebConfig, SchedulingConfig, AsyncConfig,
│       │   │                    DingTalkProperties, AppProperties, JacksonConfig
│       │   ├── auth/            AuthController, AuthService, DingTalkAuthService,
│       │   │                    DingTalkStateStore, LoginAuditService, SessionUser
│       │   ├── user/            User, UserRepository, UserService, UserController, dto/
│       │   ├── meeting/         Meeting, DecisionItem, *Repository, MeetingService,
│       │   │                    DecisionConversionService, MeetingController, dto/
│       │   ├── task/            Task, TaskEvent, *Repository, TaskService,
│       │   │                    TaskCommandService, TaskAccessGuard, TaskController,
│       │   │                    TaskQueryBuilder, TaskQueryDao, OverdueSpec, TaskSql, dto/
│       │   ├── notify/          Notification, UrgeRecord, *Repository, NotificationService,
│       │   │                    NotificationController, UrgeService, MessageTemplates,
│       │   │                    DingTalkSender, DingTalkRetryJob, dto/
│       │   ├── scan/            JobRun, JobRunRepository, JobRunService, DailyScanJob
│       │   ├── report/          ReportService, ReportJob
│       │   ├── dashboard/       MatrixService, DashboardService, MatrixController,
│       │   │                    DashboardController, dto/
│       │   └── settings/        Setting, SettingRepository, SettingService, SettingController
│       └── resources/
│           ├── application.yml  application-local.yml.example
│           └── db/migration/    V1__schema.sql  V2__seed_admin.sql  V3__seed_settings.sql
├── web/
│   ├── package.json  vite.config.ts  tsconfig.json  index.html  .env.example
│   └── src/
│       ├── main.ts  App.vue
│       ├── router/index.ts        routes + 全局守卫
│       ├── stores/                auth.ts  notifications.ts  ui.ts
│       ├── api/                   http.ts  auth.ts  tasks.ts  meetings.ts  notifications.ts
│       │                          users.ts  matrix.ts  dashboard.ts  settings.ts  meta.ts
│       ├── types/                 api.ts  enums.ts
│       ├── layout/                AppShell.vue  SideNav.vue  TopBar.vue  Breadcrumb.vue
│       ├── components/ui/         AppButton AppInput AppSelect AppDatePicker AppTextarea
│       │                          AppBadge StatusBadge OverduePill SourceTag OwnerChip
│       │                          DataTable Pagination KpiCard MatrixCell ChannelChip
│       │                          Drawer Modal ConfirmDialog ReasonDialog ToastHost
│       │                          EmptyState ErrorState SkeletonTable PageHeader Timeline
│       ├── components/task/       TaskTable TaskFilterBar TaskFormDrawer TaskActions
│       │                          TransitionTimeline UrgeButton
│       ├── pages/                 LoginPage SsoCallbackPage TaskPoolPage MyTasksPage
│       │                          TaskDetailPage MeetingsPage MeetingDetailPage MatrixPage
│       │                          DashboardPage NotificationsPage UsersPage SettingsPage
│       │                          ChangePasswordModal
│       ├── pages/error/           ForbiddenPage NotFoundPage UnavailablePage ServerErrorPage
│       ├── composables/           useQuerySync.ts  useTaskActions.ts  useToast.ts
│       ├── styles/                tokens.css  base.css
│       └── utils/                 format.ts  permissions.ts
├── deploy/  nginx.conf.example  supervise.service.example  .env.example
├── scripts/ smoke-api.sh
├── docker-compose.yml            # 仅本地 MySQL 8
├── .gitignore  README.md
└── docs/teamflow/…               # 本需求文档夹（不改动）
```

**分层规则**：`Controller` 只做参数绑定 + 调用 Service + 返回 DTO，**不含权限 if**；权限与状态校验全部在 Service 层，由 `TaskAccessGuard` 一处判定；`Repository` 只做数据访问；跨模块调用只允许 `task → user`、`meeting → task`（转换）、`notify → task`、`dashboard → task`（只读），不允许反向依赖（`task` 不依赖 `notify`，由事件/调用方解耦：任务流转后由 `TaskCommandService` 调 `NotificationService`，接口在 `notify` 侧定义）。

---

## 3. 数据模型与存储

MySQL 8.0，InnoDB，`utf8mb4_0900_ai_ci`；时间列 `DATETIME`（应用层写上海本地时间，连接串固定时区），日期列 `DATE`。主键统一 `BIGINT UNSIGNED AUTO_INCREMENT`。

### 3.1 表清单

| 表 | 作用 | 关键约束 |
| --- | --- | --- |
| `users` | 用户 + 角色 + 钉钉身份 | `uk_users_username`、`uk_users_union`（可空，MySQL 允许多个 NULL） |
| `meetings` | 会议 | 只归档不删除（`archived`） |
| `decision_items` | 决议事项 | 每条必填描述/负责人/截止日；`idx_di_meeting(meeting_id,sort_no)` |
| `tasks` | 统一任务池 | 四字段 `NOT NULL`；`uk_tasks_decision(source_decision_item_id)` 保证"一决议项一任务"；`ck_tasks_source`、`ck_tasks_title` |
| `task_events` | 流转留痕 | `idx_event_task(task_id,created_at)` |
| `notifications` | 站内通知 + 钉钉渠道状态 | `uk_notify_dedup(dedup_key)` 幂等 |
| `urge_records` | 催办记录 | `idx_urge_task_actor(task_id,actor_id,created_at)` 支撑 10 分钟窗口 |
| `job_runs` | 定时任务幂等/补跑 | PK `(job_key,run_date)` |
| `settings` | 系统设置 KV | `k` 主键 |
| `audit_logs` | 登录/鉴权/设置审计 | `idx_audit_action(action,created_at)` |

### 3.2 核心 DDL（Flyway `V1__schema.sql` 的契约，实现时按此逐字落地）

```sql
CREATE TABLE tasks (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title                 VARCHAR(100)    NOT NULL,
  owner_id              BIGINT UNSIGNED NOT NULL,
  due_date              DATE            NOT NULL,
  source_type           VARCHAR(16)     NOT NULL,          -- MEETING | PROJECT | DAILY
  source_meeting_id     BIGINT UNSIGNED NULL,
  source_decision_item_id BIGINT UNSIGNED NULL,
  source_note           VARCHAR(200)    NULL,
  status                VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
  dispatcher_id         BIGINT UNSIGNED NOT NULL,
  priority              VARCHAR(8)      NOT NULL DEFAULT 'MEDIUM',
  completion_note       VARCHAR(500)    NULL,
  completion_url        VARCHAR(500)    NULL,
  submitted_at          DATETIME        NULL,
  completed_at          DATETIME        NULL,
  cancelled_at          DATETIME        NULL,
  version               INT             NOT NULL DEFAULT 0,
  created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tasks_decision (source_decision_item_id),
  KEY idx_tasks_owner_status_due (owner_id, status, due_date),
  KEY idx_tasks_status_due (status, due_date),
  KEY idx_tasks_meeting (source_meeting_id),
  CONSTRAINT fk_tasks_owner      FOREIGN KEY (owner_id)      REFERENCES users(id),
  CONSTRAINT fk_tasks_dispatcher FOREIGN KEY (dispatcher_id) REFERENCES users(id),
  CONSTRAINT fk_tasks_meeting    FOREIGN KEY (source_meeting_id) REFERENCES meetings(id),
  CONSTRAINT fk_tasks_decision   FOREIGN KEY (source_decision_item_id) REFERENCES decision_items(id),
  CONSTRAINT ck_tasks_source CHECK (
    (source_type = 'MEETING' AND source_meeting_id IS NOT NULL AND source_decision_item_id IS NOT NULL AND source_note IS NULL)
    OR (source_type IN ('PROJECT','DAILY') AND source_note IS NOT NULL AND source_meeting_id IS NULL AND source_decision_item_id IS NULL)
  ),
  CONSTRAINT ck_tasks_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

其余表同风格，字段如下（略去重复的时间戳列）：

- `users(id, username NULL UNIQUE, password_hash NULL, dingtalk_union_id NULL UNIQUE, dingtalk_user_id NULL, name NOT NULL, avatar_url NULL, department NULL, role NOT NULL DEFAULT 'OWNER', status NOT NULL DEFAULT 'ACTIVE', must_change_password TINYINT(1) NOT NULL DEFAULT 0, last_login_at NULL, created_at, updated_at)` —— `password_hash` 用 BCrypt（cost 10）；钉钉用户可无密码。
- `meetings(id, title VARCHAR(100) NOT NULL, meeting_date DATE NOT NULL, host VARCHAR(64) NOT NULL, attendees VARCHAR(500) NULL, note VARCHAR(500) NULL, archived TINYINT(1) NOT NULL DEFAULT 0, created_by NOT NULL, created_at, updated_at)` —— 参会人是自由文本（本轮不做用户关联）。
- `decision_items(id, meeting_id NOT NULL, description VARCHAR(100) NOT NULL, owner_id NOT NULL, due_date DATE NOT NULL, delivery_note VARCHAR(500) NULL, sort_no INT NOT NULL DEFAULT 0, created_at, updated_at)` + FK meeting/owner，`KEY idx_di_meeting(meeting_id, sort_no)`。**不设 `task_id` 列**：转化状态由 `tasks.source_decision_item_id` 反查，避免双写不一致。
- `task_events(id, task_id NOT NULL, action VARCHAR(24) NOT NULL, actor_id NOT NULL, from_status VARCHAR(16) NOT NULL, to_status VARCHAR(16) NOT NULL, reason VARCHAR(500) NULL, created_at)`。
- `notifications(id, user_id NOT NULL, task_id NULL, type VARCHAR(24) NOT NULL, notify_date DATE NOT NULL, dedup_key VARCHAR(128) NOT NULL UNIQUE, title VARCHAR(120) NOT NULL, body VARCHAR(1000) NOT NULL, dingtalk_status VARCHAR(24) NOT NULL DEFAULT 'PENDING', dingtalk_attempts TINYINT NOT NULL DEFAULT 0, dingtalk_next_retry_at DATETIME NULL, dingtalk_last_error VARCHAR(500) NULL, read_at DATETIME NULL, created_at, KEY idx_notify_user(user_id, created_at), KEY idx_notify_retry(dingtalk_status, dingtalk_next_retry_at))`。
- `urge_records(id, task_id NOT NULL, actor_id NOT NULL, dingtalk_status VARCHAR(24) NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))`。
- `job_runs(job_key VARCHAR(32) NOT NULL, run_date DATE NOT NULL, status VARCHAR(16) NOT NULL, detail VARCHAR(500) NULL, started_at DATETIME NOT NULL, finished_at DATETIME NULL, PRIMARY KEY(job_key, run_date))`。
- `settings(k VARCHAR(64) PK, v VARCHAR(2000) NOT NULL, updated_by NULL, updated_at)`。
- `audit_logs(id, actor_id NULL, action VARCHAR(48) NOT NULL, target_type VARCHAR(32) NULL, target_id VARCHAR(64) NULL, detail VARCHAR(1000) NULL, ip VARCHAR(45) NULL, created_at)`。

### 3.3 枚举取值（代码 enum 名 ↔ 库值，全项目只此一套）

| 枚举 | 库值 |
| --- | --- |
| `TaskStatus` | `PENDING` 待开始 / `IN_PROGRESS` 进行中 / `PENDING_ACCEPT` 待验收 / `DONE` 已完成 / `CANCELLED` 已取消 |
| `SourceType` | `MEETING` 会议决议 / `PROJECT` 项目任务 / `DAILY` 日常待办 |
| `Role` | `ADMIN` / `OWNER` / `EXEC` |
| `UserStatus` | `ACTIVE` / `DISABLED` |
| `Priority` | `HIGH` / `MEDIUM` / `LOW` |
| `NotifyType` | `DUE_SOON` / `OVERDUE` / `URGE` / `REJECTED` / `REASSIGNED` / `RESCHEDULED` / `DAILY_REPORT` / `WEEKLY_REPORT` |
| `DingTalkStatus` | `PENDING` / `SENT` / `FAILED` / `NOT_CONFIGURED` / `UNBOUND` / `SKIPPED` |
| `JobKey` | `DAILY_SCAN` / `DAILY_REPORT` / `WEEKLY_REPORT` |

### 3.4 迁移与初始化

- `V1__schema.sql`：全部表 + 索引 + CHECK + 外键（顺序：users → meetings → decision_items → tasks → task_events → notifications → urge_records → job_runs → settings → audit_logs）。
- `V2__seed_admin.sql`：`admin` / BCrypt(`Admin@12345`) / role=ADMIN / must_change_password=1。README 显著位置写明首登必须改密，且 `Admin@12345` 仅用于首次部署。
- `V3__seed_settings.sql`：`report.daily.enabled=true`、`report.daily.time=18:00`、`report.weekly.enabled=true`、`report.weekly.time=09:00`、`report.recipients=[]`。
- `docker-compose.yml` 起本地 MySQL 8（`--character-set-server=utf8mb4`、`--default-time-zone=+08:00`）；生产不依赖 docker。

---

## 4. 核心机制

### 4.1 逾期：读时计算，单一真源（AC-11 / AC-17）

**规则**：`isOverdue = status ∈ {PENDING, IN_PROGRESS, PENDING_ACCEPT} ∧ today > dueDate`；`overdueDays = DAYS.between(dueDate, today)`；`dueSoon = status ∈ 未完成 ∧ dueDate == today+1`。截止日当天不算逾期。

**实现要点**：
1. `ShanghaiClock`（`@Component`，内部 `Clock` 可注入）提供 `LocalDate today()`；`today` 在每个读请求入口取**一次**，作为参数传进所有查询（`TaskQueryDao` 每个方法都带 `LocalDate today`），SQL 内**不出现** `CURDATE()`/`NOW()` 做业务判断。
2. SQL 侧只有一处谓词常量 `TaskSql.OVERDUE = "(t.status IN ('PENDING','IN_PROGRESS','PENDING_ACCEPT') AND t.due_date < :today)"`，被任务池 WHERE、焦点排序、矩阵 SUM、看板 COUNT、扫描 SELECT 共用。
3. Java 侧只有一处判定 `OverdueSpec.isOverdue(status, dueDate, today)`，供详情 DTO 与单元测试使用。
4. **两份表示必须互相钉住**：`OverdueSqlParityTest` 用 15 组数据同时跑 SQL 与 `OverdueSpec`，断言逐条相等（覆盖 AC-11 的 15 组单测）。这是本设计唯一允许的重复实现，理由与盯防已写入 blueprint 的 `duplications`。

### 4.2 状态机（AC-9 / AC-10）

已用 `logicprobe_verify` 对 5 状态 / 18 迁移建模验证：**无死锁、无不可达状态、无同状态同事件歧义**（`verdict=pass_with_findings`，0 error）。发现并已在设计里处置的两点：

- **10 组未定义 (状态, 事件) 对**（如 `DONE + START`、`PENDING + ACCEPT`）属正常：接口层统一返回 `409 TASK_INVALID_TRANSITION`，不静默忽略、不改状态。
- **顺序相关**（`ACCEPT` 与 `CANCEL` 并发于 `PENDING_ACCEPT` 时结果不同）是真实竞态：以 `SELECT … FOR UPDATE` 行锁把同一任务的状态变更串行化，先提交者胜，后到者读到的当前状态已不满足前置条件 → 409。事件表与状态在同一事务内写入，保证留痕与状态一致。

| 当前 | 动作 | 目标 | 权限 | 必填 |
| --- | --- | --- | --- | --- |
| PENDING | `start` | IN_PROGRESS | owner 本人 / admin | — |
| PENDING, IN_PROGRESS | `submit` | PENDING_ACCEPT | owner 本人 / admin（代提交） | 完成说明/链接可选 |
| PENDING_ACCEPT | `accept` | DONE（写 `completed_at`） | 派发人 / admin | — |
| PENDING_ACCEPT | `reject` | IN_PROGRESS（清 `submitted_at`） | 派发人 / admin | reason 2–200 |
| PENDING, IN_PROGRESS, PENDING_ACCEPT | `cancel` | CANCELLED（写 `cancelled_at`） | admin | reason 2–200 |
| CANCELLED | `restore` | PENDING | admin | — |
| PENDING, IN_PROGRESS, PENDING_ACCEPT | `force-complete` | DONE | admin | reason 2–200 |
| PENDING, IN_PROGRESS, PENDING_ACCEPT | `reassign` | 状态不变 | admin / exec | ownerId + reason |
| PENDING, IN_PROGRESS, PENDING_ACCEPT | `reschedule` | 状态不变 | admin / exec | dueDate + reason |

- 每次动作写 1 条 `task_events`；`reassign`/`reschedule` 另在 `reason` 里带旧值→新值（如 `负责人 张三→李四`），不额外建表。
- `DONE` 无任何出边；重开只能新建任务（PRD 明确）。
- 实现：`@Transactional` + `TaskRepository.findByIdForUpdate(id)`（`@Lock(PESSIMISTIC_WRITE)`），`Task.version` 作为第二道保险（`ObjectOptimisticLockingFailureException` → 409）。

### 4.3 权限：一处判定，两种拒绝语义（AC-2）

| 操作 | 判定方法 | 拒绝 |
| --- | --- | --- |
| 查看任务 | `TaskAccessGuard.requireView`：admin/exec 全量；owner 仅 `ownerId == self` | owner 越界 → **404**（与不存在同响应，防探测） |
| 开始/提交 | `requireOperate`：owner 本人，或 admin | 403（他人任务已在 requireView 拦成 404） |
| 验收/打回 | `requireAccept`：admin，或 `dispatcherId == self` | 403 |
| 取消/恢复/代确认 | `requireAdmin` | 403 |
| 改派/改期 | `requireAssign`：admin 或 exec | 403 |
| 催办 | `requireUrge`：admin 或 exec；任务已结束 → 409 | 403 / 409 |
| 矩阵/看板 | `requireGlobalRead`：admin 或 exec | 403 |
| 用户/设置 | `requireAdmin` | 403 |

- `Actor`（`id/role/name`）由 `SessionUser` 解析后注入 Controller 参数，Service 只认 `Actor`，不读 `SecurityContextHolder`（可单测）。
- 页面级拦截由前端路由守卫完成（体验），**安全由服务端保证**；菜单隐藏不作为措施。
- `GET /api/tasks` 对 owner 强制忽略其 `ownerId` 筛选参数并固定为本人（否则等于提供了越权读入口）。

### 4.4 会议 → 决议 → 任务（AC-3 / AC-4 / AC-5）

- 决议项保存：`@Valid` 逐项校验 + 服务端二次校验（负责人必须 `ACTIVE`）；**逐项**返回 `fieldErrors`，带 `index`，前端定位到行。
- 转任务 `POST /api/meetings/{id}/convert {itemIds[]}`：单事务内逐条 `INSERT`，每条写 `source_decision_item_id`；命中 `uk_tasks_decision` 或已存在时**不报错**，收集进 `skipped[{itemId, reason:'ALREADY_CONVERTED', taskId}]`；返回 `200 {created[], skipped[]}`（部分成功是正常结果，符合"重复点击不产生重复任务，提示该项已转任务"）。
- 派发人 = 当前登录管理员；来源字段创建后不可改（`PATCH /api/tasks/{id}` 收到 `source*` → `422 TASK_SOURCE_IMMUTABLE`）。"重新关联"本轮不做（PRD 允许但不要求），在 §11 记为未实现项。
- 归档：`meetings.archived=1`；列表默认 `archived=false`；详情与任务来源 **不校验归档**，仍可打开；归档会议禁止编辑与增删决议项（409 `MEETING_ARCHIVED`）；不提供物理删除接口。
- 决议项一旦被转为任务即锁定（编辑/删除 → 409 `DECISION_ITEM_LOCKED`），保证来源快照稳定；后续改人改期走任务接口。
- 会议闭环：`closed = 该会议下任务数 > 0 且未完成任务数 == 0`；详情返回 `taskCount / unfinishedCount / closed`，页面按此显示"已闭环"或"未完成 N 条"。

### 4.5 每日扫描、补跑与去重（AC-12）

```
@Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")  → DailyScanJob.scheduled()
ApplicationReadyEvent                                        → 若今天 09:00 已过且 job_runs 无 DAILY_SCAN/今天 → runForDate(today)
runForDate(date):
  1) JobRunService.tryStart(DAILY_SCAN, date)  → INSERT job_runs（PK 冲突则直接返回，实现"重复触发/重启不重复"）
  2) 选 dueSoon：未完成 ∧ due_date = date+1 → 生成 DUE_SOON
  3) 选 overdue ：TaskSql.OVERDUE(date)        → 生成 OVERDUE（body 含逾期天数）
  4) 逐条 NotificationService.createIfAbsent(dedupKey="task:{taskId}:{date}:{type}")
     插入成功者才进入推送队列；唯一键冲突 = 已发过，跳过
  5) 推送钉钉（见 4.6），失败不影响本批完成
  6) JobRunService.finish(DAILY_SCAN, date, "dueSoon=3,overdue=7,pushed=2,skipped=8")
```
- 补跑**只补当天**，不补历史日期（避免上线即轰炸）。
- 已完成/已取消不进候选集（`TaskSql.NOT_FINISHED`）；周末照常（cron 不设 `MON-FRI`）。
- 任务在 09:00 后新增且当天已扫过：不补发（PRD 未要求），次日进入逾期提醒；此行为写入 §11。

### 4.6 通知、催办与钉钉降级（AC-13 / AC-14 / AC-19 / AC-21）

**通知写入**：`NotificationService.createIfAbsent(cmd)` 先 `INSERT`，捕获 `DataIntegrityViolationException` 返回 `Optional.empty()` —— 用数据库唯一键做幂等，而不是"先查后插"（后者在并发下会重复）。`dedup_key` 规则：

| 场景 | dedup_key |
| --- | --- |
| T-1 / 逾期 | `task:{taskId}:{notifyDate}:{type}` |
| 催办 | `urge:{taskId}:{notifyDate}:{actorId}:{n}`，`n` 为该 (task,actor,date) 内第几次；10 分钟窗口内不会产生第 2 条 |
| 打回/改派/改期 | `task:{taskId}:{eventId}:{type}`（事件 id 天然唯一） |
| 日报/周报 | `report:{userId}:{date}:{type}` |

**催办**（`UrgeService.urge`）：事务内 `findByIdForUpdate` → 校验任务未结束（否则 409 `TASK_FINISHED`）→ 查该 (task, actor) 最近一条 `urge_records.created_at`，若 `< now-10min` 不成立 → 409 `URGE_TOO_SOON`（body 带 `lastUrgedAt`，前端渲染"已于 HH:mm 催办过"）→ 写 `urge_records` + `notifications(URGE)` → 钉钉状态写回 → 返回 `{urgeCount, lastUrgedAt, channelStatus, message}`。10 分钟窗口按 **(任务, 操作人)**，不是全局限流。

**钉钉发送**：`DingTalkSender.sendWorkNotice(unionId, title, markdown, url)` 返回 `DingTalkStatus`：
- 未配置 → 立即 `NOT_CONFIGURED`，**不重试、不抛异常**；
- 用户无 `dingtalk_union_id` → `UNBOUND`；
- 调用成功 → `SENT`；失败 → 写 `dingtalk_attempts+1`、`next_retry_at = now + {1,5,15}min`、`FAILED`（第 3 次后不再排期）。
- `DingTalkRetryJob`（`@Scheduled(fixedDelay=60_000)`）捞 `dingtalk_status IN ('PENDING','FAILED') AND next_retry_at <= now AND attempts < 3`，串行发送；限速用 `Semaphore(1)` + 每批间隔 50ms（≤20 条/秒）。
- 重试**只更新同一行**，不新建通知 → 与站内通知共用 dedup_key，天然不重复。
- 异步：业务事务提交后通过 `@TransactionalEventListener(AFTER_COMMIT)` 触发发送，发送在 `dingtalkExecutor`（core 2 / max 4 / queue 200）执行；队列满则丢弃并落 `FAILED`（"不阻塞业务"优先于"必达"）。

**渠道文案映射**（接口统一返回 `channelStatus` + `channelLabel`）：`SENT→钉钉已送达`、`FAILED→钉钉失败`、`NOT_CONFIGURED→钉钉未配置`、`UNBOUND→未绑定钉钉`、`PENDING→推送中`；`SKIPPED→仅站内`。

### 4.7 日报 / 周报（AC-20）

- `ReportJob.tick()` 每分钟一次：读 `settings`，若 `enabled` 且 `now(HH:mm) >= 配置时间` 且 `job_runs` 无当日记录 → 执行；`JobRunService` 保证一天一次，重复触发无副作用（AC-20 "重复触发不重复发送"）。
- 收件人 = `report.recipients` 中的用户 id；为空 → **不发送**，`job_runs.detail` 与设置页均显示"未配置收件人"，且 `GET /api/settings` 返回 `recipientsEmpty=true` 供页面提示。
- 内容直接复用看板查询：`DashboardService.load(filter=all, today)`，**不二次计算**（AC-20 "同一查询服务"）。
- 日报 = 6 项 KPI + 焦点前 10 + 逾期总数/今日新增；周报额外含本周新增/完成/逾期（按 `created_at`/`completed_at`/`due_date` 聚合）。
- 发送走同一 `DingTalkSender` + 同一重试/留痕规则；未配置 → `NOT_CONFIGURED` 并结束，不重试。

### 4.8 会话、CSRF 与钉钉登录（AC-1 / AC-18）

- 会话：`server.servlet.session.timeout=7d`，Cookie `HttpOnly; SameSite=Lax; Path=/`（HTTPS 下 `Secure`，由 `server.forward-headers-strategy=framework` 识别 Nginx 的 `X-Forwarded-Proto`）。登出 `session.invalidate()` → 旧 Cookie 立即失效。
- CSRF：`CookieCsrfTokenRepository.withHttpOnlyFalse()` + `X-XSRF-TOKEN` 头；`GET /api/auth/config` 负责下发 `XSRF-TOKEN`。回调类 GET 不校验 CSRF（幂等 GET），其余写操作全校验。
- 密码：BCrypt；错误提示统一 `账号或密码不正确`（不区分账号不存在/密码错）；`status=DISABLED` → 403 `账号已停用，请联系管理员`；`must_change_password=1` 时除 `/api/auth/me`、`/api/auth/password`、`/api/auth/logout` 外一律 409 `PASSWORD_CHANGE_REQUIRED`（前端弹不可关闭改密模态）。
- 钉钉扫码：`GET /api/auth/dingtalk/authorize-url?next=` 生成 `state`（32 字节随机，存 session，TTL 5 分钟，单次消费）并拼 `https://login.dingtalk.com/oauth2/auth?...`；回调 `GET /api/auth/dingtalk/callback` 校验 `state`（失败 → `400 AUTH_STATE_INVALID` 并写审计）→ `code` 换 `accessToken` → `GET /v1.0/contact/users/me` 取 `unionId/nick/avatarUrl` → 按 `dingtalk_union_id` 建档或更新（新用户默认 `OWNER`、`ACTIVE`）→ 建立会话 → `302` 到 `/sso/callback?next=…`（前端再取 `/api/auth/me` 按角色跳转）。
- 钉钉内免登：前端在已配置时加载 `dingtalk-jsapi`，`dd.runtime.permission.requestAuthCode({corpId})` 取 `code` → `POST /api/auth/dingtalk/auto {authCode,next}` → 同上换身份建会话；失败则回退扫码入口。
- 未配置：`/api/auth/dingtalk/**`（除 `config`）一律 `503 DINGTALK_NOT_CONFIGURED`；登录页据 `config.dingtalkConfigured` 隐藏入口。
- 配置读取：`DingTalkProperties` 绑定 `DINGTALK_CORP_ID` 等环境变量；`DingTalkConfigService.missingKeys()` 返回缺失项名称（**不返回值**），启动时若缺失只打 1 条 WARN。**补齐配置后重启生效**（README 写明；不做热加载，避免配置变更的并发歧义）。

---

## 5. API 设计

统一约定：前缀 `/api`；请求/响应 `application/json; charset=utf-8`；成功直接返回数据（列表为 `{items,total,page,size}`）；失败返回

```json
{"code":"TASK_INVALID_TRANSITION","message":"任务当前状态为已完成，不能执行开始","fieldErrors":[{"field":"dueDate","message":"截止日期不能为空","index":0}],"details":{"currentStatus":"DONE"},"traceId":"…"}
```

`fieldErrors[].index` 仅决议项批量保存时出现，用于定位到行。

### 5.1 认证 `/api/auth`

| 方法 | 路径 | 权限 | 请求 | 响应 / 错误 |
| --- | --- | --- | --- | --- |
| GET | `/config` | 公开 | — | `{dingtalkConfigured, missingKeys[], passwordLogin:true, siteName}`；同时下发 `XSRF-TOKEN` |
| POST | `/login` | 公开 | `{username,password}` | `200 AuthUser`；`401 AUTH_BAD_CREDENTIALS`；`403 AUTH_DISABLED` |
| POST | `/logout` | 登录 | — | `204`（会话销毁） |
| GET | `/me` | 登录 | — | `AuthUser{id,name,role,avatarUrl,mustChangePassword,landingPath}` |
| POST | `/password` | 登录 | `{oldPassword,newPassword}` | `204`；`422 AUTH_PASSWORD_WEAK`；`400 AUTH_OLD_PASSWORD_WRONG` |
| GET | `/dingtalk/authorize-url` | 公开 | `?next=` | `{authorizeUrl}`；`503 DINGTALK_NOT_CONFIGURED` |
| GET | `/dingtalk/callback` | 公开 | `?code&state` | `302 /sso/callback?next=…`；`400 AUTH_STATE_INVALID`；`503` |
| POST | `/dingtalk/auto` | 公开 | `{authCode,next}` | `200 AuthUser`；`401 AUTH_BAD_CREDENTIALS`；`403 AUTH_DISABLED`；`503` |

### 5.2 用户 `/api/users`（admin）

| 方法 | 路径 | 请求 | 响应 |
| --- | --- | --- | --- |
| GET | `` | `?keyword&role&status&page&size` | `{items:UserRow[],total,page,size}` |
| GET | `/options` | `?includeDisabled=false` | `[{id,name,role,status}]`（表单/筛选下拉） |
| PATCH | `/{id}/role` | `{role}` | `200 UserRow` |
| PATCH | `/{id}/status` | `{status}` | `200 UserRow`；停用自己 → `409 SELF_DISABLE_FORBIDDEN` |
| POST | `/{id}/password-reset` | — | `{temporaryPassword}`（12 位随机，置 `must_change_password=1`） |

### 5.3 会议 `/api/meetings`

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `` | admin | `?archived=false&keyword&page&size` → `MeetingRow{id,title,meetingDate,host,archived,taskCount,unfinishedCount,closed}` |
| POST | `` | admin | `{title,meetingDate,host,attendees[],note}` → `201 MeetingDetail` |
| GET | `/{id}` | admin | `MeetingDetail{…, items:[DecisionItemRow], taskCount, unfinishedCount, closed}`；归档亦可读 |
| PUT | `/{id}` | admin | 同 POST；归档后 → `409 MEETING_ARCHIVED` |
| POST | `/{id}/archive` | admin | `200`；幂等 |
| POST | `/{id}/decision-items` | admin | `{description,ownerId,dueDate,deliveryNote}` → `201 DecisionItemRow` |
| PUT | `/api/decision-items/{id}` | admin | 已转任务 → `409 DECISION_ITEM_LOCKED` |
| DELETE | `/api/decision-items/{id}` | admin | 已转任务 → `409`；`204` |
| POST | `/{id}/convert` | admin | `{itemIds[]}` → `200 {created:[{itemId,taskId}], skipped:[{itemId,reason,taskId}]}` |

`DecisionItemRow{id,description,owner:{id,name},dueDate,deliveryNote,sortNo,converted, taskId, taskStatus, isOverdue, overdueDays}`。

### 5.4 任务 `/api/tasks`

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `` | admin/exec 全量；owner 自动限本人 | `?status&ownerId&sourceType&meetingId&overdue&keyword&page&size&sort` → `TaskPage`；`overdue=true/false`；`sort=due_asc\|updated_desc`（默认按 4.5/PRD 的混合排序） |
| GET | `/{id}` | 按 4.3 | `TaskDetail`（含 `events[]`、`permissions{}`） |
| POST | `` | admin | `{title,ownerId,dueDate,sourceType,sourceMeetingId?,sourceDecisionItemId?,sourceNote?,priority?}` → `201 TaskDetail`；缺项 `422` |
| PATCH | `/{id}` | admin | `{title?,ownerId?,dueDate?,priority?,sourceNote?}`；含 `source*` → `422 TASK_SOURCE_IMMUTABLE`；改人/改期**走** `reassign`/`reschedule` 以留痕 |
| GET | `/{id}/events` | 按 4.3 | `[{action,actionLabel,actor:{id,name},fromStatus,toStatus,reason,createdAt}]` |
| POST | `/{id}/start` | 4.2 | `200 TaskDetail` |
| POST | `/{id}/submit` | 4.2 | `{completionNote?,completionUrl?}` |
| POST | `/{id}/accept` | 4.2 | — |
| POST | `/{id}/reject` | 4.2 | `{reason}` |
| POST | `/{id}/cancel` | 4.2 | `{reason}` |
| POST | `/{id}/restore` | 4.2 | — |
| POST | `/{id}/force-complete` | 4.2 | `{reason}` |
| POST | `/{id}/reassign` | 4.2 | `{ownerId,reason}` |
| POST | `/{id}/reschedule` | 4.2 | `{dueDate,reason}` |
| POST | `/{id}/urges` | admin/exec | `200 {urgeCount,lastUrgedAt,channelStatus,channelLabel,message}`；`409 URGE_TOO_SOON{lastUrgedAt}`；`409 TASK_FINISHED` |

`TaskRow`：`{id,title,owner:{id,name},dueDate,source:{type,typeLabel,meetingId,meetingTitle,decisionItemId,note},status,statusLabel,isOverdue,overdueDays,dueSoon,urgeCount,lastUrgedAt,updatedAt,completedAt}`。
`TaskDetail` 追加：`dispatcher, priority, completionNote, completionUrl, submittedAt, events[], permissions{canStart,canSubmit,canAccept,canReject,canCancel,canRestore,canForceComplete,canReassign,canReschedule,canUrge, disabledReasons{}}`。
`permissions` 由服务端按 4.3 计算，前端不再自行推导（满足 DESIGN "永不具备→隐藏、条件不可用→禁用并说明原因"）。

### 5.5 通知 / 矩阵 / 看板 / 设置

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/notifications` | 登录 | `?unreadOnly=false&page&size` → `{items:[{id,type,typeLabel,taskId,taskTitle,ownerName,createdAt,channelStatus,channelLabel,read}],total,unreadCount}` |
| GET | `/api/notifications/unread-count` | 登录 | `{count}`（顶栏角标，进页面/动作后刷新） |
| POST | `/api/notifications/{id}/read` | 本人 | `204`；非本人 → `404` |
| POST | `/api/notifications/read-all` | 本人 | `{updated:n}` |
| GET | `/api/matrix` | admin/exec | `?sourceType&range=WEEK\|MONTH\|ALL&includeDisabled=false` → `{today,rangeFrom,rangeTo,rows:[MatrixRow],totals:MatrixRow}` |
| GET | `/api/dashboard` | admin/exec | `?ownerId&sourceType&meetingId&focusLimit=10` → `{today,kpis,focus:[TaskRow],focusTotal}` |
| GET | `/api/settings` | admin | `{report:{dailyEnabled,dailyTime,weeklyEnabled,weeklyTime,recipients:UserRow[],recipientsEmpty},dingtalk:{configured,missingKeys[]},updatedAt}` |
| PUT | `/api/settings/report` | admin | `{dailyEnabled,dailyTime,weeklyEnabled,weeklyTime,recipientIds[]}` → 200；`HH:mm` 校验；`recipientIds` ≤20 |
| GET | `/api/meta/options` | 登录 | `{owners,meetings,sourceTypes,statuses,priorities}`（筛选下拉，owner 只返回本人） |
| GET | `/actuator/health` | 公开 | `{status}`（仅健康检查，不暴露细节） |

`MatrixRow{userId,name,role,userStatus,pending,inProgress,pendingAccept,overdue,done,cancelled,total,completionRate}`；五列互斥、`total = pending+inProgress+pendingAccept+overdue+done`，`completionRate = done/total`（`total=0` 时 `null`，前端显示 `—`）。
`kpis{total,unfinished,overdue,overdueRate,dueSoon24h,pendingAccept}`，`unfinished = 四列之和`（逾期不重复计），`dueSoon24h = 未完成 ∧ due_date ∈ {today, today+1}`（日期粒度下的"24 小时内"定义，看板与日报共用）。

**口径裁决（AC-16 文字"未完成 = 待开始+进行中+待验收+逾期"的唯一自洽读法）**：四个桶互斥（逾期任务已从原状态列移出，AC-15 已如此规定），因此"未完成"= 四桶之和；若把逾期再加一次就是 Anti-goal #1 的重复计数。该定义同时用于看板 KPI、矩阵合计、日报与周报，代码里只有 `TaskCounters` 一处。

### 5.6 排序与分页

- 任务池默认：`ORDER BY finished ASC, (finished=0 ? due_date : NULL) ASC, (finished=1 ? completed_at : NULL) DESC, id DESC`，`finished = status IN ('DONE','CANCELLED')`。
- 看板焦点：`bucket = overdue?0 : PENDING?1 : PENDING_ACCEPT?2 : 3`（进行中），`ORDER BY bucket, overdue_days DESC, due_date ASC, submitted_at ASC`；`focusLimit` 默认 10。
- 分页 `page` 从 1 开始，`size` 默认 20、上限 100；`total` 用同一 WHERE 的 `COUNT(*)`。

---

## 6. 前端设计

### 6.1 路由与守卫

| 路由 | 组件 | 角色 | 守卫行为 |
| --- | --- | --- | --- |
| `/login` | `LoginPage` | 公开 | 已登录 → 按 `landingPath` 跳走 |
| `/sso/callback` | `SsoCallbackPage` | 公开 | 取 `/api/auth/me`，成功按角色跳，失败回 `/login` |
| `/tasks` | `TaskPoolPage` | admin / exec 只读 | owner → `/403` |
| `/tasks/:id` | `TaskDetailPage` | 按任务 | 接口 404 → `/404`；403 → `/403` |
| `/my` | `MyTasksPage` | owner / admin | exec → `/403` |
| `/meetings`、`/meetings/:id` | `MeetingsPage`、`MeetingDetailPage` | admin | 其他 → `/403` |
| `/matrix`、`/dashboard` | `MatrixPage`、`DashboardPage` | admin / exec | 其他 → `/403` |
| `/notifications` | `NotificationsPage` | 全部 | 仅本人数据 |
| `/users`、`/settings` | `UsersPage`、`SettingsPage` | admin | 其他 → `/403` |
| `/403`、`/404`、`/503`、`/500` | 异常页 | 公开 | `503` 仅钉钉回调/未配置场景 |

守卫顺序：`await auth.ensureLoaded()`（无 `user` 时 `GET /api/auth/me`，401 → `/login?next=<fullPath>`）→ 角色校验 → `mustChangePassword` 则强制改密模态。

### 6.2 状态管理（Pinia）

| store | 内容 | 关键约束 |
| --- | --- | --- |
| `auth` | `user`、`dingtalkConfigured`、`login/logout/ensureLoaded/changePassword` | 唯一身份源；`permissions` 用 `utils/permissions.ts` 从 `role` 派生（仅控制入口显隐） |
| `notifications` | `unreadCount`、`refreshUnread()`、`markAllRead()` | 顶栏角标；进入页面、切换路由、催办/打回后刷新 |
| `ui` | 侧栏折叠、Toast 队列（≤2 条）、全局 loading | Toast 用 `aria-live="polite"` |

**任务/矩阵/看板数据不放进 store**：每页进入即取服务端数据，避免"上一页缓存导致红灯与计数不一致"（AC-17 要求首个请求内正确）。这也是"不做全局任务缓存"的硬理由。

### 6.3 请求层

`api/http.ts`：`axios.create({baseURL:'/api', withCredentials:true, xsrfCookieName:'XSRF-TOKEN', xsrfHeaderName:'X-XSRF-TOKEN', timeout:15000})`；响应拦截器把错误映射为 `ApiError{status,code,message,fieldErrors}`；`401` → 清 `auth` 并跳 `/login?next=`；`403/404` 交由页面决定（整页异常或行内提示）；`409` → Toast（带服务端 message）+ 重新拉取当前视图；`422` → 回填 `fieldErrors`；`503` + `DINGTALK_NOT_CONFIGURED` → `/503` 或登录页内联提示（不回红色全局错误）。
`utils/permissions.ts` 只做"按钮/菜单是否渲染"，与 `TaskDetail.permissions` 二选一：**动作按钮以服务端 `permissions` 为准**，角色用于页面级入口。

### 6.4 页面与组件切分（与 DESIGN 一一对应）

- `AppShell` = `SideNav`（224/64，角色过滤）+ `TopBar`（面包屑、铃铛+角标、用户菜单）+ `<RouterView>`；内容区 `padding:24px`、`max-width:1440`。
- `TaskTable` 承接任务池 9 列（964px，见 DESIGN 1.7），行点击进详情，**无操作列**；`TaskFilterBar` 用 `useQuerySync` 把 `status/ownerId/sourceType/meetingId/overdue/keyword/page` 写入 `route.query`（`router.replace`），刷新/分享可复现。
- `TaskFormDrawer`（560）两列 248；`sourceType` 切换时显示"来源会议+决议项"或"来源说明"，两处都为必填并由 `fieldErrors` 高亮；时间只展示，不推算逾期。
- `TaskDetailPage` 双栏 664+320（右栏为负责人/截止/派发人/优先级/来源/催办次数），底部操作区由 `permissions` 渲染；`TransitionTimeline` 用 `events[]`。
- `MatrixPage`：`MatrixCell` 可点 → `router.push('/tasks?…')`，参数与该格口径完全一致（`ownerId + statusBucket + overdue`）；表尾合计行 + 固定脚注"逾期任务只计入逾期列"。
- `DashboardPage`：6 张 `KpiCard`；焦点列表行内"催办/改派/改期/详情"（按 `permissions`）；`ReasonDialog`（480）复用于打回/取消/代确认/改派/改期，原因 2–200 字带计数。
- `NotificationsPage`：类型/渠道 `ChannelChip` 一一对应文案；点击跳任务并标已读；"全部标为已读"。
- `SettingsPage`：日报开关/时间/收件人多选；钉钉状态只读展示缺失项名称；收件人为空时页面顶部黄条提示。
- 三态（加载/空/错误）由 `SkeletonTable`/`EmptyState`/`ErrorState` 在每个数据区块内实现；不用整页 spinner。

---

## 7. 关键边界与错误码

| 场景 | 行为 |
| --- | --- |
| 补录历史截止日 | 允许保存，创建后**立即**为逾期（读时计算，无需等扫描） |
| 归档会议下的任务 | 详情正常、来源可点、可跳会议详情；会议不可编辑 |
| owner 打开他人 `/tasks/:id` | `404`（不是 403，防探测） |
| exec 尝试验收/代确认 | `403 FORBIDDEN`（接口拒绝，界面不渲染） |
| 同一 (task, actor) 10 分钟内二次催办 | `409 URGE_TOO_SOON` + `lastUrgedAt`，不写记录、不推送 |
| 已完成/已取消催办 | 按钮禁用（前端）+ `409 TASK_FINISHED`（后端） |
| 双击"转为任务" / 并发两次 | 唯一键兜底，`skipped` 返回，不产生第 2 条任务 |
| 验收与取消并发 | 行锁串行化，先到者生效，后到者 `409` |
| 9:00 后新建任务 | 当天不补发提醒，次日 09:00 进入逾期提醒（写入 §11） |
| 钉钉未配置 | `/api/auth/dingtalk/**` → `503`；推送 → `NOT_CONFIGURED` 不重试；启动仅 1 条 WARN；业务全部可用 |
| 钉钉超时/限流 | 3 次退避（1/5/15 分钟）后 `FAILED`；站内通知已发；业务不回滚 |
| 会话过期 | `401` → 登录页并保留 `next` |
| 服务重启 | 会话丢失需重登（单实例内存会话）；`job_runs` 保证当日扫描/日报不重发 |
| 时区 | 服务器、JVM、MySQL 连接三方固定 `Asia/Shanghai`；README 写明启动参数 |

错误码全集：`AUTH_REQUIRED`、`AUTH_BAD_CREDENTIALS`、`AUTH_DISABLED`、`AUTH_STATE_INVALID`、`AUTH_PASSWORD_WEAK`、`AUTH_OLD_PASSWORD_WRONG`、`PASSWORD_CHANGE_REQUIRED`、`FORBIDDEN`、`NOT_FOUND`、`VALIDATION_FAILED`、`TASK_INVALID_TRANSITION`、`TASK_SOURCE_IMMUTABLE`、`TASK_FINISHED`、`URGE_TOO_SOON`、`DECISION_ITEM_LOCKED`、`MEETING_ARCHIVED`、`SELF_DISABLE_FORBIDDEN`、`DINGTALK_NOT_CONFIGURED`、`INTERNAL_ERROR`。

---

## 8. 测试策略

| 层 | 工具 | 覆盖 | 门禁 |
| --- | --- | --- | --- |
| 纯单元 | JUnit 5 + AssertJ | 逾期 15 组、状态机 18 合法 + 10 非法、dedup_key 生成、催办 10 分钟边界、KPI 口径公式 | `server/mvnw test` 全绿 |
| 数据一致性 | `@SpringBootTest` + MySQL | `OverdueSqlParityTest`（SQL ↔ `OverdueSpec` 15 组逐条相等）、唯一键并发插入（转任务/通知） | 同上 |
| 接口/权限 | `@SpringBootTest` + MockMvc | AC-2 全表逐行断言 200/403/404；AC-6 四组缺字段 422；AC-9 全部流转与 409；AC-13 他人通知 404 | 同上 |
| 调度 | 固定 `Clock` + 直接调 `runForDate` | 扫描跑两遍条数不变、T-1/逾期各 1 条、周末照常、补跑只当天；日报空名单不发 | 同上 |
| 前端 | Vitest | `format.ts`（日期/百分比/`—`）、`permissions.ts`（按钮显隐）、`useQuerySync`（URL 往返） | `web/pnpm test` |
| 构建 | Vite / Maven | `pnpm build`、`mvnw -q package` | 产物可启 |
| 冒烟 | `scripts/smoke-api.sh`（curl + jq） | 密码登录 → 建任务 → 开始 → 提交 → 验收 → 看板/矩阵各一次 → 未配置下钉钉路由 503 | 手跑/CI 可选 |

测试库选择：默认 **H2（`MODE=MySQL`）跑 `@SpringBootTest`**（本机无 Docker 也可跑），另留 `-Pit-mysql` profile 用 Testcontainers MySQL 8 跑同一套集成测试，作为"CHECK/唯一键/大小写"的最终口径确认。任何在 H2 上通过的集成测试，收尾前必须在 MySQL profile 下再跑一次（AC-6 的数据库层断言只认 MySQL）。

**AC-11 的 15 组**：截止日 `{-1, 0, +1}`（相对今天）× 状态 `{PENDING, IN_PROGRESS, PENDING_ACCEPT, DONE, CANCELLED}`，断言 `isOverdue` 只在"过期日 × 三未完成状态"为 true，且 `overdueDays` 边界为 1（次日）而不是 0。

---

## 9. 任务拆分（无 Pipeline 预派任务，按文件边界并行）

波次 0 先起骨架，之后同波次内文件互斥，可并行；`reads` 不阻塞并行。

| ID | 任务 | 主要文件（写） | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| T01 | 工程骨架与基础设施 | `.gitignore`、`README.md`、`docker-compose.yml`、`server/pom.xml`、`mvnw*`、`application.yml`、`db/migration/V1 V2 V3`、`common/*`、`config/*` | — | `mvnw -q package` 通过；Flyway 可在空库建全表；`/actuator/health` 200 |
| T02 | 前端脚手架 + 设计系统 + AppShell + 登录 | `web/**`（package/vite/tsconfig/main/App、`styles/*`、`layout/*`、`components/ui/*`、`stores/*`、`api/http.ts`、`router/index.ts`、`pages/LoginPage`、`SsoCallbackPage`、`ChangePasswordModal`、`pages/error/*`、`utils/*`） | — | `pnpm build` 通过；令牌与 DESIGN 1.2 逐值一致；未登录访问 `/tasks` 跳 `/login?next=` |
| T03 | 认证与用户 | `auth/*`、`user/*` | T01 | AC-1/AC-18 降级分支、AC-2 用户管理行；伪造 state 400；停用 403 |
| T04 | 任务核心域 | `task/*`（实体/仓储/`OverdueSpec`/`TaskSql`/`TaskQueryBuilder`/`TaskQueryDao`/`TaskService`/`TaskCommandService`/`TaskAccessGuard`/`TaskController`/dto） | T01 | AC-6/7/8/9/11；`OverdueSqlParityTest` 15 组绿；非法流转 409 |
| T05 | 会议与决议 | `meeting/*` | T01（读 T04 的 `TaskRepository`） | AC-3/4/5；重复转任务 skipped；归档后来源仍可读 |
| T06 | 通知/催办/扫描/钉钉 | `notify/*`、`scan/*` | T01、T04 | AC-12/13/14/19/21；扫描两遍条数不变；未配置不阻塞 |
| T07 | 矩阵与看板 | `dashboard/*` | T01、T04（读 `TaskQueryDao`） | AC-15/16/17；矩阵合计=非取消；三页同一逾期 |
| T08 | 前端任务/详情 | `pages/TaskPoolPage|MyTasksPage|TaskDetailPage`、`components/task/*`、`api/tasks.ts`、`composables/useQuerySync.ts` | T02 | AC-8/9/10 界面；URL 保留；逾期字段只展示 |
| T09 | 前端会议/矩阵/看板 | `pages/MeetingsPage|MeetingDetailPage|MatrixPage|DashboardPage`、`api/meetings.ts|matrix.ts|dashboard.ts` | T02 | AC-3/4/5/15/16 界面；矩阵下钻参数一致 |
| T10 | 前端通知/用户/设置 | `pages/NotificationsPage|UsersPage|SettingsPage`、`api/notifications.ts|users.ts|settings.ts` | T02 | AC-13/20/21 界面；钉钉未配置为灰色 chip |
| T11 | 设置与日报后端 | `settings/*`、`report/*` | T01、T06（`DingTalkSender`）、T07（KPI 查询） | AC-20；空名单不发且留痕；日报 KPI=看板 |
| T12 | 交付自检与联调 | 全部（仅修复）、`README.md`、`.gitignore`、`scripts/smoke-api.sh` | T03–T11 | 全量 `mvnw test` + `pnpm build` + 冒烟；三页数字一致；`.gitignore` 补齐；按里程碑提交 |

**波次**：`W0 = T01 ∥ T02` → `W1 = T03 ∥ T04 ∥ T05（+T02 收尾）` → `W2 = T06 ∥ T07 ∥ T08` → `W3 = T09 ∥ T10 ∥ T11` → `W4 = T12`。
**并行约束**：`TaskQueryBuilder`/`TaskQueryDao` 只由 T04 写，T06/T07 只读；`common/*` 只由 T01 写，其他人只读；`notify` 的接口由 T06 定义，T04 通过 `NotificationService` 调用（编译期依赖，需 T06 先给出接口签名，波次 2 内 T04 已完成，故不冲突）。

**Git（PRD §8 工程约束，逐条落到任务）**：
1. 全程在 `feat/meeting-task-supervision`，**不新建、不切换分支**；当前 `?? docs/` 随 T01 首个提交纳入。
2. 提交节奏：T01 → `feat(M1): 工程骨架与主数据`；T03–T05 → `feat(M1): 认证/任务/会议`；T06–T07 → `feat(M2): 扫描/通知/矩阵/看板`；T08–T11 → `feat(M3/M4): 前端与钉钉降级`；T12 → `chore: 交付自检`。每条提交信息末尾标注 `docs/teamflow/20261109-r1-meeting-task-supervision`。
3. T01 必须补齐 `.gitignore`（`target/`、`*.class`、`node_modules/`、`dist/`、`.idea/`、`*.iml`、`.vscode/`、`.settings/`、`.classpath`、`.project`、`*.log`、`logs/`、`.env`、`application-local.yml`、`application-*.local.yml`、`*.dump`、`.DS_Store`），保留现有 `logs/teamflow/`。
4. 提交时排除 `logs/teamflow/`；不得提交真实钉钉凭证（`.env.example` 只留 `TODO` 占位）。

---

## 10. AC 覆盖与验证方式

| AC | 实现位置 | 验证 |
| --- | --- | --- |
| AC-1 | `SecurityConfig`、`AuthController`、`router/index.ts` | 未登录四页 302/前端跳转；三角色落地页；错误文案同一句；logout 后旧会话 401 |
| AC-2 | `TaskAccessGuard`、`UserService`、`SettingService` | 按 AC-2 表逐行 MockMvc：200/403/404；owner 越界 404 |
| AC-3 | `MeetingService`、`MeetingDetailPage` | 缺失项 422 带 `index`；列表行定位 |
| AC-4 | `DecisionConversionService` | 重复 convert → `skipped`；任务数=勾选数；派发人=当前 admin |
| AC-5 | `MeetingService.archive`、`TaskQueryDao.findDetail` | 归档后任务来源仍完整；无删除接口 |
| AC-6 | `CreateTaskRequest`（Bean Validation）、`ck_tasks_*` | 四组缺字段 422 + 库层 `NOT NULL`/CHECK 插入失败 |
| AC-7 | `SourceType` + `ck_tasks_source` | 三类型各建一条，矩阵/看板计数各 +1 |
| AC-8 | `TaskQueryBuilder` + `TaskPoolPage` | 列/排序/分页 20；筛选组合后刷新 URL 还原 |
| AC-9 | `TaskCommandService` + `task_events` | 18 合法迁移 + 10 非法 409；事件条数逐条比对 |
| AC-10 | `accept/reject` + `NotificationService` | 打回后 owner 收到站内；无钉钉审批调用 |
| AC-11 | `OverdueSpec`、`TaskSql` | 15 组单测 + `OverdueSqlParityTest`；停扫描后列表仍红 |
| AC-12 | `DailyScanJob`、`JobRunService` | 固定 Clock 跑两遍条数不变；补跑只当天；周末照常 |
| AC-13 | `NotificationService/Controller` | 角标数=未读；他人通知 404；全部已读 |
| AC-14 | `UrgeService` | 10:00/09:59/10:01 三组；未配置文案"已站内通知…（钉钉未配置）" |
| AC-15 | `MatrixService` | 五列互斥；`total`=非取消；0 值不可点；下钻参数一致 |
| AC-16 | `DashboardService` | 6 KPI 与矩阵交叉核对；焦点排序四段；exec 无验收接口 |
| AC-17 | `TaskQueryBuilder` + `ShanghaiClock` 参数化 | 改期到昨天后三个接口首个响应即逾期；同页两模块状态一致 |
| AC-18 | `DingTalkAuthService` | 扫码/免登两路径；停用 403；伪造 state 400；未配置 503 |
| AC-19 | `DingTalkSender`、`DingTalkRetryJob` | 失败→1/5/15 三次；渠道状态五值；限速串行；不阻塞业务 |
| AC-20 | `ReportJob`、`ReportService` | 名单为空不发且留痕；重复触发一次；KPI=看板 |
| AC-21 | `DingTalkProperties`、`DingTalkConfigService` | 空配置启动 1 条 WARN；入口隐藏；全链路无 500；补齐后重启生效 |

---

## 11. 风险、取舍与未实现项

| 项 | 说明 / 处置 |
| --- | --- |
| 内存 Session | 单实例前提下可接受；重启即全员重登。若将来多实例，必须引入 Spring Session + Redis（已在部署文档注明） |
| `OverdueSpec` 与 SQL 谓词两份表示 | 已用 `OverdueSqlParityTest` 钉住 15 组；任何一侧改动而另一侧未改会立刻红（本设计唯一允许的重复） |
| H2 与 MySQL 差异 | 默认 H2 跑集成测试以便本机无 Docker 也能验；收尾前用 `-Pit-mysql` 跑一次；CHECK/唯一键语义只认 MySQL |
| 钉钉真实凭证缺席 | 全部按 AC-21 降级态开发与自测；真机联调留到 IT 补齐后（PRD 上线门禁） |
| 会议来源"重新关联" | PRD 允许但未要求，本轮不做；`PATCH` 明确拒绝改来源，避免半成品入口 |
| 09:00 后新建任务的当日提醒 | 不补发，次日进入逾期提醒；已在 §7 写明，验收时按此口径 |
| 钉钉"退出同步失效" | 系统登出即销毁会话；钉钉容器关闭由前端 `pagehide` 尽力调 `logout`，并以 7 天绝对超时兜底（无法在服务端感知钉钉侧登出） |
| 无既有代码风格可依 | 本文即风格基线：DTO 用 `record`、不用 Lombok、Service 抛 `BusinessException`、Controller 不写业务分支；后续如加 AGENTS.md 约定以团队文件为准 |
| 备份 | MySQL 每日备份为部署项（不在代码内），README 给出 `mysqldump` 示例 |

---

## 12. 交付自检记录

- 状态机（5 状态 / 18 迁移）经 `logicprobe_verify` 模型检查：0 error，无死锁、无不可达、无歧义；10 组未定义事件对由接口 409 拒绝；并发"验收 vs 取消"以行锁串行化（结果见 §4.2，非推测）。
- 本文件所有路径均以空仓为起点，T01 一次性建立；后续任务只增不改目录结构。
- 未写 `QA-REPORT.md` / `ACCEPTANCE.md`（属 QA 与验收阶段证据链）；未预跑跨浏览器/真机矩阵。
