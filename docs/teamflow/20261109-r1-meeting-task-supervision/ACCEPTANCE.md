# 验收报告 · 会议任务督办系统

- 需求夹：`docs/teamflow/20261109-r1-meeting-task-supervision/`（PRD / DESIGN / TECHNICAL / QA-REPORT）
- 分支：`feat/meeting-task-supervision`　验收日期：2026-11-09　基线：`61ef585`　验收方式：独立重跑门禁与真实 HTTP/MySQL 探针，不采信单一报告结论
- 交付面：`server/`（Spring Boot 3.3.5，Flyway V1–V3，10 表）、`web/`（Vue 3 + Vite）、`deploy/`、`scripts/`、README、docker-compose

## 1. 独立复核证据

| # | 检查 | 结果 |
| --- | --- | --- |
| V1 | `cd server && ./mvnw -q test` | exit 0；24 个测试类 / 131 tests / 0 failures / 0 errors / 0 skipped |
| V2 | `cd web && pnpm test` | exit 0；10 文件 / 70 tests 全通过 |
| V3 | `cd web && pnpm build` | exit 0；`built in 1.26s`，首屏 209.00 kB（gzip 79.52） |
| V4 | `BASE_URL=http://127.0.0.1:18090 python3 …/qa-functional.py` | exit 0；PASS=84 / FAIL=0（本轮重跑） |
| V5 | `smoke-auth-flow.py` | exit 0；18/18（含 CSRF 明文 token 契约、登出即时失效） |
| V6 | `SMOKE_PASSWORD=… smoke-convert-archived.py` | exit 0；12/12（归档会议 409 `MEETING_ARCHIVED`、活跃会议 200） |
| V7 | 运行库直查（`job_runs` + `notifications`） | `DAILY_REPORT`/`WEEKLY_REPORT` 均 `SUCCESS`，`recipients=1,pushed=0,skipped=1`，站内报表通知 2 条 |
| V8 | 结构核对 | 蓝图 31 个关键类无缺失；`TaskSql.*` 7 处复用；main 无 `LocalDate.now()`/`CURDATE`；`TaskAccessGuard` 8 处引用 |
| V9 | 库层约束 | `V1__schema.sql`：四字段 NOT NULL、`ck_tasks_source` CHECK、`uk_tasks_decision` 唯一、`archived` 归档位 |
| V10 | 安全与配置 | 会话 7d、`CsrfTokenRequestAttributeHandler`、钉钉四项空默认、README 首登改密与配置清单齐备 |

## 2. 逐条验收（PRD 21 条）

| AC | 结论 | 依据（本轮实测 / 测试） |
| --- | --- | --- |
| AC-1 登录与会话 | ✅ 通过（1 项 P3 观察） | 未登录重定向与 `next`、三角色落地页、错误文案统一、登出后旧 Cookie 401、7d 超时、钉钉入口按配置显隐；限制见 §4 |
| AC-2 权限矩阵 | ✅ 通过 | 三账号逐行请求：owner 越权 403、越界任务/通知 404、验收/代确认/设置仅授权角色 |
| AC-3 会议与决议 | ✅ 通过 | 会议字段 422、决议项逐项校验定位到行、转化状态徽标与闭环计数 |
| AC-4 一键转任务 | ✅ 通过 | 勾选 2 条 `created=2`；重复 `created=0/skipped=2 ALREADY_CONVERTED`；派发人=当前管理员 |
| AC-5 来源可追溯 | ✅ 通过 | 归档会议新增决议/转任务 409；归档后任务来源快照仍可读；会议无删除接口 |
| AC-6 四字段三层 | ✅ 通过 | 接口 422 精确到 ownerId/dueDate/sourceType；直插 MySQL 被 NOT NULL/CHECK 拒绝；表单 422 回填 |
| AC-7 三类来源 | ✅ 通过 | 会议决议/项目任务/日常待办均通；会议类必挂会议+决议项，其余必填来源说明 |
| AC-8 任务池 | ✅ 通过 | 9 列、默认排序、6 项筛选写 URL 且刷新/前进后退还原、20/页、空态提示 |
| AC-9 状态机留痕 | ✅ 通过 | 18 条合法迁移、10 条非法 409、每动作 1 条事件；改派/改期后逾期即时重算 |
| AC-10 验收闭环 | ✅ 通过 | 派发人/admin 验收、owner 403、打回原因必填并通知；待验收独立计数；已完成无催办入口 |
| AC-11 逾期读时 | ✅ 通过 | `OverdueSqlParityTest` 15 组逐条对照；Asia/Shanghai 边界单测；无表内布尔字段 |
| AC-12 每日扫描 | ✅ 通过 | 09:00 cron + 启动补跑仅当天；同日重启 `job_runs` 仍 1 行、通知不增 |
| AC-13 通知中心 | ✅ 通过 | 未读角标、20/页倒序、六类通知、渠道五态、只读本人 404、全部已读归零 |
| AC-14 一键催办 | ✅ 通过 | 站内+钉钉降级 `NOT_CONFIGURED`；10 分钟 (task,actor) 409 带 `lastUrgedAt`；次数展示 |
| AC-15 矩阵 | ✅ 通过 | 三原状态列 `NOT OVERDUE`、逾期列 `OVERDUE` 互斥；合计=非取消；下钻参数同源 |
| AC-16 看板 | ✅ 通过 | 6 KPI 与任务池同源；焦点四段排序；行内仅催办/改派/改期；owner 403 |
| AC-17 跨页一致 | ✅ 通过 | 同一 `ShanghaiClock.today()` + 同一 `TaskSql.OVERDUE`；total 42 / overdue 8 / unfinished 34 三页互算一致 |
| AC-18 钉钉身份 | ✅ 通过（真实扫码待补测） | 伪造 state 400、停用 403、未配置 503、首登 OWNER、手工分配角色；真实扫码需凭证 |
| AC-19 钉钉工作通知 | ✅ 通过（降级链路；真实送达待补测） | `NOT_CONFIGURED` 不重试；1/5/15 退避≤3 次；串行限速；失败不阻塞；真实送达需凭证 |
| AC-20 日报/周报 | ✅ 通过 | 工作日 18:00 / 周一 09:00；仅名单 1 人；空名单不发并留痕；KPI 复用看板；重复触发去重 |
| AC-21 配置与降级 | ✅ 通过 | 空凭证启动仅 1 条 WARN；入口隐藏、推送跳过、业务链零 500；补齐凭证重启即开通 |

## 3. 架构一致性（M3 质量门）

- 蓝图落地：`auth/user/meeting/task/notify/scan/dashboard/settings/report/common/config/meta` 共 121 个 main 类；接口签名、职责边界与蓝图一致，未发现重复实现或适配器漂移。
- 读侧单源：`TaskSql` 被任务池/详情/矩阵/看板/日报/扫描/查询构建器 7 处复用；写侧 `TaskCommandService` 以 `SELECT FOR UPDATE` 串行化 5 状态 18 迁移；通知 `dedup_key` INSERT-catch 幂等；`job_runs(job_key,run_date)` 主键保证一天一次。
- 时间/权限单源：`ShanghaiClock` 可注入 `Clock`，main 无 `LocalDate.now()`、SQL 无 `CURDATE`；`TaskAccessGuard` 被 8 个服务引用，Controller 不写业务分支。
- 结论：与架构蓝图一致，无结构性返工项。

## 4. 遗留、已知限制与人工补测

1. **钉钉真实链路**：凭证未就绪，按 AC-21 以空配置降级路径验收；凭证就绪后按 QA-REPORT §5 M2/M3 补测真实扫码与三类工作通知送达。
2. **真浏览器视觉/可访问性**（M1/M5/M6）：沙箱无法启动 Chromium，1440/1280/375 响应式、遮挡、焦点环、读屏与全键盘需人工目视复核。
3. **真时钟 09:00 / 周一 09:00**（M4）：以启动补跑与每分钟轮询等价覆盖，建议上线首周观察真实日历触发。
4. **P3 观察（不阻断）**：TECHNICAL §9 称钉钉容器关闭由前端 `pagehide` 尽力登出，但 `web/src` 无该监听（仅 SettingsPage 的 beforeunload 未保存提示）；服务端无法感知钉钉侧登出，当前依赖系统登出+7 天超时。建议补 `pagehide→logout` 或修正文档表述。
5. **运行环境**：本机 MySQL 9.6 / Java 25 跑测试全绿；生产按 README 使用 MySQL 8 + Java 17。

## 5. 验收意见

交付物完整，21 条 AC 均有可复现证据，未发现 P0–P2 缺陷；架构与蓝图一致、单源约束成立。建议完成 §4 钉钉真实链路与视觉人工补测后上线。

验收结论：✅ 通过
