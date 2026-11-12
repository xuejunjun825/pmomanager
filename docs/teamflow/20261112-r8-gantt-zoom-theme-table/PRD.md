<!-- meta: summary="r7 验收返工：甘特周/月档按视口宽整除适配一屏（任务条首屏可见、不横滚），并修复系统设置主题配置表 6 列错位，仅改前端且不回退既有测试基线" -->

基线依赖：`docs/teamflow/20261112-r7-themed-gantt-task-loop/`（其 14 条 AC、架构单点与 305/300 测试基线不得回归）

取代：`docs/teamflow/20261112-r7-themed-gantt-task-loop/#AC-10`：甘特三档缩放由「40px 日宽下限优先、允许横向滚动」改为「窗口天数固定（日 14 / 周 84 / 月 182）、日宽按 timelineW 整除、整屏不横滚」

取代：`docs/teamflow/20261112-r7-themed-gantt-task-loop/#AC-14`：甘特视觉证据由「截图已拍」升级为「截图落盘前断言可见条数 > 0，且文件名档位与画面一致」

# r8 甘特三档整屏 × 主题配置表列对齐（r7 验收返工）

## 1. 背景与目标

r7「主题化甘特 × 会议登记 × 任务闭环」已交付（commit `1709f2a`），验收不通过，两个缺陷均在本轮修复，功能主体不重做：

- **A-01（P0，阻断）**：`/gantt` 默认周档首屏看不到任何任务条。1512×900 实测 `.gantt__scroll` clientWidth=1238 / scrollWidth=3640（周档）、7280（月档），25 条任务全部落在视口右侧（首条 left≈1160px、lane 内 x≈1689），首屏只剩网格与今天虚线；日档 scrollWidth=1232 正常。
  根因：`web/src/composables/useGanttWindow.ts:37` `ZOOM_DAYS={day:14,week:84,month:182}`、`:38` `MIN_DAY_WIDTH=40`、`:129-132` `dayWidth()=Math.max(40, Math.min(96, floor(timelineWidth/days)))`——周档 `floor(1238/84)=14` 被抬到 40 ⇒ 84×40=3360px；月档 182×40=7280px。「40px 下限」压过了「适配一屏」，与 r7 PRD「周 12 周/屏、月 6 个月/屏」及 r7 DESIGN 自己写的「timelineW≈1063 → dayW=12.6（周档）」直接矛盾。更糟的是 `web/tests/gantt-window.spec.ts:86-98` 把这套错误法则写成了用例（"dayW clamp 40–96"），测试全绿反而保护了缺陷。
- **A-02（P2）**：系统设置「主题配置」表 6 列错位。`web/src/components/settings/ThemeConfigTable.vue:428` `.cell-name{display:flex}`、`:452` `.cell-color{display:flex}` 直接作用在 `td` 上，`td` 变成 flex 后脱离表格列宽：名称与颜色重叠，状态/排序/引用/操作整体左移一列，数值落在错误表头下（管理员可能把「排序」当「状态」读）。

**目标**（按优先级）：

1. 周档 84 天、月档 182 天在时间轴可用宽内**整屏落位**，任务条首屏可见且不横滚；日档 14 天现状不回退。
2. 任务条宽度按 `max(10px, 有效天数×dayW)`，压缩后仍可辨认、可点击。
3. 主题配置表 6 列表头与单元格逐列对齐，并用探针 + 组件测试双向防回归。
4. 证据可信：周/月档几何探针 `visible>0 且 offRight=0`，三张截图落盘前先断言可见条数 > 0。

**成功定义**（全部满足才算交付）：AC-1~AC-11 通过，AC-12 为可选；server 305/305、web ≥300/300、typecheck/build 通过；`git diff` 只含前端与测试/探针/文档。

## 2. 用户故事与验收标准

### US-1 业务主管打开甘特即见全局（P0）

> 作为业务主管，我打开甘特就想一眼看到所有任务条落在哪一周、哪一段，而不是先横向拖三次才看到第一根条。

**AC-1（P0）周档整屏、条可见**
1512×900 视口、有任务数据时打开 `/gantt`（默认周档、窗口 84 天）：`.gantt__scroll` 的 `scrollWidth ≤ clientWidth + 2`（无横向滚动）；`dayWidth = floor(timelineW / 84)`（timelineW = 时间轴可用宽），实测 dayW 落在 10–16px；几何探针 `week.visible > 0` 且 `week.offRight === 0`。

**AC-2（P0）月档整屏、条可见**
切「月」档（182 天）：`scrollWidth ≤ clientWidth + 2`；dayW 落在 4–8px；探针 `month.visible > 0` 且 `month.offRight === 0`（r7 探针 verdict 只断言了 week 的 offRight，本轮必须补上 month 的，否则本 AC 不算通过）。

**AC-3（P0）日档不回退**
切「日」档（14 天）：`scrollWidth ≤ clientWidth + 2`，dayW 与 r7 实测一致（1512 视口约 68px，允许 ±2px 取整差）；探针 `day.visible > 0`、`day.offRight === 0`。

**AC-4（P0）条宽与落点**
每条任务条 `width = max(10, 有效天数×dayW)`（有效天数 = 窗口内起止天数，含首尾）；周/月档下 1 天任务仍 ≥10px 且可点击；任何条满足 `x ≥ 0` 且 `x + width ≤ 总时间轴宽 + 1`（不越界、不出现 width=0 的隐形条）。

**AC-5（P1）刻度仍可读**
周档按周、月档按自然月出刻度（沿用现有 `buildTicks` 语义，`THIN_LABEL_WIDTH=56` 抽稀逻辑保留）；相邻可见刻度文字矩形两两不相交（探针抽前 10 个可见刻度判定）；月档至少 6 个可见月份标签；「今天」虚线与周/月带仍在。

**AC-6（P1）视觉证据可信**
交付三张截图：`day` / `week` / `month` 各一张，文件名与画面档位一致（r7 的 `13-gantt-day.png` 实为周档，禁止复用）；截图脚本在每次落盘前断言「可见条数 > 0」，断言失败必须 exit≠0 且不落盘；周/月截图肉眼可见 ≥1 条任务条。截图存 `.demo-shots/r8/`（gitignored），探针 JSON 输出存 `logs/teamflow/tf-mwdcbb3m-qxezpv/`。

### US-2 管理员在系统设置维护主题（P2）

> 作为管理员，我改一个主题的排序时，要能确定自己看的那一列就是「排序」。

**AC-7（P2，必须交付）主题配置表 6 列对齐**
`/settings` 主题配置表：表头 6 列（名称 / 颜色 / 状态 / 排序 / 引用 / 操作）与首行 6 个 `td` 左边界逐列偏差 ≤6px、单元格数 = 6；名称与颜色两列矩形不重叠；`td.cell-name` / `td.cell-color` 的 computed `display` 为 `table-cell`，flex 布局在内层 `div`；`node logs/teamflow/<runId>/scripts/qa-settings-layout.cjs` 退出码 0。

**AC-8（P2）组件测试防回归**
扩展 `web/tests/theme-config.spec.ts`（或同目录新 spec）：每行 6 个 `td`、表头文案顺序固定、`.cell-name`/`.cell-color` 的内层 flex 容器是 `div` 且父节点为 `td`；不得靠删除断言换绿，web 用例总数 ≥300 且全绿。

### US-3 工程守约（P1）

**AC-9（P1）回归基线不下降**
`server` 305/305、`web` ≥300/300、`pnpm -C web typecheck`、`pnpm -C web build` 全部通过；`git diff --stat` 不含 `server/**`；接口、状态机、验收权限、计数口径零改动。

**AC-10（P1）缩放语义不回归**
三档切换仍只发 1 个 `/api/gantt` 请求，翻屏仍 1 个请求且步长 = 窗口天数（周 84 / 月 182 天，不是被压缩后的天数）；300 条截断提示、逾期条钉左不裁剪、摘要点击即筛、点击条进详情均与 r7 一致。

**AC-11（P1）首帧不闪宽**
容器宽度尚未测量（timelineW ≤ 0）时不得按 40px×天数渲染超宽时间轴（可延迟渲染骨架或按档位保守日宽回退）；页面加载后 200ms 采样 `scrollWidth ≤ clientWidth + 2`，不出现可见的横向滚动跳变。

### 可选（P3，不阻断）

**AC-12（P3）任务详情卡片文案去重**
`web/src/pages/TaskDetailPage.vue:312` 与 `:416` 两个卡片标题不再与字段名重字、彼此不重复（推荐「进度概览」/「流转记录」）；只改文案，不动逻辑。未完成不影响验收，但需在 QA-REPORT 记录「未做」而非静默跳过。

### AC 优先级总表

| AC | 优先级 | 判据摘要 | 证据 |
|---|---|---|---|
| AC-1 周档整屏 | P0 | 无横滚 + dayW 10–16 + visible>0 且 offRight=0 | 几何探针周档段 |
| AC-2 月档整屏 | P0 | 无横滚 + dayW 4–8 + visible>0 且 offRight=0（含 month） | 几何探针月档段 |
| AC-3 日档不回退 | P0 | 无横滚 + dayW≈68 + 可见 | 几何探针日档段 |
| AC-4 条宽与落点 | P0 | max(10, 天数×dayW)、不越界、可点 | 探针 sample + DOM 量取 |
| AC-5 刻度可读 | P1 | 标签矩形不相交、月份标签 ≥6 | 探针抽 10 个刻度 |
| AC-6 证据可信 | P1 | 三张截图、档位一致、落盘前断言可见 | 截图 + 脚本退出码 |
| AC-7 表列对齐 | P2 | 6 列偏差 ≤6px、td=table-cell、探针 exit 0 | `qa-settings-layout.cjs` |
| AC-8 测试防回归 | P2 | DOM 结构断言 + web ≥300 | vitest |
| AC-9 基线 | P1 | 305/300/typecheck/build、diff 仅前端 | 命令输出 |
| AC-10 缩放语义 | P1 | 单请求、步长=窗口天数、截断/pin 不变 | 网络计数 + 探针 |
| AC-11 首帧 | P1 | 未测量时不超宽 | 200ms 采样 + 代码审查 |
| AC-12 文案 | P3 | 两标题去重 | 截图（可选） |

## 3. 范围与非目标

**范围（只改前端）**

- `web/src/composables/useGanttWindow.ts`：`dayWidth()` 改为 `max(1, min(96, floor(timelineW/days)))`，移除 40px 下限的「优先」地位；未测量时用档位保守回退；`barGeometry` 的 `MIN_BAR_WIDTH=10`、pinned 贴边、`THIN_LABEL_WIDTH` 抽稀保持。
- `web/src/pages/GanttPage.vue` / `web/src/components/gantt/*`：仅在必要时调整宽度传递（`LEFT_COLUMN=280`、totalWidth = 天数×dayW、滚动容器），不改分组/折叠/摘要/翻屏语义。
- `web/src/components/settings/ThemeConfigTable.vue`：把 `.cell-name` / `.cell-color` 的 flex 移入 `td` 内层 `div`，`td` 保持 `table-cell`；`colgroup` 六列宽（200/132/96/96/80/152）与交互不变。
- `web/src/pages/TaskDetailPage.vue`：仅 P3 文案（可选）。
- 测试与探针：改写 `web/tests/gantt-window.spec.ts:86-98` 的 clamp 用例；扩展 `web/tests/theme-config.spec.ts`；复制并扩展 r7 的 `acceptance-gantt-bars.cjs`、`qa-settings-layout.cjs`。
- 三张截图 + 探针输出。

**非目标（本轮不做）**

- 不改接口、DAO、SQL、状态机、验收权限、计数口径、风险/逾期判定；不动 `TaskRiskSpec` / `ThemeFilterSql` / `TaskRowMapper` / `ThemeTag` / `useGanttView` 的既有职责。
- 不改甘特窗口语义（仍以今天居中、日 14 / 周 84 / 月 182 天、翻屏步长 = 窗口天数）；不新增拖拽改期、缩放滑杆、批量改主题。
- 不重设计甘特视觉（配色、行高、分组层级、摘要卡）与设置页信息架构；不合并「名称/颜色」列、不改主题主数据规则。
- 不新增依赖（不引入 d3/vis/chart 库，不引入 Playwright 到 web 依赖）。
- 不调整 300 条上限与截断提示，不改 1.5s 渲染预算。

## 4. 反面目标（anti-goals）

即使测试全绿、代码也能跑，出现以下任一情形即视为交付失败：

1. **「周档」名存实亡**：为凑整屏把窗口从 84 天悄悄缩成几十天（或改翻屏步长），用户切到周档看到的跨度、翻一屏前进的天数与「12 周/屏」对不上——需要横向滚动或翻 3 次才看完一个月即失败。
2. **刻度糊成一条灰带**：为塞下 84 天把日号全画出来，相邻标签文字交叠，首屏分不清今天与任务起止（探针判定相邻刻度矩形相交即失败，不靠肉眼争论）。
3. **短任务消失**：压缩后 1 天任务变成 1–2px 细线或 width=0，用户数不清一个月内有多少任务（条宽 <10px 或不可点击即失败）。
4. **表格「修」成另一种错位**：把 `td` 全改成 `div`/卡片、或干脆隐藏颜色列来绕过对齐问题，表头与数据仍对不上（探针 exit 1 或列数 ≠6 即失败）。
5. **证据再次自证清白**：截图拍到空画布/错档位却标记通过，文件名写「日档」画面是「周档」，或未断言可见条数就落盘——与 r7 同一失败重演即失败。

## 5. 交互流程摘要

1. ADMIN/EXEC 打开 `/gantt`（默认周档）：84 天铺满时间轴（dayW = floor(timelineW/84)），今天居中，左侧主题主行 + 项目子行的任务条从首屏左半部开始可见；短任务以 ≥10px 短条呈现。
2. 切「日」→ 14 天、日宽回到约 68px，与 r7 一致；切「月」→ 182 天、日宽约 5–7px，6 个月标签可读，整屏不横滚。
3. 「上一屏 / 下一屏」按 84 / 182 天平移，单请求；逾期条仍钉左、不被窗口裁剪；超过 300 条仍出截断提示。
4. 点击任务条 → 任务详情（不变）；摘要五数点击即筛、折叠状态会话内保持（不变）。
5. ADMIN 打开 `/settings`：主题配置表 6 列表头与数据逐列对齐，名称列内色点 + 名称 +「（已停用）」不越界，颜色列独立显示色名；编辑 / 停用 / 删除 / 排序交互不变。

## 6. 依赖与风险

| 项 | 说明 | 应对 |
|---|---|---|
| 基线交付现场 | commit `1709f2a`（r7，14 条 AC，server 305 / web 300） | 从该提交起改，不回退任何既有 AC |
| 探针脚本已不在项目内 | r7 结束时 `logs/teamflow/` 已清空；归档副本在 `$DSH_HOME/teamflow/ws-project-pmo-ae1c55a9/logs/tf-mwd8png8-wnibn2/scripts/` | 复制 `acceptance-gantt-bars.cjs`、`qa-settings-layout.cjs` 到本轮 `logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/`，并在 verdict 中补 `month.offRight === 0` |
| 旧测试保护错误法则 | `web/tests/gantt-window.spec.ts:86-98` 断言 40–96 clamp；`gantt-groups.spec.ts:83/175/273` 以 `dayW` 为入参 | 改写该组 3 个用例为新公式（保留用例数，不删断言）；分组用例只验几何、不改 |
| 40 下限移除的连带 | `barGeometry` 依赖 totalWidth、pinnedStart、MIN_BAR_WIDTH | 用 `gantt-window.spec.ts` 条几何组回归；探针核 `x+width ≤ 总宽+1` |
| jsdom 测不到布局 | 组件测试无法断言真实列宽/像素 | 对齐靠 Playwright 探针（exit 0）+ DOM 结构测试双保险 |
| 视觉观感争议 | 月档条短而密，业务方可能觉得"看不清" | 窗口天数固定不可调；用三张截图交付评审，必要时只调标签抽稀，不回横滚 |
| 首帧闪烁 | `canvasWidth=0` 时旧回退返回 40 | 按 AC-11：未测量用档位保守日宽或延迟渲染，探针 200ms 采样把关 |

## 7. 里程碑建议（约 1 人日）

| 里程碑 | 内容 | 出口 |
|---|---|---|
| M1 像素映射 | `dayWidth` 公式、未测量回退、条宽/落点，改 `gantt-window.spec.ts` | AC-1~AC-4、AC-11 单测先行 |
| M2 表列修复 | `ThemeConfigTable` flex 内移、结构测试 | AC-7、AC-8 |
| M3 证据与回归 | 复制并扩展两个探针、三张截图（落盘前断言）、全量回归 | AC-5、AC-6、AC-9、AC-10 |
| M4 可选文案 | TaskDetailPage 两处标题 | AC-12（不阻断） |

## 8. 工程约束

**需求原文照录（不得改写）**

> 约束：只改前端；不改状态机/验收权限/接口/计数口径；不新增依赖；现有 14 条 AC 与架构单点（TaskRiskSpec/ThemeFilterSql/TaskRowMapper/ThemeTag/useGanttWindow/useGanttView）不推倒重来。测试基线 r7 QA：server 305/305、web 300/300、typecheck/build 通过，返工后不得下降。

> 必须复测并附证据：`logs/teamflow/tf-mwd8png8-wnibn2/scripts/acceptance-gantt-bars.cjs` 同款几何探针，周档/月档均需 `visible>0 且 offRight=0`；重拍日/周/月三张截图（r7 的 `13-gantt-day.png` 名不副实，实为周档，勿复用），截图脚本落盘前断言「可见条数>0」。

> 复跑 `node logs/teamflow/tf-mwd8png8-wnibn2/scripts/qa-settings-layout.cjs` 至 exit 0，并补/改组件测试防回归。

> 工作区已在提交 `1709f2a` 安全存档（r7 交付现场，92 项改动）。

**分支与提交**

- 基线：分支 `main`、HEAD `1709f2a`；当前唯一未提交改动是未跟踪的任务夹 `docs/teamflow/20261112-r8-gantt-zoom-theme-table/`，**保留不动**，随收口提交。
- 开发直接在该基线上进行；收口时提交一次，`git add` 必须带 pathspec 排除 `logs/teamflow/`、`.demo-shots/` 与本轮探针输出，禁止把命令输出/截图提交进历史。
- 探针脚本按 r7 约定放在本轮 `logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/`（host 结束时归档并从项目删除），截图放 `.demo-shots/r8/`。

**.gitignore 审计（提交前动作）**

- 现有 `.gitignore` 已覆盖本栈：`server/target/`、`node_modules/`、`dist/`、`.pnpm-store/`、`server/.mvn/repo|home/`、`*.tsbuildinfo`、`logs/teamflow/`、`.demo-shots/`、`.env*`、`*.log`、IDE 目录。
- 本轮新增产物均落在已忽略路径内，无需新增条目；若开发过程中引入 `coverage/`、`playwright-report/`、`test-results/` 等输出，必须先把对应目录补进 `.gitignore` 再提交（当前 `web/package.json` 无 coverage/playwright 依赖，默认不产生）。
- 提交前用 `git status --porcelain` 自查：只应出现 `web/**`、`docs/teamflow/20261112-r8-gantt-zoom-theme-table/**`，不得出现 `logs/`、`.demo-shots/`、`node_modules/`、`server/target/`。

## 9. 假设与待澄清

- **（口味 / taste）任务详情两个卡片标题改成哪组文案**：AC-12 只要求「不与字段名重字、彼此不重复」，具体措辞属产品文案偏好。若用户另有命名习惯，改动只涉及两处标题字符串，不影响逻辑与测试。
- 其余决策均已被需求/证据锁定，无遗留事实性疑问：窗口天数（14/84/182）固定、40px 下限不再优先、条宽 `max(10, 天数×dayW)`、表格 6 列保持 `td` + 内层 flex、测试基线 305/300 不回退。
