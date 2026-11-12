<!-- meta: summary="r8 技术方案：甘特日宽改为整屏整除（dayW=max(1,min(96,floor(timelineW/天数)))、未测量按档位回退、时间轴宽度改由 .gantt__scroll 实测上报），主题配置表 flex 内移到 td 内层 div；零后端改动，附测试改写与探针/截图硬证据" -->

基线依赖：`docs/teamflow/20261112-r7-themed-gantt-task-loop/`（14 条 AC、架构单点、server 305 / web 300 基线）
取代：`docs/teamflow/20261112-r7-themed-gantt-task-loop/#AC-10`、`#AC-14`（缩放规则与截图证据，见 PRD 首页）

# r8 技术方案 · 甘特三档整屏 × 主题配置表列对齐

## 1. 结论与不变量

**结论**：两个缺陷都在前端像素层，不动接口、DAO、状态机与口径。修法各一处「法则级」改动 + 若干几何/结构断言：
A-01 把「40px 日宽下限优先」改为「窗口天数 × 日宽 ≤ 时间轴可用宽」的整除落位，并把时间轴宽度的测量点从页面外壳移到真正产生滚动的 `.gantt__scroll`；A-02 把 `td` 上的 `display:flex` 移到 `td` 内层 `div`，`td` 回归 `table-cell`。

**必须保持的不变量**（改动时逐条自检）：

| 不变量 | 位置 / 判据 |
|---|---|
| 窗口天数日 14 / 周 84 / 月 182，翻屏步长 = 窗口天数 | `useGanttWindow.ts:37` `ZOOM_DAYS`、`shiftWindow()`、`windowDays()` |
| 三档切换 / 翻屏各 1 个 `GET /api/gantt` 请求 | `web/src/api/gantt.ts:ganttApi.load()`，只在 `load()` 调用 |
| 条宽 `max(10, 有效天数×dayW)`、pinned 贴边、`x ≥ 0` | `useGanttWindow.ts:136-151` `barGeometry()` |
| 逾期条钉左不裁剪、300 条截断提示由接口下发 | 服务端职责，前端零改动 |
| 刻度抽稀 `THIN_LABEL_WIDTH=56`、月带、今天线 | `buildTicks()` / `monthBands()` / `dayOffset()` |
| 表格 6 列（名称/颜色/状态/排序/引用/操作）与 `colgroup` 200/132/96/96/80/152 | `ThemeConfigTable.vue:209-224` |
| 主题主数据规则（名称唯一/上限 100/被引用只能停用） | `server/**` 零改动 |

## 2. 根因与修法（精确到行）

### 2.1 A-01：40px 下限压过「适配一屏」

现状 `web/src/composables/useGanttWindow.ts`：

- `:38` `const MIN_DAY_WIDTH = 40`，`:39` `MAX_DAY_WIDTH = 96`
- `:129-133` `dayWidth()`：`Math.max(MIN_DAY_WIDTH, Math.min(MAX_DAY_WIDTH, Math.floor(timelineWidth / days)))`
- 1512×900 实测 `.gantt__scroll` clientWidth = 1238 ⇒ timelineW = 1238 − 280 = 958；周档 `floor(958/84)=11` 被抬到 40 ⇒ 时间轴 3360px、`scrollWidth=3640`；月档 7280px。任务条都在视口右侧。

**新法则**（PRD AC-1/2/3/11）：

```ts
export const LEFT_COLUMN = 280
export const MAX_DAY_WIDTH = 96
const MIN_DAY_WIDTH = 1
/** 容器未测量时的保守日宽：宁可窄一帧，绝不撑出横滚 */
const UNMEASURED_DAY_WIDTH: Record<GanttZoom, number> = { day: 40, week: 10, month: 5 }

export function dayWidth(win: GanttWindow, timelineWidth: number): number {
  const days = windowDays(win) || ZOOM_DAYS[win.zoom]
  if (!Number.isFinite(timelineWidth) || timelineWidth <= 0) return UNMEASURED_DAY_WIDTH[win.zoom]
  return Math.max(MIN_DAY_WIDTH, Math.min(MAX_DAY_WIDTH, Math.floor(timelineWidth / days)))
}
```

实测值（timelineW = 958）：周 `floor(958/84)=11`（AC-1 要求 10–16 ✅，时间轴 924，余 34px）；月 `floor(958/182)=5`（4–8 ✅，910）；日 `floor(958/14)=68`（≈68 ✅，952，与 r7 一致）。`MAX_DAY_WIDTH=96` 仍在，只在超宽屏生效（timelineW > 84×96 = 8064 才可能触发横滚，非验收视口）。

**为什么把测量点移到 `.gantt__scroll`**：AC-1/2/3 断言的是 `<div class="gantt__scroll">` 的 `scrollWidth ≤ clientWidth + 2`。`.gantt__scroll` 是 `overflow:auto`（`GanttChart.vue:181-184`），在经典滚动条环境下其 `clientWidth` 比页面外壳 `.gantt-page__canvas` 小 ~15px；若仍按外壳宽算，日档 `14×68 = 952` 加上 280 列即 1232，可能超出滚动视口 9px（外壳宽 1238、滚动视口 1223），AC-3 的「不横滚」会边缘失败。改从滚动视口测量后恒有 `LEFT_COLUMN + 天数×floor((C−280)/天数) ≤ C`（C = `.gantt__scroll`.clientWidth），数学上不可能横滚，也不需要靠余量赌滚动条宽度。

### 2.2 首帧（AC-11）

`canvasWidth` 首帧为 0：旧实现返回 40 ⇒ 84×40 先渲染再回缩，出现可见跳变。新实现返回档位保守日宽（周 10 ⇒ 840px、月 5 ⇒ 910px、日 40 ⇒ 560px），都窄于 1512 视口的 958px，首帧不超宽，测量完成后（同一帧内 `ResizeObserver`/`nextTick`）只做「变宽」调整。不采用「延迟渲染骨架」，避免与 `loading` 骨架、空态分支叠加出新的首屏闪烁。

### 2.3 A-02：`td` 被 flex 化后脱离列宽

`web/src/components/settings/ThemeConfigTable.vue`：

- `:234` `<td class="cell-name">`、`:239` `<td class="cell-color">`
- `:428-433` `.cell-name{display:flex;align-items:center;gap:6px;min-width:0}`
- `:452-457` `.cell-color{display:flex;align-items:center;gap:6px;color:...}`

`td` 变 flex 后不再是 `table-cell`，其宽度不再参与表格列宽计算，于是名称与颜色挤在同一列、后续 4 列整体左移一格（数值落到相邻表头下）。

**修法**：`td` 保持 `table-cell`，flex 下沉到内层 `div`；DOM 只加一层，交互与文案零改动：

```html
<td class="cell-name">
  <div class="cell-name__inner">
    <span class="cell-name__dot" :style="{ backgroundColor: item.color }" aria-hidden="true" />
    <span class="cell-name__text" :title="item.name">{{ item.name }}</span>
    <span v-if="!item.enabled" class="cell-name__off">（已停用）</span>
  </div>
</td>
<td class="cell-color">
  <div class="cell-color__inner">
    <span class="cell-color__dot" :style="{ backgroundColor: item.color }" aria-hidden="true" />
    <span>{{ colorName(item.color) || '自定义' }}</span>
  </div>
</td>
```

```css
/* 显式声明为契约：防后人再把 flex 写回 td */
.theme-table td.cell-name,
.theme-table td.cell-color { display: table-cell; }

.cell-name__inner,
.cell-color__inner { display: flex; align-items: center; gap: 6px; min-width: 0; }
.cell-name__text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* .cell-name__dot / __off / .cell-color__dot 的 flex:none 与尺寸原样保留 */
```

`.cell-color` 上的 `color: var(--c-ink-700)` 移到 `td.cell-color`（颜色变量对 `td` 生效即可，内层继承）。`colgroup` 五/六列宽与 `thead` 文案不动。

## 3. 模块与文件边界

| 文件 | 角色 | 本轮改动 |
|---|---|---|
| `web/src/composables/useGanttWindow.ts` | 前端唯一日期↔像素算术（纯函数，零 Vue 依赖） | 改 `dayWidth()`；新增导出 `LEFT_COLUMN`、`MAX_DAY_WIDTH`、`UNMEASURED_DAY_WIDTH`（供测试）；`barGeometry/buildTicks/monthBands/dayOffset/shiftWindow/defaultWindow` 不改 |
| `web/src/pages/GanttPage.vue` | 页面编排：窗口状态、请求、筛选、宽高传递 | 删除 `canvas` 测量链（`measure()/observe()/window resize`）；改收 `@resize` 得 `ganttWidth`；`timelineW = max(0, ganttWidth - LEFT_COLUMN)` |
| `web/src/components/gantt/GanttChart.vue` | 表格骨架 + 滚动容器（宽度真凶所在） | 新增 `resize` 事件与滚动视口测量；两处字面量 `280` 改用 `LEFT_COLUMN`；其余模板/样式不动 |
| `web/src/components/settings/ThemeConfigTable.vue` | 主题配置表 | flex 内移、`td` 保持 `table-cell`、内层容器样式 |
| `web/src/pages/TaskDetailPage.vue` | 任务详情（P3 可选） | 仅两处卡片标题文案 |
| `web/tests/gantt-window.spec.ts` | 像素法则单测 | 改写 `:86-98` clamp 三例 + 新增整除/不横滚/条宽不变量 |
| `web/tests/theme-config.spec.ts` | 表格组件测试 | 新增 6×`td`、表头顺序、内层 flex `div` 结构断言 |
| `logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/*.cjs` | 真浏览器几何/布局/截图探针（不入依赖） | 由 r7 归档副本复制并扩展 |

未列文件一律不改：`GanttRow.vue`/`GanttBar.vue`/`GanttSummary.vue`/`useGanttView.ts`/`ThemeTag.vue`/`ThemeFilterSql.java`/`TaskRowMapper` 全部原样。

## 4. 接口与契约设计

### 4.1 对外 HTTP 接口：零改动

本轮不新增/不改动任何 HTTP 路由、参数或返回体。被复用的既有契约（仅确认语义未变）：

| 接口 | 参数 | 返回 | 本轮关系 |
|---|---|---|---|
| `GET /api/gantt` | `from,to`（`yyyy-MM-dd` 闭区间）、`includeDone,onlyRisk`、可选 `ownerId`、可选 `theme`（`'<id>'|'none'|''`） | `GanttView`（`groups[].projects[].tasks[]`、`windowTotal`、`truncated`） | 参数不含 `zoom`；档位只影响 `from/to`，故缩放改动不会新增请求（AC-10） |
| `GET /api/meta/options` | — | `owners/…` | 不变 |
| `/api/themes*` | 同 r7 | 同 r7 | 不变（A-02 只改渲染） |

### 4.2 组件契约变化（唯一一处「接口」变更）

`GanttChart.vue` 新增 emit（其余 props/emits 不变）：

```ts
const emit = defineEmits<{
  (event: 'toggle', key: string): void
  (event: 'open', id: number): void
  (event: 'retry'): void
  (event: 'resize', width: number): void   // 新增：.gantt__scroll 的 clientWidth（px）
}>()
```

- `width` 语义固定为**滚动视口宽度（已扣除滚动条）**，不是页面外壳宽、不是时间轴宽。
- 组件内部：`rootEl`/`scrollEl` 模板 ref + `ResizeObserver`（`typeof ResizeObserver === 'undefined'` 时跳过，jsdom 安全）+ `rows` 变化后 `nextTick` 复测（`.gantt__scroll` 只在有行时渲染，需在出现后挂观察并上报）；`onBeforeUnmount` 断开。
- 页面侧唯一消费者：

```ts
const ganttWidth = ref(0)
const timelineW = computed(() => Math.max(0, ganttWidth.value - LEFT_COLUMN))
const dayW = computed(() => dayWidth(win.value, timelineW.value))
```

`LEFT_COLUMN` 由页面本地常量改为从 `useGanttWindow` 导入；`GanttChart` 内 `:style="{ width: `${LEFT_COLUMN + totalWidth}px` }"` 与表头 `grid-template-columns: ${LEFT_COLUMN}px 1fr` 同源，消除 280 的三处漂移。

### 4.3 纯函数契约（dev 必须逐字调用）

```ts
export const LEFT_COLUMN: number                       // 280
export const MAX_DAY_WIDTH: number                     // 96
export function dayWidth(win: GanttWindow, timelineWidth: number): number
export function barGeometry(task: Pick<GanttTask,'startDate'|'dueDate'|'pinnedStart'>, win: GanttWindow, dayW: number): { x: number; width: number }
export function windowDays(win: GanttWindow): number
export function zoomDays(zoom: GanttZoom): number
export function buildTicks(win: GanttWindow, dayW: number): GanttTick[]
export function monthBands(win: GanttWindow, dayW: number): GanttMonthBand[]
export function dayOffset(day: string, win: GanttWindow, dayW: number): number
```

`dayWidth` 的入参是**时间轴可用宽**（= 滚动视口宽 − `LEFT_COLUMN`），不是外壳宽：传错会让时间轴超出滚动视口。

## 5. 状态管理与数据流

无新增 store、无新增全局状态。数据流保持单向：

```
view.zoom / win(from,to) ──► ganttApi.load() ──► data(groups)
                                        │
.gantt__scroll.clientWidth ──@resize──► ganttWidth ──► timelineW ──► dayW
                                        │
                     data + win + dayW ──► GanttChart（ticks/bands/totalWidth）
                     rows ──► GanttRow.barGeometry(task, win, dayW) ──► 像素
```

- `dayW` 仍是 `GanttPage` 的 `computed`，全页唯一来源；`GanttChart`/`GanttRow`/`GanttBar` 只消费，不自行测量、不自行算日宽。
- 测量只更新像素，不触发 `load()`；`ResizeObserver` 回调不含异步请求，故 AC-10 的「单请求」不受影响。
- 折叠状态、筛选、`onlyRisk/showDone`、`theme/ownerId` 全部沿用 `useGanttView`，本轮零改动。

## 6. 关键实现点与边界

1. **整屏落位**：`totalWidth = windowDays × dayW`（`GanttChart.vue:47` 不变），`dayW` 已保证 `windowDays×dayW ≤ timelineW`，故 `grid 宽 = 280 + totalWidth ≤ scroll.clientWidth`；`.gantt__grid{min-width:100%}` 只会在内容更窄时拉伸，不产生滚动。
2. **条宽与落点（AC-4）**：`barGeometry` 现状已是 `max(10, spanDays×dayW)`，`x = clamp(0, totalWidth−width, …)` 保证 `x+width ≤ totalWidth`；`pinnedStart` 走 `min(width,totalWidth)`。月档 dayW=5 时 1 天任务 = 10px，可点击（`.bar` 高度不变、点击绑定不变）。**不改** `barGeometry`，只补断言（`x+width ≤ totalWidth+1`、`width ≥ 10`）。
3. **刻度（AC-5）**：`buildTicks` 语义不变。周档 weekWidth = 7×11 = 77 ≥ 56 → 不抽稀、12 个 `MM/DD`，每格 77px 文字约 40px，不相交；月档每格 28–31×5 ≈ 140–155px，6 个月标签（2026-10-01 ~ 2027-03-31 恰好 6 个整月），满足「≥6」。若 QА 实测某视口 7×dayW < 56，抽稀逻辑自动生效（隔一标一），不需要新代码。
4. **今天线**：`dayOffset(today, win, dayW) ≥ 0` 时按 `left: todayX px` 定位，与 dayW 无关的常数偏移不存在，缩放后仍居中偏左，无需改。
5. **首帧**：`timelineW ≤ 0` ⇒ 档位回退 40/10/5；`ResizeObserver` 不可用（jsdom）⇒ 永远回退，故组件测试不应断言真实日宽，只能断言结构/文案（见 §7）。
6. **滚动条**：测量点改到 `.gantt__scroll` 后，纵向滚动条出现/消失会触发一次 `resize`，`dayW` 可能差 1px（如周档 11→11 通常不变），属预期；不引入防抖，避免滞后一帧的横滚。
7. **空态/错误态**：`v-else-if="!rows.length"` 分支没有 `.gantt__scroll`，此时不上报宽度（`0`），回退值保证骨架不超宽；行恢复后 `watch(rows)` 复测。
8. **P3 文案**：`TaskDetailPage.vue:312` `<h2 class="panel__title">进度</h2>` → `进度概览`；`:415` `<h2 class="panel__title">进度与流转</h2>` → `流转记录`。只改字符串，`panel__count`、`ProgressComposer`、`ProgressTimeline` 不动。

## 7. 测试策略

### 7.1 单测改写（`web/tests/gantt-window.spec.ts:86-98`，AC-1/2/3/4/11）

旧 `describe('useGanttWindow · dayW clamp 40–96')` 三个用例保护了缺陷，**改写为新法则（用例数不减，断言不删）**：

| 用例 | 新断言 |
|---|---|
| 超宽容器仍以 96 封顶 | `dayWidth(week, 84*500) === 96` |
| 未测量按档位保守回退（不再是 40） | `dayWidth(week, 0) === 10`、`dayWidth(month, 0) === 5`、`dayWidth(day, 0) === 40` |
| 实测宽按天数整除 | `dayWidth(day, 958) === 68`、`dayWidth(week, 958) === 11`、`dayWidth(month, 958) === 5` |
| 不横滚不变量（新增） | 对 `W ∈ [320,700,958,1238,1600]` × 三档：`windowDays(win) * dayWidth(win, W) ≤ W` |
| 40 下限已移除（新增） | `dayWidth(day, 140) === 10`（旧值 40） |

条几何组补：月档 `dayW=5` 时 1 天条 `width===10`；任意条 `x ≥ 0 && x+width ≤ totalWidth+1`；pinned 条不变。

### 7.2 组件结构测试（`web/tests/theme-config.spec.ts`，AC-8）

新增一例（不动既有 8 例）：

```
mountTable([row(), row({ id: 2, enabled: false })])
→ thead th 文本序列 === ['名称','颜色','状态','排序','引用','操作']
→ tbody 首行 td 数量 === 6
→ td.cell-name 的 element.tagName === 'TD'，其首个子元素 tagName === 'DIV'，class 含 'cell-name__inner'
→ td.cell-color 同上（'cell-color__inner'）
→ td.cell-name 不含 class 之外的 display 断言（jsdom 无布局，computed display 由探针负责）
```

`web` 用例总数 ≥300（现 300，改写/新增只会增不减）；不得靠 `skip`/删断言换绿。

### 7.3 真浏览器探针（AC-1/2/3/4/5/7/11）

三个脚本放 `logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/`（跑完由 host 归档，不入 git，不入 `web/package.json`）：

1. `gantt-bars-geometry.cjs` — 由 r7 归档 `acceptance-gantt-bars.cjs` 复制并扩展。视口 1512×900，登录（`SMOKE_PASSWORD`，演示库默认 `Admin@12345`）→ `/gantt` → 依次 week/day/month，每档断言：
   - `.gantt__scroll` 的 `scrollWidth <= clientWidth + 2`；
   - 实测 `dayW === floor((scroll.clientWidth − 280)/windowDays)`，且周 10–16、月 4–8、日 68±2；
   - `visible > 0` 且 **`offRight === 0`（week / day / month 三档都要，r7 verdict 只断言了 week，本轮必补 month）**；
   - 抽样每档 ≥20 条条几何：`width ≥ 10`、`x ≥ 0`、`x + width ≤ headAxisWidth + 1`；
   - 前 10 个可见 `.gantt__tick` 的 `textContent` 非空者两两矩形不相交；月档月份标签数 ≥ 6；
   - 任一不满足 `process.exit(1)`，JSON 明细 `console.log`。
2. `qa-settings-layout.cjs` — 由 r7 归档副本复制，补三条：`getComputedStyle(td.cell-name).display === 'table-cell'`（`td.cell-color` 同）、内层 `div` 存在且 `display === 'flex'`、名称格与颜色格矩形不相交；6 列 `th.left` 与 `td.left` 偏差 ≤6 且列数相等。全过 exit 0。
3. `gantt-shots.cjs` — 截图证据（AC-6）：每档先断言 `visible > 0`、且档位控件 `aria-pressed`/`.is-active` 与文件名一致、`.gantt-page__window` 文本含对应档位单位（日/周/月），**断言通过才 `page.screenshot` 落盘**，否则 `exit 1` 且不写文件。产物 `.demo-shots/r8/gantt-day-14d.png`、`gantt-week-84d.png`、`gantt-month-182d.png`（禁止复用 r7 的 `13-gantt-day.png`）。

探针一律用全局 playwright：`NODE_PATH=$(npm root -g) node …`（不写进 `dependencies`/`devDependencies`）。

### 7.4 回归命令（AC-9/AC-10）

```bash
cd web && pnpm exec vitest run tests/gantt-window.spec.ts tests/gantt-groups.spec.ts tests/gantt-page.spec.ts tests/theme-config.spec.ts
cd web && pnpm test                 # ≥300 全绿
cd web && pnpm typecheck && pnpm build
cd server && ./mvnw -q test         # 305/305（不改后端，用于证明未误伤）
git status --porcelain              # 只应出现 web/**、docs/teamflow/20261112-r8-gantt-zoom-theme-table/**
git diff --stat                     # 不含 server/**
```

`jsdom` 测不到真实列宽/像素：列对齐只有 Playwright 探针（exit 0）+ DOM 结构测试双保险，不试图用单测替代。

## 8. 数据模型与存储

**零改动**，明确记录以免下游误改：无 Flyway 新版本、无 DDL、无索引/约束变更；`themes`、`tasks.theme_id`、`tasks.planned_start_date`（r7 已建，列可空、零回填）原样；不写任何迁移脚本、不改 `TaskRowMapper`/`TaskQueryDao`。本轮唯一「持久化」产物是 `.demo-shots/r8/*.png`（gitignored）与探针 JSON（`logs/teamflow/<runId>/`，host 归档）。

## 9. 任务拆分（文件边界并行）

无 pipeline 预派任务，按 PRD M1–M4 + 收口给出 5 个任务。依赖：T1 → T3（探针要跑真几何）；T2、T4 与 T1 文件不相交，可并行；T5 收口最后。

### T1 甘特像素映射与首帧（P0，AC-1/2/3/4/11）
- 文件：`web/src/composables/useGanttWindow.ts`、`web/src/pages/GanttPage.vue`、`web/src/components/gantt/GanttChart.vue`、`web/tests/gantt-window.spec.ts`
- 只读：`web/src/components/gantt/GanttRow.vue`、`web/src/components/gantt/GanttBar.vue`、`web/tests/gantt-groups.spec.ts`、`web/tests/gantt-page.spec.ts`
- 做法：按 §2.1 改 `dayWidth()` 并导出 `LEFT_COLUMN/MAX_DAY_WIDTH/UNMEASURED_DAY_WIDTH`；`GanttChart` 上报 `.gantt__scroll.clientWidth`（emit `resize`），删掉页面侧 `canvas` 测量链；两处 280 改用 `LEFT_COLUMN`；按 §7.1 改写单测。
- 出口：该 spec 全绿；`gantt-groups.spec.ts`、`gantt-page.spec.ts` 不改即绿；`pnpm typecheck` 通过。

### T2 主题配置表列对齐（P2，AC-7/8）
- 文件：`web/src/components/settings/ThemeConfigTable.vue`、`web/tests/theme-config.spec.ts`
- 只读：`web/src/components/ui/AppBadge.vue`、`web/src/components/settings/*`（同目录其它组件）
- 做法：按 §2.3 加内层 `div` + 样式迁移 + `display:table-cell` 契约行；新增结构测试（§7.2）；不动交互函数（`commitSort/toggle/openEdit/openRemove`）。
- 出口：`theme-config.spec.ts` 全绿；`qa-settings-layout.cjs` exit 0（与 T3 联调）。

### T3 探针与截图证据（P1，AC-5/6 + 复测 AC-1/2/3/7）
- 文件：`logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/gantt-bars-geometry.cjs`、`logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/qa-settings-layout.cjs`、`logs/teamflow/tf-mwdcbb3m-qxezpv/scripts/gantt-shots.cjs`
- 只读：r7 归档副本 `$DSH_HOME/teamflow/ws-project-pmo-ae1c55a9/logs/tf-mwd8png8-wnibn2/scripts/{acceptance-gantt-bars.cjs,qa-settings-layout.cjs}`（复制后**扩展，不重写**）
- 做法：§7.3。服务：后端 18081、前端 5174（真机演示库）。
- 出口：`gantt-bars-geometry.cjs` exit 0（含 month `offRight===0`）；`qa-settings-layout.cjs` exit 0；三张截图存在且文件名与档位一致。

### T4 P3 文案（可选，AC-12）
- 文件：`web/src/pages/TaskDetailPage.vue`
- 只读：`web/tests/task-detail-flow.spec.ts`、`web/tests/task-detail-role-source.spec.ts`
- 做法：§6.8 两处标题字符串；若既有测试断言旧文案，改测试断言而非保留旧文案。
- 出口：`pnpm test` 全绿；未做则须在 QA-REPORT 记「未做」。

### T5 收口自检与提交（交付自检，非 QA）
- 文件：无代码改动（纯命令 + git）
- 做法：§7.4 全部命令；`git status --porcelain` 自查；一次提交，pathspec 排除 `logs/teamflow/`、`.demo-shots/`、探针输出：

```bash
git add -- web docs/teamflow/20261112-r8-gantt-zoom-theme-table docs/teamflow/memory.md
git commit -m "fix: r8 甘特三档整屏落位与主题配置表列对齐（A-01/A-02 返工）"
```

- 分支：`main`，基线 `1709f2a`；未跟踪的任务夹 `docs/teamflow/20261112-r8-gantt-zoom-theme-table/` 保留并随提交；不回退任何 r7 AC。
- `.gitignore` 审计：现有条目已覆盖 `server/target/`、`node_modules/`、`dist/`、`.tsbuildinfo`、`logs/teamflow/`、`.demo-shots/`、`.env*`；本轮不新增产物目录，故零改动（若引入 `coverage/`、`test-results/` 必须先补条目）。
- 出口：命令全通过 + 提交完成；不写 `QA-REPORT.md`/`ACCEPTANCE.md`（属于 QA 与验收阶段）。

## 10. 风险与回滚

| 风险 | 影响 | 应对 |
|---|---|---|
| 探针依赖全局 playwright 版本 | 脚本 `require('playwright')` 失败 | `NODE_PATH=$(npm root -g)`；失败即为环境问题，按「Env unavailable」上报，不改依赖 |
| 演示库口令与默认不同 | 探针登录失败 | `SMOKE_PASSWORD` 覆盖；先手工确认 18081 上 admin 口令 |
| 月档条密导致观感争议 | 业务方觉得看不清 | 窗口天数固定不可调；只允许调标签抽稀，不允许回横滚 |
| 改动 `dayWidth` 影响别处调用 | 位置错乱 | 全仓仅 `GanttPage.vue:57` 一处调用（已核）；`dayW` 入参语义在 §4.3 固定 |
| 滚动视口测量在 jsdom 不可用 | 组件测试拿不到真实日宽 | 组件测试只断言结构/文案；像素一律由 Playwright 探针负责 |
| 回滚 | — | 全部改动集中在 4 个前端文件 + 2 个测试文件，`git revert` 单次提交即回滚；后端零改动 |

## 11. 与 memory 的一致性

`docs/teamflow/memory.md` 已按 r8 写好「甘特缩放口径（r8 起，取代 r7「40px 日宽下限优先」）」一行：`dayWidth = max(1, min(96, floor(timelineW/天数)))`、未测量按档位回退、`MIN_BAR_WIDTH=10`、pinned/`THIN_LABEL_WIDTH` 不变、探针 `visible>0 且 offRight=0`（含 month）、截图落盘前断言。本轮实现不得偏离该行；若实现需调整口径，先改 memory 与 PRD，不得只改代码。
