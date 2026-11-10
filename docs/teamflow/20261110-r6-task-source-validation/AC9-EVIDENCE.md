# AC-9 · 18081 + 5174 真机闭环证据（修复前后）

执行时间：2026-11-10 18:52（Asia/Shanghai）；执行人：dev T03。
脚本：`logs/teamflow/tf-mwajy6h4-txskqj/scripts/ac9-real-machine.cjs`（Playwright，同脚本跑修复前/后两个站点）。
截图与网络记录：`.demo-shots/r6/`（按 .gitignore 不提交）。

## 0. 数据准备（未重置演示库）

会议 1「11 月产品例会（Q4 交付冲刺）」的 5 条决议项在前序手工测试中**已全部转为任务**（GET `/api/meetings/1` 逐条 `converted=true`），无可用项。按 PRD AC-9 替代口径，改用新建的验证会议，不重置、不改写既有数据：

- 会议 2「R6 真机验证会议（决议项必填）」（`POST /api/meetings` 201）
- 决议项 6「R6 联调：决议项必填真机验证（第一条）」`converted=false`（`POST /api/meetings/2/decision-items` 201）

## 1. 修复前（HEAD 版本，5175 临时站点，同一 18081 后端）

前端副本按 HEAD 物化在 `logs/.../probe/head/`（`git show HEAD:web/src/components/task/TaskFormDrawer.vue`），未改动工作区源码。

| 观测点 | 结果 |
| --- | --- |
| 「关联决议项」必填标记 | 无（`requiredMark=0`），placeholder「可不选，只关联会议」 |
| 留空点保存 | `POST /api/tasks` **1 次 → 422**，`fieldErrors=[{field:"sourceDecisionItemId",message:"请选择关联决议项"}]` |
| 底部摘要 | 「还有 1 项需要修改」 |
| 字段行内文案 | **空**（无 `.field__error`） |
| 焦点 | 无决议项字段容器，焦点不在该字段 |

截图：`before-01-form-filled.png`、`before-02-empty-save.png`；网络记录：`before-result.json`。与 PRD §1.1 现象逐项一致。

## 2. 修复后（5174）

| 观测点 | 结果 |
| --- | --- |
| 必填标记 | 「关联决议项 *」可见（`requiredMark=1`），placeholder「选择决议项」 |
| 留空点保存 | `POST /api/tasks` **0 次**；摘要「还有 1 项需要填写」；行内「请选择关联决议项」；`document.activeElement` = 该字段内 `select.field__select` |
| 选一条未转任务决议项后保存 | `POST /api/tasks` **1 次 → 201**，载荷 `sourceMeetingId=2`、`sourceDecisionItemId=6`，无 422 |
| 任务池 | 新任务「R6 真机-after-935161」出现在列表（id=11） |
| 任务详情 | 来源显示「会议决议 + R6 真机验证会议（决议项必填）」；接口 `GET /api/tasks/11` 的 `source.decisionItemId=6` 已落库 |

截图：`after-01-form-filled.png`、`after-02-empty-save.png`、`after-03-item-picked.png`、`after-04-task-pool.png`、`after-05-task-detail-source.png`；网络记录：`after-result.json`。

## 3. 一处与 AC-9 措辞的偏差（如实记录）

AC-9 写「详情来源显示『会议 + 决议项』」。实际任务详情页的来源区块只渲染 `SourceTag`（会议标题），**不渲染决议项描述**（`web/src/pages/TaskDetailPage.vue` 无 `decisionItem` 引用；grep 全 `web/src` 仅 `types/api.ts:185` 有字段定义）。决议项绑定本身已持久化（见上表 API 证据），属既有展示范围，PRD §3 非目标已明确「不改任务详情展示」，故本轮不扩范围；如需详情显示决议项描述，应另立需求。
