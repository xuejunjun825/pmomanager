# 验收报告 · r3 来源选项投影收口 + 任务详情来源标签去重

- 任务夹：`docs/teamflow/20261110-r3-source-options-extract/`
- 需求性质：tech-driven 返工单（`meta.json` mode=tech，**无新增功能 AC**）。验收对象 = r2 验收遗留 R-1（来源选项投影四处复制）与 A-01（详情来源名重复渲染）的关闭 + 架构蓝图落实 + 受影响既有 AC 的口径回归。
- 基线依赖：`docs/teamflow/20261109-r2-source-config-progress`（ACCEPTANCE §3 R-1 / §4 A-01、D-02）。
- 被测交付：`TECH-CHANGE.md` + 工作区未提交改动（5 改 2 新增 + `docs/teamflow/memory.md`）。
- 验收方式：**不转述 QA 结论**，由验收人独立复跑全部门禁并逐点核对代码。

## 1. 逐项核对

| # | 验收项 | 判据 | 证据（独立复核） | 结论 |
| --- | --- | --- | --- | --- |
| A1 | R-1 收口：唯一实现 | 来源选项组装只存在于 util，页面不再自拼 | `grep "label: '全部来源'" web/src` → 1 命中 `utils/sourceOptions.ts:50`；`grep "a.sort - b.sort"` → 1 命中 `:27`；`grep "enabled !== b.enabled"` → 1 命中 `:26` | PASS |
| A2 | 四处调用点接入 | 三页筛选 + 建任务抽屉均走 util，别名 import 规避同名遮蔽 | `TaskFilterBar.vue:13,50`、`MatrixPage.vue:23,49`、`DashboardPage.vue:34,69`、`TaskFormDrawer.vue:23,72` 全部 `sourceOptions as buildSourceOptions` | PASS |
| A3 | 行为等价 | 占位、启用按 sort 在前、停用按 sort 在后并带「（已停用）」、抽屉仅启用无占位 | 逐行比对 diff 中删除的旧实现与新 util：比较器、后缀判断、`[...all]`→`slice()`/`filter()` 复制后再排序，输出序列逐字一致；t07 断言 `['全部来源','会议决议','项目任务','复盘事项（已停用）','历史专项（已停用）']` | PASS |
| A4 | 无副作用 | 绝不就地排序入参（否则跨页顺序随渲染漂移） | `sourceOptions.ts:41-43` 先 `slice()`/`filter()` 再 `sort`；`source-options.spec.ts:74-86` 断言入参顺序、对象引用、label 均不变 | PASS |
| A5 | A-01 去重 | 详情来源区只剩 SourceTag 一个渲染点 + 空源「—」占位 | `TaskDetailPage.vue` 删 `sourceLabel` computed 与并列 `span`，`<span v-else class="source__type">—</span>`；`grep sourceLabel web/src` → 0 命中；t12 断言 `.source__head` 名称出现 1 次、`.tag` 仅 1 个、`source=null` 时文本为「—」且无 tag | PASS |
| A6 | 依赖方向 | types ← utils ← 页面，util 不反向依赖 `.vue` | `sourceOptions.ts:7` 仅 `import type { SourceTypeOption } from '@/types/api'`；契约检查 C1「.vue import=无」 | PASS |
| A7 | 类型清理正确 | Matrix 删失效 `SelectOption`、Dashboard 保留、Drawer 删具名类型导入 | 契约 C7：matrix=0、dash=4；`pnpm build`（含 `noUnusedLocals`）通过 | PASS |
| A8 | 后端边界 | `server/` 与接口零改动 | `git diff --stat server/` 空；改动面仅 `web/src` 5 文件 + 2 测试 | PASS |
| A9 | 回归只增不减 | 原有用例不删、全量全绿 | 基线 14 文件/93 用例 → 15 文件/100 用例（+5 util、+2 t12），`pnpm test` 100/100 | PASS |
| A10 | 构建门禁 | 类型检查 + 生产构建 | `pnpm build` exit 0，`vue-tsc --noEmit && vite build`，built in 1.10s | PASS |
| A11 | 定向回归 | util/t07/t12/source-tag 四套件 | `vitest run` 4 文件 18/18（5+5+4+4） | PASS |
| A12 | 静态契约 | 架构蓝图逐项可判定 | `logs/teamflow/tf-mwa04ns7-0r60ao/scripts/qa-r3-contract.py` → C1–C7 7/7 PASS | PASS |
| A13 | 提交面卫生 | 仅预期文件 | `git status --porcelain`：5 源文件 + 1 测试改 + 2 新增 + memory.md + 任务夹；无 dist/node_modules/target、无密钥 | PASS |

## 2. 受影响既有 AC 口径回归

| AC | 关联点 | 本轮证据 | 结论 |
| --- | --- | --- | --- |
| AC-2 建任务按配置选来源 | 抽屉只列启用项、无「全部来源」 | util `{allOption:false, includeDisabled:false}` + t07 抽屉用例按真实组件挂载断言选项集合 | 不变 |
| AC-3 升级兼容 | 停用来源仍留在筛选下拉、可筛历史任务；历史任务展示不变 | util 默认 `includeDisabled:true`；t07 断言停用项带后缀仍可选中；详情仍读 `typeLabel` | 不变 |
| AC-5 配置同源一致 | meta → 筛选三页/抽屉/详情标签同一配置 | 投影从「四处各抄一份」收敛为单一函数；t07 断言三页与抽屉选项序列一致；t12 断言详情标签/会议链接口径 | 不变（且重复源已消除） |

其余 AC（AC-1/4/6 及 AC-7～AC-21：进度、@、通知、催办、看板口径、钉钉等）**未触及**：`server/` 零 diff，前端改动仅限来源投影与详情来源区两处符号，无状态机、无接口、无数据变化，故不逐条重跑。

## 3. 架构质量判定（M3）

- **符合蓝图**：模块落在 `web/src/utils/sourceOptions.ts`，导出 `SourceOption` / `SourceOptionsConfig` / `sourceOptions` 与内部唯一比较器 `byEnabledThenSort`，签名与蓝图逐字一致；四处调用点用 config 表达三个变体，未新增第二个导出；依赖方向单向。
- **无新增重复/漂移**：全仓已无第二份来源选项投影（含比较器、占位字面量、停用后缀拼接的页面副本）。
- **既有结构未被破坏**：`SourceTag.vue`、`AppSelect.vue`、CSS、路由与分页逻辑未改；`MatrixPage`/`TaskFormDrawer` 仅做类型导入的连带清理。
- **范围外残留（不计本轮返工）**：`ownerOptions`/`meetingOptions` 的占位 + map 在 `TaskFilterBar` 与 `DashboardPage` 仍各有两份同形复制，属 R-1 之外的另一组投影，tech 阶段蓝图已书面裁定「本轮不动，留作下一轮候选」，故不构成 ⚠️ 条件。

## 4. 意见与遗留

1. **P3 文档**：`TECH-CHANGE.md` §5 的宽门禁 `grep -rn "全部来源" web/src` 写成「仅命中 util」，实际还会命中 `MatrixPage.vue:101`、`TaskPoolPage.vue:132` 的 toast 文案（代码正确，仅文档表述过宽）。本轮验收按窄门禁 `label: '全部来源'` 执行，建议下轮修文档。
2. **P3 覆盖**：`MatrixPage`/`DashboardPage` 的页面级挂载用例仅以 `sourceTypes: []` 覆盖空配置路径，停用项顺序/后缀由 util 单测与 t07 保证；风险低，建议后续补一条非空配置挂载断言。
3. **P3 范围外**：owner/meeting 占位同形复制（见 §3），登记为下一轮候选。
4. **人工补测**：Chromium 在本机沙箱内无法启动（`PROCESS_SET_INFORMATION`/`PROCESS_SUSPEND_RESUME` 被拒），真浏览器视觉（详情来源区单标签、三页下拉观感）与读屏可达性仍列人工补测 M1–M3，属环境限制、非交付缺陷；DOM 级去重已由 t12 真实组件挂载断言覆盖。
5. **流程提醒**：交付尚未提交（工作区未提交改动），合入/提交由后续流程负责；回滚为单提交 revert，无数据影响。

验收结论：✅ 通过
