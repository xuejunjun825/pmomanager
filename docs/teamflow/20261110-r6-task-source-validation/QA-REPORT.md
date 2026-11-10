# QA-REPORT · r6 建任务来源必填项前后端口径对齐（会议决议项必填）

- 任务夹：`docs/teamflow/20261110-r6-task-source-validation/`（PRD / TECHNICAL / AC9-EVIDENCE 同夹）
- 基线：分支 `main`，HEAD `4fbbc8e`；本轮交付 = `M web/src/components/task/TaskFormDrawer.vue` + `?? web/tests/task-form-meeting-decision.spec.ts`（零后端改动）
- 执行时间：2026-11-10 18:53–19:00（Asia/Shanghai）；执行角色：QA（depth-1）

## 1. 结论

**可验收**：未发现 P0/P1/P2 缺陷。AC-1…AC-8 全部通过；AC-9 的真机前后对照由 dev 在 5174/5175 + 18081 上执行并留证（`.demo-shots/r6/`），QA 独立核验了落库结果与证据文件；浏览器复跑受沙箱限制，已列入人工补测。另记 3 条 **P3 观察项**（不阻塞验收）。

## 2. 范围与环境

| 项 | 内容 |
| --- | --- |
| 被测形态 | Vue 3 + TS 单文件组件（建任务抽屉），无新增依赖、无后端改动 |
| 前端 | Node/pnpm；`web` 工作区；vitest 3.2.7 + jsdom |
| 后端 | Spring Boot 3.3.5 / Java 25；MySQL 9.6（`supervise_test`，Flyway V1–V6） |
| 运行态 | 18081 demo 后端在线（r5 版本，只读核验用）；5174/5175 前端 dev server 已随 dev 收尾停止 |
| 浏览器 | **不可用**：沙箱进程令牌缺 PROCESS_SET_INFORMATION / PROCESS_SUSPEND_RESUME，Chromium/Edge 的 crashpad 必然自终止（`exit -36863`），非交付缺陷；真浏览器项转人工补测 |
| 账号 | demo 后端 `admin / Supervise@2026`（带 `X-XSRF-TOKEN`，先 GET `/api/auth/config` 取 cookie） |

## 3. 验证执行记录（命令 → 结果）

| # | 命令 | 结果 |
| --- | --- | --- |
| 1 | `cd web && pnpm exec vitest run tests/task-form-meeting-decision.spec.ts` | exit 0，11/11 通过 |
| 2 | `cd web && pnpm test` | exit 0，25 文件 188/188 通过（基线 177 + 新增 11） |
| 3 | `cd web && pnpm typecheck` | exit 0，`vue-tsc --noEmit` 无输出 |
| 4 | `cd web && pnpm build` | exit 0，`✓ built in 1.29s`（生产构建含 `TaskFormDrawer-*.js`） |
| 5 | `cd server && ./mvnw -q test` | exit 0；surefire 汇总 tests=248 failures=0 errors=0 skipped=0 |
| 6 | `grep -rn -e "可不选" -e "可以先只选择会议" -e "只关联会议" web/src` | exit 1，0 命中（AC-7） |
| 7 | `cd web && pnpm exec vitest run --config ../logs/teamflow/tf-mwajy6h4-txskqj/scripts/vitest.qa-r6.config.mjs` | exit 0，5/5 通过（QA 独立探针） |
| 8 | `git status --porcelain` | 仅 3 项预期改动（见 §7） |
| 9 | `git diff --stat -- server/ AGENTS.md docs/teamflow/memory.md` | 空输出（零后端、未改团队文档） |

## 4. AC 对照（功能结论）

| AC | 判据要点 | 结果 | 证据 |
| --- | --- | --- | --- |
| AC-1 必填可见且可拦截 | 未选保存 0 次 POST、摘要「还有 1 项需要填写」、行内「请选择关联决议项」、焦点落字段 | 通过 | dev spec 2 条；QA 探针实测 0 POST + 焦点 `fieldByLabel('关联决议项').contains(activeElement)=true` |
| AC-2 选齐后正确提交 | 恰 1 次 POST/201、载荷含 `sourceMeetingId`+`sourceDecisionItemId`（数字）、手填标题不被覆盖 | 通过 | dev spec；真机 `after-result.json` 201 载荷 `sourceMeetingId=2, sourceDecisionItemId=6` |
| AC-3 三形态对齐服务端 | MEETING/PROJECT/NOTE 必填集合、行内文案、0 请求、焦点；NOTE 1–200 字 | 通过 | dev spec `it.each` 3 组；QA 探针补核 PROJECT/NOTE 编辑态 |
| AC-4 422 四字段逐字段定位 | 服务端 message 回填行内、摘要「还有 N 项需要修改」、焦点落首个错误 | 通过 | dev spec 表驱动 4 行（`sourceMeetingId`/`sourceDecisionItemId`/`sourceProjectId`/`sourceNote`） |
| AC-5 编辑态只读零回归 | 三形态来源只读、无必填提示、PUT 仅 `{title,priority}`、会议决议项回显名称 | 通过 | dev spec（MEETING）+ QA 探针（PROJECT/NOTE，PUT 精确等于 `{title,priority}`） |
| AC-6 三类边界不产生 POST | 无可选项/加载失败可重试/加载中提示，均 0 POST | 通过 | dev spec 3 条；QA 探针补核加载中 0 POST |
| AC-7 可选口径文案清零 | `web/src` 0 命中 | 通过 | 上表 #6 |
| AC-8 回归与全绿 | ≥8 条新增用例、web 全量、typecheck、server 不回归 | 通过 | 11 条新增；188/188；248/248 |
| AC-9 真机闭环留证 | 修复前 1 POST→422 且无行内/无焦点；修复后 0 POST + 行内 + 焦点，选后 201 落库 | 通过（受限复核） | dev Playwright 证据 + `.demo-shots/r6/` 下 before-result.json 与 after-result.json + QA 独立 `GET /api/tasks/11` |

**AC-9 的独立核验**（不依赖浏览器）：
- `GET /api/tasks/11` → `source = {type:"MEETING", meetingId:2, meetingTitle:"R6 真机验证会议（决议项必填）", decisionItemId:6, form:"MEETING"}`；
- `GET /api/meetings/2` → `items[0] = {id:6, converted:true}`（已被该任务消耗，与"决议项唯一绑定"一致）；
- `before-result.json`：1 次 POST 422、摘要「还有 1 项需要修改」、`inlineError=""`、`focusedInsideDecision="no-container"`；`after-result.json`：0 次 POST、行内「请选择关联决议项」、焦点 `select.field__select`、选后 1 次 POST 201。
- 说明：真机用的是**新建**会议 2（会议 1 的 5 条决议项已在前序手工测试中全部转任务），未重置演示库，符合 PRD AC-9 的替代口径。

## 5. QA 独立探针（不引用交付 spec，独立 mount + 真实事件）

文件：`logs/teamflow/tf-mwajy6h4-txskqj/scripts/qa-r6-probe.spec.ts`（配置 `vitest.qa-r6.config.mjs`）。

| 用例 | 断言 | 结果 |
| --- | --- | --- |
| AC-5 编辑 PROJECT | 只读行回显「来源项目 = 老旧小区改造」，无来源下拉，PUT 载荷 `{title,priority}` | 通过 |
| AC-5 编辑 NOTE | textarea 回显「电话转来的投诉」且 `disabled=true`，PUT 无 `source*` | 通过 |
| 竞态 1→2 切会议 | 会议 1 迟到响应不覆盖会议 2 的选项（`itemsRequestSeq` 生效） | 通过 |
| AC-1 焦点/摘要 | 0 次 POST、摘要「还有 1 项需要填写」、选中后行内清空、summary 同步清空、焦点在字段内 | 通过 |
| 加载中保存 + 标题为空 | 加载结束后自动清错路径的摘要计数观察（见 P3-3） | 通过（记录现状） |

## 6. 架构与接口一致性核查（强制项，无 P1）

- **蓝图吻合**：实现全部落在单文件 `TaskFormDrawer.vue`，蓝图中列出的 11 个 api 成员逐一存在——`validate(): string[]`(:397)、`loadItems(meetingId:number)`(:297)、`retryItems()`(:327)、`onDecisionItem(value:string)`(:343)、`decisionReason(): string`(:334)、`focusFirst(order:string[])`(:446)、`noSelectableItems`(:175)、`decisionItemError`(:209)、`decisionPlaceholder`(:200)、`currentMeetingLabel`(:184)、`currentDecisionLabel`(:191)。
- **无重复实现**：原因文案仅 `:65` 一处常量（`grep "请选择关联决议项" web/src` 仅 1 行）；`decisionReason()` 单点产出，模板只吃 `decisionItemError`；决议项请求只有一个入口（`grep taskApi.decisionItems web/src` 仅 `:303`，编辑回显复用同一 `loadItems`）；`SERVER_FIELD_MAP` 唯一（:463），无第二份字段映射。
- **依赖未改**：`AppSelect.vue` 未被修改（git status 无该文件），仅使用其既有的 `required/invalid/error` props（`:19-25`）与 `.field__error[role=alert]`（`:63`）；`sourceOptions/projectOptions/projectView/DISABLED_SUFFIX` 导出均真实存在（`utils/sourceOptions.ts:34`、`utils/projectOptions.ts:28/29/45/83`）。
- **接口形态**：`tasks.ts:135 decisionItems(meetingId:number): Promise<DecisionItemRow[]>`，调用点传 `Number(meetingId)`，返回数组直接赋给 `items`；`typecheck` + `build` + 组件真实 mount 三路证明符号/签名/参数形态一致。
- **既有结构**：无新增抽象层、无跨模块拷贝；编辑态只读行复用既有 `.source-readonly` 样式（会议/项目同款），未破坏 r4 的项目来源结构。
- 结论：**架构无 P1 项**。

## 7. 提交面卫生（0c）

- `git status --porcelain`：`M web/src/components/task/TaskFormDrawer.vue`、`?? web/tests/task-form-meeting-decision.spec.ts`、`?? docs/teamflow/20261110-r6-task-source-validation/`，无其他文件。
- 忽略态复核：`.pnpm-store/`、`server/target/`、`web/dist/`、`web/node_modules/` 均为 `!!`（已被 `.gitignore` 覆盖）；`log`/`dist`/`target`/`node_modules`/`.idea`/`.env*`/`.demo-shots` 规则齐备，无依赖目录、构建产物、IDE 文件或密钥进入提交面。
- `server/`、`AGENTS.md`、`docs/teamflow/memory.md` 零改动。
- 结论：**无 P1 版本控制缺陷**。

## 8. 缺陷表

未发现 P0/P1/P2 缺陷。以下 3 条为 P3 观察项（观察不阻塞，供后续打磨）：

| 编号 | 严重级(P0/P1/P2/P3) | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P3-01 | P3 | 建任务抽屉 · 关联决议项 | 打开抽屉，选「来源会议」后不点保存，看决议项行内 | 首次进入用中性提示（如 placeholder），提交后再转错误态 | 立即渲染红色 `.field__error[role=alert]`「请选择关联决议项」，并给下拉加 `is-invalid` 红框（`decisionItemError = errors.decisionItemId \|\| decisionReason()` 导致） | AC-1（不违背，属体验） | - | - |
| P3-02 | P3 | 建任务抽屉 · 关联决议项 | 选一个决议项已全部转任务的会议（或加载失败），看行内 | 合法空态/加载失败用中性状态色说明 | 「该会议暂无可关联的决议项…」「决议项加载失败…」经 `:invalid`/`:error` 渲染为红色错误文案 + 红边框，与"用户填错"视觉语义混同 | AC-6（文案满足，样式语义） | - | - |
| P3-03 | P3 | 建任务抽屉 · 保存摘要 | 标题留空 + 会议已选但决议项仍在加载时点保存，等加载结束 | 摘要跟随当前错误数（剩 1 条标题错误 →「还有 1 项需要填写」） | 摘要停留在「还有 4 项需要填写」，决议项错误被自动清除后计数不重算（`clearStaleLoadingError()` 只删错误项）；既有 `clearError()` 同口径，非本轮引入 | AC-1/AC-6（不影响后续保存） | - | - |

## 9. 人工补测清单（环境限制，非交付缺陷）

| 项 | 内容 | 方法 / 工具 | 判据 |
| --- | --- | --- | --- |
| M1 真浏览器视觉 | 四态（初始/加载/失败/无可选）红框与文案是否过重；决议项容器聚焦 outline；重试按钮与字段间距 | 启动 `cd web && pnpm dev`（5174）+ 18081，人工打开建任务抽屉 | 视觉与 PRD §5 交互描述一致，无遮挡/溢出 |
| M2 屏幕阅读器 | 选完会议瞬间 `role=alert` 是否播报「请选择关联决议项」（P3-01） | NVDA/VoiceOver + Chrome | 播报时机不早于提交，或产品接受现状 |
| M3 18081 真机复核 | 以 admin/Supervise@2026 按 PRD §1.1 重走空态保存与选齐提交 | 手工浏览器；先准备一条 `converted=false` 的决议项（会议 2 的 6 号已转任务） | 0 POST 拦截 + 行内 + 焦点；选后 201 |
| M4 窄屏/移动端 | 抽屉内决议项字段与重试按钮换行、点击热区 | DevTools 375px 视口 | 不溢出、可点 |

## 10. 覆盖说明与残余风险

- 已执行：组件级真实交互（jsdom 派发 `change/input/click`，真实计时器语义）、全量单测、类型检查、生产构建、后端全量测试、demo 后端只读数据核验、静态契约 grep。
- 未执行：真浏览器像素/动效/读屏（环境限制，见 §9）；AC-9 的前端复跑（5174 已停、浏览器不可启动），但 dev 的 Playwright 证据文件与后端落库结果均已被 QA 独立核对，无证据链断裂。
- 非目标确认：任务详情页仍只渲染 `SourceTag`（会议标题）不渲染决议项描述，PRD §3 已列非目标，不计缺陷。
