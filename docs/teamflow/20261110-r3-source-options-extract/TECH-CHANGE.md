# 技术变更单 · 来源选项投影收口 + 详情来源标签去重

- 需求性质：tech-driven 返工（r3），无新功能、不改既有 AC。
- 基线依赖：`docs/teamflow/20261109-r2-source-config-progress`（ACCEPTANCE §3 R-1 / §4 A-01、D-02）。
- 本单不新增、不改写任何历史任务夹里的功能 AC；无跨代行为变更，不产生「取代：<folder>#AC-n」标记。

## 1. 背景与目标

一句话：把 r2 验收报告 R-1 指出的「来源选项投影在 4 处重复」收口为单一实现，并修掉任务详情页来源标签重复渲染的视觉缺陷，口径与接口保持不变。

## 2. 影响范围

| 文件 | 改动 |
| --- | --- |
| `web/src/utils/sourceOptions.ts` | 新增：唯一投影实现（占位、排序、停用标注、仅启用变体都由参数表达） |
| `web/src/components/task/TaskFilterBar.vue:49` | 删除本地组装，改 `computed(() => sourceOptions(props.sourceTypes))` |
| `web/src/pages/MatrixPage.vue:49` | 同上，入参 `sourceTypes.value` |
| `web/src/pages/DashboardPage.vue:68` | 同上，入参 `sourceTypes.value` |
| `web/src/components/task/TaskFormDrawer.vue:70` | 删除本地组装，改 `sourceOptions(props.sourceTypes, { allOption: false, includeDisabled: false })` |
| `web/src/pages/TaskDetailPage.vue:132-136,335` | 删 `sourceLabel` computed 与并列 `span`；来源区只由 `SourceTag` 渲染，`v-else` 保留「—」占位 |
| `web/tests/source-options.spec.ts` | 新增 helper 单测；`t07`/`t12` 仅按需补断言 |

不改动：`server/` 全部、meta/任务/矩阵/看板接口、`SourceTag.vue`、CSS 令牌、状态与逾期口径、`TaskFormDrawer` 编辑态「（已停用）」只读文案（另一语义，保留）。

## 3. 技术方案

```ts
// web/src/utils/sourceOptions.ts
export interface SourceOption { label: string; value: string }
export interface SourceOptionsConfig {
  /** 首位插入「全部来源」（value:'') 占位，默认 true */
  allOption?: boolean
  /** 保留停用项并加「（已停用）」后缀，默认 true */
  includeDisabled?: boolean
}
export function sourceOptions(
  types: SourceTypeOption[] | null | undefined,
  config?: SourceOptionsConfig,
): SourceOption[]
```

- 入参防御：`Array.isArray(types) ? types : []`；先 `slice()` 再 `sort`，不就地改 props/ref 数组。
- 排序（与原 3 处逐字一致）：`a.enabled !== b.enabled ? (a.enabled ? -1 : 1) : a.sort - b.sort`；停用项 `label` 加「（已停用）」。
- 仅启用变体：先 `filter((item) => item.enabled !== false)` 再按 `sort`；全为启用项时与原实现同序。
- 返回值类型定义在 util（不 import `.vue`），`{label,value}` 结构上满足 `AppSelect` 的 `SelectOption`，`utils` 不反向依赖组件层。
- 3 个筛选页保留 `computed` 只换函数体，响应式不变；`sourceOptions` 为纯函数，无副作用。
- 详情页：`SourceTag` 已按 `name`（`typeLabel`）渲染并用 `未知来源（CODE）` 兜底，故删除并列文本，来源名不会丢；`detail.source` 为空时渲染 `v-else` 的「—」，来源区不留空白。删除不再被引用的 `sourceLabel`，避免 lint 未使用。
- 需求描述的行号 `TaskDetailPage.vue:222` 在 r2 后漂移到 335，实施按符号（`sourceLabel`、`.source__type`）定位，不按行号。

## 4. 行为兼容性影响

- 唯一用户可见变化：任务详情来源区由「会议决议 会议决议」变为单个标签。名称仍取 `typeLabel`；缺名称时标签显示「未知来源（CODE）」（旧文案裸显示 `CODE`），信息不丢且更明确，属视觉缺陷修复，不改变任何 AC 口径。
- 任务池 / 矩阵 / 看板：选项来源、顺序（启用按 sort 在前、停用按 sort 在后）、「（已停用）」文案、回传编码逐字不变；「全部来源」仍在首位。
- 建任务抽屉：仍只列启用项、按 sort、无占位。
- 无接口、DB、权限、通知、逾期与看板数字变化。

## 5. 回归与验证计划

- 回归底线（必跑）：
  - `cd web && pnpm test`
  - `cd web && pnpm build`
- 定向：`cd web && pnpm exec vitest run tests/source-options.spec.ts tests/t07-source-options.spec.ts tests/t12-source-form-display.spec.ts tests/source-tag.spec.ts`
- helper 单测覆盖：空/`undefined` 入参只剩占位；启用按 sort 在前、停用按 sort 在后并带后缀；`includeDisabled:false` 只留启用且无占位；入参数组未被 `sort` 改动。
- R-1 静态门禁（grep 唯一实现）：`grep -rn "全部来源" web/src` 仅命中 `web/src/utils/sourceOptions.ts`；`grep -rn "a.sort - b.sort" web/src` 仅 1 处。若页面仍出现字面量或比较器，R-1 未收口。
- 详情去重门禁：t12 详情用例补一条断言——`.source__head` 文本中来源名只出现 1 次。
- 回归范围：仅前端，`git diff --stat server/` 必须为空；上一轮 r2 的 HTTP 探针不重跑（脚本位于已归档的 transient 目录，接口未动）。

## 6. 风险与回滚

- 风险 1：比较器抄错导致混排 → 由 t07 既有逐字断言 + 新增 helper 单测钉住。
- 风险 2：抽屉漏传 `allOption/includeDisabled`，多出占位或停用项 → t07 抽屉用例断言选项集合，失败即拦。
- 风险 3：删 `span` 后仍有模板引用 `sourceLabel` → `pnpm build`（vue-tsc）直接报错。
- 风险 4：详情 `source` 为空时来源区空白 → `v-else` 占位「—」覆盖。
- 回滚：改动集中在一个提交，`git revert <sha>` 即可；无迁移、无数据写入，回滚零数据影响。

## 7. 假设与待澄清

- 模块位置取 `web/src/utils/sourceOptions.ts`（纯函数，沿用 `utils/format.ts` 先例）而非 composable；若团队要求统一放 composables，仅 import 路径变化，行为与测试不变。
- 取「一个 `sourceOptions(types, config)`」而非两个命名导出（`filterSourceOptions` / `createSourceOptions`）；若改为两个导出，4 处 import 需同步调整，行为不变。
- 返回值用 util 自有的 `SourceOption`（结构兼容 `SelectOption`）；若要求直接复用 `SelectOption`，util 将引入对 `.vue` 的类型依赖（不推荐，可改但需接受该依赖）。
- 详情去重方式取「删并列文本、`SourceTag` 为唯一渲染点」；若产品希望保留灰字纯文本为主、色块标签仅作装饰，则改为保留 `span` 并去掉 `SourceTag` 的名称（仅显示色块），视觉不同，需产品拍板。
- 「全部来源」文案内置为默认占位；未来若某页需要「全部类型」等不同措辞，再给 config 加 `placeholder?: string`，不影响现有调用。
