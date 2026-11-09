# QA 验收报告（第二轮复验）· 会议任务督办系统

- 需求夹：`docs/teamflow/20261109-r1-meeting-task-supervision/`（PRD / DESIGN / TECHNICAL）
- 分支：`feat/meeting-task-supervision`　复验日期：2026-11-09　基线提交：`61ef585`
- **结论：通过（可验收）**。上一轮 7 个缺陷（D-01…D-07）逐条按原 Check command 复跑全部关闭；功能探针 84/84、新增表面探针 16/16 与 15/15、认证 18/18、会议归档 12/12 全通过；后端 131 单测、前端 70 单测、生产构建均通过；架构质量门无新增偏离。遗留仅为环境受限项（真实钉钉外呼/扫码、真浏览器视觉），列第 5 节人工补测。

## 1. 范围与环境

| 项目 | 内容 |
| --- | --- |
| 测试对象 | `server/`（Spring Boot 3.3.5，Flyway V1–V3，10 张表）、`web/`（Vue 3 + Vite）、`deploy/`、`scripts/smoke-api.sh` |
| 本轮变更面 | dev 修复 7 项：新增 `meta` 包（`MetaController`/`MetaService`/`MetaOptions`）、`DailyScanJob` 改用 `TaskSql` 常量、`UserRow.lastLoginAt`、`NotificationRow.retryCount/failureReason`、`DecisionConversionService` 改 `ShanghaiClock`、`enums.ts` `UNBOUND→neutral`、README 428→409 |
| 对照基线 | PRD 21 条 AC、DESIGN 1.2、TECHNICAL §3–§6、架构蓝图（读侧单一 SQL 构建器 / 写侧状态机 / 通知幂等） |
| 运行时 | macOS arm64、Java 25.0.2、Node 22.22.0、pnpm 10.30.0、MySQL 9.6（127.0.0.1:3306） |
| 实例 | 后端 `:18090`（`server/target/supervise-1.0.0.jar`，18:47 重建），库 `supervise_qa`，`DINGTALK_*` 全空（降级路径），`SCAN_STARTUP_CATCHUP=true` |
| 方法 | 真实 HTTP（Cookie 会话 + CSRF 双提交）+ MySQL 直连断言；前端 vitest/jsdom 页面级用例与生产构建；静态接口/架构核对 |
| 限制 | 沙箱内 Chromium/Edge 因 crashpad 受限无法启动（环境限制，非交付缺陷），纯视觉与真实外呼列第 5 节 |
| 回归资产 | `logs/teamflow/tf-mw90og0k-fdjwq1/scripts/`（本轮复用并扩展，未重建） |

## 2. 执行证据（命令 / 退出码 / 断言）

| # | 检查 | 命令 | 退出码 | 关键断言 |
| --- | --- | --- | --- | --- |
| E1 | 后端单测 | `cd server && ./mvnw -q test` | 0 | surefire 聚合：classes=24、tests=131、failures=0、errors=0、skipped=0（含 `OverdueSqlParityTest` 15 组、`ScanSqlSourceTest`、`MetaApiIT`、`DailyScanJobIT`） |
| E2 | 后端打包 | `cd server && ./mvnw -q -DskipTests package` | 0 | 产出 `server/target/supervise-1.0.0.jar`（56 MB，18:47 重建，晚于全部源码改动） |
| E3 | 前端单测 | `cd web && pnpm test` | 0 | 10 文件 70/70（router-guard 12、t10-pages 12、t09-pages 10、permissions 13、use-query-sync 7、task-detail-flow 3…） |
| E4 | 前端生产构建 | `cd web && pnpm build` | 0 | `✓ built in 1.16s`；index 209.00 kB（gzip 79.52）、TaskDetailPage 19.91 kB |
| E5 | **D-01 原命令** 端到端探针 | `BASE_URL=http://127.0.0.1:18090 python3 logs/teamflow/tf-mw90og0k-fdjwq1/scripts/qa-functional.py` | **0** | `PASS=84 FAIL=0`，无 `FAILED:` 行（上轮 83/1，唯一 FAIL 为 meta 404） |
| E6 | **D-01 修复面** 新探针 | `BASE_URL=http://127.0.0.1:18090 python3 logs/teamflow/tf-mw90og0k-fdjwq1/scripts/qa-meta-scope.py` | 0 | 16/16：未登录 401；admin/exec 见全部 ACTIVE 用户与全部会议；OWNER owners 恰为本人 1 条、meetings=本人任务涉及会议数（7 vs 7）；五枚举 label 由服务端返回 |
| E7 | **D-03 / D-04 运行时** 新探针 | `BASE_URL=http://127.0.0.1:18090 python3 logs/teamflow/tf-mw90og0k-fdjwq1/scripts/qa-row-fields.py` | 0 | 15/15：`GET /api/users` 每行含 `lastLoginAt` 且非空数与库一致（3 vs 3）、无口令散列；`GET /api/notifications` 每行 `retryCount` 整数且逐行等于库 `dingtalk_attempts`；合成一条 `FAILED`（attempts=2）通知后接口回传 `failureReason="钉钉未绑定：unionId 缺失"`、`retryCount=2` |
| E8 | 认证生命周期（复用） | `BASE_URL=http://127.0.0.1:18090 python3 logs/teamflow/tf-mw90og0k-fdjwq1/scripts/smoke-auth-flow.py` | 0 | 18/18（命中重复运行分支，跳过首登 6 项）：停用 403、伪造 state 400、未配置 503、登出后旧 Cookie 401、CSRF 明文契约、用户行含 `dingtalkBound` 且不泄露 `passwordHash` |
| E9 | 会议归档约束（复用） | `BASE_URL=… SMOKE_PASSWORD='Smoke@2026pw' python3 …/scripts/smoke-convert-archived.py` | 0 | 12/12：归档会议新增决议/转任务 409 `MEETING_ARCHIVED`；活跃会议转任务 200；`dueFrom/dueTo` 绑定 200 |
| E10 | 日报/周报投递 | `bash …/scripts/qa-report-job.sh` | 0 | 清当日 `job_runs` 后 ≤75s：`DAILY_REPORT|SUCCESS|recipients=1,pushed=0,skipped=1`、`WEEKLY_REPORT` 同；报表站内通知 2 条（当日已存在则去重，`skipped` 递增） |
| E11 | **D-02 语义** 扫描补跑 | `bash …/scripts/qa-scan-rerun.sh` | 0 | `SUCCESS|dueSoon=0,overdue=6,pushed=0,skipped=6`，与 SQL 直算一致；二次重启 `DAILY_SCAN` 仍 1 行、逾期通知 6→6 不增 |
| E12 | 接口静态对照 | `python3 …/scripts/qa-api-contract.py` | 0 | 文档可解析接口 11 vs 实现 46；文档有实现无 = 0（上轮为 1，即 meta 404） |
| E13 | 提交面卫生 | `git status --porcelain` | 0 | 8 项（` M .gitignore` + README/deploy/docker-compose/docs/scripts/server/web）；无 `node_modules`/`.pnpm-store`/`target`/`dist`/`.env`/IDE 文件 |
| E14 | 静态缺陷复查 | `grep -c` 逐条（见第 4 节） | 0 | D-02=2、D-03=1、D-04=2、D-05=0、D-06=0、D-07=0 |

## 3. 用例与结果

### 3.1 功能面（真实 HTTP + MySQL）

- 端到端 84 项：**84 通过 / 0 失败**。覆盖 AC-2/3/4/5/6/7/8/9/10/11/13/14/15/16/17/18/21 的正负路径；任务池/矩阵/看板/日报四页数字互算一致，`today` 同为 2026-11-09。
- 三层约束：接口 422（`fieldErrors` 精确到 ownerId/dueDate/sourceType）、MySQL `ck_tasks_source` 与 NOT NULL 各自拒绝直插（不经接口）。
- 状态机：18 条合法迁移子集全通、非法流转 409 `TASK_INVALID_TRANSITION`、每动作 1 条事件；行锁串行化由 IT 覆盖。
- 权限：owner→`/api/users`、`/api/matrix`、`/api/dashboard`、`/api/settings` 403；越界读任务/通知 404；管理动作 403。
- 幂等：重复 convert `created=0/skipped=2 ALREADY_CONVERTED`；10 分钟内重催 409 `URGE_TOO_SOON`；扫描/日报重启不重发（E10/E11）。
- 降级：`DINGTALK_*` 全空时入口隐藏、推送 `NOT_CONFIGURED`、业务零 500。

### 3.2 接口一致性（静态，逐调用点）

- `MetaOptions` record（`owners[OwnerOption(id,name,role,status)]`、`meetings[MeetingOption(id,title,meetingDate)]`、`sourceTypes/statuses/priorities: Option{value,label}`）与 `web/src/types/api.ts:384` 一一对应；`statuses` 实回 5 项、`sourceTypes` 3 项、`priorities` 3 项，label 取自后端枚举而非前端拼装（E6 实测）。
- meta 五个调用点全部接线：`web/src/pages/TaskPoolPage.vue:204`（owners/meetings 传入 `TaskFilterBar` 与新建抽屉）、`TaskDetailPage.vue:101`（改派下拉）、`DashboardPage.vue:133`（筛选，catch 静默降级）、`MeetingsPage.vue:166`、`MeetingDetailPage.vue:97`。
- `UserRow` 后端 11 字段含 `lastLoginAt`，前端 `api.ts:101` 为可选，`UsersPage.vue:36,127-129` 消费并以「—」降级；`NotificationRow` 的 `retryCount/failureReason` 由 `NotificationsPage.vue:273` 消费；`UserOption` 与 `OwnerOption` 字段对齐。运行时已由 E7 断言，非遗留占位。

### 3.3 架构质量门（蓝图对照）

- 读侧单源：`TaskSql.*` 被 7 个文件复用（`dashboard/DashboardService`、`dashboard/MatrixService`、`meeting/MeetingService`、`report/ReportService`、`scan/DailyScanJob`、`task/TaskQueryBuilder`、`task/TaskQueryDao`）；D-02 修复后扫描 SELECT 与任务池/矩阵/看板共用同一段谓词文本。
- 时间单源：main 代码无 `LocalDate.now()`、无 `CURDATE`（仅注释），统一 `ShanghaiClock.today()` 注入查询参数。
- 权限单源：`TaskAccessGuard` 被 8 个服务引用，Controller 不写业务分支。
- 结构：121 个 main 类、无重名类；包结构与蓝图一致（common 16、task 14、notify 12、config 8、auth 8、meeting 7…）；未发现重复实现的安全包装/存储适配器，也未发现该抽未抽的横切逻辑。
- 前端：`TaskTable` 只消费服务端 `is_overdue/overdue_days/due_soon`，无日期推算；错误形状统一经 `http.ts` 归一化。
- 结论：无新增架构缺陷（上轮 D-02 已关闭）。

### 3.4 AC 结果（21 条）

| AC | 结果 | 说明 |
| --- | --- | --- |
| AC-1 登录/钉钉入口/三角色落地 | 通过 | 未配置隐藏入口 5 例、守卫 12 例；428→409 文档已同步（D-07） |
| AC-2 权限矩阵 | 通过 | 403/404 语义与 `TaskAccessGuard` 逐条吻合 |
| AC-3 会议与决议录入 | 通过 | 空标题/主持人 422；决议项缺负责人 422 带 index |
| AC-4 一键转任务 | 通过 | created=2、重复 created=0、派发人=当前管理员 |
| AC-5 只归档/快照/闭环 | 通过 | 归档后编辑 409；转任务后决议项锁定；taskCount/unfinishedCount/closed 联动 |
| AC-6 四字段三层强制 | 通过 | 接口 422、库 CHECK/NOT NULL 直插被拒 |
| AC-7 三类来源 | 通过 | 三来源后端全通；会议来源下拉现有选项（E6），不再为空 |
| AC-8 任务池 | 通过 | 9 列/排序/筛选/URL 保留；负责人与会议筛选下拉有选项（E6） |
| AC-9 状态机留痕 | 通过 | 18 条合法边 + 非法 409 + 每动作 1 事件 |
| AC-10 验收/打回/代确认 | 通过 | dispatcher 验收、admin 代确认、owner 验收 403 |
| AC-11 逾期读时 | 通过 | `OverdueSqlParityTest` 15 组；`overdueDays=1`、Asia/Shanghai 边界单测 |
| AC-12 每日扫描 | 通过 | E11：补跑仅当天、计数与 TaskSql 语义一致、重启不重复 |
| AC-13 通知中心 | 通过 | 未读角标、只读本人 404、read-all 归零；渠道五态+失败原因/重试次数（E7） |
| AC-14 一键催办 | 通过 | 站内+钉钉降级 `NOT_CONFIGURED`、10 分钟窗口 409 带 `lastUrgedAt` |
| AC-15 矩阵 | 通过 | 五列互斥、行合计=非取消、下钻参数同源 |
| AC-16 看板 | 通过 | 6 KPI、焦点排序、仅改派/改期/催办；筛选选项由 meta 提供 |
| AC-17 跨页一致 | 通过 | 同一 `today`、同一 `TaskSql.OVERDUE`，三页数字互算一致 |
| AC-18 钉钉身份 | 通过 | 伪造 state 400、停用 403、未配置 503、首登 OWNER、手工分配角色；真实扫码见 M2 |
| AC-19 钉钉工作通知 | 部分通过 | 未配置/失败不阻塞、退避/去重/渠道状态由 IT+E7 覆盖；**真实送达需凭证**（M3，环境限制） |
| AC-20 日报/周报 | 通过 | E10：工作日 18:00 日报、周一 09:00 周报、仅指定名单、空名单记录「未配置收件人」 |
| AC-21 配置与降级 | 通过 | 入口隐藏、推送跳过、`NOT_CONFIGURED` 留痕，业务无 500 |

### 3.5 回归面自检（本轮新增覆盖）

- 上轮未覆盖表面：`/api/meta/options` 的角色裁剪与枚举文案（E6）、`UserRow/NotificationRow` 新字段的**运行时**取值（E7）、扫描补跑计数与 `TaskSql` 语义一致性（E11）。三类均新增独立探针，未重建既有脚本。
- 缺陷命中数重算：D-01 由 1 处 404 降为 0；D-02 自写谓词由 2 处降为 0（`grep` 计数 0→2 处 `TaskSql.` 引用）；D-03/D-04 缺失字段由 1 处降为 0 且运行时已取值；D-05/D-06/D-07 静态计数均归零。

## 4. 缺陷表（仍 OPEN）

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

未发现缺陷（P0/P1/P2/P3 均为 0，本轮无新增 craft 观察项）。

### 4.1 上轮缺陷复验对照（round-2 级，供审计）

| 编号 | round-2 级 | 状态 | 上轮问题 | 本轮复验命令（原样） | 结果 |
| --- | --- | --- | --- | --- | --- |
| D-01 | P1 | 已关闭 | `GET /api/meta/options` 404，五处调用落空 | `BASE_URL=http://127.0.0.1:18090 python3 logs/teamflow/tf-mw90og0k-fdjwq1/scripts/qa-functional.py` | exit 0，84/84，无 `FAILED:`（E5）＋E6 16/16 覆盖角色裁剪 |
| D-02 | P1 | 已关闭 | `DailyScanJob` 自写逾期谓词 | `grep -c "TaskSql\." server/src/main/java/com/pmo/supervise/scan/DailyScanJob.java` | 输出 2（≥2）＋E11 计数与 SQL 一致 |
| D-03 | P2 | 已关闭 | `UserRow` 缺 `lastLoginAt` | `grep -c "lastLoginAt" server/src/main/java/com/pmo/supervise/user/dto/UserRow.java` | 输出 1（≥1）＋E7 运行时 3 vs 3 |
| D-04 | P2 | 已关闭 | `NotificationRow` 缺 `retryCount/failureReason` | `grep -c "retryCount\|failureReason" server/src/main/java/com/pmo/supervise/notify/dto/NotificationRow.java` | 输出 2（≥1）＋E7 合成 FAILED 行回传原因与次数 |
| D-05 | P3 | 已关闭 | 转任务兜底用 `LocalDate.now()` | `grep -c "LocalDate.now()" server/src/main/java/com/pmo/supervise/meeting/DecisionConversionService.java` | 输出 0 |
| D-06 | P3 | 已关闭 | `CHANNEL_TONES.UNBOUND` 为 warning | `grep -c "UNBOUND: 'warning'" web/src/types/enums.ts` | 输出 0（现为 neutral） |
| D-07 | P3 | 已关闭 | README 写 428 | `grep -c "428 PASSWORD_CHANGE_REQUIRED" README.md` | 输出 0（实现与文档同为 409） |

## 5. 人工补测清单（环境限制，非交付缺陷）

| # | 项 | 判据 | 方法 |
| --- | --- | --- | --- |
| M1 | 视觉与响应式（DESIGN 1.2） | 1440/1280/375 三档无横向滚动；抽屉/模态无遮挡；Toast、骨架、焦点环符合令牌 | Chrome 打开 `cd web && pnpm dev`，逐页核对（沙箱无法启动浏览器） |
| M2 | 真实钉钉扫码/免登 | 授权回跳落地正确、state 单次消费、首登建档为 OWNER、停用账号被拒 | 配置四项凭证 + 公网回调后用手机扫码 |
| M3 | 钉钉工作通知真实送达 | 预警/逾期/催办三类送达；失败按 1/5/15 分钟退避 ≤3 次；限速 ≤20 条/秒；渠道状态五态正确 | 配置凭证后观察手机端与 `notifications` 字段（`retryCount/failureReason` 已有接口） |
| M4 | 09:00 与周一 09:00 真时钟 | 定时触发、周末照常、周报仅周一 | 调系统时间或等真实日历（本轮以启动补跑/即时轮询等价覆盖） |
| M5 | 屏幕阅读器与全键盘 | 角标有 aria-label、模态焦点陷阱、表格行 Enter 可打开 | VoiceOver + Tab 走查 |
| M6 | 视觉等级 craft 观察 | 按钮 hover/pressed 可感、骨架不闪烁、数字列右对齐在真实字体下对齐 | 真浏览器逐页目视（本轮仅代码级核对） |

## 6. 结论

- **可验收**：上一轮 7 个缺陷全部关闭并逐条按原命令复验通过；主路径由真实 HTTP + MySQL 的 84 项端到端断言、两条新增表面探针（16/16、15/15）、131 后端单测、70 前端单测与生产构建共同支撑，未发现 P0–P3 缺陷。
- 唯一非缺陷遗留：AC-19 的真实钉钉送达与 M1/M2/M5/M6 的真浏览器/真设备项受沙箱与凭证限制，需人工按第 5 节复核。
- 回归入口：`cd server && ./mvnw test`、`cd web && pnpm test`、`cd web && pnpm build`、`qa-functional.py`、`qa-meta-scope.py`、`qa-row-fields.py`、`qa-scan-rerun.sh`、`qa-report-job.sh`、`qa-api-contract.py`。
