# ACCEPTANCE · r6 建任务「关联决议项」必填与三形态口径对齐

- 需求夹：`docs/teamflow/20261110-r6-task-source-validation/`（PRD / TECHNICAL / QA-REPORT / AC9-EVIDENCE 为本轮契约）
- 基线依赖：r4 `20261110-r4-project-source-binding`（三形态互斥与载荷）、r1 `20261109-r1-meeting-task-supervision`（AC-6 会议决议必须同时挂会议与决议项）
- 验收人：验收负责人（acceptance 阶段）· 验收时间 2026-11-10 19:00（Asia/Shanghai）
- 被测状态：分支 `main`，HEAD `4fbbc8e` + 工作区本需求改动（`M web/src/components/task/TaskFormDrawer.vue`、`?? web/tests/task-form-meeting-decision.spec.ts`；host 验收后统一提交）
- 结论：**✅ 通过**（无 P0/P1/P2 缺陷；3 条 P3 与 1 条 AC-9 子句观察列为遗留，不阻塞；真浏览器视觉/读屏为人工补测）

## 1. 验收方法与本次独立证据

不采信转述结论：本轮由验收人亲自重跑测试、静态核对实现与后端口径、复核真机证据并独立查询演示库。

| # | 证据（本轮实际执行） | 结果 |
| --- | --- | --- |
| V1 | `cd web && pnpm exec vitest run tests/task-form-meeting-decision.spec.ts` | exit 0，**11/11** 通过 |
| V2 | `cd web && pnpm test` | exit 0，**25 文件 / 188 用例全过**（基线 177 + 新增 11） |
| V3 | `cd web && pnpm typecheck`（vue-tsc --noEmit） | exit 0，无输出 |
| V4 | `cd server && ./mvnw -q test` + surefire 汇总 | **43 个测试类 / 248 用例 / failures=0 errors=0 skipped=0**（零后端改动下的不回归证明） |
| V5 | `grep -rn -e "可不选" -e "可以先只选择会议" -e "只关联会议" web/src` | exit 1，**0 命中**（AC-7） |
| V6 | 单一实现点 grep | 「请选择关联决议项」仅 `TaskFormDrawer.vue:65` 常量 1 处；`taskApi.decisionItems` 仅 `:303` 一个调用点；`SERVER_FIELD_MAP` 仅 `:463` 一处；spec 未 import 组件内部实现（grep exit 1） |
| V7 | `git diff --stat` / `git diff -- server/ AGENTS.md docs/teamflow/memory.md` | 源码 **1 file changed, +200/−36**；server/AGENTS.md/memory 空输出（零后端、未改团队文档） |
| V8 | 阅读实现 diff 与组件 `:65/:175/:209/:297/:334/:397/:446/:463/:490-560` | `decisionReason()` 四态单点、`validate()` MEETING 分支、`loadItems` 请求序号、编辑态只读行、`focusFirst` 可交互优先、按形态组载荷，逐项与蓝图一致 |
| V9 | 阅读后端 `TaskService.java:205-258`、`CreateTaskRequest.java:22-45` | MEETING 强制 `sourceMeetingId`+`sourceDecisionItemId`；`@Size(max=100)/@Size(max=200)` 与前端 `TITLE_MAX/NOTE_MAX` 相等 |
| V10 | 复核 `AC9-EVIDENCE.md`、`.demo-shots/r6/before-result.json`、`after-result.json` 与截图 | before：1 次 POST→422、无行内、无焦点；after：0 次 POST + 行内 + 焦点，选后 1 次 201（载荷 meeting=2/decision=6） |
| V11 | 独立 API 复核（真机 18081，admin 会话）：`GET /api/tasks/11` | 200，`source = {type:"MEETING", meetingId:2, decisionItemId:6, form:"MEETING"}`，`title="R6 真机-after-935161"` |
| V12 | 视觉抽查 `.demo-shots/r6/after-02-empty-save.png`、`after-05-task-detail-source.png` | 摘要/行内红字/必填标记/焦点态布局正常，无遮挡溢出；详情来源仅渲染会议标题（见 OBS-1） |

## 2. 逐条 AC 核对

| AC | 验收要点 | 证据（独立） | 结论 |
| --- | --- | --- | --- |
| **AC-1** 会议形态必填可见、可拦截 | 必填标记 + placeholder 不含可选暗示 + 初始为空；留空保存 0 POST、摘要「还有 1 项需要填写」、行内「请选择关联决议项」、焦点落该字段 | V1 的 2 条用例（`contains(activeElement)`）；V10 before/after 对照；V12 截图 | **PASS** |
| **AC-2** 选齐后一次提交成功 | 恰 1 次 POST/201，载荷 `sourceMeetingId`+数字 `sourceDecisionItemId`，无 422、不覆盖手填标题 | V1 载荷断言；V10 `after-result.json`（1 次 201，meeting=2/decision=6）；V11 落库 | **PASS** |
| **AC-3** 三形态前端必填 = 服务端 `validateSource()` | MEETING 会议/决议项、PROJECT 项目、NOTE 1–200 字；行内文案、0 请求、焦点；合法载荷只带本组字段 | V1 表驱动 3 行 + NOTE 空白/超长；V9 后端逐分支对照；r4 `task-form-project.spec.ts:164-181` 仍全绿（PROJECT 载荷只含 `sourceProjectId`）；`TITLE_MAX=100`/`NOTE_MAX=200` 与 DTO 相等 | **PASS** |
| **AC-4** 服务端 422 逐字段定位 | 四字段（meeting/decision/project/note）表驱动：摘要「还有 N 项需要修改」、行内服务端 message、焦点首个错误 | V1 的 AC-4 用例（4 行表驱动）；`applyServerErrors`+`SERVER_FIELD_MAP` 单点（V8） | **PASS** |
| **AC-5** 编辑态来源只读零回归 | 三形态只读、无必填红字、PUT 恰 1 次且载荷不含 `source*`、决议项正确回显 | V1 会议态用例（只读两行、`decisionItems` 仅 1 次、PUT 精确 `{title,priority}`）；QA 独立探针补核 PROJECT/NOTE 编辑态；`watch` 首行 `if (isEdit) return`（V8） | **PASS** |
| **AC-6** 决议项不可选边界可解释 | 无可选项/加载失败可重试/加载中 三态给原因，均不产生 POST | V1 三条用例；`noSelectableItems`/`itemsError`/`decisionReason` 单点；**无可选时字段禁用**（PRD 反面目标 2） | **PASS** |
| **AC-7** 可选口径文案清零 | `web/src` 内「可不选」等 0 命中，label/hint/toast 统一必填口径 | V5；hint「已转为任务的决议项不可重复选择」、toast 已改加载失败态 | **PASS** |
| **AC-8** 回归用例与全量绿灯 | ≥8 条可观察断言用例；web 全量 + typecheck 通过；server 既有用例不回归 | V1（11 条，仅请求/文案/载荷/焦点四类断言）、V2 188/188、V3、V4 248/0/0/0 | **PASS** |
| **AC-9** 18081 真机闭环 | 留空无 POST + 行内 + 焦点；选后 201、任务池可见、来源落库；前后证据留存 | V10（before 1 POST→422 无行内/无焦点，after 0 POST + 行内 + 焦点 + 201）、V11（`GET /api/tasks/11` 决策项 6 已落库）、截图 `after-01…05`；AC-9「详情显示会议 + 决议项」子句见 OBS-1 | **PASS**（核心闭环；子句受限） |

## 3. 架构与结构一致性（强制项）

- **蓝图吻合**：实现全部落在单文件 `web/src/components/task/TaskFormDrawer.vue`；蓝图列出的 11 个接口成员逐一存在——`validate():string[]`(:397)、`loadItems(meetingId:number)`(:297)、`retryItems()`(:327)、`onDecisionItem(value:string)`(:343)、`decisionReason():string`(:334)、`focusFirst(order:string[])`(:446)、`noSelectableItems`(:175)、`decisionItemError`(:209)、`decisionPlaceholder`(:200)、`currentMeetingLabel`(:184)、`currentDecisionLabel`(:191)。
- **无重复实现 / 无口径漂移**：四种原因只由 `decisionReason()` 产出（常量 :65-71），`validate()`、行内 `:error`、摘要计数共用同一判断；决议项加载只有 `loadItems()` 一个入口（新建 watch 与编辑 resetForm 共用），`itemsRequestSeq` 丢弃过期响应；`SERVER_FIELD_MAP` 唯一；测试用字面量断言而未复制实现。
- **未破坏既有结构**：`AppSelect.vue` 未被修改（既有 `required/invalid/error` props 直接复用，比蓝图「加 prop」更小改动）；编辑态只读行复用既有 `.source-readonly` 样式；无新增抽象层、无跨模块拷贝；PROJECT/NOTE 形态与 r4 行为零回归（r4 用例全绿）。
- **结论**：无 P1 架构项；「偏离蓝图 / 重复实现 / 应抽未抽」三项均不成立。

## 4. 缺陷与观察项

未发现 P0/P1/P2 缺陷。以下 4 条为不阻塞的 P3/观察项（与 QA-REPORT §8 一致，验收人已复核其代码成因）：

| 编号 | 级别 | 观察 | 影响与处置 |
| --- | --- | --- | --- |
| P3-01 | P3 | 选完会议、决议项**尚未提交**时，`decisionItemError = errors.decisionItemId \|\| decisionReason()` 即渲染红字「请选择关联决议项」并给下拉红框 | 属「提前报错」体验问题，不违背 AC（AC-1 只要求保存时可拦截）；建议后续改为「提交后转错误态」，本轮不返工 |
| P3-02 | P3 | 「暂无决议项 / 加载失败」等合法状态文案经 `:invalid/:error` 渲染为红色错误样式 | 语义偏重，但满足 AC-6「可解释 + 不产生 POST」；样式打磨列后续 |
| P3-03 | P3 | 标题为空 + 决议项加载中保存，加载结束后错误被自动清除，摘要计数不重算（「还有 4 项需要填写」滞留） | 既有 `clearError` 同口径，不影响再次保存与提交正确性；列后续 |
| OBS-1 | 观察 | AC-9 写「详情来源显示『会议 + 决议项』」，实际详情页只渲染 `SourceTag`（会议标题），不渲染决议项描述；`GET /api/tasks/11` 已返回 `decisionItemId=6`（V11），绑定本身未丢 | PRD §3 非目标明确「不改详情展示」，与 AC-9 该子句冲突；按非目标执行，**不判交付缺陷**，建议另立需求展示决议项描述 |

## 5. 人工补测遗留（环境限制，非交付缺陷）

- M1 真浏览器视觉：四态（初始/加载/失败/无可选）红框与文案轻重、聚焦 outline、重试按钮间距（`pnpm dev` + 18081）。
- M2 屏幕阅读器：选完会议瞬间 `role=alert` 是否提前播报（关联 P3-01）。
- M3 18081 手工复核：需先准备一条 `converted=false` 的决议项（会议 2 的 6 号已被任务 11 消耗）。
- M4 窄屏 375px：决议项字段与重试按钮换行、点击热区。

## 6. 提交面与文档合规

- `git status --porcelain` 仅本需求 3 项：`M web/src/components/task/TaskFormDrawer.vue`、`?? web/tests/task-form-meeting-decision.spec.ts`、`?? docs/teamflow/20261110-r6-task-source-validation/`；`.demo-shots/`、`logs/` 均被 `.gitignore` 覆盖（`.gitignore:21/37`），无构建产物、无临时脚本入库。
- 文档只写在 `docs/teamflow/20261110-r6-task-source-validation/`；`AGENTS.md` 未被改动（仓库根无该文件）；`memory.md` 仅按「已知待办」同主题追加 1 行（OBS-1 后续项），未写变更日志。
- 架构/契约零漂移：后端、DDL、错误码、`AppSelect` 公共 API、r4 三形态载荷均未变。

## 7. 验收意见

本次交付命中用户报告的根因（前端 `validate()` 漏校验决议项、错误无渲染位、`focusFirst` 无元素可聚焦），并以「0 次 POST + 行内文案 + 焦点」而非「改 placeholder」证明修复；三形态与 422 兜底、编辑态只读、无选项边界均有可观察回归，web 188 与 server 248 全绿，真机前后对照与库内数据经验收人独立复核。无返工项，可进入提交与发布；OBS-1 与 3 条 P3 作为后续打磨，不阻塞本次验收。

验收结论：✅ 通过
