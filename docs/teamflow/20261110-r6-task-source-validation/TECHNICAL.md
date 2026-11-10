# 技术方案 · 建任务「关联决议项」必填与三形态校验对齐（r6）

- 需求：`docs/teamflow/20261110-r6-task-source-validation/PRD.md`（9 条 AC）
- 基线依赖：`docs/teamflow/20261110-r4-project-source-binding/`（三形态来源绑定口径）、`docs/teamflow/20261110-r1-*/`（AC-6 会议决议必挂会议+决议项）
- 取代：无（本需求只补前端缺口，不改历史结论）
- 分支：main 直接改（不建分支、不 rebase、不 stash）；收尾用明确 pathspec 提交

## 1. 结论与范围

服务端早已强制「会议形态必须同时挂会议与决议项」，前端却只把 `sourceDecisionItemId` 映射进 `errors` 而无处渲染、无元素可聚焦——用户只看到底部「还有 1 项需要修改」。本需求**只改一个前端组件 + 新增一个测试文件**，零后端、零 DDL、零路由、零公共组件 API 变更。

| 类别 | 结论 |
| --- | --- |
| 数据模型 | 无变更（无 Flyway、无实体、无索引） |
| 接口 | 无变更；沿用 `POST /api/tasks`、`PUT /api/tasks/{id}`、`GET /api/meetings/{id}` |
| 前端 | `web/src/components/task/TaskFormDrawer.vue` 单文件改造 |
| 测试 | 新增 `web/tests/task-form-meeting-decision.spec.ts`（11 条），存量 174 条不回归 |
| 公共组件 | `AppSelect.vue` / `AppSearchSelect.vue` 的 `required/invalid/error/hint/empty-text` 已够用，**零改动** |

## 2. 现状与根因（已核对源码）

### 2.1 三处前端缺口（同一根因）

1. `TaskFormDrawer.vue:301-338 validate()` 的 `MEETING` 分支只判 `form.meetingId === ''`，**完全不校验 `form.decisionItemId`**，`order` 里永远不会出现 `decisionItemId`。
2. `TaskFormDrawer.vue:513-521` 决议项 `AppSelect` 没有 `required`、没有 `:invalid/:error`，也没有 `:ref` 包裹（对比 `:500` 会议字段有 `<div :ref>`）——结构上没有任何位置能显示这个字段的错误。
3. `TaskFormDrawer.vue:362-373 applyServerErrors()` 把 `sourceDecisionItemId` 映射成 `decisionItemId` 写进 `errors`，但上面第 2 条使这段错误既不可见也不可聚焦；`focusFirst()` 只能拿到 `order[0]` 对应元素，而 `order` 里根本没有它。

结果：用户填完标题/负责人/截止日期/会议后点保存——前端校验放行 → POST → 服务端 422 → 只落一条底部摘要。**会议形态下这是一个 100% 复现的死角**。

### 2.2 编辑态还有一个隐性缺陷（AC-5 必须一并修）

`watch(() => form.meetingId, ...)`（`:194-212`）在 `meetingId` 变化时无条件执行 `form.decisionItemId = ''`。编辑态 `resetForm()`（`:214-240`）先赋 `form.meetingId = task.source.meetingId`、再赋 `form.decisionItemId = task.source.decisionItemId`，但 watcher 是 pre-flush 异步，会在 `resetForm()` 同步代码跑完后触发，把刚回显的决议项清空；同时 `resetForm()` 里的 `loadItems()` 与 watcher 会各发一次 `decisionItems` 请求。

修法：watcher 首行 `if (isEdit.value) return`（编辑态会议不可改，items 由 `resetForm()` 自己加载）。

### 2.3 已对齐、只需用测试锁定的部分

- `PROJECT` 必填项目、`NOTE` 必填 1–200 字说明，与服务端 `TaskService.validateSource()`（`server/src/main/java/com/pmo/supervise/task/TaskService.java:205-258`）逐条一致。
- `TITLE_MAX=100` / `NOTE_MAX=200` 与 `CreateTaskRequest` 的 `@Size` 相等，**不动**。
- 提交载荷已按 `activeForm` 只带本形态字段（`save()` `:375-435`），三态互斥成立。

## 3. 数据模型与存储

无服务端变更。客户端只有组件内表单状态，无持久化、无 store、无缓存跨页：

| 状态 | 类型 | 用途 |
| --- | --- | --- |
| `form.decisionItemId` | `number \| ''` | 已选决议项；提交时 `Number()`；空串表示未选 |
| `items` | `DecisionItemRow[]` | 当前会议的可选决议项（`converted=true` 以 disabled option 呈现） |
| `itemsLoading` | `boolean` | 加载中：禁用 select、placeholder「决议项加载中…」 |
| `itemsError` | `string` | 加载失败文案；非空时字段行内显示并渲染重试按钮 |
| `itemsLoaded` | `boolean` | **新增**。区分「还没加载完」与「加载完了但一条可用都没有」，供 `noSelectableItems` 判定 |
| `itemsRequestSeq` | `number`（普通变量） | **新增**。请求序号，丢弃过期响应（切换会议竞态） |

决议项来源仍是 `web/src/api/tasks.ts:135-138 decisionItems(meetingId)` → `GET /api/meetings/{id}` 取 `meeting.items`；类型 `DecisionItemRow`（`web/src/types/api.ts:131-143`）。`TaskSource` 无决议项描述字段，因此编辑态文本只能来自 `items` 或 `决议项 #id` 兜底。

## 4. 接口设计（无变更，只固化契约）

### 4.1 请求

| 场景 | 方法与路径 | 载荷 |
| --- | --- | --- |
| 新建 MEETING | `POST /api/tasks` | `{title, ownerId, dueDate, sourceType, sourceMeetingId, sourceDecisionItemId, priority}`；**不得**出现 `sourceProjectId`/`sourceNote` |
| 新建 PROJECT | 同上 | `{..., sourceType, sourceProjectId, priority}` |
| 新建 NOTE | 同上 | `{..., sourceType, sourceNote: note.trim(), priority}` |
| 编辑 | `PUT /api/tasks/{id}` | `{title, priority}`，**不含任何 `source*`** |
| 决议项 | `GET /api/meetings/{id}` | 返回 `items[]`，`converted` 标记已转任务 |

成功：`201`（新建）/ `200`（编辑）。服务端 422 体：`{code:'VALIDATION_FAILED', message, fieldErrors:[{field, message}]}`。

### 4.2 422 字段映射（前端唯一映射表，`SERVER_FIELD_MAP` 保持不变）

| 服务端 field | 表单 key | 渲染位置（本次补齐后） | 首个错误时的焦点 |
| --- | --- | --- | --- |
| `sourceMeetingId` | `meetingId` | 来源会议 select 行内 `.field__error` | 会议 select |
| `sourceDecisionItemId` | `decisionItemId` | **关联决议项 select 行内** | 决议项 select（禁用时退到重试按钮/容器） |
| `sourceProjectId` | `projectId` | 来源项目 combobox 行内 | 项目 combobox input |
| `sourceNote` | `note` | 来源说明 textarea 行内 | textarea |

## 5. 前端设计

### 5.1 文件边界与职责

| 文件 | 变更 | 职责 |
| --- | --- | --- |
| `web/src/components/task/TaskFormDrawer.vue` | 改 | 三形态必填校验、决议项必填/加载态/失败重试/无可选原因、422 逐字段落位与聚焦、编辑态来源只读 |
| `web/tests/task-form-meeting-decision.spec.ts` | 新增 | MEETING 为主 + 三形态对齐的 11 条回归 |

不改：`AppSelect.vue`、`AppSearchSelect.vue`、`Drawer.vue`、`@/api/tasks`、任何 page/store/type。

### 5.2 组件成员契约（下游必须按名调用）

```ts
// 文案常量（组件顶部，与 TITLE_MAX 同区）
const DECISION_REQUIRED = '请选择关联决议项'
const DECISION_LOADING = '决议项加载中，请稍候'
const DECISION_LOAD_FAILED = '决议项加载失败，请点击下方「重试加载决议项」'
const DECISION_NONE_AVAILABLE = '该会议暂无可关联的决议项（均已成任务），请改选会议或改用其他来源'

const itemsLoaded = ref(false)
let itemsRequestSeq = 0

async function loadItems(meetingId: number): Promise<void>   // 唯一加载入口（新建 watch 与编辑 resetForm 共用）
function retryItems(): void                                   // 重新加载当前 form.meetingId
function onDecisionItem(value: string): void                  // 选中决议项：赋值 + 自动带出 + clearError('decisionItemId')
function decisionReason(): string                             // 未选决议项的原因文案；已选/不适用时返回 ''
const noSelectableItems: ComputedRef<boolean>                 // itemsLoaded && !loading && !error && items.every(i => i.converted)
const decisionItemError: ComputedRef<string>                  // errors.decisionItemId || decisionReason()
const decisionPlaceholder: ComputedRef<string>
const currentMeetingLabel: ComputedRef<string>                // 编辑态只读行
const currentDecisionLabel: ComputedRef<string>               // 编辑态只读行，永不空白
function validate(): string[]                                 // 扩展 MEETING 分支
async function focusFirst(order: string[]): Promise<void>     // 增强：优先可交互元素
```

`decisionReason()` 是**唯一**原因文案产出点（`validate()` 与模板都调它），避免三态文案漂移：

```ts
function decisionReason(): string {
  if (isEdit.value || form.meetingId === '') return ''
  if (form.decisionItemId !== '') return ''
  if (itemsLoading.value) return DECISION_LOADING
  if (itemsError.value) return DECISION_LOAD_FAILED
  if (noSelectableItems.value) return DECISION_NONE_AVAILABLE
  return DECISION_REQUIRED
}
```

`loadItems()` 带过期响应保护（会议 A→B 快速切换时 A 的响应不得覆盖 B）：

```ts
async function loadItems(meetingId: number): Promise<void> {
  const requestId = ++itemsRequestSeq
  itemsLoading.value = true
  itemsError.value = ''
  itemsLoaded.value = false
  try {
    const rows = await taskApi.decisionItems(meetingId)
    if (requestId !== itemsRequestSeq) return
    items.value = rows
    itemsLoaded.value = true
  } catch {
    if (requestId !== itemsRequestSeq) return
    items.value = []
    itemsError.value = DECISION_LOAD_FAILED
  } finally {
    if (requestId === itemsRequestSeq) itemsLoading.value = false
  }
}
```

`retryItems()`：`if (form.meetingId === '') return; clearError('decisionItemId'); void loadItems(Number(form.meetingId))`。

### 5.3 校验分支（`validate()` 的 MEETING 段）

```ts
if (activeForm.value === 'MEETING') {
  if (form.meetingId === '') {
    errors.meetingId = '请选择来源会议'
    order.push('meetingId')
  } else {
    const reason = decisionReason()
    if (reason) {
      errors.decisionItemId = reason
      order.push('decisionItemId')
    }
  }
}
```

要点：
- `order` 只 push 一个会议字段，摘要计数「还有 1 项需要填写」成立（AC-1）。
- 原因由状态决定：加载中 / 加载失败 / 无可选 / 未选择，四条文案互斥（AC-6 ③②①）。
- `if (!isEdit.value)` 外层不动，编辑态来源零校验（AC-5）。
- 函数末尾 `Object.keys(errors).forEach(k => { if (!errors[k]) delete errors[k] })` 保持，`errors.decisionItemId` 会被保留。

### 5.4 决议项字段模板（替换 `:513-521`）

```vue
<template v-if="activeForm === 'MEETING'">
  <template v-if="isEdit">
    <div class="source-readonly">
      <span class="source-readonly__label">来源会议</span>
      <span class="source-readonly__value">{{ currentMeetingLabel }}</span>
    </div>
    <div class="source-readonly">
      <span class="source-readonly__label">关联决议项</span>
      <span class="source-readonly__value">{{ currentDecisionLabel }}</span>
    </div>
  </template>
  <template v-else>
    <div :ref="(el) => setFieldEl('meetingId', el)">
      <AppSelect ... 现有会议字段不变 ... />
    </div>
    <div :ref="(el) => setFieldEl('decisionItemId', el)" class="task-form__decision" tabindex="-1">
      <AppSelect
        :model-value="form.decisionItemId"
        :options="itemOptions"
        label="关联决议项"
        required
        :disabled="form.meetingId === '' || itemsLoading || noSelectableItems"
        :placeholder="decisionPlaceholder"
        hint="已转为任务的决议项不可重复选择"
        :invalid="Boolean(decisionItemError)"
        :error="decisionItemError"
        @update:model-value="onDecisionItem"
      />
      <button v-if="itemsError" type="button" class="task-form__retry" @click="retryItems">
        重试加载决议项
      </button>
    </div>
  </template>
</template>
```

- `required` 由 `AppSelect.vue:43` 渲染 `.field__req` 星号；`error` 优先于 `hint` 渲染 `.field__error`（role=alert），因此「无可用决议项」在**点保存前**就已可见（AC-6 ①），且不需要额外 `.task-form__warn` 重复文案。
- `tabindex="-1"` 让容器在 select 与按钮都不可聚焦时仍是焦点兜底，`scrollIntoView` 也落在这里。
- 新增少量 scoped 样式：`.task-form__decision { display: flex; flex-direction: column; gap: var(--sp-2); }`、`.task-form__retry { align-self: flex-start; ... 文字按钮，色 var(--c-primary) }`。

`decisionPlaceholder`：无会议「请先选择来源会议」/ 加载中「决议项加载中…」/ 失败「决议项加载失败」/ 无可选「暂无可关联的决议项」/ 默认「选择决议项」——**不出现「可不选」**（AC-7）。

### 5.5 编辑态只读（AC-5）

```ts
const currentMeetingLabel = computed(() => {
  const id = props.task?.source?.meetingId
  if (id == null) return ''
  return props.meetings.find((m) => m.id === id)?.title || props.task?.source?.meetingTitle || `会议 #${id}`
})
const currentDecisionLabel = computed(() => {
  const id = props.task?.source?.decisionItemId
  if (id == null) return ''
  const found = items.value.find((item) => item.id === id)
  if (found) return found.converted ? `${found.description}（已转任务）` : found.description
  return itemsLoading.value ? '加载中…' : `决议项 #${id}`
})
```

- 编辑态不再渲染 disabled 的 select：既有任务在 `items` 为空/请求失败时也不会出现「空白下拉」（原生 select 无对应 option 即空白），这是 AC-5 的硬要求。
- 与 r4 的「来源项目」只读行同构，`.source-readonly` 样式已存在；`.source-readonly__value` 的第一个仍是「来源类型」行，t07 既有断言不受影响。
- 编辑态只提交 `{title, priority}`，不含任何 `source*`。

### 5.6 焦点（AC-1 / AC-4）

```ts
const target =
  el.querySelector<HTMLElement>(
    'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled])',
  ) ?? el
target.focus()
```

保证：决议项 select 可用时落 select；不可用（无会议/加载中/无可选）时退到重试按钮，再退到 `tabindex="-1"` 容器——焦点一定落在该字段区域内，不会落到页面顶部。

### 5.7 其它清理

| 位置 | 改动 |
| --- | --- |
| `:194-212` watch | 首行 `if (isEdit.value) return`；清 `decisionItemId` 与 error；重置 `itemsLoaded/itemsError`；调 `loadItems()` |
| `:243-254` loadItems | 见 5.2；**去掉 catch 里的 warning toast**（行内已有错误与重试） |
| `:270-282` onDecisionItem | 增加 `clearError('decisionItemId')` |
| `:284-300` onSourceChange | 增加 `clearError('decisionItemId')`、`itemsLoaded.value = false`、`itemsError.value = ''` |
| `:214-240` resetForm | 增加 `itemsLoaded=false`、`itemsError=''`、`itemsRequestSeq++`（作废在途请求） |
| `:444` description | 「四个必填项齐全才会保存」→「必填项齐全才会保存」（MEETING 现为 5 个必填） |

## 6. 关键实现要点与边界

1. **切换会议竞态**：`itemsRequestSeq` 丢弃过期响应；否则 A→B 后 A 的列表会顶掉 B 的选项，选中即绑错决议项（数据正确性问题）。
2. **加载中点保存**：`decisionReason()` 返回 `DECISION_LOADING`，0 次 POST；加载结束后 `errors.decisionItemId` 若仍是 loading 文案则清掉（`loadItems` 成功时 `if (errors.decisionItemId === DECISION_LOADING) delete errors.decisionItemId`），避免陈旧提示。
3. **全部 converted**：`itemOptions` 保留 disabled option（用户能看到「为什么不能选」），`noSelectableItems` 使 select 禁用并显示原因；不得自动选中（反目标 5）。
4. **加载失败**：`itemsError` 置位 → 行内错误 + 重试按钮；重试成功后 `itemsLoaded=true`、错误消失、可正常保存（AC-6 ②）。
5. **编辑态 watcher**：`if (isEdit.value) return` 修掉 2.2 的清空缺陷与重复请求；`form.decisionItemId` 保留原值（只读展示走 `props.task.source`，不依赖表单值）。
6. **422 竞态**：`applyServerErrors` 在 `saving=false` 之前执行，焦点在 finally 后仍有效；`errors` 里非本形态的 key（理论上不会出现）只入摘要不渲染——保持现状，不加特判。
7. **重复提交**：`if (saving.value) return` 已有，不改。
8. **文案唯一来源**：「可不选」「可以先只选择会议」「只关联会议」在 `web/src` 中 grep 必须 0 命中（AC-7），原因文案只由 `decisionReason()` 产出。

## 7. 测试策略

新增 `web/tests/task-form-meeting-decision.spec.ts`（`// @vitest-environment jsdom`），复用 `web/tests/task-form-project.spec.ts` 的脚手架：`vi.hoisted` 造 `taskApiMock {decisionItems, create, update}`、`vi.mock('@/api/tasks')`、`mount(TaskFormDrawer, {attachTo: document.body, global:{plugins:[createPinia()]}})`、`fieldByLabel()` / `pickFromSelect()` / `fillBasics()` / `saveButton()`、`afterEach` 卸载并清空 body。断言只用四类证据：**请求是否发出 / 字段文案 / 载荷 / 焦点**（不断言内部 ref、不 mock 组件）。

| # | 覆盖 | 场景与断言 |
| --- | --- | --- |
| C1 | AC-1 | 填基础四项 + 选会议、不选决议项 → 保存：`create` 0 次、摘要「还有 1 项需要填写」、行内「请选择关联决议项」、`document.activeElement` 为该 select |
| C2 | AC-1/7 | 决议项字段有 `.field__req`；placeholder 不含「可不选」；hint 为「已转为任务的决议项不可重复选择」 |
| C3 | AC-2 | 选齐后保存：`create` 恰 1 次，载荷 `sourceMeetingId`/数字 `sourceDecisionItemId` 正确，且不含 `sourceProjectId`/`sourceNote` |
| C4 | AC-2 | 选决议项自动带出标题/负责人/截止日期；已手填标题不被覆盖 |
| C5 | AC-3 | 表驱动三形态：MEETING/PROJECT/NOTE 各自缺失时的行内文案与载荷字段互斥（含 NOTE trim） |
| C6 | AC-4 | 表驱动四个服务端字段：构造 `new ApiError({status:422, fieldErrors:[...]})`，断言摘要「还有 1 项需要修改」、行内 message、焦点落在对应字段（`sourceDecisionItemId` 这一行是本需求的核心回归） |
| C7 | AC-5 | 编辑 MEETING 任务（`decisionItems` 返回含 id=11）：来源会议/关联决议项为只读文本、无 select；改标题保存 → `update` 恰 1 次，载荷仅 `{title, priority}` |
| C8 | AC-5 | `decisionItems` 返回 `[]` 时只读行显示「决议项 #11」（永不空白） |
| C9 | AC-6 ① | 全部 `converted=true` → 保存前即显示「该会议暂无可关联的决议项…」、select 禁用、保存 0 次 POST |
| C10 | AC-6 ② | 首次 reject 后显示「决议项加载失败」；点「重试加载决议项」→ 第二次 resolve → 选项可用 |
| C11 | AC-6 ③ | `decisionItems` 挂起（never resolve）时保存：0 次 POST、行内「决议项加载中，请稍候」 |

验证命令（全绿为通过；server 未改，跑既有套件防误伤）：

```bash
cd web && pnpm exec vitest run tests/task-form-meeting-decision.spec.ts
cd web && pnpm test            # 基线 174 条 + 新增 11 条
cd web && pnpm typecheck       # vue-tsc --noEmit
cd server && ./mvnw -q test    # 既有 247 条保持绿
grep -rn "可不选\|可以先只选择会议\|只关联会议" web/src   # 期望 0 命中
```

## 8. 任务拆分（按文件边界；并行/串行已标注）

| 任务 | 文件（ownership） | 依赖 | 交付 |
| --- | --- | --- | --- |
| T01 组件改造 | `web/src/components/task/TaskFormDrawer.vue` | 无 | 必填/错误/焦点/编辑只读/加载三态/竞态保护/文案清理 |
| T02 回归用例 | `web/tests/task-form-meeting-decision.spec.ts` | T01（文案与成员契约由本文件 §5 固定） | 11 条用例 + 全量绿 |
| T03 收尾 | 无源码改动（`.demo-shots/r6/*.png` 为 gitignore 证据） | T01、T02 | 全量回归、18081+5174 真机闭环留证、明确 pathspec 提交 |

### 8.1 T01 spec

- 按 §5.2–§5.7 落地，成员名与文案常量**逐字**一致；不引第三方校验库，不重构表单框架。
- 只改本文件；不动 `AppSelect/AppSearchSelect`；不动 `save()` 的 payload 结构（已正确）。
- 自检：新建 MEETING 不选决议项保存 → 摘要 1 项 + 行内 + 焦点三件套齐；选齐后 201 一次；编辑态只读且 PUT 无 `source*`。

### 8.2 T02 spec

- 新建文件，不复用 `task-form-project.spec.ts` 的用例；helper 可复制（测试文件之间不 import，保持独立）。
- 每条用例自己设定 `taskApiMock.decisionItems`（默认 `mockResolvedValue([])`），并用 `mockReset()` 复位。
- 断言文案用字面量，不引组件内常量（避免断言与实现同源失效）。

### 8.3 T03 spec（含 PRD 工程约束，逐字承接）

- 在 **main** 上直接提交，不建分支、不 rebase、不 stash。
- 提交命令必须带明确 pathspec，排除 `.demo-shots/`、`logs/`、`web/dist/`、`server/target/`：
  ```bash
  git add web/src/components/task/TaskFormDrawer.vue web/tests/task-form-meeting-decision.spec.ts \
          docs/teamflow/20261110-r6-task-source-validation/
  git commit -m "fix: 建任务会议形态补齐「关联决议项」必填与行内定位（r6）" -- \
          web/src/components/task/TaskFormDrawer.vue web/tests/task-form-meeting-decision.spec.ts \
          docs/teamflow/20261110-r6-task-source-validation/
  ```
- 真机闭环（AC-9）：18081 后端 + 5174 前端，admin 登录，按 PRD 1.1 步骤；修复前证据（旧构建或 `git stash` 前的截图）与修复后截图/Network 存 `.demo-shots/r6/`；目标决议项已被消耗时改选会议，**禁止重置演示库**。
- 提交前复核 `git status --short` 与 `git diff --stat`，确认 `server/` 零改动、`AGENTS.md`/`memory.md` 零改动。

## 9. AC → 实现 → 验证 映射

| AC | 实现落点 | 验证 |
| --- | --- | --- |
| AC-1 | §5.3 校验分支 + §5.4 `required/:error` + §5.6 焦点 | C1、C2 |
| AC-2 | `save()` 现有 payload + `onDecisionItem` 自动带出 | C3、C4 |
| AC-3 | `validate()` 三形态 + `onSourceChange` 清字段 | C5 |
| AC-4 | `SERVER_FIELD_MAP` + `:error` 绑定 + `focusFirst` | C6 |
| AC-5 | §5.5 只读行 + watcher 守卫 + PUT 载荷 | C7、C8 |
| AC-6 | §5.2 `decisionReason/loadItems/retryItems/noSelectableItems` | C9、C10、C11 |
| AC-7 | §5.7 文案清理 + `grep` | C2 + grep 0 命中 |
| AC-8 | T02 用例 11 条 + 全量/类型检查 | 验证命令 4 条 |
| AC-9 | T03 真机闭环 | `.demo-shots/r6/` 证据（不提交） |

## 10. 风险与回滚

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| 编辑态由 disabled select 改只读行 | 视觉变化，截图对比可能被判「回归」 | 与 r4「来源项目」同构；t07 仅断言 `来源类型` 只读行，不受影响 |
| 决议项全 converted 时常驻红色错误 | 观感偏重 | 文案解释原因 + 禁用控件；点保存不再重复弹 toast |
| 会议快速切换竞态 | 绑错决议项 | `itemsRequestSeq` 丢弃过期响应（§5.2） |
| 前端必填比服务端严 | 理论上可能拦住服务端允许的请求 | 逐条对齐 `validateSource()`；唯一收紧点「决议项」服务端本就强制 |
| 文案漂移 | AC-7 失败 | 常量集中 + `decisionReason()` 单点产出 + grep 校验 |

回滚：单文件 `git revert` 该提交即可，无数据/无迁移/无接口兼容问题。
