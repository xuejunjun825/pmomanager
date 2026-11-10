# QA-REPORT · r3 来源选项投影收口 + 详情来源标签去重

- 任务夹：`docs/teamflow/20261110-r3-source-options-extract/`（本轮无新增 AC，关闭 r2 验收遗留 R-1 与 A-01）
- 被测交付：`TECH-CHANGE.md` + 工作区未提交改动（7 改 2 新增；`docs/teamflow/memory.md` 为 tech 阶段同步）
- **结论：可验收**。未发现 P0/P1/P2 缺陷；P3 观察 3 条（文档门禁表述、范围外同形复制、页面级覆盖），均不阻断。

## 1. 范围与环境

- 变更形态：纯前端重构收口。新增 `web/src/utils/sourceOptions.ts`（唯一投影）+ `web/tests/source-options.spec.ts`；`TaskFilterBar.vue` / `MatrixPage.vue` / `DashboardPage.vue` / `TaskFormDrawer.vue` 四处改走 util；`TaskDetailPage.vue` 删除并列 `sourceLabel`，`SourceTag` 成为唯一渲染点；`t12` 追加去重与空源占位断言。`server/`、接口、数据零改动。
- 环境：macOS 宿主沙箱；Chromium 在沙箱内受宿主 token 限制无法启动（`PROCESS_SET_INFORMATION`/`PROCESS_SUSPEND_RESUME` 被拒，crashpad 自终止；非交付缺陷，换 flag 无效）。因此 DOM 级验证使用仓库既有 jsdom 挂载用例，真浏览器视觉列入 §7 人工补测。
- 测试资产：`logs/teamflow/tf-mwa04ns7-0r60ao/scripts/qa-r3-contract.py`（本轮新增静态契约检查器，可复跑）。

## 2. 用例与结果

| 编号 | 检查项 | 检测命令 | 结果 |
| --- | --- | --- | --- |
| Q1 | 前端全量单测回归（只增不减） | `cd web && pnpm test` | PASS，15 files / 100 tests，exit 0（基线 14/93） |
| Q2 | 类型检查 + 构建（含 `noUnusedLocals`） | `cd web && pnpm build` | PASS，`vue-tsc --noEmit && vite build`，built in 1.10s，exit 0 |
| Q3 | 定向回归四套件 | `cd web && pnpm exec vitest run tests/source-options.spec.ts tests/t07-source-options.spec.ts tests/t12-source-form-display.spec.ts tests/source-tag.spec.ts` | PASS，18/18（5+5+4+4），exit 0 |
| Q4 | 投影行为：占位/启用优先/停用后置标注/仅启用变体/入参不被 sort | Q3 中 `source-options.spec.ts` 5 用例 | PASS，输出序列逐字匹配；入参顺序、引用、label 均未被修改 |
| Q5 | 筛选条与建任务抽屉按配置渲染（DOM 级，真实组件挂载） | Q3 中 `t07-source-options.spec.ts` 5 用例 | PASS：`['全部来源','会议决议','项目任务','复盘事项（已停用）','历史专项（已停用）']`；抽屉新建态只列启用项 |
| Q6 | 详情来源名只渲染一次 + 空源「—」占位 | Q3 中 `t12-source-form-display.spec.ts`（新增 2 用例） | PASS，`.source__head` 文本中来源名 1 次、`.tag` 1 个；`source=null` 时文本 `—` 且无 tag |
| Q7 | 静态契约（架构蓝图落实 + 接口一致性，7 项） | `python3 logs/teamflow/tf-mwa04ns7-0r60ao/scripts/qa-r3-contract.py` | PASS，7/7，exit 0 |
| Q8 | R-1 唯一实现窄门禁 | `grep -rn "label: '全部来源'" web/src`；`grep -rn "a.sort - b.sort" web/src`；`grep -rn "enabled !== b.enabled" web/src` | PASS，各 1 命中且均在 `web/src/utils/sourceOptions.ts:50/:27/:26` |
| Q9 | 详情旧符号彻底移除 | `grep -rn "sourceLabel" web/src` | PASS，0 命中（exit 1） |
| Q10 | 服务端零改动 | `git diff --stat server/` | PASS，空输出 |
| Q11 | 行为等价性（改造前 vs 改造后） | `git diff -- web/src/components/task/TaskFilterBar.vue web/src/pages/MatrixPage.vue web/src/pages/DashboardPage.vue web/src/components/task/TaskFormDrawer.vue` | PASS：比较器、占位、停用后缀、"先复制后排序"与旧实现逐字等价；模板 `:options` 与 chips 未改 |
| Q12 | 提交面卫生 | `git status --porcelain` | PASS：仅 5 个源文件 + 2 个测试 + memory.md + 任务夹 + 2 个新增文件；`dist/`、`node_modules/`、`target/` 均被 `.gitignore` 覆盖，无缓存/IDE/密钥 |

补充：本交付未改动 `server/`，故未重跑后端 198 单测（改动面为空，风险为零）；若需全量回归可执行 `cd server && ./mvnw -q test`。

## 3. 架构蓝图落实（需求 0）

| 蓝图项 | 期望 | 实际 | 结论 |
| --- | --- | --- | --- |
| 模块抽取 | `web/src/utils/sourceOptions.ts`，纯函数不依赖 Vue/组件 | 文件存在；仅 `import type { SourceTypeOption } from '@/types/api'`，无 `.vue` import | 符合 |
| 接口签名 | `sourceOptions(types, config?) => SourceOption[]`；`SourceOption`/`SourceOptionsConfig` 导出；内部唯一比较器 `byEnabledThenSort` | 逐字一致（`sourceOptions.ts:10/15/25/34`） | 符合 |
| 依赖方向 | types ← utils ← 页面 | util 只依赖 types；页面/组件只从 util 取投影，无反向依赖 | 符合 |
| 四处收口 | 筛选三页 + 抽屉改调用；抽屉传 `{allOption:false, includeDisabled:false}` | 4/4 均为别名 import `buildSourceOptions`（C2 PASS），模板零改动 | 符合 |
| 详情去重 | 删 `sourceLabel`，`SourceTag` 唯一渲染 + `v-else`「—」 | `TaskDetailPage.vue:316-329`；`sourceLabel` 0 命中；`.source__type` 样式保留给空源占位 | 符合 |
| 后端边界 | `server/` 与接口零改动 | `git diff --stat server/` 空 | 符合 |
| 无回归 | 只增不减 | 100 > 93 用例；15 > 14 文件；build 绿 | 符合 |

未发现抽象该抽未抽、既有结构被破坏的问题。已知「（已停用）」后缀在 util 与 `TaskFormDrawer.vue:83` 双写，但后者是编辑态只读文案（另一语义），蓝图已显式裁定保留，不计缺陷。

## 4. 接口一致性（需求 0a，静态可判定）

| 调用点 | 被调符号 | 目标模块实际导出 | 参数形态 | 结论 |
| --- | --- | --- | --- | --- |
| TaskFilterBar:50 / MatrixPage:49 / DashboardPage:69 / TaskFormDrawer:71 | `sourceOptions`（别名 `buildSourceOptions`） | `sourceOptions.ts:34` `export function sourceOptions` | `(SourceTypeOption[] \| null \| undefined, config?)`，调用处传 props/ref 与可选 config，匹配 | PASS |
| MatrixPage:23 / DashboardPage:34 / TaskFormDrawer:23 | `SourceOption` 类型 | `sourceOptions.ts:10` `export interface SourceOption` | 结构 `{label:string, value:string}`，可赋给 `AppSelect` 的 `SelectOption{value:string\|number}`（Q2 编译验证） | PASS |
| TaskDetailPage:321-327 | `SourceTag` props | `SourceTag.vue:11-17` `code/name/color/softColor/borderColor/meetingTitle` 均存在且可空 | 传参一一对应 | PASS |
| MatrixPage / DashboardPage / TaskFormDrawer | `SelectOption` 清理 | `vue-tsc --noEmit` + `noUnusedLocals` 通过；Matrix 引用 0 处、Dashboard 4 处仍在使用 | 无失效未用导入 | PASS |

## 5. 缺陷

### 5.1 P0/P1/P2

未发现 P0/P1/P2 缺陷（需求 0a2 要求的可执行主路径证据见 Q1–Q7：真实组件挂载 + 纯函数断言 + 构建，非存在性检查）。

### 5.2 P3 观察（不阻断验收）

| 编号 | 严重级 | 功能模块 | 复现步骤 | 期望行为 | 实际行为 | 关联验收项 | 检测命令 | 通过判据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| QA-R3-01 | P3 | 文档/门禁 | 按 `TECH-CHANGE.md` §5 第一条执行 `grep -rn "全部来源" web/src` | 文档声称「仅命中 util」 | 实际 4 处：util:50 选项字面量、util:16 注释、MatrixPage:101 与 TaskPoolPage:132 的 toast 文案；门禁应如实收窄 | r2 R-1 | `grep -rn "全部来源" web/src \| wc -l` | -（代码正确；下轮文档把门禁改为 `label: '全部来源'`） |
| QA-R3-02 | P3 | 架构 | `grep -rn "全部负责人\|全部会议" web/src` | 与来源选项同样抽公共投影 | TaskFilterBar:59/67 与 DashboardPage:61/65 两处同形复制（本轮范围外，蓝图已登记留待下轮） | AC-5 一致性（扩展） | `grep -rn "全部负责人\|全部会议" web/src \| wc -l` | -（建议下轮抽 util） |
| QA-R3-03 | P3 | 测试覆盖 | Matrix/Dashboard 在 `t09-pages.spec.ts` 中以 `sourceTypes: []` 挂载 | 有停用来源配置时的下拉顺序/后缀有页面级断言 | 仅 util 单测与 t07（筛选条/抽屉）覆盖；两页挂载只验证了空配置路径 | AC-5 | `grep -n "已停用" web/tests/t09-pages.spec.ts`（仅 2 处无关的「负责人已停用」） | -（风险低，建议补一条挂载断言） |

## 6. 人工补测清单

| 编号 | 项 | 判据 | 方法/工具 | 说明 |
| --- | --- | --- | --- | --- |
| M1 | 真浏览器视觉：详情来源区 | `/tasks/5` 来源区只有一个来源标签，无「会议决议 会议决议」重复；与改造前相比仅少一处重复文字 | `cd web && pnpm dev` → Chrome 131 打开对应路由，与 HEAD 构建对照 | 沙箱无法启动 Chromium，属环境限制、非交付缺陷 |
| M2 | 三页筛选 + 抽屉的下拉观感 | 管理端停用一个来源后，任务池/矩阵/看板下拉：启用按 sort 在前、停用在后带「（已停用）」、仍可选中筛历史；抽屉新建态不出现停用项且无「全部来源」 | Chrome 131 + 管理端配置页手工停用一项 | 行为已由 Q4/Q5 断言，此处只补视觉与交互手感 |
| M3 | 键盘/读屏可达性 | 来源下拉为原生 `select`，Tab/方向键/读屏标签与改造前一致 | VoiceOver + Chrome | 本轮未改控件实现，仅投影来源变化 |

## 7. 结论

- 验收结论：**可验收**。r2 遗留 R-1（来源选项三处复制）已收口为单一纯函数实现，A-01（详情来源名重复）已修复；架构蓝图逐项落实，`server/` 与接口零改动，全量/定向/构建/静态契约全绿。
- 仅 3 条 P3 观察，均不阻断：文档门禁表述过宽、owner/meeting 占位仍两处重复（范围外）、Matrix/Dashboard 非空配置缺页面级断言。
- 人工补测 M1–M3 由验收人按上表执行；当前环境限制已如实记录。
