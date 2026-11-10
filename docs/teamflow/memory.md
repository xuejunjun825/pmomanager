# 产品记忆 · 会议任务督办系统

## 技术栈与结构
- 后端 `server/`：Spring Boot 3.3.5 / Java 17 / MySQL 8 / Flyway V1–V6 / Spring Security（Session + CSRF Cookie）；前端 `web/`：Vue 3 + Vite + TypeScript + Pinia；单实例，`@Scheduled` 承担扫描与报表。
- 交付：README（启动、时区、admin 初始口令 `Admin@12345` 首登改密、钉钉配置清单）、docker-compose、deploy/ 示例、scripts/smoke-api.sh。

## 团队约定
- 「今天」唯一来源 `ShanghaiClock.today()`（`Clock` 可注入），每请求取一次并作为参数贯穿查询；SQL 禁止 `CURDATE()`。
- 逾期谓词唯一常量 `TaskSql.*`（任务池/详情/矩阵/看板/日报/扫描/查询构建器复用）；Java 侧 `OverdueSpec` 由 `OverdueSqlParityTest` 15 组逐条对照。
- 权限唯一判定点 `TaskAccessGuard`（8 个服务引用）；越权 403、owner 越界 404；Controller 不写业务分支。
- 通知幂等：`dedup_key` 唯一键 INSERT-catch；扫描/报表一天一次由 `job_runs(job_key,run_date)` 主键保证。
- 钉钉未配置：`DingTalkProperties` 判定，渠道状态 `NOT_CONFIGURED` 不重试；入口隐藏、推送跳过、业务零 500。
- 来源类型唯一配置源（r2 起）：管理端维护、任务只存编码；内置 `MEETING/PROJECT/DAILY` 不可删，「会议决议」不可停用；任务进度 append-only 不改状态，@ 通知按（进度, 被 @ 人）幂等，被 @ 人获得该任务只读可见与续写进度权。前端来源选项投影唯一实现 `web/src/utils/sourceOptions.ts:sourceOptions()`（r3 起：占位/启用优先/停用标注集中，抽屉传 `allOption:false, includeDisabled:false`），页面不得自拼；任务详情来源只由 `SourceTag` 渲染一次。
- 来源形态三态（r4 起）：`MEETING` 绑会议+决议项 / `PROJECT` 绑项目 / `NOTE` 自由说明，三者互斥由服务端单点判定、库层只兜「至多一类非空」；形态变更不迁移历史任务数据（改形后历史任务显示「未指定项目」）。注意内置来源编码 `PROJECT`（项目任务）是 `NOTE` 形态，与形态值同名不同义，不得互相兜底。
- 项目是主数据不是权限维度（r4 起）：仅 ADMIN 维护（名称唯一 2–20 字、编码 `P%05d` 系统生成不可改、被引用只能停用、上限 200），全员可读可筛；建任务时「形态 ↔ 字段」配对由服务端单点判定，库层 `ck_tasks_source` 只兜「三组字段恰好一组非空」。
- 项目三处单一实现点（r4 起，禁止复制）：筛选谓词只有 `server/src/main/java/com/pmo/supervise/project/ProjectFilterSql.java:append()`（任务池/看板经 `TaskQueryBuilder`、矩阵经 `MatrixService` 共用，`none` = 未指定项目，走 `source_types` 子查询）；前端下拉投影只有 `web/src/utils/projectOptions.ts:projectOptions()`、项目位判定只有 `projectView()`，渲染只有 `web/src/components/ui/ProjectTag.vue`。任务池/矩阵/看板三处合计必须对齐，任何页面不得自拼项目条件或自行比较 `enabled/sort`。
- 错误体统一 `{code,message,fieldErrors,details,traceId}`，前端 `http.ts` 归一化；会话 7d，CSRF 使用明文 token 契约（`CsrfTokenRequestAttributeHandler`）。会话 id 只在登录成功时轮换一次（r5 起）：登录处显式一次换发、认证请求一律不换发 `SUPERVISE_SESSION`；不得用「关掉会话固定防护」或「前端 401 重试/串行化」换取并发稳定。
- 契约文档只写 `docs/teamflow/<yyyyMMdd-rN-slug>/`；跨需求记忆在本文件。

## 已知待办
- 真实钉钉扫码/工作通知送达需凭证补测（QA-REPORT §5 M2/M3）。
- 真浏览器视觉与可访问性（M1/M5/M6）、真时钟 09:00 / 周一 09:00 触发（M4）待人工复核。
- AC-1「钉钉侧退出后系统会话同步失效」：TECHNICAL 称前端 `pagehide` 尽力登出，但 `web/src` 未实现该监听，需补监听或修正文档表述（P3，不阻断）。
- r3 返工（r2 R-1 来源选项收口 + 详情来源标签去重）已于 2026-11-10 验收通过（`docs/teamflow/20261110-r3-source-options-extract/ACCEPTANCE.md`）；待办候选：`ownerOptions`/`meetingOptions` 下拉占位仍在 `TaskFilterBar` 与 `DashboardPage` 两处同形复制，下一轮抽 util。
- r6 后待办：任务详情来源只渲染会议标题，不显示决议项描述（AC-9 该子句与 PRD §3 非目标冲突，决议项绑定已落库）；如需展示另立需求。
