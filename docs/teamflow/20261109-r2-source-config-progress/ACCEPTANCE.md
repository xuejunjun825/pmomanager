# r2 来源配置 + 进度 @ · 验收报告

- 任务夹：`docs/teamflow/20261109-r2-source-config-progress/`
- 验收对象：`main` 工作树（基线提交 `b8c09a9` + 未提交终态 11 改 / 4 新增，含 T08 前端与 QA 第 3 轮修复；`QA-REPORT.md` 记录被测对象即此状态）
- 验收依据：`PRD.md` 13 条 AC（基线 r1，显式取代 r1 AC-2/AC-7 相关口径）、`TECHNICAL.md`、`QA-REPORT.md`（第 3 轮，结论「可验收」）
- 结论概述：**13 条 AC 功能面全部满足，未发现 P0/P1/P2 缺陷**；因 1 项架构收口（R-1，前端来源选项投影三处重复）与 1 项交付动作（R-2，终态未提交），本轮判有条件通过，明细见 §3/§4。

## 1. 逐条验收

| AC | 优先级 | 验收要点（PRD） | 核对证据 | 结论 |
|---|---|---|---|---|
| AC-1 来源配置维护页 | P0 | 仅 admin；名称 2–10 字唯一、上限 20、8 色板、排序 0–99、启停、被引用数 | `source/SourceTypeService.java:67-97`（LIMIT=20、重名 409、编码 `S+5位`）、`:205-215`（codePoint 计长）、`SourcePalette.java:26-34`（8 色）；`SettingsPage.vue:417-428` 第四张卡；`router/index.ts:115` 系统设置 roles:['ADMIN']；QA 功能 81/81、CRUD 11/11 | 通过 |
| AC-2 按配置建任务 | P0 | 仅启用项；会议型必绑会议+决议项；说明型 1–200 字；未知/停用/形态不符 400；库层约束不放宽 | `task/TaskService.java:186-234`（`requireEnabled` + 形态分支 + 会议归属/决议项唯一）；`dto/CreateTaskRequest.java:28-35`（sourceType 必填、note @Size 200）；`V4__source_types.sql:27-36`（保留结构互斥 CHECK + FK，未放宽 NOT NULL）；QA 81/81 | 通过 |
| AC-3 升级兼容 | P0 | 自动写入三条内置、颜色与 r1 一致、不改写 tasks；MEETING 不可删/停 | `V4__source_types.sql:21-24`（MEETING/PROJECT/DAILY，#1B4D8F/#6941C6/#475467，sort 0/1/2，全启用）；迁移无 `UPDATE tasks`；`SourceCodes.java:16-17` MEETING 锁定；QA 升级库 10/10 | 通过 |
| AC-4 删除停用护栏 | P0 | 被引用删除 409 只能停用；停用后新建不可选、历史/筛选仍显示；内置会议不可删停 | `SourceTypeService.java:131-165`（停用/删除、内置保护、`SOURCE_IN_USE` 409）；`SourceTypeDao` 引用计数；前端 `TaskFormDrawer.vue:70-77` 仅启用项、三处筛选保留全量；QA 81/81 + E2E 删除 409/停用建任务 400 | 通过 |
| AC-5 配置同源一致 | P1 | meta/表单/池/矩阵/看板/标签同一配置；未知编码 400；新增类型两处可筛 | `meta/MetaService.java:72` 输出全量；`TaskQueryBuilder.java:52`、`dashboard/MatrixService.java:150` 统一 `requireKnown`；前端三处筛选读 `meta.sourceTypes`；QA 跨页 11/11；静态契约 3 PASS（含「不再按来源编码判断形态」） | 通过（架构收口见 R-1） |
| AC-6 权限与审计 | P1 | 非 admin 403；五类 audit action；detail 含前后值 | `SourceTypeService.java:33-37` 五常量、`:198-201` `requireAdmin`；create/update/setEnabled/delete 均先鉴权并 `audit.record`；QA `qa-r3-fixes.py` 12/12、接口冒烟含越权用例 | 通过 |
| AC-7 进度追加 | P0 | 1–500 字、1 秒内入时间线顶部、append-only、单任务 200 条、任何状态可补、不改状态 | `TaskProgressService.java:56-71`（trim/长度/200 上限/仅 INSERT）；`TaskDetailPage.vue:392-400` 合并时间线、`:118` 成功后乐观置顶；无编辑/删除端点；前端 t08 + QA 81/81 | 通过 |
| AC-8 进度写权限 | P0 | 可查看者可写；其他人 403 而详情 404；不授予状态动作；停用用户不可写 | `TaskAccessGuard.java:26-51`（`canView` = admin/exec/owner/被@；`requireView` 404、`requireProgressWrite` 403）；状态动作仍走 `requireOperate/requireAccept/...`；QA `TaskProgressAccessIT` 6/6 | 通过 |
| AC-9 @ 选择 | P0 | 仅启用、排除自己、可搜索多选、≤10、去重、仅选择器选中才算 | `TaskProgressService.java:93-113`（distinct、剔自己、≤10、仅启用）；`ProgressComposer.vue:36,66-74,111-128`（选择器写 token、不解析正文）；QA 81/81 | 通过 |
| AC-10 @ 通知 | P0 | MENTION 站内+钉钉、100 字截断、异步不阻塞、未配置降级、按(进度,用户)幂等 | `MessageTemplates.java:57-73`（标题模板 + 100 code point）；`NotificationKeys.java:35-37`（`mention:{pid}:{uid}`）；`NotificationService.java:96-99` `@Async("dingtalkExecutor")`、`:199-214` 未读/已读覆盖本行用户；QA 81/81 + 通知 IT | 通过 |
| AC-11 被 @ 可见性 | P0 | 详情 404→200、可续写进度、状态动作全 403、矩阵/KPI 口径不变 | `TaskAccessGuard.java:26-33` 被@分支；`TaskDetailPage.vue:256-261` 只读提示、`:277,287` `mentionOnly` 隐藏状态动作；`TaskPermissions` canAddProgress/mentionOnly；接口 403 兜底；QA 81/81 + t08 | 通过 |
| AC-12 口径不变 | P0/P1 | 不改 status/due/owner、不写 task_events；池/矩阵/看板/逾期数字一致；更新时间不变 | `TaskProgressDao.java:57-74` 仅两条 INSERT，无 tasks/task_events 写入；`TaskProgressService` 不 touch 任务字段；QA 跨页 11/11 + `TaskProgressRegressionIT` 4/4 | 通过 |
| AC-13 通知中心类型 | P1 | 「@我的」标签；未读角标与全部已读含 MENTION；只读本人 | `NotifyType.java:8` MENTION「被@」；`NotificationsPage.vue:203-208`「@我的」tab、`:65-69` 深链；`NotificationService.java:199-214` 未按类型过滤未读/已读；QA 81/81 | 通过 |

## 2. 本轮独立复核（我实际执行）

- `cd server && ./mvnw -q test` → **exit 0**；36 个 surefire 报告合计 **198 tests / 0 failures / 0 errors**（与 QA 报告一致）。
- `cd web && pnpm test` → **14 files / 93 tests passed**。
- `python3 logs/teamflow/tf-mw96rkbc-ejjjuq/scripts/qa-r2-static-contract.py` → **3 PASS**（53 个前端调用名 / 71 个导出成员；55 条前端路径 / 55 条后端路由；来源形态不再按编码判断）。
- 代码核对：`V4__source_types.sql`、`V5__task_progress.sql`、`SourceTypeService`、`TaskService.validateSource`、`TaskAccessGuard`、`TaskProgressService/Dao`、`NotificationService`、`TaskDetailPage.vue`、`NotificationsPage.vue`。
- 未重跑 QA 的 HTTP 功能探针（依赖其 18090/18091 实例与专用夹具）；QA-REPORT §2 的命令与计数逐条在案，且本轮修复面已被上述单测、静态契约与 DOM 门禁覆盖。

## 3. 架构一致性检查（M3 质量门）

本轮未注入架构蓝图切片，按代码事实核对结构底线：

- **来源唯一入口成立**：`SourceTypeService.requireKnown` 被 `TaskQueryBuilder.java:52` 与 `dashboard/MatrixService.java:150` 复用；`requireEnabled` 仅建任务 `TaskService.java:190` 使用，无第二套枚举解析。
- **可见性单点成立**：`TaskAccessGuard.canView` 是唯一谓词（含被@分支），`TaskService` 已复用（D-03 关闭），无复制实现。
- **SQL/时钟单源成立**：`TaskSql`/`selectBase()` 被看板与任务池复用；全仓无真实 `LocalDate.now()`/`CURDATE()`（QA 轻检一致）。
- **进度写入单点**：`TaskProgressDao` 只追加两张表，`DecisionConversionService.java:111` 已改用 `SourceCodes.MEETING`（D-06 关闭）。
- **未达标项（A-01）**：前端来源选项投影在 `TaskFilterBar.vue:52-63`、`MatrixPage.vue:49-60`、`DashboardPage.vue:68-79` 三处逐字重复，`TaskFormDrawer.vue:70` 另有「仅启用」变体。D-02 正是漏改其中一处造成漂移，虽已修复并有看板 DOM 门禁，但复制仍在，属「应抽未抽」→ 条件项 **R-1**。

## 4. 返工项与遗留

| 编号 | 类型 | 内容 | 复验判据 |
|---|---|---|---|
| R-1 | 架构（条件） | 抽公共 `sourceOptions(items)`（含「全部来源」占位、启用按 sort 在前、停用按 sort 在后并带「（已停用）」），`TaskFilterBar`/`MatrixPage`/`DashboardPage` 复用；`TaskFormDrawer` 的仅启用变体同样走该模块 | 全仓无重复投影（grep 仅一处实现）；`pnpm test` 绿；三页下拉顺序/文案不变、看板 DOM 门禁仍绿 |
| R-2 | 交付动作 | 终态未提交：T08、QA 第 3 轮修复、新增测试与本任务夹均在工作树 | `git status` 干净，HEAD 包含 `docs/teamflow/20261109-r2-source-config-progress/` |
| D-05 | P3（r1 既有） | 登录后紧跟写请求可能 403 的会话/CSRF 行为，非本轮引入 | 另开任务修复，不阻塞本需求 |
| 人工补测 | — | 真实钉钉送达（MENTION/催办/扫码）、真浏览器视觉与可访问性、真实 18:00 定时 | 按 `QA-REPORT.md` §7 清单执行，环境限制项 |

## 5. 验收意见

- 功能面：13 条 AC 全部可判通过，P0 无缺项；PRD 五条反面目标（认不出来源 / 三处不一致 / @ 人 404 / 补进度改数字 / 删在用来源）均未复现。
- 质量面：后端 198、前端 93、静态契约 3/3 全绿；D-01（P2）与 D-02/D-03/D-04/D-06（P3）已关闭；无 P0/P1/P2 缺陷。
- 判有条件通过的唯一实质原因是 R-1：AC-5「同源一致」的数据源已单点，但展示投影仍三处复制，且本轮已因此产生过一次真实缺陷（D-02）；按架构质量门需收口后复验。R-2 为交付动作，随返工一并完成。
- 返工范围小且局部：复验仅需检查 R-1/R-2 与三页下拉回归，无需重跑全量 HTTP 探针。

验收结论：⚠️ 有条件通过
